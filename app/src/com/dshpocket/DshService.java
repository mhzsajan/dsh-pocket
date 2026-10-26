package com.dshpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that owns the PRoot runtime:
 *
 *   ensureRuntimeInstalled -> spawn proot -> parse "DSH_READY <url>" -> publish state.
 *
 * Exactly one dsh process may exist at a time; Restart kills the old one first.
 * A partial wake lock is held while the server is alive, the notification offers Stop/Open.
 */
public class DshService extends Service {
    public static final String TAG = "DshPocket";

    public static final String ACTION_START = "com.dshpocket.action.START";
    public static final String ACTION_STOP = "com.dshpocket.action.STOP";
    public static final String ACTION_RESTART = "com.dshpocket.action.RESTART";

    private static final int NOTIFICATION_ID = 0x4453;
    private static final String CHANNEL_ID = "dsh_runtime";
    private static final String CHANNEL_NAME = "DSH runtime";

    private static volatile boolean alive = false;
    private static volatile boolean userStopped = false;

    private PowerManager.WakeLock wakeLock;
    private volatile Process process;
    private volatile Thread worker;
    private volatile boolean stopping = false;
    private volatile boolean restarting = false;
    private final Object processLock = new Object();

    // ------------------------------------------------------------------ statics --

    public static boolean isAlive() {
        return alive;
    }

    /** True when the user explicitly pressed Stop (auto-start must not undo that). */
    public static boolean isUserStopped() {
        return userStopped;
    }

    public static void start(Context context) {
        userStopped = false;
        Intent intent = new Intent(context, DshService.class);
        intent.setAction(ACTION_START);
        context.startService(intent);
    }

    public static void stop(Context context) {
        userStopped = true;
        Intent intent = new Intent(context, DshService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void restart(Context context) {
        userStopped = false;
        Intent intent = new Intent(context, DshService.class);
        intent.setAction(ACTION_RESTART);
        context.startService(intent);
    }

    // --------------------------------------------------------------- lifecycle --

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_START;
        // Must call startForeground() promptly after startService() on modern Android.
        startForeground(NOTIFICATION_ID, buildNotification());

        if (ACTION_STOP.equals(action)) {
            DshLog.append("stop requested");
            userStopped = true;
            stopRuntime();
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART.equals(action)) {
            DshLog.append("restart requested");
            userStopped = false;
            if (isWorkerBusy()) {
                beginRestart();
                return START_STICKY;
            }
            startRuntime();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            userStopped = false;
            if (isWorkerBusy()) {
                updateNotification();
                return START_STICKY;
            }
            startRuntime();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Cleanup only: never overwrite the last published state (an ERROR must stay visible).
        // Do not block the main thread here: kill the child and let init reap it.
        stopping = true;
        alive = false;
        Process p;
        synchronized (processLock) {
            p = process;
            process = null;
        }
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
        releaseWakeLock();
        super.onDestroy();
    }

    /** Kill the current runtime in the background, then start a fresh one. */
    private void beginRestart() {
        restarting = true;
        stopping = true;
        final Thread oldWorker = worker;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                killProcess();
                try {
                    if (oldWorker != null) { oldWorker.join(3000); }
                } catch (InterruptedException ignored) {
                }
                if (restarting) {
                    stopping = false;
                    restarting = false;
                    startRuntime();
                }
            }
        }, "dsh-restart");
        t.start();
    }

    // ------------------------------------------------------------------ runtime --

    private boolean isWorkerBusy() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    private void startRuntime() {
        stopping = false;
        alive = true;
        acquireWakeLock();
        DshState.set(DshState.Status.STARTING, "Preparing instant Linux runtime\u2026", null, -1);
        updateNotification();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                runRuntime();
            }
        }, "dsh-runtime");
        worker = t;
        t.start();
    }

    private void runRuntime() {
        File filesDir = getFilesDir();
        File rootfs = RuntimeInstaller.rootfsDir(this);
        DshLog.init(new File(new File(filesDir, "logs"), "dsh.log"));
        DshLog.append("==== DSH Pocket runtime start ====");

        try {
            DshState.set(DshState.Status.INSTALLING, "Checking Linux runtime\u2026", null, 0);
            if (!RuntimeInstaller.isInstalled(this)) {
                long freeMb = RuntimeInstaller.freeMegabytes(filesDir);
                if (freeMb > 0 && freeMb < 2200) {
                    DshLog.append("WARNING: only " + freeMb + " MB free; ~2.2 GB needed for extraction");
                    DshState.set(DshState.Status.INSTALLING, "Low storage: " + freeMb
                            + " MB free (need ~2.2 GB)", null, 2);
                }
            }
            RuntimeInstaller.ensureInstalled(this, new RuntimeInstaller.Progress() {
                @Override public void onProgress(int percent, String message) {
                    if (stopping) { return; }
                    DshState.set(DshState.Status.INSTALLING, message, null, percent);
                    updateNotification();
                }

                @Override public boolean isCancelled() {
                    return stopping;
                }
            });
            if (stopping) { return; }

            File proot = RuntimeInstaller.prootFile(this);
            if (!proot.isFile()) {
                throw new IllegalStateException("proot binary missing at " + proot);
            }
            RuntimeInstaller.chmod(proot, 00755);
            File loader = RuntimeInstaller.loaderFile(this);
            if (!loader.isFile()) {
                throw new IllegalStateException("proot loader missing at " + loader);
            }
            RuntimeInstaller.chmod(loader, 00755);
            File tmp = RuntimeInstaller.tmpDir(this);
            if (!tmp.isDirectory()) { tmp.mkdirs(); }
            RuntimeInstaller.chmod(tmp, 00777);
            File workspace = new File(filesDir, "workspace");
            if (!workspace.isDirectory()) { workspace.mkdirs(); }

            List<String> command = new ArrayList<>();
            command.add(proot.getAbsolutePath());
            command.add("-0");                              // fake root
            command.add("-l");                              // link2symlink (needed by apt/dpkg later)
            command.add("-r");
            command.add(rootfs.getAbsolutePath());
            command.add("-b");
            command.add("/dev");
            command.add("-b");
            command.add("/proc");
            command.add("-b");
            command.add("/sys");
            // internal workspace is always mapped; /sdcard only when storage access is granted
            command.add("-b");
            command.add(workspace.getAbsolutePath() + ":/root/workspace");
            if (hasExternalStorage()) {
                File external = android.os.Environment.getExternalStorageDirectory();
                if (external != null && external.isDirectory()) {
                    command.add("-b");
                    command.add(external.getAbsolutePath() + ":/sdcard");
                }
            }
            command.add("-w");
            command.add("/root");
            command.add("/bin/bash");
            command.add("/root/start-dsh.sh");

            DshState.set(DshState.Status.STARTING, "Starting dsh web\u2026", null, 100);
            updateNotification();
            DshLog.append("exec: " + join(command));
            // never let a stale URL from a previous run confuse the fallback watcher
            new File(rootfs, "root/dsh-web.url").delete();

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(filesDir);
            RuntimeInstaller.applyRuntimeEnv(pb.environment(), filesDir);
            DshLog.append("env: LD_LIBRARY_PATH=" + pb.environment().get("LD_LIBRARY_PATH")
                    + " PROOT_LOADER=" + pb.environment().get("PROOT_LOADER")
                    + " PROOT_TMP_DIR=" + pb.environment().get("PROOT_TMP_DIR"));

            Process p = pb.start();
            synchronized (processLock) { process = p; }
            if (stopping) {
                killProcess();
                return;
            }
            try { p.getOutputStream().close(); } catch (Exception ignored) { }
            startUrlFileWatcher(rootfs);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                handleOutputLine(line, rootfs);
                if (stopping) { break; }
            }

            int exitCode;
            try {
                exitCode = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitCode = -1;
            }
            synchronized (processLock) {
                if (process == p) { process = null; }
            }
            if (stopping) {
                DshState.set(DshState.Status.STOPPED, "Stopped", null, -1);
            } else {
                DshLog.append("dsh process exited with code " + exitCode);
                DshState.set(DshState.Status.ERROR,
                        "Server exited (code " + exitCode + "). Tap Restart or check the log below.",
                        null, -1);
            }
        } catch (Throwable t) {
            DshLog.append("runtime error: " + t);
            if (!stopping) {
                DshState.set(DshState.Status.ERROR, "Error: " + shortMessage(t), null, -1);
            }
        } finally {
            alive = false;
            releaseWakeLock();
            if (!stopping && !restarting) {
                updateNotification();
                stopForegroundCompat();
                stopSelf();
            }
        }
    }

    private void handleOutputLine(String rawLine, File rootfs) {
        String line = rawLine;
        DshLog.appendRaw(line);

        if (DshState.isReady()) {
            return;
        }
        String trimmed = line.trim();
        String url = null;
        if (trimmed.startsWith("DSH_READY ")) {
            url = trimmed.substring("DSH_READY ".length()).trim();
        } else if (trimmed.startsWith("DSH_READY\t")) {
            url = trimmed.substring("DSH_READY\t".length()).trim();
        } else if (trimmed.contains("dsh web: ")) {
            // fallback: dsh prints "dsh web: http://127.0.0.1:PORT/?token=..." itself
            String candidate = trimmed.substring(trimmed.indexOf("dsh web: ") + "dsh web: ".length()).trim();
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                url = candidate;
            }
        }
        if (url == null) { return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            DshLog.append("ignoring DSH_READY with unusable url: " + url);
            return;
        }
        onServerReady(url, rootfs);
    }

    private void onServerReady(String url, File rootfs) {
        // strip trailing punctuation that some log lines carry
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(")") || url.endsWith(";")) {
            url = url.substring(0, url.length() - 1);
        }
        DshLog.append("server ready at " + url);
        new Prefs(this).setLastUrl(url);
        DshState.set(DshState.Status.READY, "Server ready", url, 100);
        updateNotification();
        // mirror file (guest writes it too; keep host-side copy for diagnostics)
        try {
            RuntimeInstaller.writeFile(new File(rootfs, "root/dsh-web.url"), url + "\n");
        } catch (Exception ignored) {
        }
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) { return t.getClass().getSimpleName(); }
        return m;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) { sb.append(' '); }
            sb.append(part);
        }
        return sb.toString();
    }

    /** True when legacy external-storage access was granted to this app. */
    private boolean hasExternalStorage() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) { return true; }
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Free space on the volume holding the app files dir, in MB (-1 if unknown). */
    private static long freeMegabytes(File path) {
        return RuntimeInstaller.freeMegabytes(path);
    }

    /**
     * Fallback for stdout URL parsing: the guest mirrors the URL to
     * {@code <files>/rootfs/root/dsh-web.url}; poll it while we are not ready yet.
     */
    private void startUrlFileWatcher(final File rootfs) {
        final File urlFile = new File(rootfs, "root/dsh-web.url");
        Thread watcher = new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 300; i++) {
                    if (stopping || DshState.isReady()) { return; }
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    if (stopping || DshState.isReady()) { return; }
                    try {
                        String url = RuntimeInstaller.readFile(urlFile);
                        if (url != null) {
                            url = url.trim();
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                DshLog.append("server URL recovered from dsh-web.url");
                                onServerReady(url, rootfs);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "dsh-url-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    // -------------------------------------------------------------------- stop --

    private void stopRuntime() {
        stopping = true;
        alive = false;
        releaseWakeLock();
        DshState.set(DshState.Status.STOPPED, "Stopped", null, -1);
        // never block the main thread while waiting for the child to die
        new Thread(new Runnable() {
            @Override public void run() {
                killProcess();
                new android.os.Handler(getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        stopForegroundCompat();
                        stopSelf();
                    }
                });
            }
        }, "dsh-stop").start();
    }

    private void killProcess() {
        Process p;
        synchronized (processLock) {
            p = process;
            process = null;
        }
        if (p == null) { return; }
        DshLog.append("killing dsh process");
        try { p.destroy(); } catch (Exception ignored) { }
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                DshLog.append("dsh process did not exit, force killing");
                try { p.destroyForcibly(); } catch (Exception ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ notification --

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { return; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) { return; }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("DeepSeek Harness runtime status");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        String url = DshState.getUrl();
        String text = DshState.getMessage();
        if (DshState.getProgress() >= 0 && DshState.getProgress() < 100) {
            text = text + " (" + DshState.getProgress() + "%)";
        }

        Intent openTarget = (url != null)
                ? new Intent(this, WebActivity.class).putExtra(WebActivity.EXTRA_URL, url)
                : new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(openTarget).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent = PendingIntent.getService(this, 1,
                new Intent(this, DshService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent openIntent = PendingIntent.getActivity(this, 2,
                new Intent(openTarget).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("DSH Pocket")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat, "Stop", stopIntent).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat, "Open", openIntent).build());
        return builder.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable t) {
            DshLog.append("notification update failed: " + t);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- wake lock --

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) { return; }
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":runtime");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire();
            DshLog.append("partial wake lock acquired");
        } catch (Throwable t) {
            DshLog.append("wake lock failed: " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                DshLog.append("partial wake lock released");
            }
        } catch (Throwable ignored) {
        }
    }
}
