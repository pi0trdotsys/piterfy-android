package com.eddyizm.tempus.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.util.PiterfyGesturePlan
import com.eddyizm.tempus.util.PiterfyHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.util.RadioCoverArtDownloader
import kotlin.math.absoluteValue

@Composable
fun NowPlayingArtworkPager(
    queue: List<Child>,
    currentIndex: Int,
    isRadio: Boolean = false,
    radioArtworkUri: Uri? = null,
    radioCoverArtId: String? = null,
    onPageSelected: (Int) -> Unit = {},
    onCoverClick: () -> Unit = {},
    onDoubleTapSeek: (PiterfyGesturePlan.Zone) -> Unit = {}
) {
    val currentQueue by rememberUpdatedState(queue)
    val currentTrackIndex by rememberUpdatedState(currentIndex)
    val currentOnPageSelected by rememberUpdatedState(onPageSelected)
    val currentOnCoverClick by rememberUpdatedState(onCoverClick)
    val currentOnDoubleTapSeek by rememberUpdatedState(onDoubleTapSeek)
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val edge = remember { Animatable(0f) }           // przesunięcie „gumą” przy próbie wyjścia poza koniec kolejki
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    var flashCount by remember { mutableStateOf(0) }

    val count = if (isRadio || queue.isEmpty()) 1 else queue.size
    val safeIndex = if (isRadio) 0 else currentIndex.coerceIn(0, (count - 1).coerceAtLeast(0))

    val pagerState = rememberPagerState(
        initialPage = safeIndex,
        pageCount = { count }
    )
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var userSwiped by remember { mutableStateOf(false) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            userSwiped = true
        }
    }

    // Programmatic smooth scroll when track index changes externally (e.g. next/prev buttons, notification, end-of-track).
    // Guarded by safeIndex != pagerState.targetPage to avoid interrupting manual swipes.
    LaunchedEffect(safeIndex) {
        if (!isDragged && safeIndex in 0 until count && safeIndex != pagerState.targetPage) {
            pagerState.animateScrollToPage(
                page = safeIndex,
                animationSpec = tween(durationMillis = 300)
            )
        }
    }

    // Capture user swipe settlement and trigger track change only when user physically swiped
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settledPage ->
            if (userSwiped && !isRadio && settledPage in currentQueue.indices && settledPage != currentTrackIndex) {
                userSwiped = false
                PiterfyHaptics.click(view)   // okładka „zatrzasnęła się” na nowym utworze
                currentOnPageSelected(settledPage)
            }
        }
    }

    val cornerRadius = if (Preferences.isCornerRoundingEnabled()) {
        Preferences.getRoundedCornerSize().dp
    } else {
        0.dp
    }

    // Liquid Glass: okladka z miekkim, glebokim cieniem (jak w odtwarzaczu iOS)
    val glass = remember { com.eddyizm.tempus.appearance.PiterfyAppearance.glassEnabled() }

    // Piterfy: opór „gumy” na końcach kolejki + podwójne stuknięcie po bokach okładki = przewiń o 10 s
    val slopPx = with(density) { PiterfyGesturePlan.DRAG_SLOP_DP.dp.toPx() }
    val bumpPx = with(density) { PiterfyGesturePlan.EDGE_BUMP_DP.dp.toPx() }
    val rubberMaxPx = with(density) { PiterfyGesturePlan.RUBBER_MAX_DP.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isRadio, count) {
                if (isRadio) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dx = 0f
                        var dy = 0f
                        var bumped = false
                        var horizontal = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
                            if (!horizontal && PiterfyGesturePlan.axisLock(dx, dy, slopPx) == PiterfyGesturePlan.Axis.HORIZONTAL) horizontal = true
                            if (!horizontal) continue
                            val past = PiterfyGesturePlan.pastEdge(pagerState.currentPage, count, dx, 1f)
                            val shift = if (past) PiterfyGesturePlan.rubberBand(dx, rubberMaxPx) else 0f
                            scope.launch { edge.snapTo(shift) }
                            if (past && !bumped && PiterfyGesturePlan.pastEdge(pagerState.currentPage, count, dx, bumpPx)) {
                                bumped = true
                                PiterfyHaptics.reject(view)
                            }
                        }
                        scope.launch { edge.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
                    }
                }
            }
            .graphicsLayer { translationX = edge.value }
    ) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 0.dp),
        pageSpacing = 16.dp,
        userScrollEnabled = !isRadio && count > 1,
        key = { page ->
            if (isRadio) "radio" else "${queue.getOrNull(page)?.id ?: "track"}_$page"
        }
    ) { page ->
        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
        val scale = lerp(0.92f, 1.0f, 1f - pageOffset.coerceIn(0f, 1f))
        val alpha = lerp(0.7f, 1.0f, 1f - pageOffset.coerceIn(0f, 1f))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .then(
                    if (glass) Modifier.shadow(
                        elevation = 22.dp, shape = RoundedCornerShape(cornerRadius), clip = false,
                        ambientColor = Color.Black, spotColor = Color.Black
                    ) else Modifier
                )
                .clip(RoundedCornerShape(cornerRadius))
                .pointerInput(page, isRadio) {
                    detectTapGestures(
                        onTap = { if (page == pagerState.currentPage) currentOnCoverClick() },
                        onDoubleTap = { offset ->
                            if (page != pagerState.currentPage || isRadio) return@detectTapGestures
                            val zone = PiterfyGesturePlan.zoneOf(offset.x, size.width.toFloat())
                            if (zone == PiterfyGesturePlan.Zone.CENTER) {
                                currentOnCoverClick()
                            } else {
                                PiterfyHaptics.confirm(view)
                                flashCount += 1
                                flash = SeekFlash(zone, flashCount)
                                currentOnDoubleTapSeek(zone)
                            }
                        }
                    )
                }
                .semantics {
                    onClick { if (page == pagerState.currentPage) { currentOnCoverClick(); true } else false }
                    if (!isRadio) customActions = listOf(
                        CustomAccessibilityAction(view.context.getString(R.string.piterfy_seek_back_ten)) { currentOnDoubleTapSeek(PiterfyGesturePlan.Zone.LEFT); true },
                        CustomAccessibilityAction(view.context.getString(R.string.piterfy_seek_forward_ten)) { currentOnDoubleTapSeek(PiterfyGesturePlan.Zone.RIGHT); true }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isRadio) {
                RadioArtwork(artworkUri = radioArtworkUri, coverArtId = radioCoverArtId)
            } else {
                val song = queue.getOrNull(page)
                SongArtwork(coverArtId = song?.coverArtId, title = song?.title)
            }
        }
    }
    flash?.let { shown ->
        LaunchedEffect(shown) {
            delay(650)
            if (flash === shown) flash = null
        }
    }
    val flashAlpha by animateFloatAsState(if (flash != null) 1f else 0f, tween(if (flash != null) 90 else 260), label = "seekFlash")
    val lastZone = remember { mutableStateOf(PiterfyGesturePlan.Zone.RIGHT) }
    flash?.let { lastZone.value = it.zone }
    if (flashAlpha > 0.01f) {
        val label = PiterfyGesturePlan.seekLabel(lastZone.value)
        if (label != null) {
            Box(
                modifier = Modifier
                    .align(if (lastZone.value == PiterfyGesturePlan.Zone.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 28.dp)
                    .graphicsLayer { alpha = flashAlpha; scaleX = 0.85f + 0.15f * flashAlpha; scaleY = 0.85f + 0.15f * flashAlpha }
                    .background(Color(0x99000000), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp))
            }
        }
    }
    }  // koniec Box z gumą na końcach kolejki
}

private data class SeekFlash(val zone: PiterfyGesturePlan.Zone, val id: Int)

@Composable
private fun SongArtwork(
    coverArtId: String?,
    title: String?
) {
    val appContext = LocalContext.current.applicationContext
    var bitmap by remember(coverArtId) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(coverArtId) {
        if (coverArtId.isNullOrEmpty()) {
            bitmap = null
            return@DisposableEffect onDispose {}
        }

        val url = CustomGlideRequest.createUrl(coverArtId, Preferences.getImageSize())
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }

        Glide.with(appContext)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(CustomGlideRequest.DEFAULT_DISK_CACHE_STRATEGY)
            .into(target)

        onDispose {
            Glide.with(appContext).clear(target)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_placeholder_song),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun RadioArtwork(
    artworkUri: Uri?,
    coverArtId: String?
) {
    val appContext = LocalContext.current.applicationContext
    var bitmap by remember(artworkUri, coverArtId) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(artworkUri, coverArtId) {
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }

        var request = Glide.with(appContext)
            .asBitmap()
            .load(artworkUri)
            .apply(CustomGlideRequest.createRequestOptions(appContext, coverArtId, CustomGlideRequest.ResourceType.Radio))

        request = RadioCoverArtDownloader.applyLocalFileSignature(request, artworkUri)
        request.into(target)

        onDispose {
            Glide.with(appContext).clear(target)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_placeholder_radio),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
