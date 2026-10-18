package lab.youtube.accelerator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Row of equal-width, single-choice segments; listener fires only on user taps. */
@SuppressLint("ViewConstructor") // Built only in code by the speed sheet.
final class SegmentedControl extends LinearLayout {
    interface Listener { void onSelect(int index); }

    private final Context context;
    private final TextView[] segments;
    private final int selectedFill;
    private int selectedIndex = -1;

    SegmentedControl(Context context, String[] labels, String[] descriptions, Listener listener) {
        super(context);
        this.context = context;
        setOrientation(HORIZONTAL);
        setBackground(outline());
        setClipToOutline(true);
        TypedArray colors = context.obtainStyledAttributes(new int[] {android.R.attr.colorControlActivated});
        int accent = colors.getColor(0, Color.WHITE);
        colors.recycle();
        selectedFill = (accent & 0x00FFFFFF) | 0x40000000;
        segments = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView segment = new TextView(context);
            segment.setText(labels[i]);
            segment.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            segment.setGravity(Gravity.CENTER);
            segment.setClickable(true);
            segment.setFocusable(true);
            segment.setContentDescription(descriptions[i]);
            int index = i;
            segment.setOnClickListener(view -> { setSelectedIndex(index); listener.onSelect(index); });
            segments[i] = segment;
            addView(segment, new LayoutParams(0, dp(40), 1));
        }
        setSelectedIndex(-1);
    }

    private Drawable outline() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(20));
        TypedArray colors = context.obtainStyledAttributes(new int[] {android.R.attr.colorControlNormal});
        int stroke = colors.getColor(0, Color.GRAY);
        colors.recycle();
        shape.setStroke(dp(1), (stroke & 0x00FFFFFF) | 0x66000000);
        return shape;
    }

    // Resolved per use: one Drawable instance must not be shared between views.
    private Drawable ripple() {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return context.getDrawable(value.resourceId);
    }

    void setSelectedIndex(int index) {
        selectedIndex = index;
        for (int j = 0; j < segments.length; j++) {
            TextView segment = segments[j];
            segment.setSelected(j == index);
            segment.setTypeface(j == index ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            Drawable ripple = ripple();
            if (j != index) segment.setBackground(ripple);
            else segment.setBackground(new LayerDrawable(new Drawable[] {new ColorDrawable(selectedFill), ripple}));
        }
    }

    int getSelectedIndex() { return selectedIndex; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
