package com.eddyizm.tempus.ui.activity;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

/** Provides a consistent focus indicator for the native controls when using a TV remote. */
public final class TvFocus {
    private final ViewGroup decor;
    private final GradientDrawable outline = new GradientDrawable();
    private final int[] viewPosition = new int[2];
    private final int[] windowPosition = new int[2];
    private final int inset;

    private TvFocus(Activity activity) {
        decor = (ViewGroup) activity.getWindow().getDecorView();
        float density = activity.getResources().getDisplayMetrics().density;
        inset = Math.max(1, Math.round(density));
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(8 * density);
        outline.setStroke(Math.round(3 * density), 0xff66ee99);
        decor.getOverlay().add(outline);
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            update();
            return true;
        });
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
