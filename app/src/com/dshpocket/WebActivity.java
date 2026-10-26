package com.dshpocket;

import android.app.Activity;
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
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Full-screen WebView showing the DeepSeek Harness web UI served by the on-device runtime. */
public class WebActivity extends Activity implements DshState.Listener {
    public static final String EXTRA_URL = "url";
    private static final int REQ_FILE_CHOOSER = 0x61;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView loadingText;
    private LinearLayout errorOverlay;
    private TextView errorText;
    private Prefs prefs;
    private String currentUrl;
    private ValueCallback<Uri[]> pendingFileCallback;

    /** phone-first skin, injected after each page load (see assets/mobile/) */
    private String skinCss;
    private String skinJs;
    private boolean skinApplied;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable showFallback = new Runnable() {
        @Override public void run() {
            if (webView != null) { injectSkin(webView); }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        DshLog.init(new java.io.File(new java.io.File(getFilesDir(), "logs"), "dsh.log"));
        setContentView(buildUi());
        DshState.addListener(this);
        if (prefs.isKeepScreenOn()) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String url = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        if (url == null) { url = DshState.getUrl(); }
        if (url == null) {
            showError("The DSH server is not ready yet.\n\nGo back to the launcher and wait for "
                    + "\"Server ready\", then tap Open DeepSeek.");
        } else {
            load(url);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getStringExtra(EXTRA_URL) != null) {
            load(intent.getStringExtra(EXTRA_URL));
        }
    }

    @Override
    public void onDshStateChanged() {
        if (DshState.isReady()) {
            String url = DshState.getUrl();
            if (url != null && !url.equals(currentUrl)) {
                load(url);
            }
        } else if (DshState.getStatus() == DshState.Status.ERROR
                || DshState.getStatus() == DshState.Status.STOPPED) {
            showError("The DSH server stopped.\n\n" + DshState.getMessage()
                    + "\n\nTap Restart to start it again.");
        }
    }

    @Override
    protected void onDestroy() {
        DshState.removeListener(this);
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------- UI ----

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1220);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B1220);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        // phone-first skin (injected after each load; see assets/mobile/skin.*)
        skinCss = readAsset("mobile/skin.css");
        skinJs = readAsset("mobile/skin.js");
        // bridge for the injected bottom bar's "More" button
        webView.addJavascriptInterface(new NativeBridge(), "DshPocketNative");
        // keep the flash of desktop layout invisible until the skin is in
        webView.setAlpha(skinCss != null ? 0f : 1f);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
                if (skinCss != null) { view.setAlpha(0f); }
                pageStarted();
                // Safety net: some pages keep the load event pending (long polling /
                // streaming boot); inject as soon as the first frame is ready anyway.
                uiHandler.removeCallbacks(showFallback);
                uiHandler.postDelayed(showFallback, 3500);
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                uiHandler.removeCallbacks(showFallback);
                injectSkin(view);
            }

            @Override public void onPageFinished(WebView view, String url) {
                uiHandler.removeCallbacks(showFallback);
                injectSkin(view);
                pageFinished();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showError("Could not load the DSH web UI.\n\n" + error.getDescription()
                            + "\n\nURL: " + request.getUrl());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) { return; }
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                                       FileChooserParams params) {
                if (pendingFileCallback != null) {
                    try { pendingFileCallback.onReceiveValue(null); } catch (Exception ignored) { }
                }
                pendingFileCallback = filePathCallback;
                try {
                    Intent intent = params != null ? params.createIntent() : null;
                    if (intent == null) {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    Toast.makeText(WebActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                                    Message resultMsg) {
                // target=_blank and window.open: open in the same WebView
                try {
                    WebView temp = new WebView(WebActivity.this);
                    temp.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                            if (webView != null && url != null) { webView.loadUrl(url); }
                            v.destroy();
                            return true;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(temp);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    android.util.Log.i(DshService.TAG, "console: " + consoleMessage.message());
                }
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                // no camera/mic needed for the DSH web UI
                if (request != null) { request.deny(); }
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4C8DFF));
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.gravity = Gravity.TOP;
        root.addView(progressBar, progressParams);

        // friendly placeholder while the WebView loads (the page itself is hidden
        // until the phone skin is injected, so this avoids a black screen)
        loadingText = new TextView(this);
        loadingText.setText("Loading DeepSeek Harness…");
        loadingText.setTextColor(0xFF9AA6BF);
        loadingText.setTextSize(15f);
        loadingText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadingParams.gravity = Gravity.CENTER;
        root.addView(loadingText, loadingParams);

        // NOTE: the phone skin draws its own bottom navigation (Chats/New/Search/More);
        // the old floating menu bubble is gone, "More" calls NativeBridge.openMenu().

        errorOverlay = new LinearLayout(this);
        errorOverlay.setOrientation(LinearLayout.VERTICAL);
        errorOverlay.setBackgroundColor(0xFF0B1220);
        errorOverlay.setGravity(Gravity.CENTER);
        errorOverlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorOverlay.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(0xFFECF2FF);
        errorText.setTextSize(14f);
        errorText.setGravity(Gravity.CENTER);
        errorOverlay.addView(errorText);

        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setAllCaps(false);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = DshState.getUrl();
                if (url != null) { load(url); } else { onBackPressed(); }
            }
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(16);
        errorOverlay.addView(retry, retryParams);

        Button back = new Button(this);
        back.setText("Back to launcher");
        back.setAllCaps(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backParams.topMargin = dp(8);
        errorOverlay.addView(back, backParams);

        root.addView(errorOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- loading ----

    private void load(String url) {
        if (webView == null) { return; }
        currentUrl = url;
        errorOverlay.setVisibility(View.GONE);
        if (loadingText != null) { loadingText.setVisibility(View.VISIBLE); }
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        webView.loadUrl(url);
    }

    private void pageStarted() {
        errorOverlay.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void pageFinished() {
        progressBar.setVisibility(View.GONE);
        errorOverlay.setVisibility(View.GONE);
        // Keep the server URL, even if the SPA changed the visible URL with fragments.
        String serverUrl = DshState.getUrl();
        if (serverUrl != null) { currentUrl = serverUrl; }
    }

    private void showError(String message) {
        if (errorText == null) { return; }
        if (loadingText != null) { loadingText.setVisibility(View.GONE); }
        errorText.setText(message);
        errorOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private boolean handleUrl(String url) {
        if (url == null) { return false; }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
            return false;                  // keep local DSH UI inside the app
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "No app to open " + url, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ------------------------------------------------------------ phone skin ----

    /** Read a UTF-8 asset, or null when it is missing. */
    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            android.util.Log.w(DshService.TAG, "skin asset missing: " + name + " (" + t + ")");
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * Inject the CSS + JS phone shell. Runs on every page load; both are idempotent.
     * The WebView stays invisible until the skin is in, so the user never sees the
     * desktop layout flash.
     */
    private void injectSkin(WebView view) {
        if (view == null || skinCss == null) {
            if (view != null) { view.setAlpha(1f); }
            return;
        }
        String program = "(function(){try{"
                + "var c=" + JSONObject.quote(skinCss) + ";"
                + "var s=document.getElementById('dshp-skin');"
                + "if(!s){s=document.createElement('style');s.id='dshp-skin';s.appendChild(document.createTextNode(c));document.head.appendChild(s);}"
                + "else if(s.textContent!==c){s.textContent=c;}"
                + "}catch(e){console.error('dshp css',e);}"
                + "try{" + (skinJs != null ? skinJs : "") + "}catch(e){console.error('dshp js',e);}"
                + "})()";
        view.evaluateJavascript(program, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                skinApplied = true;
                if (webView != null) { webView.setAlpha(1f); }
                if (loadingText != null) { loadingText.setVisibility(View.GONE); }
            }
        });
    }

    /** Java side of the injected bottom bar's "More" item. */
    public class NativeBridge {
        @JavascriptInterface
        public void openMenu() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView != null) { showMenu(webView); }
                }
            });
        }
    }

    // ------------------------------------------------------------------- menu ----

    private void showMenu(View anchor) {
        // A PopupMenu anchored to a fullscreen WebView renders partly off-screen on
        // phones, so use a centred dialog with a plain list instead.
        final String[] items = {
                "Account, sign in & settings",
                "Reload",
                "Open in Chrome",
                "Copy URL",
                "Restart server",
                "Stop server"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("DSH Pocket")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                webView.evaluateJavascript(
                                    "(function(){var b=[...document.querySelectorAll('button')]" +
                                    ".find(function(x){return x.getAttribute('aria-label')==='Account menu';});" +
                                    "if(b){b.click();return 'ok';}return 'missing';})()", null);
                                break;
                            case 1:
                                if (currentUrl != null) { load(currentUrl); }
                                break;
                            case 2:
                                openInBrowser();
                                break;
                            case 3:
                                copyUrl();
                                break;
                            case 4:
                                DshService.restart(WebActivity.this);
                                finish();
                                break;
                            case 5:
                                DshService.stop(WebActivity.this);
                                finish();
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void openInBrowser() {
        String url = currentUrl != null ? currentUrl : DshState.getUrl();
        if (url == null) { return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyUrl() {
        String url = currentUrl != null ? currentUrl : DshState.getUrl();
        if (url == null) { return; }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("DSH URL", url));
            Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileCallback != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null) {
                    ClipData clip = data.getClipData();
                    if (clip != null && clip.getItemCount() > 0) {
                        uris = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            uris[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                try { pendingFileCallback.onReceiveValue(uris); } catch (Exception ignored) { }
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // let the phone shell close a drawer / panel first
        webView.evaluateJavascript("(window.__dshpBack && window.__dshpBack()) || false",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        if ("true".equals(value)) { return; }
                        if (webView != null && webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            WebActivity.super.onBackPressed();
                        }
                    }
                });
    }
}
