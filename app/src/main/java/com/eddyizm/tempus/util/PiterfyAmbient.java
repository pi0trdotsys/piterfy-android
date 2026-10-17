package com.eddyizm.tempus.util;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.eddyizm.tempus.glide.CustomGlideRequest;

import java.util.function.IntConsumer;

/**
 * Piterfy: kolor "z okladki" jak w Spotify. Wylicza sredni kolor okladki granego utworu, sciemnia go do
 * poziomu, na ktorym bialy tekst jest czytelny, i udostepnia jako LiveData - na nim opieraja sie tlo
 * mini-odtwarzacza i gradient pelnego odtwarzacza.
 */
public final class PiterfyAmbient {
    public static final int DEFAULT = 0xFF2A2A2A;
    public static final int SURFACE = 0xFF121212;
    public static final MutableLiveData<Integer> COLOR = new MutableLiveData<>(DEFAULT);
    private static String lastCover;

    private PiterfyAmbient() {
    }

    /** Wywolaj przy zmianie utworu; ten sam utwor nie pobiera okladki drugi raz. */
    public static void update(Context context, @Nullable String coverId) {
        if (coverId == null) {
            lastCover = null;
            COLOR.postValue(DEFAULT);
            return;
        }
        if (coverId.equals(lastCover)) return;
        lastCover = coverId;
        Glide.with(context.getApplicationContext())
                .asBitmap()
                .load(CustomGlideRequest.createUrl(coverId, 64))
                .into(new CustomTarget<Bitmap>(32, 32) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        COLOR.postValue(fromBitmap(bitmap));
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        COLOR.postValue(DEFAULT);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    /**
     * Dominujacy, zywy kolor okladki jak w Spotify: piksele trafiaja do 12 koszykow odcieni, wazone
     * nasyceniem i jasnoscia (szarosci i czerni sie nie licza); wygrywa najciezszy koszyk. Kolor jest potem
     * sciemniony do poziomu, na ktorym bialy tekst jest czytelny.
     */
    static int fromBitmap(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 24, true);
        double[] weight = new double[12];
        double[] hue = new double[12];
        double[] saturation = new double[12];
        float[] hsv = new float[3];
        double total = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                Color.colorToHSV(small.getPixel(x, y), hsv);
                if (hsv[2] < 0.15f || hsv[1] < 0.12f) continue;  // czern i szarosc
                double w = hsv[1] * hsv[2];
                int bucket = Math.min(11, (int) (hsv[0] / 30f));
                weight[bucket] += w;
                hue[bucket] += hsv[0] * w;
                saturation[bucket] += hsv[1] * w;
                total += w;
            }
        }
        if (total < 8) return DEFAULT;  // okladka prawie czarno-biala
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        hsv[0] = (float) (hue[best] / weight[best]);
        hsv[1] = (float) Math.max(0.45, Math.min(0.85, saturation[best] / weight[best]));
        hsv[2] = 0.42f;
        return Color.HSVToColor(hsv);
    }

    /** Jednorazowo: kolor okladki podanego albumu/playlisty (np. dla gradientu strony albumu). */
    public static void forCover(Context context, @Nullable String coverId, IntConsumer done) {
        if (coverId == null) return;
        Glide.with(context.getApplicationContext())
                .asBitmap()
                .load(CustomGlideRequest.createUrl(coverId, 64))
                .into(new CustomTarget<Bitmap>(32, 32) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        done.accept(fromBitmap(bitmap));
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
    }

    /** Tlo strony jak w Spotify: kolor okladki u gory, plynnie przechodzacy w czern powierzchni. */
    public static Drawable pageGradient(int color) {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{color, SURFACE, SURFACE});
    }

    /**
     * Subskrybuje kolor i przekazuje go do {@code apply}, plynnie przechodzac od poprzedniego do nowego.
     * Subskrypcja konczy sie razem z {@code owner}.
     */
    public static void observe(LifecycleOwner owner, IntConsumer apply) {
        final int[] current = {COLOR.getValue() == null ? DEFAULT : COLOR.getValue()};
        apply.accept(current[0]);
        COLOR.observe(owner, target -> {
            if (target == null || target == current[0]) return;
            ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), current[0], target);
            animator.setDuration(450);
            animator.addUpdateListener(a -> {
                current[0] = (int) a.getAnimatedValue();
                apply.accept(current[0]);
            });
            animator.start();
        });
    }
}
