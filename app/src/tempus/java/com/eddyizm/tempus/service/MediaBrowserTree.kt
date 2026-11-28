package com.eddyizm.tempus.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.repository.AutomotiveRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.ConstantsAA
import com.eddyizm.tempus.util.Preferences
import androidx.core.net.toUri
import androidx.media3.session.SessionError
import com.eddyizm.tempus.util.Preferences.getServerId
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.PiterfyAutoPlaylists
import com.eddyizm.tempus.util.PiterfyAutoResume
import com.eddyizm.tempus.util.PiterfyAutoSearch
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@UnstableApi
object MediaBrowserTree {
    // Odczyty z bazy i mapowanie zapisanej kolejki nie mogą blokować wątku sesji (Auto czeka na odpowiedź).
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    private lateinit var appContext: Context
    private lateinit var automotiveRepository: AutomotiveRepository

    private var treeNodes: MutableMap<String, MediaItemNode> = mutableMapOf()

    private var isInitialized = false

    private fun iconUri(resId: Int): Uri =
        "android.resource://${BuildConfig.APPLICATION_ID}/$resId".toUri()

    private class MediaItemNode(val item: MediaItem) {
        private val children: MutableList<MediaItem> = ArrayList()

        fun addChild(childID: String) {
            this.children.add(treeNodes[childID]!!.item)
        }

        fun getChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val listenableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val libraryResult = LibraryResult.ofItemList(children, null)

            listenableFuture.set(libraryResult)

            return listenableFuture
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaItem(
        gridView: Boolean,
        title: String,
        mediaId: String,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        mediaType: @MediaMetadata.MediaType Int,
        subtitleConfigurations: List<SubtitleConfiguration> = mutableListOf(),
        album: String? = null,
        artist: String? = null,
        genre: String? = null,
        sourceUri: Uri? = null,
        imageUri: Uri? = null
    ): MediaItem {
        val extras = Bundle()
        if( gridView ) {
                extras.apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                )
            }
        }
        else{
            extras.apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
            }
        }

        val metadata = MediaMetadata.Builder()
            .setAlbumTitle(album)
            .setTitle(title)
            .setArtist(artist)
            .setGenre(genre)
            .setIsBrowsable(isBrowsable)
            .setIsPlayable(isPlayable)
            .setArtworkUri(imageUri)
            .setMediaType(mediaType)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(metadata)
            .setUri(sourceUri)
            .build()
    }
    fun initialize(
        context: Context,
        automotiveRepository: AutomotiveRepository) {
        this.automotiveRepository = automotiveRepository
        appContext = context.applicationContext
        if (isInitialized) return

        isInitialized = true
    }

    fun buildTree() {
        val albumView: Boolean = Preferences.isAndroidAutoAlbumViewEnabled()
        val homeView: Boolean = Preferences.isAndroidAutoHomeViewEnabled()
        val playlistView: Boolean = Preferences.isAndroidAutoPlaylistViewEnabled()
        val podcastView: Boolean = Preferences.isAndroidAutoPodcastViewEnabled()
        val radioView: Boolean = Preferences.isAndroidAutoRadioViewEnabled()

        // clear before rebuild
        treeNodes.clear()

        // This list must be exactly the same as the one in aa_tab_titles
        val allFunctions = listOf(
            ConstantsAA.HOME_ID,
            ConstantsAA.LAST_PLAYED_ID,
            ConstantsAA.ALBUMS_ID,
            ConstantsAA.ARTISTS_ID,
            ConstantsAA.PLAYLIST_ID,
            ConstantsAA.PODCAST_ID,
            ConstantsAA.RADIO_ID,
            ConstantsAA.FOLDER_ID,
            ConstantsAA.MOST_PLAYED_ID,
            ConstantsAA.RECENTLY_ADDED_ID,
            ConstantsAA.MADE_FOR_YOU_ID,
            ConstantsAA.STARRED_BUNDLE_ID,
            ConstantsAA.TRACKS_ID,
            ConstantsAA.GENRES_ID
        )

        // Prevents index error
        val indexMax = allFunctions.lastIndex

        fun indexGuard(index: Int, reset: () -> Unit): Int {
            return if (index in -1..indexMax) index else {
                reset()
                -1
            }
        }

        val tabIndex = listOf(
            indexGuard(Preferences.getAndroidAutoFirstTab(), Preferences::resetAndroidAutoFirstTab),
            indexGuard(Preferences.getAndroidAutoSecondTab(), Preferences::resetAndroidAutoSecondTab),
            indexGuard(Preferences.getAndroidAutoThirdTab(), Preferences::resetAndroidAutoThirdTab),
            indexGuard(Preferences.getAndroidAutoFourthTab(), Preferences::resetAndroidAutoFourthTab)
        )

        // Root level
        treeNodes[ConstantsAA.ROOT_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = "Root Folder",
                    mediaId = ConstantsAA.ROOT_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

		// All available functions
		// if HOME is in first place or no item is selected
        if (tabIndex.firstOrNull() == 0 || tabIndex.all { it == -1 }){
			treeNodes[ConstantsAA.HOME_ID] =
				MediaItemNode(
					buildMediaItem(
						gridView = homeView,
						title = appContext.getString(R.string.aa_home),
						mediaId = ConstantsAA.HOME_ID,
						isPlayable = false,
						isBrowsable = true,
						imageUri = iconUri(R.drawable.ic_aa_home),
						mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
					)
				)
		}
		else { // More instead of Home
			treeNodes[ConstantsAA.HOME_ID] =
				MediaItemNode(
					buildMediaItem(
						gridView = homeView,
						title = appContext.getString(R.string.aa_more),
						mediaId = ConstantsAA.HOME_ID,
						isPlayable = false,
						isBrowsable = true,
						imageUri = iconUri(R.drawable.ic_aa_other),
						mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
					)
				)
		}
		
        treeNodes[ConstantsAA.LAST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_recent_albums),
                    mediaId = ConstantsAA.LAST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_recent),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )
			
        treeNodes[ConstantsAA.ALBUMS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_albums),
                    mediaId = ConstantsAA.ALBUMS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_albums),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )
			
        treeNodes[ConstantsAA.ARTISTS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_artists),
                    mediaId = ConstantsAA.ARTISTS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_artists),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )
			
        treeNodes[ConstantsAA.PLAYLIST_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = playlistView,
                    title = appContext.getString(R.string.aa_playlists),
                    mediaId = ConstantsAA.PLAYLIST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_playlist),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[ConstantsAA.PODCAST_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = podcastView,
                    title = appContext.getString(R.string.aa_podcast),
                    mediaId = ConstantsAA.PODCAST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_podcasts),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS
                )
            )

        treeNodes[ConstantsAA.RADIO_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = radioView,
                    title = appContext.getString(R.string.aa_radio),
                    mediaId = ConstantsAA.RADIO_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_radio),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                )
            )

        treeNodes[ConstantsAA.MOST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_album_most_played),
                    mediaId = ConstantsAA.MOST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_mostplayed),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )
        treeNodes[ConstantsAA.RECENTLY_ADDED_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_album_recently_added),
                    mediaId = ConstantsAA.RECENTLY_ADDED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_added_album),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )
		
        treeNodes[ConstantsAA.RECENT_TRACKS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_song_recently_played),
                    mediaId = ConstantsAA.RECENT_TRACKS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_recent_title),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.TRACKS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = homeView,
                    title = appContext.getString(R.string.aa_tracks),
                    mediaId = ConstantsAA.TRACKS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_title),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.MADE_FOR_YOU_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = homeView,
                    title = appContext.getString(R.string.aa_made_for_you),
                    mediaId = ConstantsAA.MADE_FOR_YOU_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_for_you),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[ConstantsAA.STARRED_TRACKS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_starred_tracks),
                    mediaId = ConstantsAA.STARRED_TRACKS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_star_title),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.STARRED_BUNDLE_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_starred),
                    mediaId = ConstantsAA.STARRED_BUNDLE_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_bundle_star),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[ConstantsAA.STARRED_ALBUMS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_starred_albums),
                    mediaId = ConstantsAA.STARRED_ALBUMS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_star_album),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                )
            )

        treeNodes[ConstantsAA.STARRED_ARTISTS_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = albumView,
                    title = appContext.getString(R.string.aa_starred_artists),
                    mediaId = ConstantsAA.STARRED_ARTISTS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_artists),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                )
            )

        treeNodes[ConstantsAA.FOLDER_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_music_folder),
                    mediaId = ConstantsAA.FOLDER_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_folders),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.RANDOM_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_random),
                    mediaId = ConstantsAA.RANDOM_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_random),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.GENRES_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_genres),
                    mediaId = ConstantsAA.GENRES_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_genres),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.DOWNLOADED_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.download_title_section),
                    mediaId = ConstantsAA.DOWNLOADED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_downloaded),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.CONTINUE_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.piterfy_aa_continue),
                    mediaId = ConstantsAA.CONTINUE_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_recent),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.PITERFY_FOR_YOU_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = playlistView,
                    title = appContext.getString(R.string.piterfy_aa_for_you),
                    mediaId = ConstantsAA.PITERFY_FOR_YOU_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    imageUri = iconUri(R.drawable.ic_aa_for_you),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[ConstantsAA.QUICKMIX_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_quick_mix),
                    artist = "By Tempus",
                    mediaId = ConstantsAA.MADE_FOR_YOU_SOURCE
                            + "[" +ConstantsAA.NUMBER_OF_TRACKS_IN_SMALL_MIX + "]"
                            + ConstantsAA.QUICKMIX_ID,
                    isPlayable = true,
                    isBrowsable = false,
                    imageUri = iconUri(R.drawable.ic_aa_quickmix),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.MYMIX_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_my_mix),
                    artist = "By Tempus",
                    mediaId = ConstantsAA.MADE_FOR_YOU_SOURCE
                            + "[" + ConstantsAA.NUMBER_OF_TRACKS_IN_MEDIUM_MIX + "]"
                            + ConstantsAA.MYMIX_ID,
                    isPlayable = true,
                    isBrowsable = false,
                    imageUri = iconUri(R.drawable.ic_aa_mymix),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[ConstantsAA.DISCOVERYMIX_ID] =
            MediaItemNode(
                buildMediaItem(
                    gridView = false,
                    title = appContext.getString(R.string.aa_discovery_mix),
                    artist = "By Tempus",
                    mediaId = ConstantsAA.MADE_FOR_YOU_SOURCE
                            + "[" + ConstantsAA.NUMBER_OF_TRACKS_IN_LARGE_MIX + "]"
                            + ConstantsAA.DISCOVERYMIX_ID,
                    isPlayable = true,
                    isBrowsable = false,
                    imageUri = iconUri(R.drawable.ic_aa_discoverymix),
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        val root = treeNodes[ConstantsAA.ROOT_ID]!!
        val selectedIds = mutableSetOf<String>()

        // First level
		// add functions selected by user for the 4 tabs
        tabIndex
            .filter { it != -1 }
            .forEach { index ->
                allFunctions.getOrNull(index)?.let { function ->
                    if (selectedIds.add(function)) {
                        root.addChild(function)
                    }
                }
            }
		// if no function is selected, add at least HOME_ID
        if (selectedIds.isEmpty()) {
            root.addChild(ConstantsAA.HOME_ID)
            selectedIds.add(ConstantsAA.HOME_ID)
        }

        // Second level for HOME_ID even there is no HOME_ID displayed
        // Downloads first: it is the only section that works with no connectivity
        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.DOWNLOADED_ID)
        // Piterfy: „Kontynuuj” (wznów to, co grało) i „Dla Ciebie” (miksy, Odkrywaj, listy przebojów z serwera) zaraz po pobranych
        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.CONTINUE_ID)
        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.PITERFY_FOR_YOU_ID)

		// add all functions not previously added
        allFunctions
            .filter { it !in selectedIds }
            .forEach { function ->
                when (function) {
                    ConstantsAA.MADE_FOR_YOU_ID -> {
                        // add Quick Mix, My Mix and Discovery Mix instead of Made For You to Home
                        treeNodes[ConstantsAA.HOME_ID]!!.addChild(ConstantsAA.QUICKMIX_ID)
                        treeNodes[ConstantsAA.HOME_ID]!!.addChild(ConstantsAA.MYMIX_ID)
                        treeNodes[ConstantsAA.HOME_ID]!!.addChild(ConstantsAA.DISCOVERYMIX_ID)
                    }
                    ConstantsAA.STARRED_BUNDLE_ID -> {
                        // add starred function instead of Starred bundle to Home
                        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.STARRED_ARTISTS_ID)
                        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.STARRED_ALBUMS_ID)
                        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.STARRED_TRACKS_ID)
                    }
                    ConstantsAA.TRACKS_ID -> {
                        // add Random and Recent instead of Tracks to Home
                        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.RANDOM_ID)
                        treeNodes[ConstantsAA.HOME_ID]?.addChild(ConstantsAA.RECENT_TRACKS_ID)
                    }
                    else -> treeNodes[ConstantsAA.HOME_ID]?.addChild(function)
                }
            }

        // build Made For You bundle
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.QUICKMIX_ID)
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.MYMIX_ID)
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.DISCOVERYMIX_ID)
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.STARRED_ARTISTS_ID)
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.STARRED_ALBUMS_ID)
        treeNodes[ConstantsAA.MADE_FOR_YOU_ID]!!.addChild(ConstantsAA.STARRED_TRACKS_ID)

        // create starred bundle
        treeNodes[ConstantsAA.STARRED_BUNDLE_ID]?.addChild(ConstantsAA.STARRED_ARTISTS_ID)
        treeNodes[ConstantsAA.STARRED_BUNDLE_ID]?.addChild(ConstantsAA.STARRED_ALBUMS_ID)
        treeNodes[ConstantsAA.STARRED_BUNDLE_ID]?.addChild(ConstantsAA.STARRED_TRACKS_ID)

        // create tracks bundle
        treeNodes[ConstantsAA.TRACKS_ID]?.addChild(ConstantsAA.RANDOM_ID)
        treeNodes[ConstantsAA.TRACKS_ID]?.addChild(ConstantsAA.GENRES_ID)
        treeNodes[ConstantsAA.TRACKS_ID]?.addChild(ConstantsAA.RECENT_TRACKS_ID)
        treeNodes[ConstantsAA.TRACKS_ID]?.addChild(ConstantsAA.STARRED_TRACKS_ID)
	}
	
    fun getRootItem(): MediaItem {
        return treeNodes[ConstantsAA.ROOT_ID]!!.item
    }

    /** Playlisty z serwera discover (miksy, Odkrywaj, listy przebojów, soundtracki) w kolejności i z nagłówkami grup. */
    private fun forYouChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.transform(automotiveRepository.getPlaylists(ConstantsAA.PLAYLIST_ID), { result ->
            val list = result.value ?: return@transform result
            val mine = PiterfyAutoPlaylists.order(list) { it.mediaMetadata.title?.toString() }
                .filter { PiterfyAutoPlaylists.isPiterfy(it.mediaMetadata.title?.toString()) }
                .map { PiterfyAutoItems.withGroupTitle(it, PiterfyAutoItems.groupTitle(appContext, PiterfyAutoPlaylists.group(it.mediaMetadata.title?.toString()))) }
            LibraryResult.ofItemList(mine, result.params)
        }, MoreExecutors.directExecutor())

    /** „Wszystkie moje playlisty”: playlisty Piterfy na początku z nagłówkiem, reszta pod własnym; bez playlist Piterfy lista jest jak dawniej. */
    private fun playlistChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.transform(automotiveRepository.getPlaylists(ConstantsAA.PLAYLIST_ID), { result ->
            val list = result.value ?: return@transform result
            if (list.none { PiterfyAutoPlaylists.isPiterfy(it.mediaMetadata.title?.toString()) }) return@transform result
            val ordered = PiterfyAutoPlaylists.order(list) { it.mediaMetadata.title?.toString() }.map {
                PiterfyAutoItems.withGroupTitle(it, PiterfyAutoItems.groupTitle(appContext, PiterfyAutoPlaylists.group(it.mediaMetadata.title?.toString())))
            }
            LibraryResult.ofItemList(ordered, result.params)
        }, MoreExecutors.directExecutor())

    /**
     * „Kontynuuj”: najpierw „Wznów: ostatni utwór” (cała zapisana kolejka od miejsca, w którym przerwano), potem odcinki i długie
     * utwory z półki „Kontynuuj” aplikacji (najnowsze pierwsze), z paskiem postępu. Odczyty z bazy idą poza wątkiem sesji.
     */
    private fun continueChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit(Callable {
        val items = ArrayList<MediaItem>()
        val last = runCatching { QueueRepository().lastPlayedMedia }.getOrNull()
        if (last != null) {
            val text = last.title?.let { appContext.getString(R.string.piterfy_aa_resume_queue, it) }
                ?: appContext.getString(R.string.piterfy_aa_resume_queue_plain)
            items.add(
                buildMediaItem(
                    gridView = false,
                    title = text,
                    mediaId = ConstantsAA.RESUME_QUEUE_SOURCE,
                    isPlayable = true,
                    isBrowsable = false,
                    artist = last.artist,
                    album = last.album,
                    imageUri = last.coverArtId?.let { com.eddyizm.tempus.provider.AlbumArtContentProvider.contentUri(it) }
                        ?: iconUri(R.drawable.ic_aa_recent),
                    mediaType = MediaMetadata.MEDIA_TYPE_MUSIC
                )
            )
        }
        val points = Preferences.getContinueListening().take(ConstantsAA.NUMBER_OF_DISPLAYED_CONTINUE)
        if (points.isNotEmpty()) {
            val children = points.map { point ->
                Child(point.id).apply {
                    title = point.title
                    album = point.album
                    artist = point.artist
                    albumId = point.albumId
                    coverArtId = point.coverArtId
                    type = point.type
                    duration = if (point.durationMs > 0) (point.durationMs / 1000).toInt() else null
                }
            }
            // te same elementy w pamięci podręcznej listy (QUEUE_CACHED_SOURCE): kliknięcie zagra odcinek i resztę „Kontynuuj”
            points.zip(children).forEach { (point, child) ->
                // jeden niemapowalny zapis (zmienione dane po zmianie serwera) nie może zepsuć całej listy
                var item = runCatching { MappingUtil.mapMediaItem(child, ConstantsAA.QUEUE_CACHED_SOURCE) }.getOrNull() ?: return@forEach
                val start = PiterfyAutoResume.continuePosition(point.position, point.durationMs)
                PiterfyAutoResume.completion(point.position, point.durationMs)?.let { item = PiterfyAutoItems.withCompletion(item, it) }
                if (start > 0) item = PiterfyAutoItems.withResumeMs(item, start)
                items.add(item)
            }
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), null)
    })

    fun getChildren(
        id: String,
        offline: Boolean = false
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when (id) {
            // bez internetu na samym początku tylko pobrane: reszta drzewa i tak by się nie załadowała
            ConstantsAA.ROOT_ID -> if (offline) {
                Futures.immediateFuture(LibraryResult.ofItemList(listOf(treeNodes[ConstantsAA.DOWNLOADED_ID]!!.item), null))
            } else {
                treeNodes[ConstantsAA.ROOT_ID]?.getChildren()!!
            }

            ConstantsAA.CONTINUE_ID -> continueChildren()
            ConstantsAA.PITERFY_FOR_YOU_ID -> forYouChildren()

            ConstantsAA.HOME_ID -> treeNodes[ConstantsAA.HOME_ID]?.getChildren()!!
            ConstantsAA.MADE_FOR_YOU_ID -> treeNodes[ConstantsAA.MADE_FOR_YOU_ID]?.getChildren()!!
            ConstantsAA.STARRED_BUNDLE_ID -> treeNodes[ConstantsAA.STARRED_BUNDLE_ID]?.getChildren()!!
            ConstantsAA.TRACKS_ID -> treeNodes[ConstantsAA.TRACKS_ID]?.getChildren()!!

            ConstantsAA.LAST_PLAYED_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "recent",
                ConstantsAA.NUMBER_OF_DISPLAYED_ALBUMS,
                false)
            ConstantsAA.ALBUMS_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "alphabeticalByName",
                ConstantsAA.MAX_ITEMS,
                true)
            ConstantsAA.ARTISTS_ID -> automotiveRepository.getArtists(
                ConstantsAA.ARTIST_ID,
                true)
            ConstantsAA.PLAYLIST_ID -> playlistChildren()
            ConstantsAA.PODCAST_ID -> automotiveRepository.getNewestPodcastEpisodes(ConstantsAA.NUMBER_OF_DISPLAYED_PODCASTS)
            ConstantsAA.RADIO_ID -> automotiveRepository.getInternetRadioStations()
            ConstantsAA.FOLDER_ID -> automotiveRepository.getMusicFolders(ConstantsAA.FOLDER_ID)
            ConstantsAA.MOST_PLAYED_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "frequent",
                ConstantsAA.NUMBER_OF_DISPLAYED_ALBUMS,
                false)
            ConstantsAA.RECENT_TRACKS_ID -> automotiveRepository.getRecentlyPlayedSongs(
                getServerId(),
                ConstantsAA.NUMBER_OF_DISPLAYED_RECENT_TRACKS)
            ConstantsAA.RECENTLY_ADDED_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "newest",
                ConstantsAA.NUMBER_OF_DISPLAYED_ALBUMS,
                false)
            ConstantsAA.DOWNLOADED_ID -> automotiveRepository.getDownloadedSongs()
            ConstantsAA.STARRED_TRACKS_ID -> automotiveRepository.getStarredSongs()
            ConstantsAA.STARRED_ALBUMS_ID -> automotiveRepository.getStarredAlbums(
                ConstantsAA.ALBUM_ID,
                true)
            ConstantsAA.STARRED_ARTISTS_ID -> automotiveRepository.getStarredArtists(
                ConstantsAA.ARTIST_ID,
                true)
            ConstantsAA.RANDOM_ID -> automotiveRepository.getRandomSongs(ConstantsAA.MAX_SHUFFLE_ITEMS)
            ConstantsAA.GENRES_ID -> automotiveRepository.getGenres(ConstantsAA.GENRES_ID)

            ConstantsAA.JUMP_TO_ALBUMS_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "alphabeticalByName",
                ConstantsAA.MAX_ITEMS,
                false)
            ConstantsAA.JUMP_TO_STARRED_ALBUMS_ID -> automotiveRepository.getStarredAlbums(
                ConstantsAA.ALBUM_ID,
                false)
            ConstantsAA.JUMP_TO_ARTISTS_ID -> automotiveRepository.getArtists(
                ConstantsAA.ARTIST_ID,
                false)
            ConstantsAA.JUMP_TO_STARRED_ARTISTS_ID -> automotiveRepository.getStarredArtists(
                ConstantsAA.ARTIST_ID,
                false)
            ConstantsAA.ARTISTS_BY_ALBUMS_ID -> automotiveRepository.getAlbums(
                ConstantsAA.ALBUM_ID,
                "alphabeticalByArtist",
                ConstantsAA.MAX_ITEMS,
                false)

            else -> {
                if (id.startsWith(ConstantsAA.GENRES_ID)) {
                    val shuffle = Preferences.isAndroidAutoShuffleGenreSongsEnabled()
                    // If the user doesn't want random songs, it's likely it's for perusing them, so provide as many as possible
                    val count = if (shuffle) ConstantsAA.MAX_SHUFFLE_ITEMS else ConstantsAA.MAX_ITEMS
                    return automotiveRepository.getSongsByGenre(id.removePrefix(ConstantsAA.GENRES_ID), count, shuffle)
                }

                if (id.startsWith(ConstantsAA.PLAYLIST_ID)) {
                    return automotiveRepository.getPlaylistSongs(id.removePrefix(ConstantsAA.PLAYLIST_ID))
                }

                if (id.startsWith(ConstantsAA.ALBUM_ID)) {
                    return automotiveRepository.getAlbumTracks(id.removePrefix(ConstantsAA.ALBUM_ID))
                }

                if (id.startsWith(ConstantsAA.ARTIST_ID)) {
                    return automotiveRepository.getArtistAlbum(ConstantsAA.ALBUM_ID,id.removePrefix(ConstantsAA.ARTIST_ID))
                }

                if (id.startsWith(ConstantsAA.FOLDER_ID)) {
                    return automotiveRepository.getIndexes(ConstantsAA.INDEX_ID,id.removePrefix(ConstantsAA.FOLDER_ID))
                }

                if (id.startsWith(ConstantsAA.INDEX_ID)) {
                    return automotiveRepository.getDirectories(ConstantsAA.DIRECTORY_ID,id.removePrefix(ConstantsAA.INDEX_ID))
                }

                if (id.startsWith(ConstantsAA.DIRECTORY_ID)) {
                    return automotiveRepository.getDirectories(ConstantsAA.DIRECTORY_ID,id.removePrefix(ConstantsAA.DIRECTORY_ID))
                }

                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }
    }

    /**
     * Wyszukiwanie z samochodu: zdanie z asystenta („zagraj album Wolność”) zamieniamy na frazę dla serwera, a wyniki
     * układamy od najlepszego dopasowania (PiterfyAutoSearch). Dokładny wykonawca czy album ponad luźne trafienia w tytułach.
     */
    fun search(query: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val parsed = PiterfyAutoSearch.parse(query)
        val text = if (parsed.isEmpty) query else parsed.queryText
        return Futures.transform(
            automotiveRepository.search(text, ConstantsAA.ALBUM_ID, ConstantsAA.ARTIST_ID),
            { result -> rankResult(result, parsed) },
            MoreExecutors.directExecutor()
        )
    }

    /** Wyniki wyszukiwania od najlepszego dopasowania; błąd i pusta fraza przechodzą bez zmian. */
    fun rankResult(
        result: LibraryResult<ImmutableList<MediaItem>>,
        parsed: PiterfyAutoSearch.Parsed
    ): LibraryResult<ImmutableList<MediaItem>> {
        val list = result.value ?: return result
        if (parsed.isEmpty || list.size < 2) return result
        val byId = LinkedHashMap<String, MediaItem>()
        list.forEach { byId.putIfAbsent(it.mediaId, it) }
        val ranked = PiterfyAutoSearch.rank(byId.values.map { PiterfyAutoItems.candidate(it) }, parsed).mapNotNull { byId[it.id] }
        return LibraryResult.ofItemList(ranked, result.params)
    }

    /** Przy pustym wyniku Auto dostaje jeden element z komunikatem zamiast pustego ekranu. */
    fun emptyText(parentId: String): String = appContext.getString(
        when (parentId) {
            ConstantsAA.DOWNLOADED_ID -> R.string.piterfy_aa_empty_downloads
            ConstantsAA.PITERFY_FOR_YOU_ID -> R.string.piterfy_aa_empty_for_you
            ConstantsAA.CONTINUE_ID -> R.string.piterfy_aa_empty_continue
            else -> R.string.piterfy_aa_empty
        }
    )
}
