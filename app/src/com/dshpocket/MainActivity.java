package com.dshpocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Launcher screen: runtime status + Open/ Restart/ Stop + live log.
 * Auto-starts DshService and auto-opens the WebView once the server is ready.
 */
public class MainActivity extends Activity implements DshState.Listener {
    private static String lastAutoOpenedUrl = null;
    private static final int REQ_STORAGE = 0x51;
    private static final int MAX_AUTO_RESTARTS = 2;
    private static int autoRestartAttempts = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private TextView statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar progress;
    private Button openButton;
    private Button restartButton;
    private Button stopButton;
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout onboardingView;

    private boolean resumed = false;
    private boolean autoOpenScheduled = false;

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            if (!resumed) { return; }
            refreshLog();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        DshLog.init(new File(new File(getFilesDir(), "logs"), "dsh.log"));
        setContentView(buildUi());
        DshState.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        DshState.addListener(this);
        refresh();
        refreshLog();
        ui.removeCallbacks(logTick);
        ui.postDelayed(logTick, 1500);

        if (prefs.isKeepScreenOn()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Request storage access first (if needed); the runtime is started from the permission
        // callback so a first-run extraction is never interrupted by a mid-extraction restart.
        if (!maybeRequestStoragePermission()) {
            ensureRuntimeStarted();
        }
    }

    /** The embedded Linux runtime is aarch64 only; detect phones that can run it. */
    private static boolean isArm64() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) { return false; }
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) { return true; }
        }
        return false;
    }

    private static String abiList() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) { return "unknown"; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < abis.length; i++) {
            if (i > 0) { sb.append(", "); }
            sb.append(abis[i]);
        }
        return sb.toString();
    }

    /** Auto-start the runtime on a cold app start / after an explicit stop (not after Stop). */
    private void ensureRuntimeStarted() {
        if (!isArm64()) {
            DshState.set(DshState.Status.ERROR,
                    "This APK needs a 64-bit ARM phone (arm64-v8a).\n\n"
                            + "This device reports: " + abiList() + "\n\n"
                            + "Install DSH Pocket on an arm64 Android 8+ phone. The APK installs "
                            + "everywhere, but the embedded Linux runtime (PRoot/Ubuntu arm64) only "
                            + "runs on arm64 devices.", null, -1);
            return;
        }
        DshState.Status status = DshState.getStatus();
        if (prefs.isAutoStart() && !DshService.isAlive() && !DshService.isUserStopped()
                && (status == DshState.Status.IDLE || status == DshState.Status.STOPPED)) {
            if (autoRestartAttempts < MAX_AUTO_RESTARTS) {
                autoRestartAttempts++;
                if (autoRestartAttempts > 1) {
                    DshLog.append("auto-restart attempt " + autoRestartAttempts + "/" + MAX_AUTO_RESTARTS);
                }
                DshService.start(this);
            } else {
                DshState.set(DshState.Status.ERROR,
                        "Runtime stopped repeatedly. Tap Restart.", null, -1);
            }
        } else if (status == DshState.Status.IDLE && DshService.isAlive()) {
            DshState.set(DshState.Status.STARTING, "Attaching to runtime\u2026", null, -1);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(logTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        DshState.removeListener(this);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // -------------------------------------------------------------------- UI ----

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.bg));
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(16));

        TextView title = new TextView(this);
        title.setText("DSH Pocket");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColorCompat(R.color.text_primary));
        root.addView(title);

        TextView subtitle = new TextView(this);
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?";
        subtitle.setText("DeepSeek Harness \u00b7 Android " + Build.VERSION.RELEASE
                + " \u00b7 " + abi + " \u00b7 " + (isArm64() ? "supported" : "NOT supported"));
        subtitle.setTextSize(13f);
        subtitle.setTextColor(getColorCompat(R.color.text_secondary));
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        // ---- first-run onboarding (hidden once the runtime is installed) ----
        onboardingView = buildOnboarding();
        root.addView(onboardingView);

        // ---- status card ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(getColorCompat(R.color.surface));
        cardBg.setCornerRadius(dp(16));
        card.setBackground(cardBg);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new TextView(this);
        statusDot.setText("\u25cf");
        statusDot.setTextSize(16f);
        statusDot.setPadding(0, 0, dp(8), 0);
        statusRow.addView(statusDot);

        statusTitle = new TextView(this);
        statusTitle.setTextSize(16f);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setTextColor(getColorCompat(R.color.text_primary));
        statusRow.addView(statusTitle);
        card.addView(statusRow);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(12f);
        statusDetail.setTextColor(getColorCompat(R.color.text_secondary));
        statusDetail.setPadding(0, dp(4), 0, 0);
        statusDetail.setTextIsSelectable(true);
        card.addView(statusDetail);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        progressParams.topMargin = dp(10);
        card.addView(progress, progressParams);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- primary button ----
        openButton = makeButton("Open DeepSeek", true);
        openButton.setEnabled(false);
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeb(); }
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        openParams.topMargin = dp(18);
        root.addView(openButton, openParams);

        // ---- secondary buttons ----
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        restartButton = makeButton("Restart", false);
        stopButton = makeButton("Stop", false);
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        third.topMargin = dp(10);
        row1.addView(restartButton, third);
        LinearLayout.LayoutParams third2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        third2.topMargin = dp(10);
        third2.leftMargin = dp(10);
        row1.addView(stopButton, third2);
        root.addView(row1);

        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                lastAutoOpenedUrl = null;
                DshService.restart(MainActivity.this);
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                DshService.stop(MainActivity.this);
            }
        });

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button batteryButton = makeButton("Battery", false);
        Button selfTestButton = makeButton("Self-test", false);
        Button copyLogButton = makeButton("Copy log", false);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t3.topMargin = dp(8);
        row2.addView(batteryButton, t3);
        LinearLayout.LayoutParams t4 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t4.topMargin = dp(8);
        t4.leftMargin = dp(8);
        row2.addView(selfTestButton, t4);
        LinearLayout.LayoutParams t5 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t5.topMargin = dp(8);
        t5.leftMargin = dp(8);
        row2.addView(copyLogButton, t5);
        root.addView(row2);

        batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestBatteryExemption(); }
        });
        selfTestButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSelfTest(); }
        });
        copyLogButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyLog(); }
        });

        // ---- log ----
        TextView logLabel = new TextView(this);
        logLabel.setText("Runtime log");
        logLabel.setTextSize(12f);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setTextColor(getColorCompat(R.color.text_secondary));
        logLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(logLabel);

        logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(getColorCompat(R.color.log_bg));
        logBg.setCornerRadius(dp(10));
        logScroll.setBackground(logBg);
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView = new TextView(this);
        logView.setTextSize(10f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(getColorCompat(R.color.text_secondary));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private LinearLayout buildOnboarding() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF16213E);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), getColorCompat(R.color.accent_dark));
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("First start");
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColorCompat(R.color.accent));
        title.setPadding(0, 0, 0, dp(4));
        card.addView(title);

        String[] bullets = {
                "\u2022 First launch extracts the Linux runtime (~1.25 GB, 30k files, 1\u20133 min). Keep the app open.",
                "\u2022 DeepSeek Harness opens right here in the app \u2014 no PC, no Termux.",
                "\u2022 Sign in to DeepSeek in the web UI (or add an API key in Settings \u2192 Account).",
                "\u2022 Workspace: /root/workspace \u00b7 phone storage mapped at /sdcard.",
                "\u2022 Guest tools: git, curl, unzip, xz, nano; apt + npm work (IPv4 forced).",
        };
        for (String bullet : bullets) {
            TextView line = new TextView(this);
            line.setText(bullet);
            line.setTextSize(11.5f);
            line.setTextColor(getColorCompat(R.color.text_secondary));
            line.setPadding(0, dp(2), 0, dp(2));
            card.addView(line);
        }
        return card;
    }

    private Button makeButton(String text, boolean primary) {        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(primary ? 17f : 13f);
        button.setTextColor(primary ? Color.WHITE : getColorCompat(R.color.text_primary));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(primary ? 16 : 12));
        bg.setColor(getColorCompat(primary ? R.color.accent : R.color.surface_alt));
        button.setBackground(bg);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    // --------------------------------------------------------------- refresh ----

    @Override
    public void onDshStateChanged() {
        if (DshState.getStatus() == DshState.Status.READY) {
            autoRestartAttempts = 0;
            scheduleAutoOpen();
        }
        refresh();
    }

    private void refresh() {
        DshState.Status status = DshState.getStatus();
        String message = DshState.getMessage();
        String url = DshState.getUrl();
        int percent = DshState.getProgress();

        statusTitle.setText(titleFor(status));
        statusTitle.setTextColor(colorFor(status));
        statusDot.setTextColor(colorFor(status));

        StringBuilder detail = new StringBuilder(message);
        if (url != null) {
            detail.append("\n").append(url);
        }
        if (status == DshState.Status.INSTALLING && percent >= 0) {
            detail.append("\n").append(percent).append("%");
        }
        statusDetail.setText(detail.toString());

        if (status == DshState.Status.INSTALLING && percent >= 0 && percent < 100) {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(false);
            progress.setProgress(percent);
        } else if (status == DshState.Status.STARTING) {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
        } else {
            progress.setVisibility(View.GONE);
        }

        boolean ready = status == DshState.Status.READY && url != null;
        openButton.setEnabled(ready);
        openButton.setAlpha(ready ? 1f : 0.45f);
        openButton.setText(ready ? "Open DeepSeek" : "Open DeepSeek (not ready)");
        restartButton.setEnabled(!DshState.isBusy());
        stopButton.setEnabled(status != DshState.Status.STOPPED
                && status != DshState.Status.IDLE);

        refreshLog();
        if (onboardingView != null) {
            onboardingView.setVisibility(RuntimeInstaller.isInstalled(this) ? View.GONE : View.VISIBLE);
        }
    }

    private String titleFor(DshState.Status status) {
        switch (status) {
            case INSTALLING: return "Extracting Linux runtime";
            case STARTING: return "Starting server";
            case READY: return "Server ready";
            case ERROR: return "Error";
            case STOPPED: return "Stopped";
            default: return "Idle";
        }
    }

    private int colorFor(DshState.Status status) {
        switch (status) {
            case READY: return getColorCompat(R.color.ok);
            case ERROR: return getColorCompat(R.color.err);
            case INSTALLING:
            case STARTING: return getColorCompat(R.color.warn);
            default: return getColorCompat(R.color.text_secondary);
        }
    }

    private void refreshLog() {
        String tail = DshLog.tail(400);
        if (!tail.equals(logView.getText().toString())) {
            logView.setText(tail);
            logScroll.post(new Runnable() {
                @Override public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void scheduleAutoOpen() {
        if (autoOpenScheduled) { return; }
        final String url = DshState.getUrl();
        if (url == null || url.equals(lastAutoOpenedUrl)) { return; }
        autoOpenScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                autoOpenScheduled = false;
                if (!resumed) { return; }
                if (!DshState.isReady()) { return; }
                if (url.equals(lastAutoOpenedUrl)) { return; }
                lastAutoOpenedUrl = url;
                openWeb();
            }
        }, 600);
    }

    // ----------------------------------------------------------------- actions --

    private void openWeb() {
        String url = DshState.getUrl();
        if (url == null) {
            Toast.makeText(this, "Server is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        lastAutoOpenedUrl = url;
        Intent intent = new Intent(this, WebActivity.class);
        intent.putExtra(WebActivity.EXTRA_URL, url);
        startActivity(intent);
    }

    private void runSelfTest() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Self-test")
                .setMessage("Running probes\u2026")
                .setNegativeButton("Close", null)
                .create();
        dialog.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = ExecProbe.runSelfTest(MainActivity.this);
                DshLog.append("self-test:\n" + result);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) { return; }
                        dialog.setMessage(result);
                        dialog.show();     // re-show so the message can grow
                    }
                });
            }
        }, "self-test").start();
    }

    private void copyLog() {
        String text = DshLog.tail(500);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("DSH Pocket log", text));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean maybeRequestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23 || hasStoragePermission()) { return false; }
        if (prefs.hasAskedStorage()) { return false; }
        prefs.setAskedStorage(true);
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return true;
        } catch (Throwable t) {
            DshLog.append("storage permission request failed: " + t);
            return false;
        }
    }

    private boolean hasStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) { return true; }
        try {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) { return; }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        DshLog.append(granted
                ? "external storage granted (/sdcard will be mapped)"
                : "external storage denied (optional; workspace stays in app storage)");
        if (granted && DshService.isAlive() && DshState.getStatus() == DshState.Status.READY) {
            // quick restart so proot re-binds /storage/emulated/0 at /sdcard
            DshService.restart(this);
        } else {
            ensureRuntimeStarted();
        }
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
            Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
