package lab.youtube.accelerator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.SeekBar;

/** Slider track with 0.25× tick lines; whole speeds longest, halves medium, quarters short. */
@SuppressLint("ViewConstructor") // Built only in code by SpeedSlider.
final class TickedSeekBar extends SeekBar {
    static final int NONE = 0, SHORT = 1, MEDIUM = 2, LONG = 3;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    TickedSeekBar(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(1.5f * density);
        tick.setStrokeCap(Paint.Cap.ROUND);
        TypedArray colors = context.obtainStyledAttributes(new int[] {android.R.attr.colorControlNormal});
        int color = colors.getColor(0, Color.GRAY);
        colors.recycle();
        tick.setColor((color & 0x00FFFFFF) | 0x99000000);
    }

    /** Tick level for slider progress index 0..RATE_STEPS (rate = MIN_RATE + index × RATE_STEP). */
    static int tickLevel(int index) {
        if (index < 0 || index > NativeProtocol.RATE_STEPS) return NONE;
        int units = index + 5; // Rate in 0.05× units; 0.25× is 5.
        if (index == 0 || units % 20 == 0) return LONG;   // 0.25× is labelled like the whole speeds.
        if (units % 10 == 0) return MEDIUM;
        return units % 5 == 0 ? SHORT : NONE;
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        int left = getPaddingLeft();
        int width = getWidth() - left - getPaddingRight();
        float centre = getHeight() / 2f;
        if (width > 0 && getMax() > 0) {
            for (int i = 0; i <= getMax(); i++) {
                int level = tickLevel(i);
                if (level == NONE) continue;
                float half = (level == LONG ? 8 : level == MEDIUM ? 5 : 3) * density;
                float x = left + width * (float) i / getMax();
                canvas.drawLine(x, centre - half, x, centre + half, tick);
            }
        }
        super.onDraw(canvas);
    }
}
