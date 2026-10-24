package lab.youtube.accelerator;

import android.app.Activity;
import android.app.Dialog;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

/** HTTPS browser with one bundled accelerator extension: a browse page and a video player layer above it. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com/";
    private static final String EXTENSION_ID = "accelerator@ad-ccelerator.local";
    private static final int SPEED_BUTTON_WIDTH_DP = 64;
    private static final int SPEED_BUTTON_HEIGHT_DP = 48;
    private static final int SEEK_BUTTON_WIDTH_DP = 48;
    private static final int CHIP_DIVIDER_DP = 1;
    // Keeps the mini player clear of YouTube's bottom navigation bar.
    private static final int MINI_BOTTOM_MARGIN_DP = 64;
    private static final int MINI_MARGIN_DP = 12;
    // A release faster than this is a flick: it picks the mini player's corner, or expands it when upward.
    private static final int MINI_FLICK_DP_PER_S = 800;
    private static final int CHIP_WIDTH_DP = SEEK_BUTTON_WIDTH_DP * 2 + SPEED_BUTTON_WIDTH_DP + CHIP_DIVIDER_DP * 2;
    // GeckoRuntime may only be created once per process; sessions belong to activities.
    private static GeckoRuntime runtime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GeckoView web;
    private GeckoView watchView;
    private FrameLayout root;
    private FrameLayout watchFrame;
    private GradientDrawable watchBackground;
    private FrameLayout stage;
    private View miniOverlay;
    private ImageButton miniPlay;
    private Button miniClose;
    private LinearLayout controls;
    private TextView status;
    private WebExtension extension;
    private Layer browse;
    // The video player above the browse page, like YouTube's own app; null while closed.
    private Layer watch;
    private boolean mini;
    // Mini player corner: bit 0 is right, bit 1 is bottom.
    private int miniCorner = 3;
    private boolean draggingMini;
    private VelocityTracker miniVelocity;
    private float miniRawX;
    private float miniRawY;
    private float miniStartX;
    private float miniStartY;
    private boolean paused = true;
    private boolean fullscreen;
    private boolean extensionReady;
    private int previousSystemUiVisibility;
    private int previousOrientation;
    private Button reloadButton;
    private RefreshIndicator refreshIndicator;
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
    /** The last speed chosen for a normal video; each new one starts at it. */
    private double defaultSpeed = 1;
    private boolean contentSpeedAvailable = false;
    private int commandId;
    private int pendingCommand;
    private Dialog speedSelector;
    private TextView seekFlash;
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

    private final Runnable hideSeekFlash = () ->
        seekFlash.animate().alpha(0).setDuration(150).withEndAction(() -> seekFlash.setVisibility(View.GONE));

    /** One page surface with its own session, extension port and history. */
    private final class Layer {
        final boolean isWatch;
        final GeckoView view;
        final GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(true).build());
        final Runnable suspend = () -> { if (paused && session.isOpen()) session.setActive(false); };
        WebExtension.Port port;
        GeckoSession.SessionState sessionState;
        String currentUrl = HOME;
        boolean canGoBack;
        boolean loadFailed;
        int revision;
        float scrollY;
        boolean scrollKnown;
        boolean playing;
        // Set when a tap opens a video; the new page plays it once loaded, as YouTube's own navigation would.
        boolean autoplay;

        Layer(boolean isWatch, GeckoView view) {
            this.isWatch = isWatch;
            this.view = view;
            installDelegates(this);
            session.open(runtime);
            view.setSession(session);
            if (extension != null) connectExtension(this);
        }
    }

    /** The layer in view; the speed chip, pull-to-refresh and error banner follow it. */
    private Layer front() { return watch != null && !mini ? watch : browse; }

    private Layer[] layers() { return watch == null ? new Layer[] {browse} : new Layer[] {browse, watch}; }

    // The browse page sleeps while the full player covers it; the player keeps playing as the mini player.
    private boolean shown(Layer layer) { return layer.isWatch || watch == null || mini; }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        // Retire prototype-only preferences without touching the browser profile.
        getPreferences(MODE_PRIVATE).edit().remove("enabled").remove("searchHistory").apply();
        chipX = getPreferences(MODE_PRIVATE).getFloat("speedChipX", -1);
        chipY = getPreferences(MODE_PRIVATE).getFloat("speedChipY", -1);
        miniCorner = getPreferences(MODE_PRIVATE).getInt("miniCorner", 3) & 3;
        int savedSeek = getPreferences(MODE_PRIVATE).getInt("seekInterval", 10);
        seekInterval = NativeProtocol.seekInterval(savedSeek) ? savedSeek : 10;
        defaultSpeed = NativeProtocol.snapRate(getPreferences(MODE_PRIVATE).getFloat("defaultSpeed", 1));
        buildUi();
        // A file left by a crash or killed process must not resurface on a later cold start.
        if (savedState == null) {
            SessionStateStore.clear(stateFile(SessionStateStore.FILE_NAME));
            SessionStateStore.clear(stateFile(SessionStateStore.WATCH_FILE_NAME));
        }
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
        browse = new Layer(false, web);
        runtime.getWebExtensionController().ensureBuiltIn(
            "resource://android/assets/accelerator/", EXTENSION_ID).accept(installed -> {
                if (isDestroyed()) return;
                extension = installed;
                connectExtension(browse);
                extensionReady = true;
                if (savedState != null && savedState.getBoolean("browserStateSaved")) {
                    String saved = SessionStateStore.take(stateFile(SessionStateStore.FILE_NAME));
                    if (saved != null) browse.sessionState = GeckoSession.SessionState.fromString(saved);
                }
                if (browse.sessionState != null) browse.session.restoreState(browse.sessionState);
                else browse.session.loadUri(HOME);
                if (savedState != null && savedState.getBoolean("watchStateSaved")) {
                    String saved = SessionStateStore.take(stateFile(SessionStateStore.WATCH_FILE_NAME));
                    if (saved != null) openWatch(null, GeckoSession.SessionState.fromString(saved), savedState.getBoolean("watchMini"));
                }
            }, error -> {
                if (!isDestroyed()) showError(R.string.assets_unavailable);
            });
    }

    private void connectExtension(Layer layer) {
        layer.session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port connection) {
                WebExtension.MessageSender sender = connection.sender;
                if (isDestroyed() || sender.session != layer.session || !sender.isTopLevel()
                    || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
                    || !EXTENSION_ID.equals(sender.webExtension.id)
                    || !UrlPolicy.trusted(sender.url) || !"accelerator".equals(connection.name)) {
                    connection.disconnect();
                    return;
                }
                if (layer.port != null) layer.port.disconnect();
                layer.port = connection;
                connection.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object message, WebExtension.Port source) {
                        receiveMessage(message, layer, source);
                    }
                    @Override public void onDisconnect(WebExtension.Port source) {
                        if (layer.port == source) {
                            layer.port = null;
                            if (layer == front()) resetContentControls();
                        }
                    }
                });
                sendLayer(layer);
                sendDefaultSpeed(layer); // Before the page resumes, so its first video starts at it.
                applyState(layer);
                sendChipPosition(layer);
                sendSeekInterval(layer);
            }
        }, "accelerator");
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
        stage = new FrameLayout(this);
        browser.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new GeckoView(this);
        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.setOnTouchListener(this::pullToRefresh);
        watchFrame = new FrameLayout(this);
        watchBackground = new GradientDrawable();
        watchBackground.setColor(Color.BLACK);
        watchFrame.setBackground(watchBackground);
        watchFrame.setClipToOutline(true);
        watchView = new GeckoView(this);
        // A texture, unlike a surface, stacks above the browse page's surface and clips to the mini player's corners.
        watchView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
        watchView.setOnTouchListener(this::pullToRefresh);
        watchFrame.addView(watchView, new FrameLayout.LayoutParams(-1, -1));
        // The mini player is one tap target; the page inside it gets no touches.
        miniOverlay = new View(this);
        miniOverlay.setContentDescription(getString(R.string.expand_player));
        miniOverlay.setOnClickListener(view -> setMini(false));
        miniOverlay.setOnTouchListener(this::dragMini);
        watchFrame.addView(miniOverlay, new FrameLayout.LayoutParams(-1, -1));
        miniPlay = new ImageButton(this);
        miniPlay.setBackground(miniButtonBackground());
        miniPlay.setScaleType(ImageView.ScaleType.CENTER);
        miniPlay.setPadding(0, 0, 0, 0);
        miniPlay.setOnClickListener(view -> togglePlayback());
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.START);
        playParams.setMargins(dp(4), dp(4), 0, 0);
        watchFrame.addView(miniPlay, playParams);
        miniClose = new Button(this);
        miniClose.setBackground(miniButtonBackground());
        miniClose.setStateListAnimator(null);
        miniClose.setMinWidth(0);
        miniClose.setMinimumWidth(0);
        miniClose.setMinHeight(0);
        miniClose.setMinimumHeight(0);
        miniClose.setPadding(0, 0, 0, 0);
        miniClose.setText(R.string.close_player_symbol);
        miniClose.setTextColor(Color.WHITE);
        miniClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        miniClose.setContentDescription(getString(R.string.close_player));
        miniClose.setOnClickListener(view -> closeWatch());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(4), dp(4), 0);
        watchFrame.addView(miniClose, closeParams);
        stage.addView(watchFrame);
        stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return;
            // The mini player's page follows the stage size; relaid out after this pass.
            if (watch != null && mini) handler.post(this::layoutWatch);
            else placeMini(false);
        });
        layoutWatch();
        refreshIndicator = new RefreshIndicator(this);
        root.addView(refreshIndicator, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        browser.setFocusableInTouchMode(true);
        browser.requestFocus();
        fullscreenChip = new LinearLayout(this);
        fullscreenChip.setOrientation(LinearLayout.HORIZONTAL);
        seekBackButton = new Button(this);
        seekForwardButton = new Button(this);
        fullscreenSpeedButton = new Button(this);
        for (Button button : new Button[] {seekBackButton, fullscreenSpeedButton, seekForwardButton}) button.setAllCaps(false);
        styleSegment(fullscreenSpeedButton);
        styleSeekButton(seekBackButton, R.drawable.ic_seek_back);
        styleSeekButton(seekForwardButton, R.drawable.ic_seek_forward);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xD9181818);
        pill.setCornerRadius(dp(24));
        fullscreenChip.setBackground(pill);
        fullscreenChip.setClipToOutline(true);
        GradientDrawable line = new GradientDrawable();
        line.setColor(0x38FFFFFF);
        line.setSize(dp(CHIP_DIVIDER_DP), 0);
        fullscreenChip.setDividerDrawable(new InsetDrawable(line, 0, dp(12), 0, dp(12)));
        fullscreenChip.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        seekBackButton.setOnClickListener(view -> sendSeek(-1));
        seekForwardButton.setOnClickListener(view -> sendSeek(1));
        fullscreenSpeedButton.setOnClickListener(view -> chooseSpeed());
        fullscreenSpeedButton.setOnTouchListener(this::dragSpeedButton);
        fullscreenChip.addView(seekBackButton, new LinearLayout.LayoutParams(dp(SEEK_BUTTON_WIDTH_DP), -1));
        fullscreenChip.addView(fullscreenSpeedButton, new LinearLayout.LayoutParams(dp(SPEED_BUTTON_WIDTH_DP), -1));
        fullscreenChip.addView(seekForwardButton, new LinearLayout.LayoutParams(dp(SEEK_BUTTON_WIDTH_DP), -1));
        // Positioned by translation so dragging and rotation never trigger a relayout.
        root.addView(fullscreenChip, new FrameLayout.LayoutParams(
            dp(CHIP_WIDTH_DP), dp(SPEED_BUTTON_HEIGHT_DP), Gravity.TOP | Gravity.START));
        seekFlash = new TextView(this);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(0xD9181818);
        bubble.setCornerRadius(dp(12));
        seekFlash.setBackground(bubble);
        seekFlash.setTextColor(Color.WHITE);
        seekFlash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        seekFlash.setTypeface(Typeface.DEFAULT_BOLD);
        seekFlash.setPadding(dp(10), dp(4), dp(10), dp(4));
        seekFlash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        seekFlash.setVisibility(View.GONE);
        root.addView(seekFlash, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            placeSpeedButton());
        fullscreenChip.setVisibility(View.GONE);
        updateSeekButtons();
        resetContentControls();
    }

    /** Observes the front layer only; Gecko keeps ownership of scrolling and player gestures. */
    private boolean pullToRefresh(View view, MotionEvent event) {
        if (browse == null || view != front().view) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (fullscreen) revealSpeedButton();
                pulling = pageAtTop() && !fullscreen && !paused && !front().loadFailed
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
        return false;
    }

    private void styleSegment(Button button) {
        // Transparent segment inside the chip's single dark pill.
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, new ColorDrawable(Color.WHITE)));
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

    private void styleSeekButton(Button button, int icon) {
        styleSegment(button);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {getDrawable(icon), button.getBackground()});
        layers.setLayerGravity(0, Gravity.CENTER);
        layers.setLayerSize(0, dp(30), dp(30));
        button.setBackground(layers);
        button.setPadding(0, dp(2), 0, 0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
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

    private void sendChipPosition() { for (Layer layer : layers()) sendChipPosition(layer); }

    private void sendChipPosition(Layer layer) {
        if (layer.port == null) return;
        try {
            layer.port.postMessage(new JSONObject().put("type", "chip-position")
                .put("x", chipX < 0 ? JSONObject.NULL : chipX).put("y", chipY < 0 ? JSONObject.NULL : chipY));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void updateSeekButtons() {
        seekBackButton.setText(String.valueOf(seekInterval));
        seekForwardButton.setText(String.valueOf(seekInterval));
        seekBackButton.setContentDescription(getString(R.string.seek_back_description, seekInterval));
        seekForwardButton.setContentDescription(getString(R.string.seek_forward_description, seekInterval));
        boolean enabled = videoContext != null && !paused && contentSpeedAvailable && seekAvailable;
        seekBackButton.setEnabled(enabled);
        seekForwardButton.setEnabled(enabled);
        seekBackButton.setAlpha(enabled ? 1 : 0.4f);
        seekForwardButton.setAlpha(enabled ? 1 : 0.4f);
    }

    private void setSeekInterval(int seconds) {
        if (!NativeProtocol.seekInterval(seconds) || seconds == seekInterval) return;
        seekInterval = seconds;
        getPreferences(MODE_PRIVATE).edit().putInt("seekInterval", seconds).apply();
        updateSeekButtons();
        sendSeekInterval();
    }

    private void sendSeekInterval() { for (Layer layer : layers()) sendSeekInterval(layer); }

    private void sendSeekInterval(Layer layer) {
        if (layer.port == null) return;
        try {
            layer.port.postMessage(new JSONObject().put("type", "seek-interval").put("seconds", seekInterval));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void setDefaultSpeed(double rate) {
        rate = NativeProtocol.snapRate(rate);
        if (rate == defaultSpeed) return;
        defaultSpeed = rate;
        getPreferences(MODE_PRIVATE).edit().putFloat("defaultSpeed", (float) rate).apply();
        for (Layer layer : layers()) sendDefaultSpeed(layer);
    }

    private void sendDefaultSpeed(Layer layer) {
        if (layer.port == null) return;
        try {
            layer.port.postMessage(new JSONObject().put("type", "default-speed").put("rate", defaultSpeed));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void sendSeek(int direction) {
        Layer layer = front();
        if (layer.port == null || videoContext == null || paused || !seekAvailable || !fullscreen) return;
        revealSpeedButton();
        try {
            layer.port.postMessage(new JSONObject().put("type", "seek").put("revision", layer.revision)
                .put("videoContext", videoContext).put("seconds", direction * seekInterval));
            showSeekFlash(direction);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void showSeekFlash(int direction) {
        seekFlash.setText(getString(direction < 0 ? R.string.seek_flash_back : R.string.seek_flash_forward, seekInterval));
        seekFlash.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // Centred under the chip, wherever it has been dragged.
        seekFlash.setTranslationX(fullscreenChip.getTranslationX() + (dp(CHIP_WIDTH_DP) - seekFlash.getMeasuredWidth()) / 2f);
        seekFlash.setTranslationY(fullscreenChip.getTranslationY() + dp(SPEED_BUTTON_HEIGHT_DP) + dp(4));
        seekFlash.animate().cancel();
        seekFlash.setAlpha(1);
        seekFlash.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekFlash);
        handler.postDelayed(hideSeekFlash, 600);
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
        Layer layer = front();
        return layer.scrollKnown && layer.scrollY <= 1;
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
        Layer layer = front();
        if (layer.loadFailed) navigate(layer, UrlPolicy.browsableHttps(layer.currentUrl) ? layer.currentUrl : HOME);
        else layer.session.reload();
    }

    private void installDelegates(Layer layer) {
        GeckoSession session = layer.session;
        // Compositor updates include the initial viewport and asynchronous gesture scrolling.
        session.setCompositorScrollDelegate(new GeckoSession.CompositorScrollDelegate() {
            @Override public void onScrollChanged(GeckoSession source, GeckoSession.ScrollPositionUpdate position) {
                layer.scrollKnown = true;
                layer.scrollY = position.scrollY;
                if (layer == front() && !pageAtTop()) cancelPull();
            }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession source, LoadRequest request) {
                if (!UrlPolicy.browsableHttps(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
                // There are no tabs. Keep popup links in the same browser profile.
                if (request.target == TARGET_WINDOW_NEW) {
                    handler.post(() -> openUrl(request.uri));
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override public void onLocationChange(GeckoSession source, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                if (url == null) return;
                layer.currentUrl = url;
                if (layer == front()) {
                    resetContentControls();
                    if (!layer.loadFailed) status.setVisibility(View.GONE);
                }
                // The page reroutes tapped links itself; this catches YouTube navigating without a link.
                if (!layer.isWatch && UrlPolicy.watch(url)) handler.post(() -> moveToWatch(url));
                else if (layer.isWatch && !UrlPolicy.watch(url) && UrlPolicy.browsableHttps(url))
                    handler.post(() -> leaveWatch(layer, url));
            }
            @Override public void onCanGoBack(GeckoSession source, boolean available) { layer.canGoBack = available; }
            @Override public GeckoResult<String> onLoadError(GeckoSession source, String uri, WebRequestError error) {
                layer.loadFailed = true;
                if (layer == front()) {
                    resetContentControls();
                    showFullscreen(false);
                    showError(R.string.load_error);
                }
                return null; // Retain Gecko's normal error page and certificate protections.
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession source, String url) {
                layer.loadFailed = false;
                layer.scrollKnown = false;
                if (layer.port != null) { layer.port.disconnect(); layer.port = null; }
                if (layer != front()) return;
                cancelPull();
                reloadButton.setVisibility(View.GONE);
                resetContentControls();
                showFullscreen(false);
                status.setVisibility(View.GONE);
            }
            @Override public void onPageStop(GeckoSession source, boolean success) {
                layer.loadFailed = !success;
                if (layer != front()) return;
                refreshIndicator.finish();
                if (success) {
                    status.setVisibility(View.GONE);
                    reloadButton.setVisibility(View.GONE);
                }
                else showError(R.string.load_error);
            }
            @Override public void onSessionStateChange(GeckoSession source, GeckoSession.SessionState state) {
                layer.sessionState = state;
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onFullScreen(GeckoSession source, boolean active) {
                if (layer == front()) showFullscreen(active);
                else if (active) source.exitFullScreen();
            }
            @Override public void onCrash(GeckoSession source) {
                recoverSession(layer);
            }
            @Override public void onKill(GeckoSession source) { recoverSession(layer); }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession source, ContentPermission permission) {
                // Each video loads in a fresh, untapped document, so YouTube and the mini player's play need autoplay.
                boolean autoplay = permission.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || permission.permission == PERMISSION_AUTOPLAY_INAUDIBLE;
                return GeckoResult.fromValue(autoplay && UrlPolicy.trusted(permission.uri)
                    ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
            }
        }); // Camera/microphone/Android permissions also reject by default.
    }

    private void recoverSession(Layer layer) {
        layer.port = null;
        layer.loadFailed = true;
        // A crashed player is simply closed; the browse page underneath recovers below.
        if (layer.isWatch) {
            handler.post(() -> { if (watch == layer) closeWatch(); });
            return;
        }
        if (layer == front()) {
            resetContentControls();
            showFullscreen(false);
            showError(R.string.browser_crashed);
        }
        layer.session.open(runtime);
        layer.session.setActive(!paused && shown(layer));
        // Reload loads currentUrl explicitly because a reopened session has no history.
    }

    private void navigate(Layer layer, String url) {
        if (!UrlPolicy.browsableHttps(url)) {
            showError(R.string.https_required);
        } else if (extensionReady) layer.session.loadUri(url);
    }

    /** Videos open in the player layer; any other page opens underneath it and the player shrinks. */
    private void openUrl(String url) {
        if (!UrlPolicy.browsableHttps(url)) {
            showError(R.string.https_required);
            return;
        }
        if (!extensionReady) return;
        if (UrlPolicy.watch(url)) openWatch(url, null, false);
        else {
            setMini(true);
            browse.session.loadUri(url);
        }
    }

    // A new video replaces the one in the player rather than stacking another layer.
    private void openWatch(String url, GeckoSession.SessionState state, boolean startMini) {
        if (watch == null) watch = new Layer(true, watchView);
        if (state != null) watch.session.restoreState(state);
        else watch.session.loadUri(url);
        watch.autoplay = state == null && !startMini;
        showWatch(startMini);
    }

    private void moveToWatch(String url) {
        if (isDestroyed() || !url.equals(browse.currentUrl)) return;
        openWatch(url, null, false);
        if (browse.canGoBack) browse.session.goBack();
        else browse.session.loadUri(HOME);
    }

    private void leaveWatch(Layer layer, String url) {
        if (isDestroyed() || layer != watch || !url.equals(layer.currentUrl)) return;
        closeWatch();
        browse.session.loadUri(url);
    }

    private void setMini(boolean value) {
        if (watch != null && mini != value) showWatch(value);
    }

    private void showWatch(boolean value) {
        if (value && fullscreen) {
            watch.session.exitFullScreen();
            showFullscreen(false);
        }
        mini = value;
        layoutWatch();
        sendLayer(watch);
        frontChanged();
    }

    private void closeWatch() {
        if (watch == null) return;
        if (fullscreen) showFullscreen(false);
        Layer layer = watch;
        watch = null;
        mini = false;
        handler.removeCallbacks(layer.suspend);
        if (layer.port != null) { layer.port.disconnect(); layer.port = null; }
        watchView.releaseSession();
        layer.session.close();
        layoutWatch();
        frontChanged();
    }

    private void frontChanged() {
        cancelPull();
        refreshIndicator.hide();
        resetContentControls();
        for (Layer layer : layers()) layer.session.setActive(!paused && shown(layer));
        Layer layer = front();
        if (layer.loadFailed) showError(R.string.load_error);
        else {
            status.setVisibility(View.GONE);
            reloadButton.setVisibility(View.GONE);
        }
        applyState(layer); // A new revision makes the page report now rather than on its next heartbeat.
    }

    private void layoutWatch() {
        boolean open = watch != null;
        boolean small = open && mini;
        watchFrame.setVisibility(open ? View.VISIBLE : View.GONE);
        miniOverlay.setVisibility(small ? View.VISIBLE : View.GONE);
        miniClose.setVisibility(small ? View.VISIBLE : View.GONE);
        miniPlay.setVisibility(small ? View.VISIBLE : View.GONE);
        updateMiniPlay();
        // Screen readers skip whichever page is covered or shrunk to a single tap target.
        web.setImportantForAccessibility(open && !small
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        watchView.setImportantForAccessibility(small
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        FrameLayout.LayoutParams page = new FrameLayout.LayoutParams(-1, -1);
        float scale = 1;
        if (small) {
            // Half the screen's short side keeps one size through rotation.
            int width = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) / 2;
            params = new FrameLayout.LayoutParams(width, width * 9 / 16, Gravity.TOP | Gravity.START);
            // YouTube pauses a player it sees shrink ("tiny mode"), so the page keeps the full player size and is
            // only drawn scaled down; the page pins its player to the top strip this frame shows.
            int pageWidth = stage.getWidth() > 0 ? stage.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int pageHeight = stage.getHeight() > 0 ? stage.getHeight() : getResources().getDisplayMetrics().heightPixels;
            page = new FrameLayout.LayoutParams(pageWidth, pageHeight, Gravity.TOP | Gravity.START);
            scale = width / (float) pageWidth;
        }
        watchFrame.setLayoutParams(params);
        watchView.setLayoutParams(page);
        watchView.setPivotX(0);
        watchView.setPivotY(0);
        watchView.setScaleX(scale);
        watchView.setScaleY(scale);
        watchBackground.setCornerRadius(small ? dp(8) : 0);
        watchFrame.setElevation(small ? dp(8) : 0);
        placeMini(false);
    }

    private Drawable miniButtonBackground() {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xB3000000);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), dot, null);
    }

    private float miniLeft() { return dp(MINI_MARGIN_DP); }

    private float miniTop() { return dp(MINI_MARGIN_DP); }

    private float miniRight() {
        return Math.max(miniLeft(), stage.getWidth() - watchFrame.getLayoutParams().width - dp(MINI_MARGIN_DP));
    }

    // The bottom corners keep clear of YouTube's bottom navigation bar.
    private float miniBottom() {
        return Math.max(miniTop(), stage.getHeight() - watchFrame.getLayoutParams().height - dp(MINI_BOTTOM_MARGIN_DP));
    }

    private void placeMini(boolean animate) {
        boolean small = watch != null && mini;
        float x = !small ? 0 : (miniCorner & 1) != 0 ? miniRight() : miniLeft();
        float y = !small ? 0 : (miniCorner & 2) != 0 ? miniBottom() : miniTop();
        watchFrame.animate().cancel();
        if (animate) watchFrame.animate().translationX(x).translationY(y).alpha(1).setDuration(200);
        else {
            watchFrame.setTranslationX(x);
            watchFrame.setTranslationY(y);
            watchFrame.setAlpha(1);
        }
    }

    /** How far the mini player has been dragged past the left or right edge of its travel. */
    private float miniOvershoot(float x) { return Math.max(0, Math.max(miniLeft() - x, x - miniRight())); }

    /** A drag moves the mini player, which then settles in the nearest corner; a tap expands it. */
    private boolean dragMini(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                watchFrame.animate().cancel();
                draggingMini = false;
                if (miniVelocity != null) miniVelocity.recycle();
                miniVelocity = VelocityTracker.obtain();
                trackMini(event);
                miniRawX = event.getRawX();
                miniRawY = event.getRawY();
                miniStartX = watchFrame.getTranslationX();
                miniStartY = watchFrame.getTranslationY();
                return true;
            case MotionEvent.ACTION_MOVE:
                trackMini(event);
                float dx = event.getRawX() - miniRawX;
                float dy = event.getRawY() - miniRawY;
                if (!draggingMini && Math.hypot(dx, dy) > ViewConfiguration.get(this).getScaledTouchSlop()) draggingMini = true;
                if (draggingMini) {
                    // Sideways it may leave the screen, fading as it goes, to be dismissed.
                    float width = watchFrame.getWidth();
                    float x = Math.min(miniRight() + width, Math.max(miniLeft() - width, miniStartX + dx));
                    watchFrame.setTranslationX(x);
                    watchFrame.setTranslationY(Math.min(miniBottom(), Math.max(miniTop(), miniStartY + dy)));
                    watchFrame.setAlpha(1 - 0.7f * Math.min(1, miniOvershoot(x) / Math.max(1, width)));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!draggingMini) {
                    view.performClick();
                    return true;
                }
                draggingMini = false;
                float vx = 0;
                float vy = 0;
                if (miniVelocity != null) {
                    trackMini(event);
                    miniVelocity.computeCurrentVelocity(1000);
                    vx = miniVelocity.getXVelocity();
                    vy = miniVelocity.getYVelocity();
                    miniVelocity.recycle();
                    miniVelocity = null;
                }
                float flick = dp(MINI_FLICK_DP_PER_S);
                if (-vy > flick && -vy > Math.abs(vx)) {
                    expandMini();
                    return true;
                }
                float x = watchFrame.getTranslationX();
                float y = watchFrame.getTranslationY();
                boolean onRight = x - miniLeft() > miniRight() - x;
                // A sideways flick toward the edge it already sits on, or a drag mostly off it, dismisses it.
                boolean outward = Math.abs(vx) > flick && Math.abs(vx) > Math.abs(vy) && vx > 0 == onRight;
                if (outward || miniOvershoot(x) > watchFrame.getWidth() * 0.4f) {
                    dismissMini(onRight);
                    return true;
                }
                // A flick picks the corner it points to; a slow release settles in the nearest one.
                boolean right = Math.abs(vx) > flick ? vx > 0 : x - miniLeft() > miniRight() - x;
                boolean bottom = vy > flick || (Math.abs(vy) <= flick && y - miniTop() > miniBottom() - y);
                miniCorner = (right ? 1 : 0) | (bottom ? 2 : 0);
                getPreferences(MODE_PRIVATE).edit().putInt("miniCorner", miniCorner).apply();
                placeMini(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingMini = false;
                if (miniVelocity != null) { miniVelocity.recycle(); miniVelocity = null; }
                placeMini(true);
                return true;
            default: return true;
        }
    }

    // Screen coordinates: the overlay moves with the finger, so its own coordinates barely change.
    private void trackMini(MotionEvent event) {
        if (miniVelocity == null) return;
        MotionEvent screen = MotionEvent.obtain(event);
        screen.setLocation(event.getRawX(), event.getRawY());
        miniVelocity.addMovement(screen);
        screen.recycle();
    }

    // The mini player slides to the top before it fills the screen, so the expansion reads as one upward motion.
    private void expandMini() {
        watchFrame.animate().cancel();
        watchFrame.animate().translationY(0).setDuration(150).withEndAction(() -> setMini(false));
    }

    private void dismissMini(boolean toRight) {
        watchFrame.animate().cancel();
        float x = toRight ? stage.getWidth() : -watchFrame.getWidth();
        watchFrame.animate().translationX(x).alpha(0).setDuration(180).withEndAction(this::closeWatch);
    }

    private void updateMiniPlay() {
        boolean playing = watch != null && watch.playing;
        miniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        miniPlay.setContentDescription(getString(playing ? R.string.pause_video : R.string.play_video));
    }

    private void togglePlayback() {
        if (watch == null || !mini || watch.port == null || paused) return;
        try {
            watch.port.postMessage(new JSONObject().put("type", "playback").put("play", !watch.playing)
                .put("revision", watch.revision));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        watch.playing = !watch.playing; // The page's next status confirms or corrects this.
        updateMiniPlay();
    }

    private void sendLayer(Layer layer) {
        if (layer == null || layer.port == null) return;
        try {
            layer.port.postMessage(new JSONObject().put("type", "layer").put("watch", layer.isWatch)
                .put("mini", layer.isWatch && mini).put("autoplay", layer.autoplay));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void applyState() { for (Layer layer : layers()) applyState(layer); }

    private void applyState(Layer layer) {
        if (layer.port == null) return;
        try {
            layer.port.postMessage(new JSONObject().put("type", "state").put("enabled", true)
                .put("paused", paused).put("revision", ++layer.revision));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void receiveMessage(Object message, Layer layer, WebExtension.Port source) {
        if (source != layer.port || !(message instanceof JSONObject) || isDestroyed()) return;
        JSONObject state = (JSONObject) message;
        if (!NativeProtocol.integer(state.opt("revision"), layer.revision)) return;
        Object type = state.opt("type");
        boolean front = layer == front();
        if ("open-watch".equals(type) || "open-browse".equals(type)) {
            Object target = state.opt("target");
            // Browse pages may only open videos; the player may only send other pages down to browse.
            if (front && !paused && layer.isWatch == "open-browse".equals(type) && currentDocument(layer, state)
                && target instanceof String && UrlPolicy.browsableHttps((String) target)
                && layer.isWatch != UrlPolicy.watch((String) target)) openUrl((String) target);
            return;
        }
        if ("open-speed-selector".equals(type)) {
            if (front && !paused && !layer.loadFailed && !fullscreen && currentDocument(layer, state)
                && contentSpeedAvailable && videoContextEquals(state.opt("videoContext")) && speedSelector == null) chooseSpeed();
            return;
        }
        if ("chip-position".equals(type)) {
            if (front && !paused && !layer.loadFailed && currentDocument(layer, state)
                && NativeProtocol.fraction(state.opt("x")) && NativeProtocol.fraction(state.opt("y"))) {
                saveChipPosition(((Number) state.opt("x")).floatValue(), ((Number) state.opt("y")).floatValue());
            }
            return;
        }
        if ("speed-result".equals(type)) {
            if (!front || paused || !NativeProtocol.integer(state.opt("commandId"), pendingCommand)
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
        if (layer.isWatch && currentDocument(layer, state) && state.opt("playing") instanceof Boolean) {
            layer.playing = (Boolean) state.opt("playing");
            if (layer.playing) layer.autoplay = false;
            updateMiniPlay();
        }
        if (paused) {
            if (Boolean.TRUE.equals(state.opt("paused"))) {
                handler.removeCallbacks(layer.suspend);
                layer.suspend.run();
            }
            return;
        }
        if (!front || !currentDocument(layer, state) || layer.loadFailed || !Boolean.FALSE.equals(state.opt("paused"))
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
        // Selector and YouTube-menu choices both land here; Shorts are YouTube's and are not remembered.
        if (contentSpeedAvailable && !rejected && videoContext != null && NativeProtocol.selectableRate(contentRate))
            setDefaultSpeed(contentRate);

    }

    private static boolean optionalRate(JSONObject state, String key) {
        return state.has(key) && NativeProtocol.optionalRate(state.isNull(key) ? null : state.opt(key));
    }

    private boolean currentDocument(Layer layer, JSONObject state) {
        return UrlPolicy.trusted(layer.currentUrl) && layer.currentUrl.equals(state.opt("url"));
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

    private View sectionHeader(Context context, int text) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setAlpha(0.7f);
        if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        header.setPadding(dp(24), dp(16), dp(24), dp(4));
        return header;
    }

    private void chooseSpeed() {
        Layer layer = front();
        if (layer.port == null || videoContext == null || paused || !contentSpeedAvailable) return;
        String context = videoContext;
        WebExtension.Port connection = layer.port;
        boolean moved = chipX >= 0;
        Dialog sheet = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        Context themed = sheet.getContext();
        sheet.setTitle(R.string.playback_speed); // Window title for accessibility; this theme shows no title bar.
        LinearLayout body = new LinearLayout(themed);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, dp(8));
        View handle = new View(themed);
        GradientDrawable grip = new GradientDrawable();
        grip.setColor(0x66FFFFFF);
        grip.setCornerRadius(dp(2));
        handle.setBackground(grip);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(handle, handleParams);
        // Each slider release, step or mark applies at once; Done only closes the selector.
        body.addView(new SpeedSlider(themed, contentRate, moved ? null : getString(R.string.speed_chip_hint), rate -> {
                if (layer != front() || connection != layer.port || !videoContextEquals(context) || paused) {
                    if (speedSelector != null) speedSelector.dismiss();
                    showSpeedError();
                    return;
                }
                try {
                    int id = ++commandId;
                    pendingCommand = id;
                    connection.postMessage(new JSONObject().put("type", "content-speed").put("revision", layer.revision)
                        .put("videoContext", context).put("commandId", id).put("rate", rate));
                    handler.postDelayed(() -> {
                        if (pendingCommand == id) { pendingCommand = 0; showSpeedError(); }
                    }, 2000);
                } catch (JSONException error) { throw new IllegalStateException(error); }
            }));
        body.addView(sectionHeader(themed, R.string.seek_interval_title));
        String[] seekLabels = new String[NativeProtocol.SEEK_CHOICES.length];
        for (int i = 0; i < seekLabels.length; i++) seekLabels[i] = getString(R.string.seek_choice, NativeProtocol.SEEK_CHOICES[i]);
        SegmentedControl seekChoices = new SegmentedControl(themed, seekLabels, seekLabels,
            i -> setSeekInterval(NativeProtocol.SEEK_CHOICES[i]));
        for (int i = 0; i < NativeProtocol.SEEK_CHOICES.length; i++) {
            if (NativeProtocol.SEEK_CHOICES[i] == seekInterval) seekChoices.setSelectedIndex(i);
        }
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, -2);
        seekParams.setMargins(dp(24), dp(8), dp(24), dp(4));
        body.addView(seekChoices, seekParams);
        LinearLayout footer = new LinearLayout(themed);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(12), dp(16), 0);
        if (moved) {
            Button reset = new Button(themed, null, android.R.attr.borderlessButtonStyle);
            reset.setText(R.string.reset_chip_position);
            reset.setAllCaps(false);
            reset.setOnClickListener(view -> { resetChipPosition(); sheet.dismiss(); });
            footer.addView(reset);
        }
        Button done = new Button(themed, null, android.R.attr.borderlessButtonStyle);
        done.setText(R.string.done);
        done.setAllCaps(false);
        done.setOnClickListener(view -> sheet.dismiss());
        footer.addView(done);
        body.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(body);
        sheet.setContentView(scroll);
        Window window = sheet.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xF2202020);
            float r = dp(16);
            panel.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
            window.setBackgroundDrawable(panel);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            // Full width on phones in portrait, capped in landscape so the video stays visible beside it.
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels, dp(560)),
                WindowManager.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.3f);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        sheet.setCanceledOnTouchOutside(true);
        speedSelector = sheet;
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
        if (!active) {
            handler.removeCallbacks(hideSeekFlash);
            seekFlash.animate().cancel();
            seekFlash.setVisibility(View.GONE);
        }
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
        if (browse == null) return;
        for (Layer layer : layers()) {
            handler.removeCallbacks(layer.suspend);
            layer.session.setActive(shown(layer));
        }
        applyState();
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
        for (Layer layer : layers()) {
            if (layer.port == null) layer.suspend.run();
            else handler.postDelayed(layer.suspend, 250);
        }
        super.onPause();
    }

    private File stateFile(String name) { return new File(getFilesDir(), name); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (browse == null) return;
        saveLayer(state, browse, "browserStateSaved", SessionStateStore.FILE_NAME);
        if (watch != null && saveLayer(state, watch, "watchStateSaved", SessionStateStore.WATCH_FILE_NAME))
            state.putBoolean("watchMini", mini);
    }

    private boolean saveLayer(Bundle state, Layer layer, String key, String name) {
        if (layer.sessionState == null) return false;
        try {
            SessionStateStore.save(stateFile(name), layer.sessionState.toString());
            state.putBoolean(key, true);
            return true;
        } catch (IOException error) {
            // Losing history on recreation is better than crashing on an oversized Bundle.
            SessionStateStore.clear(stateFile(name));
            return false;
        }
    }

    // Android 8–12 fallback; Android 13+ uses the native callback registered above.
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (fullscreen) { front().session.exitFullScreen(); showFullscreen(false); }
        else if (watch != null && !mini) setMini(true);
        else if (browse.canGoBack) browse.session.goBack();
        else if (watch != null) closeWatch();
        else finish();
    }

    @Override protected void onDestroy() {
        showFullscreen(false);
        if (speedSelector != null) speedSelector.dismiss();
        handler.removeCallbacksAndMessages(null);
        if (browse != null) {
            for (Layer layer : layers()) {
                if (layer.port != null) { layer.port.disconnect(); layer.port = null; }
                layer.view.releaseSession();
                layer.session.close();
            }
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
