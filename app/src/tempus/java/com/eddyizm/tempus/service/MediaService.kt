package com.eddyizm.tempus.service

import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.cast.CastMediaItemConverter
import com.eddyizm.tempus.cast.PiterfyCastLibrary
import com.eddyizm.tempus.repository.AutomotiveRepository
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class MediaService : BaseMediaService(), SessionAvailabilityListener {
    private val automotiveRepository = AutomotiveRepository()
    private lateinit var castPlayer: CastPlayer
    private var castContext: CastContext? = null
    private var castLibrary: PiterfyCastLibrary? = null

    @Suppress("DEPRECATION")
    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this))
                .addOnSuccessListener { castContext ->
                    this.castContext = castContext
                    castPlayer = CastPlayer(castContext, CastMediaItemConverter())
                    if (com.eddyizm.tempus.BuildConfig.PITERFY_CAST_RECEIVER_ID.isNotEmpty()) {
                        castLibrary = PiterfyCastLibrary(castPlayer)
                    }
                    castPlayer.setSessionAvailabilityListener(this@MediaService)
                    initializePlayerListener(castPlayer)
                    if (castPlayer.isCastSessionAvailable) {
                        setPlayer(mediaLibrarySession.player, castPlayer)
                        castLibrary?.attach(castContext.sessionManager.currentCastSession)
                    }
                }
        }
    }

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        if (sessionCallback == null) {
            sessionCallback = MediaLibrarySessionCallback(baseContext, this, automotiveRepository)
        }
        return sessionCallback!!
    }

    override fun playerInitHook() {
        super.playerInitHook()
        initializeCastPlayer()
        if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable)
            setPlayer(null, castPlayer)
    }

    override fun releasePlayers() {
        castLibrary?.close()
        castLibrary = null
        if (this::castPlayer.isInitialized) {
            castPlayer.setSessionAvailabilityListener(null)
            castPlayer.release()
        }
        automotiveRepository.deleteMetadata()
        super.releasePlayers()
    }

    override fun onCastSessionAvailable() {
        setPlayer(exoplayer, castPlayer)
        castLibrary?.attach(castContext?.sessionManager?.currentCastSession)
    }

    override fun onCastSessionUnavailable() {
        castLibrary?.attach(null)
        setPlayer(castPlayer, exoplayer)
    }
}
