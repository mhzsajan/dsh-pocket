package com.dshpocket;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostics: copy an executable from APK assets into the app data dir, chmod it executable and
 * execve it; and run the installed proot with the exact runtime environment.
 *
 * This is the app's "Self-test" (proot --version + static ELF + bionic ELF probes).
 */
public final class ExecProbe {
    private ExecProbe() {}

    /** Run the full self-test; call from a background thread. */
    public static String runSelfTest(Context context) {
        StringBuilder sb = new StringBuilder();
        File filesDir = context.getFilesDir();

        sb.append("== proot --version ==\n");
        File proot = new File(filesDir, "proot");
        if (!proot.isFile()) {
            sb.append("proot missing: ").append(proot.getAbsolutePath()).append("\n");
        } else {
            ProcessBuilder pb = new ProcessBuilder(proot.getAbsolutePath(), "--version");
            pb.redirectErrorStream(true);
            pb.directory(filesDir);
            RuntimeInstaller.applyRuntimeEnv(pb.environment(), filesDir);
            sb.append(execProcess(pb, 20));
        }

        sb.append("\n== proot companion files ==\n");
        for (String rel : new String[]{"loader", "lib/libtalloc.so.2", "lib/libandroid-shmem.so"}) {
            File f = new File(filesDir, rel);
            sb.append(rel).append(": ").append(f.isFile() ? f.length() + " bytes" : "MISSING").append("\n");
        }

        sb.append("\n== static aarch64 ELF probe (exec from app data) ==\n");
        sb.append(runAsset(context, "hello-arm64", "hello-arm64", new String[0]));

        sb.append("\n== bionic PIE probe (/system/bin/toybox copy) ==\n");
        sb.append(runAsset(context, "toybox", "toybox", new String[]{"echo", "bionic-exec-ok"}));
        return sb.toString();
    }

    /** Extract assets/<assetName> to filesDir/probe/<targetName>, chmod 0755, exec it. */
    public static String runAsset(Context context, String assetName, String targetName, String[] args) {
        StringBuilder out = new StringBuilder();
        File probeDir = new File(context.getFilesDir(), "probe");
        if (!probeDir.isDirectory() && !probeDir.mkdirs()) {
            return "cannot create " + probeDir;
        }
        File exe = new File(probeDir, targetName);
        try {
            long copied = copyAsset(context, "probe/" + assetName, exe);
            exe.setReadable(true, false);
            exe.setWritable(true, false);
            exe.setExecutable(true, false);
            out.append("asset:  assets/probe/").append(assetName).append(" (").append(copied).append(" bytes)\n");
            out.append("dest:   ").append(exe.getAbsolutePath()).append("\n");
            out.append("mode:   exec=").append(exe.canExecute()).append(" read=").append(exe.canRead()).append("\n");
            String[] cmd = new String[args.length + 1];
            cmd[0] = exe.getAbsolutePath();
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            out.append(execProcess(pb, 15));
        } catch (Throwable t) {
            out.append("EXEC FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        }
        return out.toString();
    }

    /** Execute a prepared ProcessBuilder, merging stderr into stdout, with a timeout. */
    public static String execProcess(ProcessBuilder pb, int timeoutSeconds) {
        StringBuilder out = new StringBuilder();
        out.append("cmd:    ").append(join(pb.command())).append("\n");
        try {
            Process process = pb.start();
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread pump = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        InputStream in = process.getInputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) {
                            synchronized (buffer) {
                                if (buffer.size() < 256 * 1024) { buffer.write(b, 0, n); }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "probe-reader");
            pump.setDaemon(true);
            pump.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.append("TIMEOUT after ").append(timeoutSeconds).append("s\n");
            } else {
                out.append("exit:   ").append(process.exitValue()).append("\n");
            }
            pump.join(2000);
            String text;
            synchronized (buffer) { text = buffer.toString("UTF-8"); }
            out.append("stdout+stderr:\n").append(text);
            if (!text.endsWith("\n")) { out.append("\n"); }
        } catch (Throwable t) {
            out.append("EXEC FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        }
        return out.toString();
    }

    public static long copyAsset(Context context, String assetPath, File dest) throws Exception {
        dest.getParentFile().mkdirs();
        InputStream in = context.getAssets().open(assetPath);
        try {
            FileOutputStream fos = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.flush();
                return total;
            } finally {
                fos.close();
            }
        } finally {
            in.close();
        }
    }

    public static String join(java.util.List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) { sb.append(' '); }
            sb.append(part);
        }
        return sb.toString();
    }
}
