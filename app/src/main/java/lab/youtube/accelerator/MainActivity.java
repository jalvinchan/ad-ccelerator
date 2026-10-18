package lab.youtube.accelerator;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Single-session HTTPS browser with one bundled accelerator extension. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com/";
    private static final String EXTENSION_ID = "accelerator@ad-ccelerator.local";
    private static final int SPEED_BUTTON_WIDTH_DP = 64;
    private static final int SPEED_BUTTON_HEIGHT_DP = 48;
    private static final int SEEK_BUTTON_WIDTH_DP = 48;
    private static final int CHIP_GAP_DP = 4;
    private static final int CHIP_WIDTH_DP = SEEK_BUTTON_WIDTH_DP * 2 + SPEED_BUTTON_WIDTH_DP + CHIP_GAP_DP * 2;
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
    private RefreshIndicator refreshIndicator;
    private float scrollY;
    private boolean scrollKnown;
    private boolean pulling;
    private float pullX;
    private float pullY;
    private Button fullscreenSpeedButton;
    private LinearLayout fullscreenChip;
    private Button seekBackButton;
    private Button seekForwardButton;
    private int seekInterval = 10;
    private boolean seekAvailable;
    private String videoContext;
    private double contentRate = 1;
    private boolean contentSpeedAvailable = false;
    private int commandId;
    private int pendingCommand;
    private AlertDialog speedSelector;
    // Shared chip spot as fractions of its travel within the video; negative keeps the default top-right.
    private float chipX = -1;
    private float chipY = -1;
    private boolean draggingSpeed;
    private boolean speedRevealed;
    // YouTube's own control visibility from the page; null when unknown, so the timer decides.
    private Boolean pageControls;
    private float dragRawX;
    private float dragRawY;
    private float dragStartX;
    private float dragStartY;

    private final Runnable pickUpSpeedButton = () -> {
        draggingSpeed = true;
        fullscreenSpeedButton.setPressed(false);
        fullscreenChip.setScaleX(1.15f);
        fullscreenChip.setScaleY(1.15f);
        fullscreenSpeedButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    };

    // Without YouTube's control state, the fullscreen button fades after a few seconds and returns on touch.
    private final Runnable fadeSpeedButton = () -> {
        speedRevealed = false;
        showSpeedButton();
    };

    private final Runnable suspendSession = () -> {
        if (paused && session != null && session.isOpen()) session.setActive(false);
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        // Retire prototype-only preferences without touching the browser profile.
        getPreferences(MODE_PRIVATE).edit().remove("enabled").remove("searchHistory").apply();
        chipX = getPreferences(MODE_PRIVATE).getFloat("speedChipX", -1);
        chipY = getPreferences(MODE_PRIVATE).getFloat("speedChipY", -1);
        int savedSeek = getPreferences(MODE_PRIVATE).getInt("seekInterval", 10);
        seekInterval = NativeProtocol.seekInterval(savedSeek) ? savedSeek : 10;
        buildUi();
        // A file left by a crash or killed process must not resurface on a later cold start.
        if (savedState == null) SessionStateStore.clear(sessionStateFile());
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
                        sendChipPosition();
                        sendSeekInterval();
                    }
                }, "accelerator");
                extensionReady = true;
                if (savedState != null && savedState.getBoolean("browserStateSaved")) {
                    String saved = SessionStateStore.take(sessionStateFile());
                    if (saved != null) sessionState = GeckoSession.SessionState.fromString(saved);
                }
                if (sessionState != null) session.restoreState(sessionState);
                else session.loadUri(HOME);
            }, error -> {
                if (!isDestroyed()) showError(R.string.assets_unavailable);
            });
    }

    // Page listener is observer only; the speed button's drag listener still lets taps reach its click handler.
    @SuppressLint("ClickableViewAccessibility")
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
                    if (fullscreen) revealSpeedButton();
                    pulling = pageAtTop() && !fullscreen && !paused && !loadFailed
                        && !refreshIndicator.isLoading();
                    pullX = event.getX();
                    pullY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() != 1 || !pageAtTop()
                        || Math.abs(event.getX() - pullX) > dp(32) || event.getY() < pullY) pulling = false;
                    if (pulling) refreshIndicator.drag(event.getY() - pullY);
                    else refreshIndicator.release();
                    break;
                case MotionEvent.ACTION_UP:
                    boolean refresh = pulling && event.getY() - pullY >= dp(96);
                    pulling = false;
                    if (refresh) {
                        refreshIndicator.startLoading();
                        handler.post(this::reloadPage);
                    } else refreshIndicator.release();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancelPull();
                    break;
                default: break;
            }
            return false; // Gecko keeps ownership of scrolling and player gestures.
        });
        refreshIndicator = new RefreshIndicator(this);
        root.addView(refreshIndicator, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        browser.setFocusableInTouchMode(true);
        browser.requestFocus();
        fullscreenChip = new LinearLayout(this);
        fullscreenChip.setOrientation(LinearLayout.HORIZONTAL);
        seekBackButton = new Button(this);
        seekForwardButton = new Button(this);
        fullscreenSpeedButton = new Button(this);
        for (Button button : new Button[] {seekBackButton, fullscreenSpeedButton, seekForwardButton}) {
            button.setAllCaps(false);
            stylePill(button);
        }
        seekBackButton.setOnClickListener(view -> sendSeek(-1));
        seekForwardButton.setOnClickListener(view -> sendSeek(1));
        fullscreenSpeedButton.setOnClickListener(view -> chooseSpeed());
        fullscreenSpeedButton.setOnTouchListener(this::dragSpeedButton);
        fullscreenChip.addView(seekBackButton, new LinearLayout.LayoutParams(dp(SEEK_BUTTON_WIDTH_DP), -1));
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(dp(SPEED_BUTTON_WIDTH_DP), -1);
        speedParams.setMargins(dp(CHIP_GAP_DP), 0, dp(CHIP_GAP_DP), 0);
        fullscreenChip.addView(fullscreenSpeedButton, speedParams);
        fullscreenChip.addView(seekForwardButton, new LinearLayout.LayoutParams(dp(SEEK_BUTTON_WIDTH_DP), -1));
        // Positioned by translation so dragging and rotation never trigger a relayout.
        root.addView(fullscreenChip, new FrameLayout.LayoutParams(
            dp(CHIP_WIDTH_DP), dp(SPEED_BUTTON_HEIGHT_DP), Gravity.TOP | Gravity.START));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            placeSpeedButton());
        fullscreenChip.setVisibility(View.GONE);
        updateSeekButtons();
        resetContentControls();
    }

    private void stylePill(Button button) {
        // Same dark pill as the page chip, so both modes look alike.
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xD9181818);
        pill.setCornerRadius(dp(24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill, null));
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
            new int[] {0x80FFFFFF, Color.WHITE}));
    }

    private float speedTravelX() {
        return Math.max(0, root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(16) - dp(CHIP_WIDTH_DP));
    }

    private float speedTravelY() {
        return Math.max(0, root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - dp(16) - dp(SPEED_BUTTON_HEIGHT_DP));
    }

    private void placeSpeedButton() {
        fullscreenChip.setTranslationX(dp(8) + (chipX < 0 ? 1 : chipX) * speedTravelX());
        fullscreenChip.setTranslationY(dp(8) + (chipY < 0 ? 0 : chipY) * speedTravelY());
    }

    private static float clampFraction(float value) { return Math.min(1, Math.max(0, value)); }

    /** Long-press picks the button up; a plain tap still opens the selector. */
    private boolean dragSpeedButton(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                revealSpeedButton();
                dragRawX = event.getRawX();
                dragRawY = event.getRawY();
                dragStartX = fullscreenChip.getTranslationX();
                dragStartY = fullscreenChip.getTranslationY();
                handler.postDelayed(pickUpSpeedButton, ViewConfiguration.getLongPressTimeout());
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - dragRawX;
                float dy = event.getRawY() - dragRawY;
                if (!draggingSpeed) {
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) {
                        handler.removeCallbacks(pickUpSpeedButton);
                    }
                    return false;
                }
                // Dropping past the screen edge snaps the button back inside it.
                float travelX = speedTravelX();
                float travelY = speedTravelY();
                chipX = travelX > 0 ? clampFraction((dragStartX + dx - dp(8)) / travelX) : 0;
                chipY = travelY > 0 ? clampFraction((dragStartY + dy - dp(8)) / travelY) : 0;
                placeSpeedButton();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(pickUpSpeedButton);
                if (!draggingSpeed) return false;
                endSpeedDrag();
                saveChipPosition(chipX, chipY);
                sendChipPosition();
                return true; // The release after a drag is not a tap.
            default: return draggingSpeed;
        }
    }

    private void endSpeedDrag() {
        handler.removeCallbacks(pickUpSpeedButton);
        if (draggingSpeed && fullscreen) revealSpeedButton();
        draggingSpeed = false;
        fullscreenChip.setScaleX(1);
        fullscreenChip.setScaleY(1);
    }

    private void saveChipPosition(float x, float y) {
        chipX = x;
        chipY = y;
        getPreferences(MODE_PRIVATE).edit().putFloat("speedChipX", x).putFloat("speedChipY", y).apply();
        placeSpeedButton();
    }

    private void sendChipPosition() {
        if (port == null) return;
        try {
            port.postMessage(new JSONObject().put("type", "chip-position")
                .put("x", chipX < 0 ? JSONObject.NULL : chipX).put("y", chipY < 0 ? JSONObject.NULL : chipY));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void updateSeekButtons() {
        seekBackButton.setText(getString(R.string.seek_back_label, seekInterval));
        seekForwardButton.setText(getString(R.string.seek_forward_label, seekInterval));
        seekBackButton.setContentDescription(getString(R.string.seek_back_description, seekInterval));
        seekForwardButton.setContentDescription(getString(R.string.seek_forward_description, seekInterval));
        boolean enabled = videoContext != null && !paused && contentSpeedAvailable && seekAvailable;
        seekBackButton.setEnabled(enabled);
        seekForwardButton.setEnabled(enabled);
    }

    private void setSeekInterval(int seconds) {
        if (!NativeProtocol.seekInterval(seconds) || seconds == seekInterval) return;
        seekInterval = seconds;
        getPreferences(MODE_PRIVATE).edit().putInt("seekInterval", seconds).apply();
        updateSeekButtons();
        sendSeekInterval();
    }

    private void sendSeekInterval() {
        if (port == null) return;
        try {
            port.postMessage(new JSONObject().put("type", "seek-interval").put("seconds", seekInterval));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void sendSeek(int direction) {
        if (port == null || videoContext == null || paused || !seekAvailable || !fullscreen) return;
        revealSpeedButton();
        try {
            port.postMessage(new JSONObject().put("type", "seek").put("revision", revision)
                .put("videoContext", videoContext).put("seconds", direction * seekInterval));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void resetChipPosition() {
        chipX = -1;
        chipY = -1;
        getPreferences(MODE_PRIVATE).edit().remove("speedChipX").remove("speedChipY").apply();
        placeSpeedButton();
        sendChipPosition();
        root.announceForAccessibility(getString(R.string.chip_position_reset));
    }

    private boolean pageAtTop() {
        // APZ can report subpixel rounding (including small negative values) at the top edge.
        return scrollKnown && scrollY <= 1;
    }

    private void cancelPull() {
        pulling = false;
        refreshIndicator.release();
    }

    private void reloadPage() {
        cancelPull();
        if (!extensionReady || paused || fullscreen) {
            refreshIndicator.finish();
            return;
        }
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
                refreshIndicator.finish();
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
                && contentSpeedAvailable && videoContextEquals(state.opt("videoContext")) && speedSelector == null) chooseSpeed();
            return;
        }
        if ("chip-position".equals(type)) {
            if (!paused && !loadFailed && currentDocument(state)
                && NativeProtocol.fraction(state.opt("x")) && NativeProtocol.fraction(state.opt("y"))) {
                saveChipPosition(((Number) state.opt("x")).floatValue(), ((Number) state.opt("y")).floatValue());
            }
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
            || !(state.opt("contentSpeedAvailable") instanceof Boolean)
            || !(state.opt("seekAvailable") instanceof Boolean)
            || !(state.opt("enabled") instanceof Boolean) || !(state.opt("adActive") instanceof Boolean)
            || !(state.opt("contentRate") instanceof Number) || !NativeProtocol.optionalRate(state.opt("contentRate"))
            || !optionalRate(state, "rate") || !optionalRate(state, "contentAppliedRate")
            || !state.has("videoContext") || !state.has("contentError")
            || !(state.isNull("videoContext") || NativeProtocol.context(state.opt("videoContext")))
            || !(state.isNull("contentError") || "rate-rejected".equals(state.opt("contentError")))
            || !(state.isNull("controlsVisible") || state.opt("controlsVisible") instanceof Boolean)) return;
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
        contentSpeedAvailable = Boolean.TRUE.equals(state.opt("contentSpeedAvailable"));
        seekAvailable = Boolean.TRUE.equals(state.opt("seekAvailable"));
        if (!contentSpeedAvailable && speedSelector != null) speedSelector.dismiss();
        contentRate = ((Number) state.opt("contentRate")).doubleValue();
        pageControls = state.isNull("controlsVisible") ? null : (Boolean) state.opt("controlsVisible");
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
        pageControls = null;
        contentSpeedAvailable = false;
        seekAvailable = false;
        contentRate = 1;
        pendingCommand = 0;
        if (speedSelector != null) speedSelector.dismiss();
        updateSpeedControls(false);
    }

    private void updateSpeedControls(boolean rejected) {
        String label = rejected ? getString(R.string.speed_unavailable)
            : getString(R.string.content_speed, SpeedSlider.label(contentRate).replace("×", ""));
        fullscreenSpeedButton.setText(rejected ? "! ×" : SpeedSlider.label(contentRate));
        fullscreenSpeedButton.setContentDescription(label);
        showSpeedButton();
        fullscreenSpeedButton.setEnabled(videoContext != null && !paused && contentSpeedAvailable);
        updateSeekButtons();
    }

    private void revealSpeedButton() {
        speedRevealed = true;
        handler.removeCallbacks(fadeSpeedButton);
        handler.postDelayed(fadeSpeedButton, 3000);
        showSpeedButton();
    }

    private boolean speedButtonShown() {
        return draggingSpeed || speedSelector != null || (pageControls != null ? pageControls : speedRevealed);
    }

    private void showSpeedButton() {
        View button = fullscreenChip;
        if (!fullscreen || !contentSpeedAvailable) {
            button.animate().cancel();
            button.setAlpha(1);
            button.setVisibility(View.GONE);
        } else if (speedButtonShown()) {
            if (button.getVisibility() != View.VISIBLE) {
                button.setAlpha(0);
                button.setVisibility(View.VISIBLE);
            }
            button.animate().alpha(1).setDuration(200).withEndAction(null);
        } else if (button.getVisibility() == View.VISIBLE) {
            // Invisible, not just transparent, so a touch there reaches the video and reveals it.
            button.animate().alpha(0).setDuration(200).withEndAction(() -> {
                if (!speedButtonShown()) button.setVisibility(View.INVISIBLE);
            });
        }
    }

    private View seekIntervalPicker() {
        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setPadding(dp(24), dp(16), dp(24), dp(8));
        TextView title = new TextView(this);
        title.setText(R.string.seek_interval_title);
        picker.addView(title);
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.HORIZONTAL);
        for (int seconds : NativeProtocol.SEEK_CHOICES) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(getString(R.string.seek_choice, seconds));
            // Click, not the group's check listener, so restoring the saved choice sends nothing.
            choice.setOnClickListener(view -> setSeekInterval(seconds));
            choices.addView(choice, new RadioGroup.LayoutParams(0, -2, 1));
            if (seconds == seekInterval) choices.check(choice.getId());
        }
        picker.addView(choices);
        return picker;
    }

    private void chooseSpeed() {
        if (port == null || videoContext == null || paused || !contentSpeedAvailable) return;
        String context = videoContext;
        WebExtension.Port connection = port;
        boolean moved = chipX >= 0;
        // Each slider release or step applies at once; Done only closes the selector.
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(R.string.playback_speed);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(new SpeedSlider(this, contentRate, moved ? null : getString(R.string.speed_chip_hint), rate -> {
                if (connection != port || !videoContextEquals(context) || paused) {
                    if (speedSelector != null) speedSelector.dismiss();
                    showSpeedError();
                    return;
                }
                try {
                    int id = ++commandId;
                    pendingCommand = id;
                    connection.postMessage(new JSONObject().put("type", "content-speed").put("revision", revision)
                        .put("videoContext", context).put("commandId", id).put("rate", rate));
                    handler.postDelayed(() -> {
                        if (pendingCommand == id) { pendingCommand = 0; showSpeedError(); }
                    }, 2000);
                } catch (JSONException error) { throw new IllegalStateException(error); }
            }));
        body.addView(seekIntervalPicker());
        builder.setView(body).setPositiveButton(R.string.done, null);
        if (moved) builder.setNeutralButton(R.string.reset_chip_position, (dialog, which) -> resetChipPosition());
        speedSelector = builder.create();
        speedSelector.setOnDismissListener(dialog -> {
            speedSelector = null;
            if (fullscreen) revealSpeedButton();
        });
        speedSelector.show();
    }

    private void showSpeedError() { Toast.makeText(this, R.string.speed_rejected, Toast.LENGTH_SHORT).show(); }

    private void showError(int message) {
        cancelPull();
        refreshIndicator.finish();
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        reloadButton.setVisibility(extensionReady ? View.VISIBLE : View.GONE);
    }

    private void showFullscreen(boolean active) {
        if (fullscreen == active) return;
        fullscreen = active;
        endSpeedDrag();
        if (active) revealSpeedButton();
        cancelPull();
        refreshIndicator.hide();
        if (active) {
            previousOrientation = getRequestedOrientation();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else setRequestedOrientation(previousOrientation);
        updateSpeedControls(false);
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

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // The activity now survives window and theme changes, so Gecko must hear about them itself.
        if (runtime != null) runtime.configurationChanged(config);
    }

    @Override protected void onPause() {
        paused = true;
        endSpeedDrag();
        cancelPull();
        refreshIndicator.hide();
        if (speedSelector != null) speedSelector.dismiss();
        updateSpeedControls(false);
        applyState();
        // Restore speed and pause media before suspension; bound the wait if content hangs.
        if (port == null) suspendSession.run();
        else handler.postDelayed(suspendSession, 250);
        super.onPause();
    }

    private File sessionStateFile() { return new File(getFilesDir(), SessionStateStore.FILE_NAME); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (sessionState == null) return;
        try {
            SessionStateStore.save(sessionStateFile(), sessionState.toString());
            state.putBoolean("browserStateSaved", true);
        } catch (IOException error) {
            // Losing history on recreation is better than crashing on an oversized Bundle.
            SessionStateStore.clear(sessionStateFile());
        }
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
