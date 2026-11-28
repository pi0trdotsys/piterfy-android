package com.eddyizm.tempus.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.eddyizm.tempus.R
import com.eddyizm.tempus.repository.AutomotiveRepository
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.ConstantsAA
import com.eddyizm.tempus.util.ConnectionUtil
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.NetworkUtil
import com.eddyizm.tempus.util.PiterfyAutoErrors
import com.eddyizm.tempus.util.PiterfyAutoPaging
import com.eddyizm.tempus.util.PiterfyAutoResume
import com.eddyizm.tempus.util.PiterfyAutoSearch
import com.eddyizm.tempus.util.PiterfyRepeatPlan
import com.eddyizm.tempus.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "MediaLibrarySessionCallback"
private val queueSourceCache = ConcurrentHashMap<String, List<MediaItem>>()
private const val VOICE_QUEUE_LIMIT = 20
private const val ADDRESS_WAIT_MS = 3_000

@UnstableApi
class MediaLibrarySessionCallback(
    context: Context,
    service: BaseMediaService,
    private val automotiveRepository: AutomotiveRepository
) : BaseSessionCallback(context, service) {
    init {
        MediaBrowserTree.initialize(context, automotiveRepository)
    }

    /** Kolejka do ustawienia w odtwarzaczu: elementy i miejsce startu. */
    private data class Resolved(val items: List<MediaItem>, val startIndex: Int = 0, val positionMs: Long = C.TIME_UNSET)

    // Odczyt zapisanej kolejki i mapowanie jej utworów robią blokujące zapytania do bazy; poza wątkiem sesji.
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    // Samochód zgłosił, że chce tylko to, co działa bez internetu (LibraryParams.isOffline)
    @Volatile
    private var offlineHint = false

    private fun offline() = offlineHint || NetworkUtil.isOffline()

    // ─────────────────────────────────────────────────────────────
    // Android Auto — connection, short driving errors
    // ─────────────────────────────────────────────────────────────

    private var errorSession: MediaSession? = null
    private var errorListenerPlayer: Player? = null
    private val autoErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            sendAutoError(error)
        }
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        if (isCar(session, controller)) {
            // Komunikaty o błędach odtwarzania dla samochodu: słuchamy odtwarzacza raz na sesję (po reconnectcie bez dubli)
            errorSession = session
            val player = session.player
            if (errorListenerPlayer !== player) {
                errorListenerPlayer?.removeListener(autoErrorListener)
                player.addListener(autoErrorListener)
                errorListenerPlayer = player
            }
        }
        return super.onConnect(session, controller)
    }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        session.isAutomotiveController(controller) || session.isAutoCompanionController(controller)

    /** Jedno krótkie zdanie i, gdy to pomaga, przycisk „Otwórz Piterfy”; tylko do samochodu (telefon ma własne komunikaty). */
    private var lastErrorKind: PiterfyAutoErrors.Kind? = null
    private var lastErrorAt = 0L

    private fun sendAutoError(error: PlaybackException) {
        val session = errorSession ?: return
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val kind = PiterfyAutoErrors.classify(error.errorCode, http)
        // usługa ponawia odtwarzanie po chwilowych błędach sieci; ten sam komunikat nie może migać co kilka sekund
        val now = android.os.SystemClock.elapsedRealtime()
        if (kind == lastErrorKind && now - lastErrorAt < 30_000L) return
        lastErrorKind = kind
        lastErrorAt = now
        val text = context.getString(
            when (kind) {
                PiterfyAutoErrors.Kind.NETWORK -> R.string.piterfy_aa_error_network
                PiterfyAutoErrors.Kind.AUTH -> R.string.piterfy_aa_error_auth
                PiterfyAutoErrors.Kind.UNAVAILABLE -> R.string.piterfy_aa_error_unavailable
                PiterfyAutoErrors.Kind.OTHER -> R.string.piterfy_aa_error_other
            }
        )
        val code = when (kind) {
            PiterfyAutoErrors.Kind.AUTH -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
            PiterfyAutoErrors.Kind.NETWORK, PiterfyAutoErrors.Kind.UNAVAILABLE -> SessionError.ERROR_IO
            PiterfyAutoErrors.Kind.OTHER -> SessionError.ERROR_UNKNOWN
        }
        val extras = Bundle()
        if (PiterfyAutoErrors.offersOpenApp(kind)) {
            session.sessionActivity?.let {
                extras.putString(
                    MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                    context.getString(R.string.piterfy_aa_error_open_app)
                )
                extras.putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, it)
            }
        }
        runCatching {
            session.connectedControllers.filter { isCar(session, it) }
                .forEach { session.sendError(it, SessionError(code, text, extras)) }
        }.onFailure { Log.w(TAG, "sendAutoError failed", it) }
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — resume
    // ─────────────────────────────────────────────────────────────

    /**
     * Zapisana kolejka od ostatnio granego utworu i miejsca, w którym przerwano. Null, gdy nic nie zapisano.
     * Czeka (najwyżej chwilę) na sprawdzony adres serwera, tak jak przywracanie kolejki przy starcie usługi: adres jest
     * wpisany w adresy strumieni, które tu powstają.
     */
    private fun storedQueue(): ListenableFuture<Resolved?> = io.submit(Callable<Resolved?> {
        var waited = 0
        while (ConnectionUtil.pingsOutstanding() && waited < ADDRESS_WAIT_MS) {
            Thread.sleep(25)
            waited += 25
        }
        val repository = QueueRepository()
        val stored = repository.media
        if (stored.isNullOrEmpty()) return@Callable null
        val mapped = MappingUtil.mapMediaItems(stored)
        if (mapped.isEmpty()) return@Callable null
        val last = runCatching { repository.lastPlayedMedia }.getOrNull()
        val start = PiterfyAutoResume.start(
            mapped.map { it.mediaId },
            last?.id,
            last?.let { MediaManager.resumePosition(it) } ?: 0L
        ) ?: return@Callable null
        Resolved(mapped.map { PiterfyAutoItems.enrich(it) }, start.index, start.positionMs)
    })

    /** Gdy nic nie zapisano, a ktoś wciska „play”: polubione utwory w losowej kolejności, a gdy ich nie ma, losowe z biblioteki. */
    private fun favourites(): ListenableFuture<Resolved?> {
        val starred = Futures.catching(
            automotiveRepository.starredSongs,
            Throwable::class.java,
            { _ -> noResults() },
            MoreExecutors.directExecutor()
        )
        return Futures.transformAsync(starred, { result ->
            val songs = result.value?.filter { it.mediaMetadata.isPlayable == true }.orEmpty()
            if (songs.isNotEmpty()) {
                Futures.immediateFuture<Resolved?>(
                    Resolved(songs.shuffled().take(ConstantsAA.MAX_SHUFFLE_ITEMS).map { PiterfyAutoItems.enrich(it) }, 0)
                )
            } else {
                val random = Futures.catching(
                    automotiveRepository.getRandomSongs(ConstantsAA.MAX_SHUFFLE_ITEMS),
                    Throwable::class.java,
                    { _ -> noResults() },
                    MoreExecutors.directExecutor()
                )
                Futures.transform<LibraryResult<ImmutableList<MediaItem>>, Resolved?>(random, { picked ->
                    val items = picked.value?.filter { it.mediaMetadata.isPlayable == true }.orEmpty()
                    if (items.isEmpty()) null else Resolved(items.map { PiterfyAutoItems.enrich(it) }, 0)
                }, MoreExecutors.directExecutor())
            }
        }, MoreExecutors.directExecutor())
    }

    private fun resumeOrFavourites(allowFavourites: Boolean = true): ListenableFuture<Resolved> =
        Futures.transformAsync(storedQueue(), { stored ->
            if (stored != null) {
                Futures.immediateFuture(stored)
            } else if (allowFavourites) {
                Futures.transformAsync(favourites(), { picked ->
                    if (picked != null) Futures.immediateFuture(picked)
                    else Futures.immediateFailedFuture<Resolved>(UnsupportedOperationException("nothing to play"))
                }, MoreExecutors.directExecutor())
            } else {
                Futures.immediateFailedFuture<Resolved>(UnsupportedOperationException("nothing stored to resume"))
            }
        }, MoreExecutors.directExecutor())

    /**
     * Media3 pyta o to, gdy samochód łączy się z pustym odtwarzaczem albo ktoś wciska „play” (słuchawki, kierownica, Bluetooth),
     * a kolejki w odtwarzaczu jeszcze nie ma (usługa dopiero wstaje i przywraca ją w tle, albo ją usunięto). Bez tego „play” nic nie robiło.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onPlaybackResumption isForPlayback=$isForPlayback pkg=${controller.packageName}")
        return Futures.transform(resumeOrFavourites(allowFavourites = isForPlayback), { resolved ->
            MediaSession.MediaItemsWithStartPosition(resolved.items, resolved.startIndex, resolved.positionMs)
        }, MoreExecutors.directExecutor())
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — browse
    // ─────────────────────────────────────────────────────────────

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.d(TAG, "onGetLibraryRoot Start pkg=${browser.packageName}")
        offlineHint = params?.isOffline == true
        MediaBrowserTree.buildTree()
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val offline = offline()
        Log.d(TAG, "onGetChildren parentId = $parentId page=$page pageSize=$pageSize offline=$offline")

        // Błąd sieci to zdanie w samochodzie, a nie ogólny błąd bez wyjaśnienia
        val future = Futures.catching(
            MediaBrowserTree.getChildren(parentId, offline),
            Throwable::class.java,
            { t ->
                Log.w(TAG, "onGetChildren failed for $parentId", t)
                LibraryResult.ofError<ImmutableList<MediaItem>>(
                    SessionError(
                        SessionError.ERROR_IO,
                        context.getString(if (offline) R.string.piterfy_aa_error_network else R.string.piterfy_aa_error_other)
                    )
                )
            },
            MoreExecutors.directExecutor()
        )

        return Futures.transform(future, { result ->
            if (result.resultCode != LibraryResult.RESULT_SUCCESS) return@transform result
            val items = (result.value ?: ImmutableList.of()).map { PiterfyAutoItems.enrich(it) }
            // pamięć podręczna całej listy (nie jednej strony): kliknięcie utworu zagra całą listę od niego
            if (items.isNotEmpty() || parentId != ConstantsAA.ROOT_ID) {
                queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE] = items
            }
            // pusta lista: jeden element z komunikatem zamiast pustego ekranu (bez korzenia, który jest tylko menu)
            val shown = if (items.isEmpty() && parentId != ConstantsAA.ROOT_ID) {
                listOf(PiterfyAutoItems.placeholder(MediaBrowserTree.emptyText(parentId)))
            } else {
                PiterfyAutoPaging.slice(items, page, pageSize)
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(shown), result.params)
        }, MoreExecutors.directExecutor())
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — queue resolution
    // ─────────────────────────────────────────────────────────────

    /** Polecenie głosowe niesie szukaną frazę w requestMetadata i nie ma identyfikatora („zagraj Taco Hemingway”, „zagraj muzykę”). */
    private fun voiceQuery(item: MediaItem): String? =
        if (item.mediaId.isEmpty()) item.requestMetadata.searchQuery else null

    private fun isResumeRequest(item: MediaItem) = item.mediaId == ConstantsAA.RESUME_QUEUE_SOURCE

    private fun resetRepeatOne(session: MediaSession) {
        if (PiterfyRepeatPlan.shouldTurnOffForNewQueue(session.player.repeatMode)) {
            session.player.repeatMode = Player.REPEAT_MODE_OFF
            Preferences.setRepeatMode(Player.REPEAT_MODE_OFF)
        }
    }

    private fun persistQueue(items: List<MediaItem>) {
        val children = items.mapNotNull { MappingUtil.mapToChild(it) }
        if (children.isNotEmpty()) QueueRepository().insertAll(children, true, 0)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onSetMediaItems")
        val firstItem = mediaItems.firstOrNull()
            ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}, startIndex = $startIndex, startPositionMs = $startPositionMs")

        if (isRadio(firstItem)) {
            QueueRepository().deleteAll()
            return super.onSetMediaItems(mediaSession, controller, mediaItems, 0, 0)
        }

        // „Wznów: …” z listy Kontynuuj oraz „zagraj” bez frazy: zapisana kolejka, powtarzanie zostaje
        if (isResumeRequest(firstItem)) {
            return Futures.transform(resumeOrFavourites(), { resolved ->
                MediaSession.MediaItemsWithStartPosition(resolved.items, resolved.startIndex, resolved.positionMs)
            }, MoreExecutors.directExecutor())
        }

        // Nowa kolejka wybrana w samochodzie (lista albo głos) to ręczna zmiana utworu: powtarzanie jednego utworu się wyłącza
        val voice = voiceQuery(firstItem)
        if (voice != null || isCar(mediaSession, controller)) resetRepeatOne(mediaSession)

        if (voice != null) {
            if (PiterfyAutoSearch.parse(voice).isEmpty) {
                // „zagraj muzykę”: wznów to, co grało, albo polubione
                return Futures.transform(resumeOrFavourites(), { resolved ->
                    MediaSession.MediaItemsWithStartPosition(resolved.items, resolved.startIndex, resolved.positionMs)
                }, MoreExecutors.directExecutor())
            }
            return Futures.transform(resolveSearch(voice), { resolved ->
                val items = resolved.items.map { PiterfyAutoItems.enrich(detagForQueue(it)) }
                persistQueue(items)
                MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
            }, MoreExecutors.directExecutor())
        }

        // Odcinek z „Kontynuuj” startuje od zapisanej pozycji i bez reszty listy (indeks startowy zerowałby pozycję)
        val cachedClicked = queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE]?.firstOrNull { it.mediaId == firstItem.mediaId }
        val resumeMs = cachedClicked?.let { PiterfyAutoItems.resumeMsOf(it) } ?: 0L
        if (cachedClicked != null && resumeMs > 0L) {
            val single = listOf(PiterfyAutoItems.enrich(detagForQueue(cachedClicked)))
            persistQueue(single)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(single, 0, resumeMs))
        }

        val futureQueue = resolveQueueForItem(firstItem, mediaItems)

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                if (!resolvedItems.isNullOrEmpty()) {
                    val resolvedItemsUntagged = resolvedItems.map { detagForQueue(it) }
                    val children = resolvedItemsUntagged.mapNotNull { MappingUtil.mapToChild(it) }
                    if (children.isNotEmpty()) QueueRepository().insertAll(children, true, 0)
                }
                MediaSession.MediaItemsWithStartPosition(
                    resolvedItems ?: emptyList(),
                    startIndex,
                    startPositionMs
                )
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun detagForQueue(item: MediaItem): MediaItem {
        val extras = item.mediaMetadata.extras?.let { Bundle(it) } ?: Bundle()
        extras.remove("parent_id")
        extras.remove(PiterfyAutoItems.EXTRA_RESUME_MS)
        return item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "onAddMediaItems")
        val firstItem = mediaItems.firstOrNull() ?: return Futures.immediateFuture(mediaItems)

        Log.d(TAG, "mediaId = ${firstItem.mediaId}")
        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        Log.d(TAG, "extras: ${extras?.keySet()?.joinToString { key -> "$key=${extras.getString(key)}" } ?: "null"}")

        if (isRadio(firstItem)) {
            Log.d(TAG, "Radio")
            return fetchRadioItem(firstItem)
        }

        if (isResumeRequest(firstItem)) {
            return Futures.transform(resumeOrFavourites(), { it.items }, MoreExecutors.directExecutor())
        }

        voiceQuery(firstItem)?.let { query ->
            resetRepeatOne(mediaSession)
            val resolved = if (PiterfyAutoSearch.parse(query).isEmpty) resumeOrFavourites() else resolveSearch(query)
            return Futures.transform(resolved, { r -> r.items.map { PiterfyAutoItems.enrich(detagForQueue(it)) } }, MoreExecutors.directExecutor())
        }

        return resolveQueueForItem(firstItem, mediaItems)
    }

    private fun isRadio(item: MediaItem): Boolean {
        return item.mediaId.startsWith("ir-") ||
                item.mediaMetadata.extras?.getString("type", "") == Constants.MEDIA_TYPE_RADIO ||
                item.requestMetadata.extras?.getString("type", "") == Constants.MEDIA_TYPE_RADIO
    }

    private fun fetchRadioItem(firstItem: MediaItem): ListenableFuture<List<MediaItem>> {
        val radioFuture = Futures.transformAsync(
            automotiveRepository.internetRadioStations,
            { result ->
                val selected = result?.value?.find { it.mediaId == firstItem.mediaId }
                if (selected != null) {
                    val updated = selected.buildUpon()
                        .setMimeType(selected.localConfiguration?.mimeType)
                        .build()
                    Futures.immediateFuture(listOf(updated))
                } else {
                    Futures.immediateFuture(listOf(firstItem))
                }
            },
            androidx.core.content.ContextCompat.getMainExecutor(context)
        )
        return Futures.catchingAsync(
            radioFuture,
            Exception::class.java,
            { Futures.immediateFuture(listOf(firstItem)) },
            androidx.core.content.ContextCompat.getMainExecutor(context)
        )
    }

    private fun resolveQueueForItem(
        firstItem: MediaItem,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        Log.d(TAG, "Resolve queue for item")

        val extras = firstItem.requestMetadata.extras ?: firstItem.mediaMetadata.extras
        val parentId = extras?.getString("parent_id")

        val futureQueue: ListenableFuture<List<MediaItem>> = when {
            parentId?.startsWith(ConstantsAA.QUEUE_CACHED_SOURCE) == true -> {
                Log.d(TAG, "Fetching AA list source tracks for $parentId")
                val cachedItems = queueSourceCache[ConstantsAA.QUEUE_CACHED_SOURCE] ?: emptyList()
                Futures.immediateFuture(cachedItems)
            }

            firstItem.mediaId.startsWith(ConstantsAA.INSTANTMIX_SOURCE) -> {
                Log.d(TAG, "Fetching instant mix for $firstItem.mediaId")

                val withoutPrefix = firstItem.mediaId.removePrefix(ConstantsAA.INSTANTMIX_SOURCE)
                val countStr = withoutPrefix.substringAfter("[").substringBefore("]")
                val artistId = withoutPrefix.substringAfter("]")
                val count = countStr.toIntOrNull() ?: ConstantsAA.NUMBER_OF_TRACKS_IN_SMALL_MIX

                // connect handle
                MediaServiceExtensionRegistry.handler = TracksChangedExtension(automotiveRepository)

                Futures.transform(
                    automotiveRepository.getInstantMix(artistId, count),
                    { it.value ?: emptyList() },
                    MoreExecutors.directExecutor()
                )
            }

            firstItem.mediaId.startsWith(ConstantsAA.MADE_FOR_YOU_SOURCE) -> {
                Log.d(TAG, "Fetching MadeForYou for $firstItem.mediaId")

                val withoutPrefix = firstItem.mediaId.removePrefix(ConstantsAA.MADE_FOR_YOU_SOURCE)
                val countStr = withoutPrefix.substringAfter("[").substringBefore("]")
                val mixType = withoutPrefix.substringAfter("]")
                val count = countStr.toIntOrNull() ?: ConstantsAA.NUMBER_OF_TRACKS_IN_SMALL_MIX

                // connect handle
                MediaServiceExtensionRegistry.handler = TracksChangedExtension(automotiveRepository)

                Futures.transform(
                    automotiveRepository.getMadeForYou(mixType, count),
                    { it.value ?: emptyList() },
                    MoreExecutors.directExecutor()
                )
            }

            else -> {
                Log.d(TAG, "Fallback queue for item ${firstItem.mediaId}")
                val resolvedItems = ArrayList<MediaItem>()
                mediaItems.forEach { item ->
                    val sessionItem = item.localConfiguration?.uri?.let { item }
                        ?: automotiveRepository.getSessionMediaItem(item.mediaId)?.let { session ->
                            automotiveRepository.getMetadatas(session.timestamp!!)
                        }
                    sessionItem?.let { resolved ->
                        when (resolved) {
                            is List<*> -> resolvedItems.addAll(resolved.filterIsInstance<MediaItem>())
                            is MediaItem -> resolvedItems.add(resolved)
                            else -> { /* ignore */ }
                        }
                    }
                }
                if (resolvedItems.isEmpty()) resolvedItems.add(firstItem)
                Futures.immediateFuture(resolvedItems)
            }
        }

        return Futures.transform(
            futureQueue,
            { resolvedItems ->
                if (resolvedItems.isEmpty()) return@transform resolvedItems

                val startIndex = resolvedItems.indexOfFirst { it.mediaId == firstItem.mediaId }
                Log.d(TAG, "Start index for clicked item ${firstItem.mediaId} = $startIndex")
                if (startIndex <= 0) return@transform resolvedItems

                val children = resolvedItems.mapNotNull { MappingUtil.mapToChild(it) }
                if (children.isNotEmpty()) {
                    QueueRepository().insertAll(children, true, 0)
                }

                val firstResolved = resolvedItems[0]
                val extras = (firstResolved.mediaMetadata.extras ?: Bundle()).apply {
                    putInt(Constants.AA_START_INDEX, startIndex)
                }
                val newFirstResolved = firstResolved.buildUpon()
                    .setMediaMetadata(firstResolved.mediaMetadata.buildUpon().setExtras(extras).build())
                    .build()

                val updatedResolved = resolvedItems.toMutableList()
                updatedResolved[0] = newFirstResolved
                updatedResolved
            },
            MoreExecutors.directExecutor()
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Android Auto — voice and search
    // ─────────────────────────────────────────────────────────────

    private fun noResults(): LibraryResult<ImmutableList<MediaItem>> =
        LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), null)

    private fun tracksOf(result: LibraryResult<ImmutableList<MediaItem>>, label: String): Resolved {
        val items = result.value?.filter { it.mediaMetadata.isPlayable == true }.orEmpty()
        if (items.isEmpty()) throw IllegalStateException("no tracks for $label")
        return Resolved(items, 0)
    }

    /**
     * Polecenie głosowe → kolejka. Zdanie rozbijamy na rodzaj i frazę (PiterfyAutoSearch), szukamy utworów, albumów i wykonawców
     * na serwerze oraz playlist po nazwie, bierzemy najlepsze dopasowanie i zamieniamy je w kolejkę: utwór = on i podobnie
     * pasujące, album = jego utwory, wykonawca = miks z jego utworów, playlista = jej utwory. Bez dopasowania gramy znalezione
     * utwory, a gdy ich nie ma, zwracamy błąd (Auto powie, że nie znalazł).
     */
    private fun resolveSearch(query: String): ListenableFuture<Resolved> {
        val parsed = PiterfyAutoSearch.parse(query)
        val search = Futures.catching(
            automotiveRepository.search(parsed.queryText, ConstantsAA.ALBUM_ID, ConstantsAA.ARTIST_ID),
            Throwable::class.java, { _ -> noResults() }, MoreExecutors.directExecutor()
        )
        val playlists = if (parsed.kind == PiterfyAutoSearch.Kind.ANY || parsed.kind == PiterfyAutoSearch.Kind.PLAYLIST) {
            Futures.catching(
                automotiveRepository.getPlaylists(ConstantsAA.PLAYLIST_ID),
                Throwable::class.java, { _ -> noResults() }, MoreExecutors.directExecutor()
            )
        } else {
            Futures.immediateFuture(noResults())
        }

        return Futures.transformAsync(Futures.allAsList(search, playlists), { results ->
            val found: List<MediaItem> = results[0].value ?: ImmutableList.of()
            val lists: List<MediaItem> = results[1].value ?: ImmutableList.of()
            val all = LinkedHashMap<String, MediaItem>()
            (found + lists).forEach { all.putIfAbsent(it.mediaId, it) }
            val best = PiterfyAutoSearch.best(all.values.map { PiterfyAutoItems.candidate(it) }, parsed)
            val bestItem = best?.let { all[it.id] }
            val kind = best?.kind
            val songs = found.filter { it.mediaMetadata.isPlayable == true }

            when {
                bestItem == null || kind == null -> if (songs.isNotEmpty()) {
                    Futures.immediateFuture(Resolved(songs.take(VOICE_QUEUE_LIMIT), 0))
                } else {
                    Futures.immediateFailedFuture<Resolved>(IllegalStateException("nothing found for '${parsed.queryText}'"))
                }

                kind == PiterfyAutoSearch.Kind.SONG -> {
                    // wybrany utwór pierwszy, dalej pozostałe znalezione utwory od najlepiej dopasowanych
                    val others = PiterfyAutoSearch.rank(songs.map { PiterfyAutoItems.candidate(it) }, parsed)
                        .mapNotNull { all[it.id] }.filter { it.mediaId != bestItem.mediaId }
                    Futures.immediateFuture(Resolved(listOf(bestItem) + others.take(VOICE_QUEUE_LIMIT - 1), 0))
                }

                kind == PiterfyAutoSearch.Kind.ALBUM -> Futures.transform(
                    automotiveRepository.getAlbumTracks(bestItem.mediaId.removePrefix(ConstantsAA.ALBUM_ID)),
                    { tracksOf(it, "album") }, MoreExecutors.directExecutor()
                )

                kind == PiterfyAutoSearch.Kind.PLAYLIST -> Futures.transform(
                    automotiveRepository.getPlaylistSongs(bestItem.mediaId.removePrefix(ConstantsAA.PLAYLIST_ID)),
                    { tracksOf(it, "playlist") }, MoreExecutors.directExecutor()
                )

                else -> {
                    // wykonawca: miks z jego utworów (ta sama ścieżka co „Miks z wykonawcy” w Auto)
                    val artistId = bestItem.mediaId.removePrefix(ConstantsAA.ARTIST_ID)
                    val mix = MediaItem.Builder()
                        .setMediaId(ConstantsAA.INSTANTMIX_SOURCE + "[" + ConstantsAA.NUMBER_OF_TRACKS_IN_LARGE_MIX + "]" + artistId)
                        .build()
                    Futures.transform(resolveQueueForItem(mix, listOf(mix)), { items ->
                        if (items.isEmpty()) throw IllegalStateException("no tracks for artist")
                        Resolved(items, 0)
                    }, MoreExecutors.directExecutor())
                }
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return Futures.transform(MediaBrowserTree.search(query), { result ->
            val items = result.value ?: return@transform result
            LibraryResult.ofItemList(ImmutableList.copyOf(PiterfyAutoPaging.slice(items, page, pageSize)), result.params)
        }, MoreExecutors.directExecutor())
    }
}
