package com.eddyizm.tempus.cast

import android.content.Context
import androidx.annotation.Keep
import androidx.media3.cast.DefaultCastOptionsProvider
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.BuildConfig
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Use the registered Piterfy TV app when configured, otherwise preserve Media3's current receiver. */
@Keep
@UnstableApi
class PiterfyCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val id = BuildConfig.PITERFY_CAST_RECEIVER_ID
        if (id.isEmpty()) return DefaultCastOptionsProvider().getCastOptions(context)
        return CastOptions.Builder()
            .setReceiverApplicationId(id)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setRemoteToLocalEnabled(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()
}
