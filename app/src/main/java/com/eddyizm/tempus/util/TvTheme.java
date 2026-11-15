package com.eddyizm.tempus.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.function.IntConsumer;

/**
 * Piterfy TV: wspolny jezyk kolorow "z okladki" dla ekranu telewizora. Bazuje na {@link PiterfyAmbient}
 * (jeden zrodlowy kolor okladki), a tutaj zamienia go na gradienty czytelne z kanapy: jasniejszy rog,
 * gleboki cien i stala czern powierzchni, zeby biały tekst zawsze mial kontrast.
 */
public final class TvTheme {
    public static final int SURFACE = 0xFF0A0F0D;
    private static final LruCache<String, Integer> COVER_COLORS = new LruCache<>(96);

    private TvTheme() {
    }

    /** Zywszy, jasniejszy wariant koloru okladki (PiterfyAmbient zwraca kolor przyciemniony pod tekst). */
    @ColorInt
    public static int vivid(@ColorInt int ambient) {
        float[] hsv = new float[3];
        Color.colorToHSV(ambient, hsv);
        hsv[1] = clamp(hsv[1] * 1.15f, 0.30f, 0.80f);
        hsv[2] = clamp(hsv[2] * 1.9f, 0.46f, 0.70f);
        return Color.HSVToColor(hsv);
    }

    /** Bardzo ciemny wariant tego samego odcienia - srodek gradientu. */
    @ColorInt
    public static int deep(@ColorInt int ambient) {
        float[] hsv = new float[3];
        Color.colorToHSV(ambient, hsv);
        hsv[1] = clamp(hsv[1], 0.25f, 0.70f);
        hsv[2] = clamp(hsv[2] * 0.55f, 0.08f, 0.22f);
        return Color.HSVToColor(hsv);
    }

    /** Pelnoekranowe tlo odtwarzacza: po przekatnej od jasnego rogu, przez gleboki odcien, do czerni. */
    @NonNull
    public static GradientDrawable playerGradient(@ColorInt int ambient) {
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{vivid(ambient), deep(ambient), SURFACE});
        gradient.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return gradient;
    }

    /** Lagodniejsze tlo ekranow z lista: kolor u gory, plynnie przechodzacy w czern. */
    @NonNull
    public static GradientDrawable pageGradient(@ColorInt int ambient) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(vivid(ambient), 0xB0), withAlpha(deep(ambient), 0xE0), SURFACE, SURFACE});
    }

    @ColorInt
    public static int withAlpha(@ColorInt int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * Kolor okladki z pamieci podrecznej albo pobrany raz. Wynik trafia do {@code done} na watku glownym;
     * gdy okladki brak, nic sie nie dzieje (tlo zostaje takie, jakie bylo).
     */
    public static void forCover(Context context, String coverId, IntConsumer done) {
        if (coverId == null || coverId.isEmpty()) return;
        Integer cached = COVER_COLORS.get(coverId);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        PiterfyAmbient.forCover(context, coverId, color -> {
            COVER_COLORS.put(coverId, color);
            done.accept(color);
        });
    }

    /** Spokojny, miętowy odcień dla ekranów bez okładki (wyszukiwanie, ustawienia). */
    public static final int CALM = 0xFF1F4D36;

    /** Tlo strony w kolorze okladki; bez okladki - spokojny odcien. */
    public static void paintPage(androidx.fragment.app.Fragment page, String coverId) {
        if (!(page.getActivity() instanceof com.eddyizm.tempus.ui.activity.MainActivity activity)) return;
        activity.setTvBackdropColor(page, CALM);
        forCover(page.requireContext(), coverId, color -> {
            if (page.isAdded()) activity.setTvBackdropColor(page, color);
        });
    }

    public static void paintCalm(androidx.fragment.app.Fragment page) {
        if (page.getActivity() instanceof com.eddyizm.tempus.ui.activity.MainActivity activity) {
            activity.setTvBackdropColor(page, CALM);
        }
    }

    public static void releasePage(androidx.fragment.app.Fragment page) {
        if (page.getActivity() instanceof com.eddyizm.tempus.ui.activity.MainActivity activity) {
            activity.releaseTvBackdrop(page);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
