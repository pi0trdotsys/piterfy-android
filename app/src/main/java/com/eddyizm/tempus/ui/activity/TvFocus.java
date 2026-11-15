package com.eddyizm.tempus.ui.activity;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;

/** Provides a consistent focus indicator for the native controls when using a TV remote. */
public final class TvFocus {
    private final ViewGroup decor;
    private final GradientDrawable outline = new GradientDrawable();
    private final int[] viewPosition = new int[2];
    private final int[] windowPosition = new int[2];
    private final int inset;
    private View animatedFocus;

    private TvFocus(Activity activity) {
        decor = (ViewGroup) activity.getWindow().getDecorView();
        float density = activity.getResources().getDisplayMetrics().density;
        inset = Math.max(1, Math.round(density));
        // A translucent mint "spotlight" is easier to follow from a sofa than the
        // platform's thin white rectangle, while retaining a very clear edge.
        outline.setColor(0x2449f59a);
        outline.setCornerRadius(12 * density);
        outline.setStroke(Math.round(3 * density), 0xff82f7b0);
        decor.getOverlay().add(outline);
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            animateFocus(oldFocus, false);
            animateFocus(newFocus, true);
        });
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            update();
            return true;
        });
    }

    private void animateFocus(View view, boolean selected) {
        if (!isCompactControl(view)) return;
        if (selected) animatedFocus = view;
        view.animate().cancel();
        view.animate()
                .scaleX(selected ? 1.055f : 1f)
                .scaleY(selected ? 1.055f : 1f)
                .setDuration(selected ? 150L : 100L)
                .start();
    }

    private boolean isCompactControl(View view) {
        return view instanceof Button || view instanceof ImageButton
                || view instanceof android.widget.CompoundButton;
    }

    public static boolean install(Activity activity) {
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return false;
        }
        new TvFocus(activity);
        return true;
    }

    private void update() {
        View focused = decor.findFocus();
        if (focused == null || focused == decor || !focused.isShown()) {
            outline.setVisible(false, false);
            return;
        }
        focused.getLocationInWindow(viewPosition);
        decor.getLocationInWindow(windowPosition);
        int left = viewPosition[0] - windowPosition[0] + inset;
        int top = viewPosition[1] - windowPosition[1] + inset;
        outline.setBounds(left, top, left + focused.getWidth() - 2 * inset,
                top + focused.getHeight() - 2 * inset);
        outline.setVisible(true, false);
    }
}
