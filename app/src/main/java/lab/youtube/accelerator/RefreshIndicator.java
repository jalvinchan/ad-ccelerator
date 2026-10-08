package lab.youtube.accelerator;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** Decorative pull feedback; never takes touch ownership from Gecko. */
final class RefreshIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Path arrow = new Path();
    private final float density;
    private ValueAnimator animation;
    private float progress;
    private float spin;
    private boolean ready;
    private boolean loading;

    RefreshIndicator(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
        setElevation(6 * density);
    }

    boolean isLoading() { return loading; }

    void drag(float distance) {
        if (loading) return;
        stopAnimation();
        progress = Math.min(1, Math.max(0, distance / (96 * density)));
        boolean atThreshold = progress >= 1;
        if (atThreshold && !ready) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            announceForAccessibility(getContext().getString(R.string.release_to_reload));
        }
        ready = atThreshold;
        setContentDescription(getContext().getString(ready ? R.string.release_to_reload : R.string.pull_to_reload));
        setVisibility(progress > 0 ? VISIBLE : GONE);
        setAlpha(progress);
        setTranslationY(-getHeight() + progress * (getHeight() + 24 * density));
        invalidate();
    }

    void startLoading() {
        stopAnimation();
        loading = true;
        ready = false;
        progress = 1;
        setVisibility(VISIBLE);
        setAlpha(1);
        setTranslationY(24 * density);
        setContentDescription(getContext().getString(R.string.reloading));
        announceForAccessibility(getContext().getString(R.string.reloading));
        animation = ValueAnimator.ofFloat(0, 360);
        animation.setDuration(800);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.setInterpolator(new LinearInterpolator());
        animation.addUpdateListener(value -> {
            spin = (float) value.getAnimatedValue();
            invalidate();
        });
        animation.start();
    }

    void release() {
        if (!loading) finish();
    }

    void finish() {
        // Repeated scroll/cancel callbacks must not restart an in-flight return.
        if (!loading && animation != null) return;
        stopAnimation();
        loading = false;
        ready = false;
        if (getVisibility() != VISIBLE) return;
        float startY = getTranslationY();
        float startAlpha = getAlpha();
        animation = ValueAnimator.ofFloat(0, 1);
        animation.setDuration(200);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> {
            float fraction = (float) value.getAnimatedValue();
            setTranslationY(startY + (-getHeight() - startY) * fraction);
            setAlpha(startAlpha * (1 - fraction));
            if (fraction == 1) setVisibility(GONE);
        });
        animation.start();
    }

    void hide() {
        stopAnimation();
        loading = false;
        ready = false;
        setVisibility(GONE);
    }

    private void stopAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        hide();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 20 * density, paint);
        paint.setColor(ready ? Color.rgb(0, 105, 92) : Color.rgb(60, 64, 67));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((ready ? 3 : 2.5f) * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float radius = 10 * density;
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.save();
        canvas.rotate(loading ? spin : progress * 180, cx, cy);
        canvas.drawArc(arc, -90, loading ? 270 : 285, false, paint);
        if (!loading) {
            // Arrowhead at the end of the clockwise circular arc.
            double end = Math.toRadians(195);
            float x = cx + radius * (float) Math.cos(end);
            float y = cy + radius * (float) Math.sin(end);
            float tx = -(float) Math.sin(end);
            float ty = (float) Math.cos(end);
            arrow.reset();
            arrow.moveTo(x + tx * 3 * density, y + ty * 3 * density);
            arrow.lineTo(x - tx * 3 * density + ty * 4 * density,
                y - ty * 3 * density - tx * 4 * density);
            arrow.lineTo(x - tx * 3 * density - ty * 4 * density,
                y - ty * 3 * density + tx * 4 * density);
            arrow.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, paint);
        }
        canvas.restore();
    }
}
