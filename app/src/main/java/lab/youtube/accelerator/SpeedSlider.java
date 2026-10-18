package lab.youtube.accelerator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Selector body: a 0.25×–4× slider with whole-speed marks, an exact readout, ±0.05× steps and an optional note. */
@SuppressLint("ViewConstructor") // Built only in code with its rate and listener.
final class SpeedSlider extends LinearLayout {
    interface Listener { void onRate(double rate); }

    private static final double[] MARKS = {0.25, 1, 2, 3, 4};
    private final Listener listener;
    private final TextView readout;
    private final SeekBar slider;
    private final FrameLayout marks;
    private final Button slower;
    private final Button faster;
    private double applied;
    private boolean tracking;

    SpeedSlider(Context context, double rate, CharSequence note, Listener listener) {
        super(context);
        this.listener = listener;
        applied = NativeProtocol.snapRate(rate);
        setOrientation(VERTICAL);
        setPadding(dp(24), dp(8), dp(24), 0);

        readout = new TextView(context);
        readout.setGravity(Gravity.CENTER);
        readout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        readout.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(readout, new LayoutParams(-1, -2));

        slider = new TickedSeekBar(context);
        slider.setMax(NativeProtocol.RATE_STEPS);
        slider.setKeyProgressIncrement(1);
        slider.setContentDescription(context.getString(R.string.playback_speed));
        addView(slider, new LayoutParams(-1, dp(48)));

        marks = new FrameLayout(context);
        // Each whole-speed label is also a shortcut to that speed.
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        for (double mark : MARKS) {
            TextView label = new TextView(context);
            label.setText(label(mark));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setGravity(Gravity.CENTER);
            label.setMinWidth(dp(48));
            label.setBackgroundResource(ripple.resourceId);
            label.setContentDescription(context.getString(R.string.speed_mark_description, label(mark)));
            label.setOnClickListener(view -> {
                slider.setProgress(index(mark));
                apply();
            });
            marks.addView(label, new FrameLayout.LayoutParams(-2, -1));
        }
        addView(marks, new LayoutParams(-1, dp(48)));
        marks.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> placeMarks());

        LinearLayout steps = new LinearLayout(context);
        steps.setGravity(Gravity.CENTER);
        slower = stepButton(R.string.speed_slower, R.string.speed_slower_description, -1);
        faster = stepButton(R.string.speed_faster, R.string.speed_faster_description, 1);
        steps.addView(slower);
        steps.addView(faster);
        addView(steps, new LayoutParams(-1, -2));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                render();
                // Keyboard and accessibility adjustments have no release, so they apply at once.
                if (fromUser && !tracking) apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { tracking = false; apply(); }
        });
        slider.setProgress(index(applied));
        render();
        if (note != null) {
            TextView hint = new TextView(context);
            hint.setText(note);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setGravity(Gravity.CENTER);
            hint.setAlpha(0.7f);
            LayoutParams params = new LayoutParams(-1, -2);
            params.topMargin = dp(12);
            addView(hint, params);
        }
    }

    private Button stepButton(int text, int description, int direction) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(view -> {
            slider.setProgress(slider.getProgress() + direction);
            apply();
        });
        LayoutParams params = new LayoutParams(-2, -2);
        params.setMargins(dp(6), dp(8), dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    private double rate() { return NativeProtocol.snapRate(NativeProtocol.MIN_RATE + slider.getProgress() * NativeProtocol.RATE_STEP); }

    private static int index(double rate) {
        return (int) Math.round((rate - NativeProtocol.MIN_RATE) / NativeProtocol.RATE_STEP);
    }

    private void render() {
        String text = label(rate());
        readout.setText(text);
        if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription(text);
        slower.setEnabled(slider.getProgress() > 0);
        faster.setEnabled(slider.getProgress() < NativeProtocol.RATE_STEPS);
    }

    private void apply() {
        double rate = rate();
        if (rate == applied) return;
        applied = rate;
        listener.onRate(rate);
    }

    /** Aligns each whole-speed label under its point on the slider track. */
    private void placeMarks() {
        int trackLeft = slider.getLeft() - marks.getLeft() + slider.getPaddingLeft();
        int trackWidth = slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight();
        for (int i = 0; i < MARKS.length; i++) {
            View label = marks.getChildAt(i);
            float fraction = (float) ((MARKS[i] - NativeProtocol.MIN_RATE) / (NativeProtocol.MAX_RATE - NativeProtocol.MIN_RATE));
            float x = trackLeft + fraction * trackWidth - label.getWidth() / 2f;
            label.setTranslationX(Math.max(0, Math.min(marks.getWidth() - label.getWidth(), x)));
        }
    }

    static String label(double rate) {
        return java.math.BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString() + "×";
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
