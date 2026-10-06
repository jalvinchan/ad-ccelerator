package lab.youtube.accelerator;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout root;
    private LinearLayout browser;
    private TextView status;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int previousSystemUiVisibility;
    private boolean enabled;
    private boolean paused = true;
    private boolean loadFailed;
    private String scripts;
    private AlertDialog externalLinkDialog;

    private final Runnable updateStatus = new Runnable() {
        @Override public void run() {
            if (paused) return;
            if (UrlPolicy.trusted(web.getUrl()) && !loadFailed) {
                web.evaluateJavascript("JSON.stringify(window.YouTubeAdAccelerator?.getStatus() ?? null)", value -> {
                    if (paused || loadFailed || !UrlPolicy.trusted(web.getUrl())) return;
                    try {
                        Object decoded = new org.json.JSONTokener(value).nextValue();
                        if (!(decoded instanceof String) || "null".equals(decoded)) return;
                        JSONObject state = new JSONObject((String) decoded);
                        String message = !enabled ? getString(R.string.acceleration_off) :
                            state.optBoolean("adActive") ?
                                (state.isNull("rate") ? getString(R.string.ad_speed_unavailable) :
                                    getString(R.string.ad_speed, state.optDouble("rate"))) :
                            getString(R.string.waiting_for_ad);
                        status.setText(message);
                    } catch (org.json.JSONException ignored) {
                        status.setText(R.string.status_unavailable);
                    }
                });
            }
            handler.postDelayed(this, 1000);
        }
    };

    // YouTube requires JS; navigation is restricted and no native JS bridge exists.
    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        enabled = getPreferences(MODE_PRIVATE).getBoolean("enabled", true);
        try {
            scripts = readAsset("ad-session.js") + "\n" + readAsset("page-controller.js");
        } catch (IOException error) {
            TextView failure = new TextView(this);
            failure.setText(R.string.assets_unavailable);
            setContentView(failure);
            return;
        }
        root = new FrameLayout(this);
        browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        // Target Android 15+ edge-to-edge: keep controls clear of system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        Button home = new Button(this);
        home.setText(R.string.home);
        home.setOnClickListener(view -> web.loadUrl(HOME));
        toolbar.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = new Button(this);
        reload.setText(R.string.reload);
        reload.setOnClickListener(view -> web.reload());
        toolbar.addView(reload, new LinearLayout.LayoutParams(0, -2, 1));
        Button openBrowser = new Button(this);
        openBrowser.setText(R.string.browser);
        openBrowser.setContentDescription(getString(R.string.open_current_page));
        openBrowser.setOnClickListener(view -> {
            String url = UrlPolicy.trusted(web.getUrl()) ? web.getUrl() : HOME;
            if (openInBrowser(Uri.parse(url))) {
                Toast.makeText(this, R.string.browser_session_separate, Toast.LENGTH_LONG).show();
            }
        });
        toolbar.addView(openBrowser, new LinearLayout.LayoutParams(0, -2, 1));
        browser.addView(toolbar);
        Switch accelerate = new Switch(this);
        accelerate.setText(R.string.accelerate_ads);
        accelerate.setPadding(dp(16), 0, dp(16), 0);
        accelerate.setMinHeight(dp(48));
        accelerate.setChecked(enabled);
        accelerate.setOnCheckedChangeListener((button, checked) -> {
            enabled = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("enabled", checked).apply();
            applyState();
            status.setText(checked ? R.string.waiting_for_ad : R.string.acceleration_off);
        });
        browser.addView(accelerate, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setPadding(dp(16), dp(4), dp(16), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        browser.addView(status);
        web = new WebView(this);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        browser.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (UrlPolicy.trusted(uri.toString())) return false;
                // External links leave this WebView. Never launch arbitrary intent:// URLs.
                if (UrlPolicy.externalHttps(uri.toString())) {
                    if (request.hasGesture()) openInBrowser(uri);
                    else if (UrlPolicy.trusted(view.getUrl())) offerExternalLink(uri);
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                loadFailed = false;
                status.setText(R.string.loading);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!UrlPolicy.trusted(url) || !url.equals(view.getUrl()) || loadFailed) return;
                view.evaluateJavascript(onYouTube(scripts), ignored -> applyState());
                status.setText(enabled ? R.string.waiting_for_ad : R.string.acceleration_off);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    status.setText(R.string.load_error);
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    loadFailed = true;
                    status.setText(R.string.http_error);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view;
                fullscreenCallback = callback;
                browser.setVisibility(View.GONE);
                root.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController bars = getWindow().getInsetsController();
                    if (bars != null) {
                        bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        bars.hide(WindowInsets.Type.systemBars());
                    }
                } else {
                    previousSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        if (savedState == null || web.restoreState(savedState) == null) web.loadUrl(HOME);
    }

    private String readAsset(String name) throws IOException {
        try (InputStream stream = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean openInBrowser(Uri uri) {
        if (!UrlPolicy.externalHttps(uri.toString())) return false;
        // Resolve a browser rather than a YouTube deep-link handler. The selected
        // browser receives ACTION_VIEW with the actual link, including auth redirects.
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(uri);
        try {
            startActivity(intent);
            if ("accounts.google.com".equals(uri.getHost())) {
                Toast.makeText(this, R.string.browser_sign_in_separate, Toast.LENGTH_LONG).show();
            }
            return true;
        }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void offerExternalLink(Uri uri) {
        if (paused || isFinishing() || isDestroyed() || externalLinkDialog != null) return;
        // WebView can lose the gesture on a JavaScript navigation or redirect.
        // Require an explicit native tap instead of launching a browser automatically.
        externalLinkDialog = new AlertDialog.Builder(this)
            .setMessage(getString(R.string.external_link_prompt, uri.getHost()))
            .setPositiveButton(R.string.open_browser, (dialog, which) -> openInBrowser(uri))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        externalLinkDialog.setOnDismissListener(dialog -> externalLinkDialog = null);
        externalLinkDialog.show();
    }

    private void applyState() {
        if (web == null || !UrlPolicy.trusted(web.getUrl())) return;
        web.evaluateJavascript(onYouTube("window.YouTubeAdAccelerator?.setSuspended(" + paused + ");" +
            "window.YouTubeAdAccelerator?.setEnabled(" + enabled + ");"), null);
    }

    private static String onYouTube(String code) {
        return "if (location.origin === 'https://m.youtube.com' || " +
            "location.origin === 'https://www.youtube.com') {\n" + code + "\n}";
    }

    private void exitFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen);
        fullscreen = null;
        browser.setVisibility(View.VISIBLE);
        root.setBackgroundColor(Color.TRANSPARENT);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(previousSystemUiVisibility);
        }
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        callback.onCustomViewHidden();
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        if (web != null) { web.onResume(); applyState(); handler.post(updateStatus); }
    }

    @Override protected void onPause() {
        paused = true;
        handler.removeCallbacks(updateStatus);
        if (web != null) {
            applyState();
            // Let restoration and pausing run before suspending WebView's execution.
            web.evaluateJavascript("document.querySelectorAll('video').forEach(video => video.pause())", ignored -> {
                if (paused && !isDestroyed()) web.onPause();
            });
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (web != null) web.saveState(state);
    }

    // Legacy Android 8–12 fallback. Android 13+ uses the registered native callback.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (fullscreen != null) exitFullscreen();
        else if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(updateStatus);
        if (externalLinkDialog != null) externalLinkDialog.dismiss();
        if (web != null) {
            exitFullscreen();
            browser.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
