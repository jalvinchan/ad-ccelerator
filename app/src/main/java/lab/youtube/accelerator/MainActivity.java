package lab.youtube.accelerator;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;
import java.util.List;

/** Single-session HTTPS browser with one bundled accelerator extension. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com/";
    private static final String EXTENSION_ID = "accelerator@ad-ccelerator.local";
    // GeckoRuntime may only be created once per process; sessions belong to activities.
    private static GeckoRuntime runtime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GeckoSession session;
    private GeckoView web;
    private FrameLayout root;
    private LinearLayout controls;
    private TextView status;
    private EditText address;
    private WebExtension.Port port;
    private GeckoSession.SessionState sessionState;
    private String currentUrl = HOME;
    private boolean enabled;
    private boolean paused = true;
    private boolean canGoBack;
    private boolean fullscreen;
    private boolean extensionReady;
    private boolean loadFailed;
    private int previousSystemUiVisibility;
    private int revision;

    private final Runnable suspendSession = () -> {
        if (paused && session != null && session.isOpen()) session.setActive(false);
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        enabled = getPreferences(MODE_PRIVATE).getBoolean("enabled", true);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(debug)
                .consoleOutput(debug)
                .allowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY)
                .contentBlocking(new ContentBlocking.Settings.Builder()
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                    .build())
                .build();
            runtime = GeckoRuntime.create(getApplicationContext(), settings);
        }
        session = new GeckoSession(new GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(true).build());
        installDelegates();
        session.open(runtime);
        web.setSession(session);
        runtime.getWebExtensionController().ensureBuiltIn(
            "resource://android/assets/accelerator/", EXTENSION_ID).accept(extension -> {
                if (isDestroyed()) return;
                session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
                    @Override public void onConnect(WebExtension.Port connection) {
                        WebExtension.MessageSender sender = connection.sender;
                        if (isDestroyed() || sender.session != session || !sender.isTopLevel()
                            || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
                            || !EXTENSION_ID.equals(sender.webExtension.id)
                            || !UrlPolicy.trusted(sender.url) || !"accelerator".equals(connection.name)) {
                            connection.disconnect();
                            return;
                        }
                        if (port != null) port.disconnect();
                        port = connection;
                        connection.setDelegate(new WebExtension.PortDelegate() {
                            @Override public void onPortMessage(Object message, WebExtension.Port source) {
                                receiveStatus(message, source);
                            }
                            @Override public void onDisconnect(WebExtension.Port source) {
                                if (port == source) {
                                    port = null;
                                    if (!loadFailed && UrlPolicy.trusted(currentUrl)) {
                                        status.setText(R.string.connecting_accelerator);
                                    }
                                }
                            }
                        });
                        applyState();
                    }
                }, "accelerator");
                extensionReady = true;
                if (savedState != null) {
                    sessionState = GeckoSession.SessionState.fromString(savedState.getString("browserState"));
                }
                if (sessionState != null) session.restoreState(sessionState);
                else session.loadUri(HOME);
            }, error -> {
                if (!isDestroyed()) status.setText(R.string.assets_unavailable);
            });
    }

    private void buildUi() {
        root = new FrameLayout(this);
        LinearLayout browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
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
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        browser.addView(controls);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        Button home = new Button(this);
        home.setText(R.string.home);
        home.setOnClickListener(view -> navigate(HOME));
        toolbar.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = new Button(this);
        reload.setText(R.string.reload);
        reload.setOnClickListener(view -> {
            if (!extensionReady) return;
            if (loadFailed) navigate(UrlPolicy.browsableHttps(currentUrl) ? currentUrl : HOME);
            else session.reload();
        });
        toolbar.addView(reload, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(toolbar);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setMinHeight(dp(48));
        address.setContentDescription(getString(R.string.page_address));
        address.setText(HOME);
        address.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            String url = address.getText().toString().trim();
            if (!url.contains(":")) url = "https://" + url;
            navigate(url);
            address.clearFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
            return true;
        });
        controls.addView(address, new LinearLayout.LayoutParams(-1, -2));
        Switch accelerate = new Switch(this);
        accelerate.setText(R.string.accelerate_ads);
        accelerate.setPadding(dp(16), 0, dp(16), 0);
        accelerate.setMinHeight(dp(48));
        accelerate.setChecked(enabled);
        accelerate.setOnCheckedChangeListener((button, checked) -> {
            enabled = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("enabled", checked).apply();
            applyState();
            showIdleStatus();
        });
        controls.addView(accelerate, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setPadding(dp(16), dp(4), dp(16), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(R.string.starting_browser);
        controls.addView(status);
        web = new GeckoView(this);
        browser.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        browser.setFocusableInTouchMode(true);
        browser.requestFocus();
    }

    private void installDelegates() {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source, LoadRequest request) {
                if (!UrlPolicy.browsableHttps(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
                // This app has one tab. Keep popup links in the same browser profile.
                if (request.target == TARGET_WINDOW_NEW) {
                    handler.post(() -> navigate(request.uri));
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override public void onLocationChange(GeckoSession source, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (url == null) return;
                currentUrl = url;
                address.setText(url);
                showIdleStatus();
            }
            @Override public void onCanGoBack(GeckoSession source, boolean available) { canGoBack = available; }
            @Override public GeckoResult<String> onLoadError(GeckoSession source, String uri, WebRequestError error) {
                loadFailed = true;
                status.setText(R.string.load_error);
                return null; // Retain Gecko's normal error page and certificate protections.
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession source, String url) {
                loadFailed = false;
                if (port != null) { port.disconnect(); port = null; }
                status.setText(R.string.loading);
            }
            @Override public void onPageStop(GeckoSession source, boolean success) {
                loadFailed = !success;
                if (success) showIdleStatus();
                else status.setText(R.string.load_error);
            }
            @Override public void onSessionStateChange(GeckoSession source, GeckoSession.SessionState state) {
                sessionState = state;
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onFullScreen(GeckoSession source, boolean active) { showFullscreen(active); }
            @Override public void onCrash(GeckoSession source) {
                recoverSession(source);
            }
            @Override public void onKill(GeckoSession source) { recoverSession(source); }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession source, ContentPermission permission) {
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        }); // Camera/microphone/Android permissions also reject by default.
    }

    private void recoverSession(GeckoSession source) {
        port = null;
        loadFailed = true;
        showFullscreen(false);
        status.setText(R.string.browser_crashed);
        source.open(runtime);
        source.setActive(!paused);
        // Reload loads currentUrl explicitly because a reopened session has no history.
    }

    private void navigate(String url) {
        if (!UrlPolicy.browsableHttps(url)) {
            status.setText(R.string.https_required);
            address.setText(currentUrl);
        } else if (extensionReady) session.loadUri(url);
    }

    private void applyState() {
        if (port == null) return;
        try {
            port.postMessage(new JSONObject().put("type", "state").put("enabled", enabled)
                .put("paused", paused).put("revision", ++revision));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void receiveStatus(Object message, WebExtension.Port source) {
        if (source != port || !(message instanceof JSONObject) || isDestroyed()) return;
        JSONObject state = (JSONObject) message;
        if (!"status".equals(state.optString("type")) || state.optInt("revision", -1) != revision) return;
        if (paused) {
            if (state.optBoolean("paused")) {
                handler.removeCallbacks(suspendSession);
                suspendSession.run();
            }
            return;
        }
        if (!UrlPolicy.trusted(currentUrl) || loadFailed) return;
        if (!enabled) status.setText(R.string.acceleration_off);
        else if (!state.optBoolean("adActive")) status.setText(R.string.waiting_for_ad);
        else if (state.isNull("rate")) status.setText(R.string.ad_speed_unavailable);
        else status.setText(getString(R.string.ad_speed, state.optDouble("rate")));
    }

    private void showIdleStatus() {
        if (loadFailed) return;
        status.setText(!UrlPolicy.trusted(currentUrl) ? R.string.acceleration_youtube_only :
            !enabled ? R.string.acceleration_off :
            port == null ? R.string.connecting_accelerator : R.string.waiting_for_ad);
    }

    private void showFullscreen(boolean active) {
        if (fullscreen == active) return;
        fullscreen = active;
        controls.setVisibility(active ? View.GONE : View.VISIBLE);
        root.setBackgroundColor(active ? Color.BLACK : Color.TRANSPARENT);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                if (active) {
                    bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    bars.hide(WindowInsets.Type.systemBars());
                } else bars.show(WindowInsets.Type.systemBars());
            }
        } else if (active) {
            previousSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else getWindow().getDecorView().setSystemUiVisibility(previousSystemUiVisibility);
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        handler.removeCallbacks(suspendSession);
        if (session != null) { session.setActive(true); applyState(); }
    }

    @Override protected void onPause() {
        paused = true;
        applyState();
        // Restore speed and pause media before suspension; bound the wait if content hangs.
        if (port == null) suspendSession.run();
        else handler.postDelayed(suspendSession, 250);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (sessionState != null) state.putString("browserState", sessionState.toString());
    }

    // Android 8–12 fallback; Android 13+ uses the native callback registered above.
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (fullscreen) session.exitFullScreen();
        else if (canGoBack) session.goBack();
        else finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (port != null) { port.disconnect(); port = null; }
        if (web != null) web.releaseSession();
        if (session != null) session.close();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
