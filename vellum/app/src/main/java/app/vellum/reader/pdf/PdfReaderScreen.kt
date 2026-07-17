package app.vellum.reader.pdf

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.PdfStrokeEntity
import app.vellum.reader.core.model.HighlightColors
import app.vellum.reader.core.settings.ReaderSettings
import app.vellum.reader.core.theme.sharedCoverBounds
import kotlinx.coroutines.flow.debounce
import kotlin.math.roundToInt

/** Inverts page colors for dark themes — the classic PDF night mode. */
private val invertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/**
 * The PDF wing of the reader: swipeable pages with pinch-zoom, night-mode
 * inversion, and a freehand markup layer. Strokes live in normalized page
 * coordinates so they track the page through zoom and re-rendering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(bookUuid: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: PdfReaderViewModel = viewModel(key = "pdf-$bookUuid") { PdfReaderViewModel(app, bookUuid) }
    val settings by app.settingsStore.settings.collectAsState(initial = ReaderSettings())
    val ui by viewModel.ui.collectAsState()
    val strokesByPage by viewModel.strokesByPage.collectAsState()
    val theme = settings.theme

    Box(modifier = Modifier.fillMaxSize().sharedCoverBounds(bookUuid).background(theme.pageColor)) {
        when {
            ui.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            ui.error != null -> Text(ui.error!!, modifier = Modifier.align(Alignment.Center))
            else -> {
                val pagerState = rememberPagerState(initialPage = ui.startPage) { ui.pageCount }
                LaunchedEffect(pagerState.currentPage) { viewModel.persistPage(pagerState.currentPage) }

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !ui.markupMode,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    PdfPage(
                        viewModel = viewModel,
                        pageIndex = pageIndex,
                        strokes = strokesByPage[pageIndex].orEmpty(),
                        markupMode = ui.markupMode,
                        markupColor = HighlightColors.byId(ui.markupColorId).color,
                        invert = theme.isDark,
                        onToggleChrome = viewModel::toggleChrome,
                    )
                }

                PdfChrome(
                    ui = ui,
                    currentPage = pagerState.currentPage,
                    themeIsDark = theme.isDark,
                    onBack = onBack,
                    onToggleMarkup = { viewModel.setMarkupMode(!ui.markupMode) },
                    onColor = viewModel::setMarkupColor,
                    onUndo = { viewModel.undoStroke(pagerState.currentPage) },
                )
            }
        }
    }
}

/** One PDF page: bitmap, saved + in-progress ink, zoom/pan, draw capture. */
@Composable
private fun PdfPage(
    viewModel: PdfReaderViewModel,
    pageIndex: Int,
    strokes: List<PdfStrokeEntity>,
    markupMode: Boolean,
    markupColor: Color,
    invert: Boolean,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
        var pan by remember(pageIndex) { mutableStateOf(Offset.Zero) }
        val livePoints = remember(pageIndex) { mutableStateListOf<Offset>() }

        // Base render at view width; once a pinch settles, re-render at the
        // zoomed width (upgrade-only while zoomed, capped for memory) so text
        // stays sharp instead of showing a stretched bitmap.
        var renderWidth by remember(pageIndex, widthPx) { mutableIntStateOf(widthPx) }
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        LaunchedEffect(pageIndex, widthPx) {
            snapshotFlow { scale }
                .debounce(250)
                .collect { settled ->
                    val target = (widthPx * settled.coerceAtMost(3f)).roundToInt().coerceAtMost(2600)
                    if (target > renderWidth) renderWidth = target
                    else if (settled <= 1.05f) renderWidth = widthPx
                }
        }
        // The old bitmap stays visible while a sharper one renders — no flash.
        var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(pageIndex) { mutableStateOf(false) }
        LaunchedEffect(pageIndex, renderWidth) {
            val rendered = viewModel.renderer?.renderPage(pageIndex, renderWidth)
            if (rendered != null) bitmap = rendered else if (bitmap == null) failed = true
        }
        LaunchedEffect(pageIndex, widthPx) { viewModel.prefetchAround(pageIndex, widthPx) }

        val pageBitmap = bitmap
        if (pageBitmap == null) {
            if (failed) {
                Text("Couldn't display this page", modifier = Modifier.align(Alignment.Center))
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@BoxWithConstraints
        }
        val aspect = pageBitmap.width.toFloat() / pageBitmap.height.toFloat()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .align(Alignment.Center)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = pan.x,
                    translationY = pan.y,
                )
                .pointerInput(markupMode, pageIndex) {
                    if (markupMode) {
                        // Single-finger ink; points normalized against page size.
                        detectDragGestures(
                            onDragStart = { start ->
                                livePoints.clear()
                                livePoints.add(Offset(start.x / size.width, start.y / size.height))
                            },
                            onDragEnd = {
                                viewModel.commitStroke(pageIndex, livePoints.toList(), 0.004f)
                                livePoints.clear()
                            },
                            onDragCancel = { livePoints.clear() },
                        ) { change, _ ->
                            livePoints.add(
                                Offset(change.position.x / size.width, change.position.y / size.height),
                            )
                        }
                    } else {
                        detectTransformGestures { _, panDelta, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            val maxPanX = size.width * (scale - 1f) / 2f
                            val maxPanY = size.height * (scale - 1f) / 2f
                            pan = Offset(
                                (pan.x + panDelta.x * scale).coerceIn(-maxPanX, maxPanX),
                                (pan.y + panDelta.y * scale).coerceIn(-maxPanY, maxPanY),
                            )
                        }
                    }
                }
                .pointerInput(markupMode) {
                    if (!markupMode) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                pan = Offset.Zero
                            },
                        ) { onToggleChrome() }
                    }
                },
        ) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                colorFilter = if (invert) invertFilter else null,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach { stroke ->
                    drawInk(
                        PdfReaderViewModel.parsePoints(stroke.points),
                        HighlightColors.byId(stroke.colorId).color,
                        stroke.strokeWidth,
                    )
                }
                if (livePoints.isNotEmpty()) {
                    drawInk(livePoints.toList(), markupColor, 0.004f)
                }
            }
        }
    }
}

/** Connects normalized points into a smooth polyline scaled to canvas size. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(
    normalizedPoints: List<Offset>,
    color: Color,
    widthNormalized: Float,
) {
    if (normalizedPoints.size < 2) return
    val path = Path()
    normalizedPoints.forEachIndexed { index, point ->
        val x = point.x * size.width
        val y = point.y * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = (widthNormalized * size.width).coerceAtLeast(2f)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfChrome(
    ui: PdfUiState,
    currentPage: Int,
    themeIsDark: Boolean,
    onBack: () -> Unit,
    onToggleMarkup: () -> Unit,
    onColor: (String) -> Unit,
    onUndo: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = ui.chromeVisible || ui.markupMode,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(ui.bookTitle, fontFamily = FontFamily.Serif, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    if (ui.markupMode) {
                        HighlightColors.All.forEach { highlight ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(26.dp)
                                    .background(highlight.color, CircleShape)
                                    .border(
                                        width = if (highlight.id == ui.markupColorId) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    )
                                    .clickable { onColor(highlight.id) },
                            )
                        }
                        IconButton(onClick = onUndo) {
                            // Core icons have no Undo glyph; a mirrored Refresh
                            // gives the expected counterclockwise arrow.
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Undo last stroke",
                                modifier = Modifier.graphicsLayer(scaleX = -1f),
                            )
                        }
                        IconButton(onClick = onToggleMarkup) {
                            Icon(Icons.Filled.Check, contentDescription = "Finish markup")
                        }
                    } else {
                        IconButton(onClick = onToggleMarkup) {
                            Icon(Icons.Filled.Edit, contentDescription = "Draw on page")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (themeIsDark) Color(0xF0202024) else Color(0xF0FBF8F1),
                ),
            )
        }

        AnimatedVisibility(
            visible = ui.chromeVisible && !ui.markupMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Text(
                text = "Page ${currentPage + 1} of ${ui.pageCount}",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (themeIsDark) Color(0xF0202024) else Color(0xF0FBF8F1))
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
            )
        }
    }
}
