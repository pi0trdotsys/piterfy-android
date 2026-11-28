package com.eddyizm.tempus.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.ConstantsAA
import com.eddyizm.tempus.util.PiterfyAutoPlaylists
import com.eddyizm.tempus.util.PiterfyAutoResume
import com.eddyizm.tempus.util.PiterfyAutoSearch

/**
 * Piterfy: drobne przeróbki elementów przeglądania dla Android Auto, które nie mają własnej logiki do testowania
 * (cała logika jest w PiterfyAuto*.kt): pusta lista z komunikatem, tytuł grupy, pasek postępu, kompletne metadane.
 */
@UnstableApi
object PiterfyAutoItems {
    private fun icon(resId: Int): Uri = "android.resource://${BuildConfig.APPLICATION_ID}/$resId".toUri()

    /** Element „nic tu nie ma”: Auto pokazuje komunikat zamiast pustego ekranu. Nie da się go odtworzyć ani otworzyć. */
    fun placeholder(text: String): MediaItem = MediaItem.Builder()
        .setMediaId(ConstantsAA.EMPTY_PLACEHOLDER_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(text)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .setArtworkUri(icon(R.drawable.ic_aa_title))
                .build()
        )
        .build()

    private fun withExtras(item: MediaItem, change: (Bundle) -> Unit): MediaItem {
        val extras = item.mediaMetadata.extras?.let { Bundle(it) } ?: Bundle()
        change(extras)
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** Auto grupuje sąsiednie elementy o tym samym tytule grupy (nagłówek nad kafelkami). */
    fun withGroupTitle(item: MediaItem, title: String): MediaItem =
        withExtras(item) { it.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title) }

    /** Pasek postępu przy elemencie listy (np. odcinek wysłuchany do połowy). */
    fun withCompletion(item: MediaItem, fraction: Double): MediaItem = withExtras(item) {
        it.putInt(
            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
            if (fraction >= 1.0) MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            else MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        it.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, fraction)
    }

    /** Pozycja (ms), od której ma ruszyć odcinek wybrany z „Kontynuuj”. */
    const val EXTRA_RESUME_MS = "pf_resume_ms"

    fun withResumeMs(item: MediaItem, ms: Long): MediaItem = withExtras(item) { it.putLong(EXTRA_RESUME_MS, ms) }

    fun resumeMsOf(item: MediaItem): Long =
        item.requestMetadata.extras?.getLong(EXTRA_RESUME_MS) ?: item.mediaMetadata.extras?.getLong(EXTRA_RESUME_MS) ?: 0L

    /**
     * Kompletne metadane utworu: czas (pole `duration` w sekundach było tylko w dodatkowych polach, a Auto czyta
     * `durationMs`, więc pasek postępu nie znał długości do czasu załadowania) i okładka zastępcza.
     */
    fun enrich(item: MediaItem): MediaItem {
        val metadata = item.mediaMetadata
        val playable = metadata.isPlayable == true
        var builder: MediaMetadata.Builder? = null
        if (playable && metadata.durationMs == null) {
            PiterfyAutoResume.durationMs(metadata.extras?.getInt("duration") ?: 0)?.let {
                builder = metadata.buildUpon().setDurationMs(it)
            }
        }
        if (metadata.artworkUri == null && metadata.artworkData == null) {
            builder = (builder ?: metadata.buildUpon()).setArtworkUri(icon(R.drawable.ic_aa_title))
        }
        return builder?.let { item.buildUpon().setMediaMetadata(it.build()).build() } ?: item
    }

    /** Pozycja jako kandydat do rankingu wyszukiwania. */
    fun candidate(item: MediaItem): PiterfyAutoSearch.Candidate {
        val metadata = item.mediaMetadata
        val id = item.mediaId
        val kind = when {
            id.startsWith(ConstantsAA.ARTIST_ID) -> PiterfyAutoSearch.Kind.ARTIST
            id.startsWith(ConstantsAA.ALBUM_ID) -> PiterfyAutoSearch.Kind.ALBUM
            id.startsWith(ConstantsAA.PLAYLIST_ID) -> PiterfyAutoSearch.Kind.PLAYLIST
            else -> PiterfyAutoSearch.Kind.SONG
        }
        return PiterfyAutoSearch.Candidate(
            id = id,
            title = metadata.title?.toString().orEmpty(),
            artist = (metadata.artist ?: metadata.albumArtist)?.toString().orEmpty(),
            kind = kind,
            starred = (metadata.userRating as? HeartRating)?.isHeart == true,
            playCount = metadata.extras?.getLong("playCount") ?: 0L,
        )
    }

    /** Nazwa grupy playlist do nagłówka w Auto. */
    fun groupTitle(context: Context, group: PiterfyAutoPlaylists.Group): String = context.getString(
        when (group) {
            PiterfyAutoPlaylists.Group.DISCOVER -> R.string.piterfy_aa_group_discover
            PiterfyAutoPlaylists.Group.MIX -> R.string.piterfy_aa_group_mixes
            PiterfyAutoPlaylists.Group.CHART -> R.string.piterfy_aa_group_charts
            PiterfyAutoPlaylists.Group.SOUNDTRACK -> R.string.piterfy_aa_group_soundtracks
            PiterfyAutoPlaylists.Group.OTHER -> R.string.piterfy_aa_group_playlists
        }
    )
}
