package lab.youtube.accelerator;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
    private WebExtension.Port port;
    private GeckoSession.SessionState sessionState;
    private String currentUrl = HOME;
    private boolean paused = true;
    private boolean canGoBack;
    private boolean fullscreen;
    private boolean extensionReady;
    private boolean loadFailed;
    private int previousSystemUiVisibility;
    private int revision;
    private int previousOrientation;
    private Button reloadButton;
    private TextView refreshHint;
    private float scrollY;
    private boolean scrollKnown;
    private boolean pulling;
    private float pullX;
    private float pullY;
    private Button fullscreenSpeedButton;
    private String videoContext;
    private double contentRate = 1;
    private int commandId;
    private int pendingCommand;
    private AlertDialog speedSelector;

    private final Runnable suspendSession = () -> {
        if (paused && session != null && session.isOpen()) session.setActive(false);
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        // Retire prototype-only preferences without touching the browser profile.
        getPreferences(MODE_PRIVATE).edit().remove("enabled").remove("searchHistory").apply();
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
                                receiveMessage(message, source);
                            }
                            @Override public void onDisconnect(WebExtension.Port source) {
                                if (port == source) {
                                    port = null;
                                    resetContentControls();
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
                if (!isDestroyed()) showError(R.string.assets_unavailable);
            });
    }

    @SuppressLint("ClickableViewAccessibility") // Observer only: Gecko handles taps and its accessibility tree.
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
        reloadButton = new Button(this);
        reloadButton.setText(R.string.reload);
        reloadButton.setOnClickListener(view -> reloadPage());
        reloadButton.setVisibility(View.GONE);
        controls.addView(reloadButton);
        status = new TextView(this);
        status.setPadding(dp(16), dp(4), dp(16), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        controls.addView(status);
        web = new GeckoView(this);
        browser.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pulling = pageAtTop() && !fullscreen && !paused && !loadFailed;
                    pullX = event.getX();
                    pullY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() != 1 || !pageAtTop()
                        || Math.abs(event.getX() - pullX) > dp(32) || event.getY() < pullY) pulling = false;
                    refreshHint.setVisibility(pulling && event.getY() - pullY >= dp(96) ? View.VISIBLE : View.GONE);
                    break;
                case MotionEvent.ACTION_UP:
                    boolean refresh = pulling && event.getY() - pullY >= dp(96);
                    cancelPull();
                    if (refresh) handler.post(this::reloadPage);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancelPull();
                    break;
                default: break;
            }
            return false; // Gecko keeps ownership of scrolling and player gestures.
        });
        refreshHint = new TextView(this);
        refreshHint.setText(R.string.release_to_reload);
        refreshHint.setTextColor(Color.WHITE);
        refreshHint.setBackgroundColor(Color.DKGRAY);
        refreshHint.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(refreshHint, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        refreshHint.setVisibility(View.GONE);
        browser.setFocusableInTouchMode(true);
        browser.requestFocus();
        fullscreenSpeedButton = new Button(this);
        fullscreenSpeedButton.setOnClickListener(view -> chooseSpeed());
        fullscreenSpeedButton.setAllCaps(false);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(dp(88), dp(48), Gravity.TOP | Gravity.END);
        overlay.setMargins(dp(8), dp(8), dp(8), 0);
        root.addView(fullscreenSpeedButton, overlay);
        fullscreenSpeedButton.setVisibility(View.GONE);
        resetContentControls();
    }

    private boolean pageAtTop() {
        // APZ can report subpixel rounding (including small negative values) at the top edge.
        return scrollKnown && scrollY <= 1;
    }

    private void cancelPull() {
        pulling = false;
        refreshHint.setVisibility(View.GONE);
    }

    private void reloadPage() {
        cancelPull();
        if (!extensionReady || paused || fullscreen) return;
        if (loadFailed) navigate(UrlPolicy.browsableHttps(currentUrl) ? currentUrl : HOME);
        else session.reload();
    }

    private void installDelegates() {
        // Compositor updates include the initial viewport and asynchronous gesture scrolling.
        session.setCompositorScrollDelegate(new GeckoSession.CompositorScrollDelegate() {
            @Override public void onScrollChanged(GeckoSession source, GeckoSession.ScrollPositionUpdate position) {
                scrollKnown = true;
                scrollY = position.scrollY;
                if (!pageAtTop()) cancelPull();
            }
        });
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
                resetContentControls();
                currentUrl = url;
                if (!loadFailed) status.setVisibility(View.GONE);
            }
            @Override public void onCanGoBack(GeckoSession source, boolean available) { canGoBack = available; }
            @Override public GeckoResult<String> onLoadError(GeckoSession source, String uri, WebRequestError error) {
                loadFailed = true;
                resetContentControls();
                showFullscreen(false);
                showError(R.string.load_error);
                return null; // Retain Gecko's normal error page and certificate protections.
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession source, String url) {
                loadFailed = false;
                scrollKnown = false;
                cancelPull();
                reloadButton.setVisibility(View.GONE);
                resetContentControls();
                showFullscreen(false);
                if (port != null) { port.disconnect(); port = null; }
                status.setVisibility(View.GONE);
            }
            @Override public void onPageStop(GeckoSession source, boolean success) {
                loadFailed = !success;
                if (success) {
                    status.setVisibility(View.GONE);
                    reloadButton.setVisibility(View.GONE);
                }
                else showError(R.string.load_error);
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
        resetContentControls();
        loadFailed = true;
        showFullscreen(false);
        showError(R.string.browser_crashed);
        source.open(runtime);
        source.setActive(!paused);
        // Reload loads currentUrl explicitly because a reopened session has no history.
    }

    private void navigate(String url) {
        if (!UrlPolicy.browsableHttps(url)) {
            showError(R.string.https_required);
        } else if (extensionReady) session.loadUri(url);
    }

    private void applyState() {
        if (port == null) return;
        try {
            port.postMessage(new JSONObject().put("type", "state").put("enabled", true)
                .put("paused", paused).put("revision", ++revision));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void receiveMessage(Object message, WebExtension.Port source) {
        if (source != port || !(message instanceof JSONObject) || isDestroyed()) return;
        JSONObject state = (JSONObject) message;
        if (!NativeProtocol.integer(state.opt("revision"), revision)) return;
        Object type = state.opt("type");
        if ("open-speed-selector".equals(type)) {
            if (!paused && !loadFailed && !fullscreen && currentDocument(state)
                && videoContextEquals(state.opt("videoContext")) && speedSelector == null) chooseSpeed();
            return;
        }
        if ("speed-result".equals(type)) {
            if (paused || !NativeProtocol.integer(state.opt("commandId"), pendingCommand)
                || pendingCommand == 0 || !(state.opt("accepted") instanceof Boolean)
                || !videoContextEquals(state.opt("videoContext"))) return;
            pendingCommand = 0;
            if (Boolean.FALSE.equals(state.opt("accepted"))) showSpeedError();
            return;
        }
        if (!"status".equals(type) || !(state.opt("paused") instanceof Boolean)
            || !(state.opt("enabled") instanceof Boolean) || !(state.opt("adActive") instanceof Boolean)
            || !NativeProtocol.contentRate(state.opt("contentRate"))
            || !optionalRate(state, "rate") || !optionalRate(state, "contentAppliedRate")
            || !state.has("videoContext") || !state.has("contentError")
            || !(state.isNull("videoContext") || NativeProtocol.context(state.opt("videoContext")))
            || !(state.isNull("contentError") || "rate-rejected".equals(state.opt("contentError")))) return;
        if (paused) {
            if (Boolean.TRUE.equals(state.opt("paused"))) {
                handler.removeCallbacks(suspendSession);
                suspendSession.run();
            }
            return;
        }
        if (!currentDocument(state) || loadFailed || !Boolean.FALSE.equals(state.opt("paused"))
            || !Boolean.TRUE.equals(state.opt("enabled"))) return;
        String context = state.isNull("videoContext") ? null : (String) state.opt("videoContext");
        if (videoContext != null && !videoContext.equals(context) && speedSelector != null) speedSelector.dismiss();
        videoContext = context;
        contentRate = ((Number) state.opt("contentRate")).doubleValue();
        boolean rejected = !state.isNull("contentError") || (!state.isNull("contentAppliedRate")
            && ((Number) state.opt("contentAppliedRate")).doubleValue() != contentRate);
        updateSpeedControls(rejected);

    }

    private static boolean optionalRate(JSONObject state, String key) {
        return state.has(key) && NativeProtocol.optionalRate(state.isNull(key) ? null : state.opt(key));
    }

    private boolean currentDocument(JSONObject state) {
        return UrlPolicy.trusted(currentUrl) && currentUrl.equals(state.opt("url"));
    }

    private boolean videoContextEquals(Object value) {
        return videoContext != null && videoContext.equals(value);
    }

    private void resetContentControls() {
        videoContext = null;
        contentRate = 1;
        pendingCommand = 0;
        if (speedSelector != null) speedSelector.dismiss();
        updateSpeedControls(false);
    }

    private void updateSpeedControls(boolean rejected) {
        String label = rejected ? getString(R.string.speed_unavailable) : getString(R.string.content_speed, rateLabel(contentRate));
        fullscreenSpeedButton.setText(rejected ? "! ×" : rateLabel(contentRate) + "×");
        fullscreenSpeedButton.setContentDescription(label);
        fullscreenSpeedButton.setEnabled(videoContext != null && !paused);
    }

    private static String rateLabel(double rate) {
        return java.math.BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString();
    }

    private void chooseSpeed() {
        if (port == null || videoContext == null || paused) return;
        String context = videoContext;
        WebExtension.Port connection = port;
        String[] labels = new String[NativeProtocol.CONTENT_RATES.length];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = rateLabel(NativeProtocol.CONTENT_RATES[i]) + "×";
            if (NativeProtocol.CONTENT_RATES[i] == contentRate) checked = i;
        }
        speedSelector = new AlertDialog.Builder(this).setTitle(R.string.playback_speed)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                dialog.dismiss();
                if (connection != port || !videoContextEquals(context) || paused) { showSpeedError(); return; }
                try {
                    int id = ++commandId;
                    pendingCommand = id;
                    connection.postMessage(new JSONObject().put("type", "content-speed").put("revision", revision)
                        .put("videoContext", context).put("commandId", id).put("rate", NativeProtocol.CONTENT_RATES[which]));
                    handler.postDelayed(() -> {
                        if (pendingCommand == id) { pendingCommand = 0; showSpeedError(); }
                    }, 2000);
                } catch (JSONException error) { throw new IllegalStateException(error); }
            }).setNegativeButton(android.R.string.cancel, null).create();
        speedSelector.setOnDismissListener(dialog -> speedSelector = null);
        speedSelector.show();
    }

    private void showSpeedError() { Toast.makeText(this, R.string.speed_rejected, Toast.LENGTH_SHORT).show(); }

    private void showError(int message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        reloadButton.setVisibility(extensionReady ? View.VISIBLE : View.GONE);
    }

    private void showFullscreen(boolean active) {
        if (fullscreen == active) return;
        fullscreen = active;
        cancelPull();
        if (active) {
            previousOrientation = getRequestedOrientation();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else setRequestedOrientation(previousOrientation);
        fullscreenSpeedButton.setVisibility(active ? View.VISIBLE : View.GONE);
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
        cancelPull();
        if (speedSelector != null) speedSelector.dismiss();
        updateSpeedControls(false);
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
        if (fullscreen) { session.exitFullScreen(); showFullscreen(false); }
        else if (canGoBack) session.goBack();
        else finish();
    }

    @Override protected void onDestroy() {
        showFullscreen(false);
        if (speedSelector != null) speedSelector.dismiss();
        handler.removeCallbacksAndMessages(null);
        if (port != null) { port.disconnect(); port = null; }
        if (web != null) web.releaseSession();
        if (session != null) session.close();
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
