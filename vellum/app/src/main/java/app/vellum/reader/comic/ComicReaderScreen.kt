package app.vellum.reader.comic

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.ComicPanelEntity
import app.vellum.reader.core.session.ActiveReadingEffect
import app.vellum.reader.core.theme.sharedCoverBounds
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The comic wing: paged image reading with pinch-zoom, per-book right-to-left
 * mode, two-page spreads in landscape, and a guided panel-by-panel view driven
 * by reader-drawn panel rects. True-black background — comics live on OLED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(bookUuid: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: ComicReaderViewModel = viewModel(key = "comic-$bookUuid") { ComicReaderViewModel(app, bookUuid) }
    val ui by viewModel.ui.collectAsState()
    ActiveReadingEffect(viewModel::setSessionActive)
    val panelsByPage by viewModel.panelsByPage.collectAsState()

    Box(modifier = Modifier.fillMaxSize().sharedCoverBounds(bookUuid).background(Color.Black)) {
        when {
            ui.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            ui.error != null -> Text(ui.error!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val landscape = constraints.maxWidth > constraints.maxHeight
                // Landscape pages come in twos: each pager item is the spread
                // (2i, 2i+1), so a swipe advances a whole spread and no page
                // repeats between neighbors.
                val pagerState = rememberPagerState(
                    initialPage = if (landscape) ui.startPage / 2 else ui.startPage,
                ) { if (landscape) (ui.pageCount + 1) / 2 else ui.pageCount }
                val scope = rememberCoroutineScope()
                LaunchedEffect(pagerState.currentPage, landscape) {
                    val leadingPage = if (landscape) pagerState.currentPage * 2 else pagerState.currentPage
                    viewModel.persistPage(leadingPage)
                    // Prefetch at the width pages actually render at (half in spreads).
                    viewModel.prefetchAround(
                        leadingPage,
                        if (landscape) constraints.maxWidth / 2 else constraints.maxWidth,
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    reverseLayout = ui.rtl,
                    userScrollEnabled = !ui.panelEditMode,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    if (landscape) {
                        SpreadView(viewModel, pageIndex, ui.pageCount, ui.rtl, viewModel::toggleChrome)
                    } else {
                        ComicPageView(
                            viewModel = viewModel,
                            pageIndex = pageIndex,
                            panels = panelsByPage[pageIndex].orEmpty(),
                            rtl = ui.rtl,
                            editMode = ui.panelEditMode,
                            onToggleChrome = viewModel::toggleChrome,
                            onNextPage = {
                                scope.launch {
                                    if (pageIndex + 1 < ui.pageCount) pagerState.animateScrollToPage(pageIndex + 1)
                                }
                            },
                            onPrevPage = {
                                scope.launch {
                                    if (pageIndex > 0) pagerState.animateScrollToPage(pageIndex - 1)
                                }
                            },
                        )
                    }
                }

                ComicChrome(
                    ui = ui,
                    currentPage = pagerState.currentPage,
                    onBack = onBack,
                    onToggleRtl = { viewModel.setRtl(!ui.rtl) },
                    onToggleEdit = { viewModel.setPanelEditMode(!ui.panelEditMode) },
                    onUndoPanel = { viewModel.undoPanel(pagerState.currentPage) },
                )
            }
        }
    }
}

/**
 * One comic page: pinch zoom, tap zones (direction-aware), the guided panel
 * walk when panels exist, and the rect-drawing editor when enabled.
 */
@Composable
private fun ComicPageView(
    viewModel: ComicReaderViewModel,
    pageIndex: Int,
    panels: List<ComicPanelEntity>,
    rtl: Boolean,
    editMode: Boolean,
    onToggleChrome: () -> Unit,
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val scope = rememberCoroutineScope()
        val scale = remember(pageIndex) { Animatable(1f) }
        val pan = remember(pageIndex) { Animatable(Offset.Zero, Offset.VectorConverter) }
        var panelStep by remember(pageIndex) { mutableIntStateOf(-1) }
        var dragRect by remember(pageIndex) { mutableStateOf<Rect?>(null) }

        // Base decode at view width; a settled zoom re-decodes at the zoomed
        // width (upgrade-only, capped) so art stays sharp under the pinch.
        var renderWidth by remember(pageIndex, widthPx) { mutableIntStateOf(widthPx) }
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        LaunchedEffect(pageIndex, widthPx) {
            snapshotFlow { scale.value }
                .debounce(250)
                .collect { settled ->
                    val target = (widthPx * settled.coerceAtMost(3f)).roundToInt().coerceAtMost(2600)
                    if (target > renderWidth) renderWidth = target
                    else if (settled <= 1.05f) renderWidth = widthPx
                }
        }
        // The old bitmap stays visible while a sharper one decodes — no flash.
        var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(pageIndex) { mutableStateOf(false) }
        LaunchedEffect(pageIndex, renderWidth) {
            val decoded = viewModel.store?.page(pageIndex, renderWidth)
            if (decoded != null) bitmap = decoded else if (bitmap == null) failed = true
        }

        val pageBitmap = bitmap
        if (pageBitmap == null) {
            if (failed) {
                Text(
                    "Couldn't display this page",
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            }
            return@BoxWithConstraints
        }
        val aspect = pageBitmap.width.toFloat() / pageBitmap.height.toFloat()

        /** Zooms the camera onto one panel rect (normalized page coords). */
        fun focusPanel(panel: ComicPanelEntity, boxSize: Size) {
            val width = max(panel.right - panel.left, 0.05f)
            val height = max(panel.bottom - panel.top, 0.05f)
            val target = min(1f / width, 1f / height).coerceIn(1f, 3.5f)
            val centerX = (panel.left + panel.right) / 2f
            val centerY = (panel.top + panel.bottom) / 2f
            scope.launch { scale.animateTo(target, tween(320)) }
            scope.launch {
                pan.animateTo(
                    Offset(
                        (0.5f - centerX) * boxSize.width * target,
                        (0.5f - centerY) * boxSize.height * target,
                    ),
                    tween(320),
                )
            }
        }

        fun resetCamera() {
            scope.launch { scale.animateTo(1f, tween(240)) }
            scope.launch { pan.animateTo(Offset.Zero, tween(240)) }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .align(Alignment.Center)
                .graphicsLayer(
                    scaleX = scale.value,
                    scaleY = scale.value,
                    translationX = pan.value.x,
                    translationY = pan.value.y,
                )
                .pointerInput(editMode, pageIndex) {
                    if (editMode) {
                        // Drag out a rectangle → one guided-view panel.
                        detectDragGestures(
                            onDragStart = { start -> dragRect = Rect(start, start) },
                            onDragEnd = {
                                dragRect?.let { rect ->
                                    viewModel.addPanel(
                                        pageIndex,
                                        min(rect.left, rect.right) / size.width,
                                        min(rect.top, rect.bottom) / size.height,
                                        max(rect.left, rect.right) / size.width,
                                        max(rect.top, rect.bottom) / size.height,
                                    )
                                }
                                dragRect = null
                            },
                            onDragCancel = { dragRect = null },
                        ) { change, _ ->
                            dragRect = dragRect?.copy(right = change.position.x, bottom = change.position.y)
                        }
                    } else {
                        detectTransformGestures { _, panDelta, zoom, _ ->
                            scope.launch {
                                val newScale = (scale.value * zoom).coerceIn(1f, 5f)
                                scale.snapTo(newScale)
                                val maxPanX = size.width * (newScale - 1f) / 2f
                                val maxPanY = size.height * (newScale - 1f) / 2f
                                pan.snapTo(
                                    Offset(
                                        (pan.value.x + panDelta.x * newScale).coerceIn(-maxPanX, maxPanX),
                                        (pan.value.y + panDelta.y * newScale).coerceIn(-maxPanY, maxPanY),
                                    ),
                                )
                            }
                        }
                    }
                }
                .pointerInput(editMode, pageIndex, rtl, panels.size) {
                    if (!editMode) {
                        detectTapGestures(
                            onDoubleTap = {
                                panelStep = -1
                                resetCamera()
                            },
                        ) { offset ->
                            // graphicsLayer inverse-maps pointer positions into
                            // page space, so once the camera zooms to a side
                            // panel every visible tap lands in that side's
                            // zone. Map back to screen space (center pivot +
                            // pan) before deciding the zone.
                            val screenX = (offset.x - size.width / 2f) * scale.value +
                                size.width / 2f + pan.value.x
                            val third = size.width / 3f
                            val forward = if (rtl) screenX < third else screenX > 2 * third
                            val backward = if (rtl) screenX > 2 * third else screenX < third
                            val boxSize = Size(size.width.toFloat(), size.height.toFloat())
                            when {
                                forward -> {
                                    // Guided walk first; falls through to page turns.
                                    if (panels.isNotEmpty() && panelStep + 1 < panels.size) {
                                        panelStep += 1
                                        focusPanel(panels[panelStep], boxSize)
                                    } else {
                                        panelStep = -1
                                        resetCamera()
                                        onNextPage()
                                    }
                                }
                                backward -> {
                                    if (panels.isNotEmpty() && panelStep > 0) {
                                        panelStep -= 1
                                        focusPanel(panels[panelStep], boxSize)
                                    } else {
                                        panelStep = -1
                                        resetCamera()
                                        onPrevPage()
                                    }
                                }
                                else -> onToggleChrome()
                            }
                        }
                    }
                },
        ) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (editMode) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    panels.forEach { panel ->
                        drawRect(
                            color = Color(0xFF64B5F6),
                            topLeft = Offset(panel.left * size.width, panel.top * size.height),
                            size = Size(
                                (panel.right - panel.left) * size.width,
                                (panel.bottom - panel.top) * size.height,
                            ),
                            style = Stroke(width = 4f),
                        )
                    }
                    dragRect?.let { rect ->
                        drawRect(
                            color = Color(0xFFFFB74D),
                            topLeft = Offset(min(rect.left, rect.right), min(rect.top, rect.bottom)),
                            size = Size(
                                kotlin.math.abs(rect.width),
                                kotlin.math.abs(rect.height),
                            ),
                            style = Stroke(width = 4f),
                        )
                    }
                }
            }
        }

        if (panels.isNotEmpty() && panelStep >= 0) {
            Text(
                text = "${panelStep + 1} / ${panels.size}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/** Landscape: the spread (2i, 2i+1) side by side, ordered by direction. */
@Composable
private fun SpreadView(
    viewModel: ComicReaderViewModel,
    spreadIndex: Int,
    pageCount: Int,
    rtl: Boolean,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onToggleChrome() } },
    ) {
        val halfWidth = constraints.maxWidth / 2
        val basePage = spreadIndex * 2
        // Manga reads right-to-left: the leading page sits on the right.
        val leftPage = if (rtl) basePage + 1 else basePage
        val rightPage = if (rtl) basePage else basePage + 1
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
            listOf(leftPage, rightPage).forEach { index ->
                Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    if (index in 0 until pageCount) {
                        val bitmap by produceState<Bitmap?>(initialValue = null, index, halfWidth) {
                            value = viewModel.store?.page(index, halfWidth)
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicChrome(
    ui: ComicUiState,
    currentPage: Int,
    onBack: () -> Unit,
    onToggleRtl: () -> Unit,
    onToggleEdit: () -> Unit,
    onUndoPanel: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = ui.chromeVisible || ui.panelEditMode,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(ui.bookTitle, fontFamily = FontFamily.Serif, maxLines = 1, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = Color.White)
                    }
                },
                actions = {
                    if (ui.panelEditMode) {
                        IconButton(onClick = onUndoPanel) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Undo last panel",
                                tint = Color.White,
                                modifier = Modifier.graphicsLayer(scaleX = -1f),
                            )
                        }
                        IconButton(onClick = onToggleEdit) {
                            Icon(Icons.Filled.Check, contentDescription = "Finish panels", tint = Color.White)
                        }
                    } else {
                        TextButton(onClick = onToggleRtl) {
                            Text(if (ui.rtl) "RTL" else "LTR", color = if (ui.rtl) Color(0xFF90CAF9) else Color.White)
                        }
                        IconButton(onClick = onToggleEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Define panels", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xE6101014)),
            )
        }

        AnimatedVisibility(
            visible = ui.chromeVisible && !ui.panelEditMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                text = "Page ${currentPage + 1} of ${ui.pageCount}",
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6101014))
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
            )
        }
    }
}
