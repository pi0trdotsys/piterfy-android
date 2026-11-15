package com.eddyizm.tempus.util;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/**
 * Przycina widok do kola. Glide z circleCrop zostawia za obrazem kwadratowy placeholder (kolor
 * powierzchni), wiec samo circleCrop nie wystarcza - kwadrat byl widoczny za kazda okragla okladka wykonawcy.
 */
public final class CircularImage {
    private static final ViewOutlineProvider OVAL = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
    };

    private CircularImage() {
    }

    public static void clip(ImageView view) {
        view.setOutlineProvider(OVAL);
        view.setClipToOutline(true);
    }
}
