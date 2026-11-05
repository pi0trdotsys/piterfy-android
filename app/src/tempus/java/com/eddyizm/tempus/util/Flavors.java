package com.eddyizm.tempus.util;

import android.content.Context;
import android.view.Menu;
import androidx.mediarouter.app.MediaRouteButton;

import androidx.core.content.ContextCompat;

import com.eddyizm.tempus.R;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

public class Flavors {
    private static CastContext sharedCastContext;
    @SuppressWarnings("deprecation")
    public static void initializeCastContext(Context context) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener(castContext -> sharedCastContext = castContext);
    }

    public static void setUpPlayerCastButton(MediaRouteButton button) {
        Context context = button.getContext();
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return;
        CastButtonFactory.setUpMediaRouteButton(context, button);
        button.setVisibility(android.view.View.VISIBLE);
    }

    public static String castDeviceName() {
        CastContext context = sharedCastContext;
        if (context == null) return null;
        var session = context.getSessionManager().getCurrentCastSession();
        if (session == null || !session.isConnected()) return null;
        var device = session.getCastDevice();
        return device == null ? null : device.getFriendlyName();
    }

    public static void setUpRouteButton(Context context, Menu menu) {
        CastButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item);
    }
}
