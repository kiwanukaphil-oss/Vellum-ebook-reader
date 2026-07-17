package app.vellum.reader.reader.ui

import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.model.HighlightColors
import app.vellum.reader.core.model.ReadingTheme
import app.vellum.reader.core.settings.ReaderSettings
import app.vellum.reader.core.settings.TurnStyle
import app.vellum.reader.reader.ambient.PageRustle
import app.vellum.reader.reader.layout.ChapterPaginator
import app.vellum.reader.reader.lookup.Lookups
import app.vellum.reader.reader.turn.PageCurlShader
import app.vellum.reader.reader.ui.ReaderContentRenderer.drawSpread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import kotlin.math.abs

/** Everything one in-flight curl turn needs, captured before the first frame. */
private class TurnSession(
    val forward: Boolean,
    val target: TurnTarget,
    val shader: android.graphics.RuntimeShader,
    val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
)

/**
 * The reading surface: page canvas underneath, gesture layer on top, and
 * chrome that stays hidden until the reader taps the page center. Page turns
 * play as a GPU cylinder curl (AGSL) driven live by the finger; slide remains
 * as the fast alternative style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookUuid: String,
    initialChapter: Int = -1,
    initialOffset: Int = -1,
    onBack: () -> Unit,
    onSearchInBook: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as VellumApp
    val viewModel: ReaderViewModel = viewModel(key = "$bookUuid/$initialChapter/$initialOffset") {
        ReaderViewModel(app, bookUuid, initialChapter, initialOffset)
    }
    val settings by app.settingsStore.settings.collectAsState(initial = ReaderSettings())
    val ui by viewModel.ui.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val ttsRange by viewModel.ttsRange.collectAsState()
    val ttsSleep by viewModel.ttsSleep.collectAsState()
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var settingsSheetOpen by remember { mutableStateOf(false) }
    var annotationsListOpen by remember { mutableStateOf(false) }
    var speedLabel by remember { mutableStateOf("1.0×") }
    var spreadMode by remember { mutableStateOf(false) }
    var voicePickerOpen by remember { mutableStateOf(false) }
    var editorForSelection by remember { mutableStateOf(false) }
    var editorAnnotation by remember { mutableStateOf<AnnotationEntity?>(null) }
    var lookup by remember { mutableStateOf<Pair<String, String>?>(null) }

    // TTS keeps the screen awake; sessions record silently either way.
    view.keepScreenOn = ttsStatus == TtsStatus.PLAYING

    // Optional ambient rustle, synthesized once, played on page commits.
    val rustle = remember(settings.pageRustle) {
        if (settings.pageRustle) app.let { PageRustle(it) } else null
    }
    androidx.compose.runtime.DisposableEffect(rustle) {
        onDispose { rustle?.release() }
    }

    // Evening mode: re-evaluate the warmth curve every minute while enabled.
    var warmth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(settings.eveningMode) {
        if (!settings.eveningMode) {
            warmth = 0f
        } else {
            while (true) {
                warmth = eveningWarmthNow()
                delay(60_000)
            }
        }
    }
    val theme = settings.theme.warmed(warmth)

    // Focus timer: quiet countdown from entering the reader; one soft tone at zero.
    var focusRemainingSec by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(settings.focusMinutes) {
        if (settings.focusMinutes <= 0) {
            focusRemainingSec = null
        } else {
            val deadline = System.currentTimeMillis() + settings.focusMinutes * 60_000L
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()) / 1000
                if (remaining <= 0) {
                    focusRemainingSec = null
                    try {
                        android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 60)
                            .startTone(android.media.ToneGenerator.TONE_PROP_ACK, 300)
                    } catch (e: Exception) {
                        // No tone available — the timer simply ends quietly.
                    }
                    break
                }
                focusRemainingSec = remaining
                delay(1_000)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.pageColor)) {
        // Page paint extends behind the system bars (outer Box); the measured
        // reading viewport does not, so no line of text sits under the status
        // bar or the gesture area.
        BoxWithConstraints(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            val columns = if (widthPx > heightPx) 2 else 1
            val marginPx = with(density) { settings.typography.pageMarginDp.dp.toPx() }
            val curlRadiusPx = with(density) { 88.dp.toPx() }

            val paginator = remember(widthPx, heightPx, settings.typography, columns) {
                ChapterPaginator(textMeasurer, widthPx, heightPx, settings.typography, density, columns)
            }
            LaunchedEffect(paginator) {
                spreadMode = columns == 2
                viewModel.onViewportChanged(paginator)
            }

            var turnSession by remember { mutableStateOf<TurnSession?>(null) }

            /** Draw spec for any (chapter, page) this screen can currently show. */
            fun specFor(chapterIndex: Int, pageIndex: Int): ReaderContentRenderer.SpreadSpec? {
                val paginated = viewModel.paginatedFor(chapterIndex) ?: return null
                val pageFraction = (pageIndex + 1f) / paginated.pages.size.coerceAtLeast(1)
                return ReaderContentRenderer.SpreadSpec(
                    paginated = paginated,
                    startPage = pageIndex,
                    columns = columns,
                    theme = theme,
                    marginPx = marginPx,
                    contentWidthPx = paginator.contentWidthPx,
                    paperTexture = settings.paperTexture,
                    pageEdges = settings.pageEdges,
                    bookProgress = ((chapterIndex + pageFraction) / ui.chapterCount.coerceAtLeast(1))
                        .coerceIn(0f, 1f),
                    highlights = annotations.filter { it.chapterIndex == chapterIndex }.map {
                        ReaderContentRenderer.HighlightSpan(
                            it.startChar, it.endChar, HighlightColors.byId(it.colorId).color,
                        )
                    } + listOfNotNull(
                        ttsRange?.takeIf { chapterIndex == ui.chapterIndex }?.let {
                            ReaderContentRenderer.HighlightSpan(it.startChar, it.endChar, Color(0xFFE8B84B))
                        },
                    ),
                    selection = selection?.takeIf { chapterIndex == ui.chapterIndex }?.let {
                        ReaderContentRenderer.HighlightSpan(it.startChar, it.endChar, Color(0xFF5B8DEF))
                    },
                    selectionHandleRadiusPx = with(density) { 7.dp.toPx() },
                    images = viewModel.imagesFor(chapterIndex),
                )
            }

            /**
             * Maps a touch point to a chapter character offset: column → slice
             * under y → layout-local position → block offset + block start.
             */
            fun chapterOffsetAt(position: androidx.compose.ui.geometry.Offset): Int? {
                val paginated = viewModel.paginatedFor(ui.chapterIndex) ?: return null
                val column =
                    if (columns == 2 && position.x > marginPx + paginator.contentWidthPx + marginPx / 2f) 1 else 0
                val page = paginated.pages.getOrNull(ui.pageIndex + column) ?: return null
                val xOffset = marginPx + column * (paginator.contentWidthPx + marginPx)
                val slice = page.slices.firstOrNull { candidate ->
                    val block = paginated.measured[candidate.blockIndex]
                    val top = marginPx + candidate.y
                    val height = block.layout.getLineBottom(candidate.lastLine) - block.layout.getLineTop(candidate.firstLine)
                    position.y >= top && position.y <= top + height
                } ?: return null
                val block = paginated.measured[slice.blockIndex]
                val lineTop = block.layout.getLineTop(slice.firstLine)
                val local = block.layout.getOffsetForPosition(
                    androidx.compose.ui.geometry.Offset(
                        x = position.x - xOffset - block.indentPx,
                        y = position.y - marginPx - slice.y + lineTop,
                    ),
                )
                return block.charStart + local
            }

            fun clickHaptic() {
                if (settings.hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                rustle?.play()
            }

            /**
             * Resolves the turn target, captures both page textures, and builds
             * the configured curl shader — everything the first frame needs.
             */
            suspend fun beginCurl(forward: Boolean): TurnSession? {
                if (turnSession != null) return turnSession
                val target = viewModel.peekTurnTarget(forward) ?: return null
                val currentSpec = specFor(ui.chapterIndex, ui.pageIndex) ?: return null
                val targetSpec = specFor(target.chapterIndex, target.pageIndex) ?: return null
                val (current, next) = withContext(Dispatchers.Default) {
                    ReaderContentRenderer.renderToBitmap(widthPx, heightPx, density, layoutDirection, currentSpec) to
                        ReaderContentRenderer.renderToBitmap(widthPx, heightPx, density, layoutDirection, targetSpec)
                }
                val front: ImageBitmap = if (forward) current else next
                val under: ImageBitmap = if (forward) next else current
                val shader = PageCurlShader.create(
                    widthPx.toFloat(), heightPx.toFloat(),
                    progress = if (forward) 0f else 1f,
                    forward = forward,
                    radiusPx = curlRadiusPx,
                    paperColor = theme.pageColor,
                    frontPage = front,
                    underPage = under,
                )
                val session = TurnSession(forward, target, shader, Animatable(if (forward) 0f else 1f))
                turnSession = session
                return session
            }

            /** Finishes a curl: play to the end (or back), commit or discard. */
            suspend fun settleCurl(session: TurnSession, commit: Boolean) {
                val end = when {
                    commit -> if (session.forward) 1f else 0f
                    else -> if (session.forward) 0f else 1f
                }
                session.progress.animateTo(end, tween(280, easing = FastOutSlowInEasing))
                if (commit) {
                    viewModel.commitTurn(session.target, session.forward)
                    clickHaptic()
                }
                turnSession = null
            }

            fun tapTurn(forward: Boolean) {
                if (settings.turnStyle == TurnStyle.SLIDE) {
                    if (forward) viewModel.nextPage() else viewModel.prevPage()
                    clickHaptic()
                    return
                }
                scope.launch {
                    val session = beginCurl(forward) ?: return@launch
                    settleCurl(session, commit = true)
                }
            }

            // ---- Page content -------------------------------------------------
            if (settings.turnStyle == TurnStyle.SLIDE) {
                AnimatedContent(
                    targetState = ui.pageKey,
                    transitionSpec = {
                        val direction = if (ui.forward) 1 else -1
                        (slideInHorizontally(tween(220)) { it * direction } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(tween(220)) { -it * direction } + fadeOut(tween(220)))
                    },
                    label = "pageTurn",
                ) { key ->
                    val spec = specFor(key.chapterIndex, key.pageIndex)
                    Canvas(modifier = Modifier.fillMaxSize()) { spec?.let { drawSpread(it) } }
                }
            } else {
                val session = turnSession
                if (session == null) {
                    val spec = specFor(ui.chapterIndex, ui.pageIndex)
                    // TalkBack reads the visible page: expose its text as semantics.
                    val pageText = viewModel.pageTextFor(ui.chapterIndex, ui.pageIndex) ?: "Page"
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = pageText },
                    ) { spec?.let { drawSpread(it) } }
                } else {
                    // The shader renders every pixel of both pages during a turn.
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        session.shader.setFloatUniform("progress", session.progress.value)
                        drawRect(brush = ShaderBrush(session.shader))
                    }
                }
            }

            // ---- Gestures -----------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(settings.turnStyle) {
                        detectTapGestures(
                            onLongPress = { offset ->
                                if (viewModel.selection.value == null) {
                                    chapterOffsetAt(offset)?.let {
                                        viewModel.beginSelectionAt(it)
                                        clickHaptic()
                                    }
                                }
                            },
                        ) { offset ->
                            // A tap on an existing highlight opens its editor;
                            // otherwise the side/center zones behave as always.
                            val tappedAnnotation = chapterOffsetAt(offset)?.let(viewModel::annotationAt)
                            if (tappedAnnotation != null) {
                                editorAnnotation = tappedAnnotation
                                return@detectTapGestures
                            }
                            val third = size.width / 3f
                            when {
                                offset.x < third -> tapTurn(forward = false)
                                offset.x > 2 * third -> tapTurn(forward = true)
                                else -> viewModel.toggleChrome()
                            }
                        }
                    }
                    .pointerInput(settings.turnStyle) {
                        if (settings.turnStyle == TurnStyle.SLIDE) {
                            var dragTotal = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragTotal = 0f },
                                onDragEnd = {
                                    if (dragTotal < -100f) viewModel.nextPage()
                                    else if (dragTotal > 100f) viewModel.prevPage()
                                },
                            ) { _, dragAmount -> dragTotal += dragAmount }
                        } else {
                            // Curl: the finger owns the fold while dragging.
                            var dragTotal = 0f
                            var beginJob: Job? = null
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragTotal = 0f
                                    beginJob = null
                                },
                                onDragEnd = {
                                    val fraction = abs(dragTotal) / size.width
                                    scope.launch {
                                        beginJob?.join()
                                        val session = turnSession ?: return@launch
                                        settleCurl(session, commit = fraction > 0.28f)
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        beginJob?.join()
                                        turnSession?.let { settleCurl(it, commit = false) }
                                    }
                                },
                            ) { _, dragAmount ->
                                if (beginJob == null && dragAmount != 0f) {
                                    val forward = dragAmount < 0
                                    beginJob = scope.launch { beginCurl(forward) }
                                }
                                dragTotal += dragAmount
                                val session = turnSession ?: return@detectHorizontalDragGestures
                                val fraction = (abs(dragTotal) / size.width).coerceIn(0f, 1f)
                                val value = if (session.forward) fraction else 1f - fraction
                                scope.launch { session.progress.snapTo(value) }
                            }
                        }
                    },
            )

            // Selection mode: an overlay owns all input — drags move the nearer
            // handle, a tap dismisses — so page turns can't fire mid-selection.
            if (selection != null && turnSession == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                chapterOffsetAt(change.position)?.let(viewModel::extendSelectionTo)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { viewModel.clearSelection() }
                        },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp),
                ) {
                    SelectionToolbar(
                        onHighlight = { colorId ->
                            viewModel.saveHighlight(colorId, null)
                            clickHaptic()
                        },
                        onNote = { editorForSelection = true },
                        onCopy = {
                            viewModel.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
                            viewModel.clearSelection()
                        },
                        onDefine = {
                            viewModel.selectedText()?.let { lookup = "define" to it.trim() }
                        },
                        onWikipedia = {
                            viewModel.selectedText()?.let { lookup = "wiki" to it.trim() }
                        },
                        onTranslate = {
                            viewModel.selectedText()?.let { text ->
                                try {
                                    val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Translate"))
                                } catch (e: Exception) {
                                    // No app handles PROCESS_TEXT — nothing to do.
                                }
                            }
                        },
                    )
                }
            }

            if (ui.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = theme.inkColor,
                )
            }

            ui.error?.let { message ->
                Text(
                    text = message,
                    color = theme.inkColor,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }

            focusRemainingSec?.let { remaining ->
                Text(
                    text = "%d:%02d".format(remaining / 60, remaining % 60),
                    color = theme.inkColor.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 10.dp, bottom = 6.dp),
                )
            }
        }

        val toc by viewModel.toc.collectAsState()
        val returnAnchor by viewModel.returnAnchor.collectAsState()
        var tocOpen by remember { mutableStateOf(false) }

        ReaderChrome(
            visible = ui.chromeVisible,
            ui = ui,
            theme = theme,
            isSpread = spreadMode,
            minutesLeft = viewModel.minutesLeftInChapter(),
            chapterTitle = toc.lastOrNull { it.chapterIndex <= ui.chapterIndex }?.title,
            bookFraction = ((ui.chapterIndex + (ui.pageIndex + 1f) / ui.pageCount.coerceAtLeast(1)) /
                ui.chapterCount.coerceAtLeast(1)).coerceIn(0f, 1f),
            returnLabel = returnAnchor?.let { anchor ->
                val title = toc.lastOrNull { it.chapterIndex <= anchor.chapterIndex }?.title
                "Return to " + (title ?: "Ch. ${anchor.chapterIndex + 1}")
            },
            onBack = onBack,
            onOpenSettings = { settingsSheetOpen = true },
            onSearch = { onSearchInBook(bookUuid) },
            onAnnotations = { annotationsListOpen = true },
            onStartTts = { viewModel.startTts() },
            onOpenToc = { tocOpen = true },
            onScrub = viewModel::scrubTo,
            onReturn = viewModel::returnToAnchor,
        )

        if (tocOpen) {
            TocSheet(
                entries = toc,
                currentChapter = ui.chapterIndex,
                onSelect = {
                    viewModel.navigateFromToc(it)
                    tocOpen = false
                },
                onDismiss = { tocOpen = false },
            )
        }

        // Listening bar: visible whenever TTS is engaged.
        if (ttsStatus != TtsStatus.OFF) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        androidx.compose.material3.TextButton(onClick = {
                            if (ttsStatus == TtsStatus.PLAYING) viewModel.pauseTts() else viewModel.startTts()
                        }) { Text(if (ttsStatus == TtsStatus.PLAYING) "Pause" else "Resume") }
                        androidx.compose.material3.TextButton(onClick = {
                            val rate = viewModel.cycleTtsSpeed()
                            speedLabel = "${rate}×"
                        }) { Text(speedLabel) }
                        androidx.compose.material3.TextButton(onClick = {
                            viewModel.prepareVoices()
                            voicePickerOpen = true
                        }) { Text("Voice") }
                        androidx.compose.material3.TextButton(onClick = { viewModel.cycleSleepTimer() }) {
                            Text(ttsSleep.label)
                        }
                        androidx.compose.material3.TextButton(onClick = { viewModel.stopTts() }) { Text("Stop") }
                    }
                }
            }
        }

        if (settingsSheetOpen) {
            ReaderSettingsSheet(
                settings = settings,
                store = app.settingsStore,
                onOpenVoices = {
                    settingsSheetOpen = false
                    viewModel.prepareVoices()
                    voicePickerOpen = true
                },
                onDismiss = { settingsSheetOpen = false },
            )
        }

        if (editorForSelection) {
            AnnotationEditorSheet(
                quote = viewModel.selectedText() ?: "",
                annotation = null,
                onSave = { colorId, note -> viewModel.saveHighlight(colorId, note) },
                onDelete = null,
                onDismiss = { editorForSelection = false },
            )
        }

        editorAnnotation?.let { annotation ->
            AnnotationEditorSheet(
                quote = annotation.quote,
                annotation = annotation,
                onSave = { colorId, note -> viewModel.updateAnnotation(annotation, colorId, note) },
                onDelete = { viewModel.deleteAnnotation(annotation.uuid) },
                onDismiss = { editorAnnotation = null },
            )
        }

        if (annotationsListOpen) {
            AnnotationsListSheet(
                bookTitle = ui.bookTitle,
                annotations = annotations,
                onJump = {
                    viewModel.jumpTo(it.chapterIndex, it.startChar)
                    annotationsListOpen = false
                },
                onExport = { shareAnnotationsAsMarkdown(context, ui.bookTitle, annotations) },
                onDismiss = { annotationsListOpen = false },
            )
        }

        if (voicePickerOpen) {
            val voices by viewModel.ttsVoices.collectAsState()
            val kokoroInstalled by viewModel.kokoroInstalled.collectAsState()
            val kokoroProgress by viewModel.kokoroDownloadProgress.collectAsState()
            VoicePickerSheet(
                engine = settings.ttsEngine,
                systemVoices = voices,
                currentSystemVoice = settings.ttsVoice,
                kokoroInstalled = kokoroInstalled,
                kokoroDownloadProgress = kokoroProgress,
                currentKokoroVoice = settings.kokoroVoice,
                onEngine = { viewModel.setTtsEngine(it) },
                onDownloadKokoro = { viewModel.downloadKokoro() },
                onPickSystemVoice = { viewModel.setTtsVoice(it.name) },
                onPickKokoroVoice = { viewModel.setKokoroVoice(it) },
                onDismiss = { voicePickerOpen = false },
            )
        }

        lookup?.let { (kind, term) ->
            LookupSheet(
                title = if (kind == "define") "Dictionary" else "Wikipedia",
                term = term,
                fetch = if (kind == "define") Lookups::define else Lookups::wikipediaSummary,
                onDismiss = { lookup = null },
            )
        }
    }
}

/** Annotations → Markdown, handed to the system share sheet. */
private fun shareAnnotationsAsMarkdown(
    context: Context,
    bookTitle: String,
    annotations: List<AnnotationEntity>,
) {
    val markdown = buildString {
        appendLine("# $bookTitle — highlights & notes")
        appendLine()
        annotations.forEach { annotation ->
            appendLine("> ${annotation.quote}")
            appendLine(">")
            appendLine("> — Chapter ${annotation.chapterIndex + 1}")
            annotation.note?.let { appendLine("\n**Note:** $it") }
            appendLine()
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "$bookTitle — highlights")
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
    context.startActivity(Intent.createChooser(intent, "Export annotations"))
}

/** Top bar + bottom progress line, both translucent in the page's own colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderChrome(
    visible: Boolean,
    ui: ReaderUiState,
    theme: ReadingTheme,
    isSpread: Boolean,
    minutesLeft: Int?,
    chapterTitle: String?,
    bookFraction: Float,
    returnLabel: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit,
    onAnnotations: () -> Unit,
    onStartTts: () -> Unit,
    onOpenToc: () -> Unit,
    onScrub: (Float) -> Unit,
    onReturn: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = ui.bookTitle,
                        fontFamily = FontFamily.Serif,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = onStartTts) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Read aloud")
                    }
                    IconButton(onClick = onAnnotations) {
                        Icon(Icons.Filled.Edit, contentDescription = "Highlights and notes")
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search in book")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reading settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.pageColor.copy(alpha = 0.94f),
                    titleContentColor = theme.inkColor,
                    navigationIconContentColor = theme.inkColor,
                    actionIconContentColor = theme.inkColor,
                ),
            )
        }

        AnimatedVisibility(
            visible = visible && !ui.loading,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val spreadEnd = minOf(ui.pageIndex + 2, ui.pageCount)
            val pageLabel =
                if (isSpread && spreadEnd > ui.pageIndex + 1) "${ui.pageIndex + 1}–$spreadEnd / ${ui.pageCount}"
                else "${ui.pageIndex + 1} / ${ui.pageCount}"
            val timeLeft = minutesLeft?.let { " · ~${it}m" } ?: ""
            // Rust is the reading signal, matched to the page's temperature.
            val accent = if (theme.isDark) Color(0xFFD98B66) else Color(0xFFB85C38)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.pageColor.copy(alpha = 0.94f))
                    .navigationBarsPadding(),
            ) {
                SpineScrubber(
                    fraction = bookFraction,
                    accent = accent,
                    track = theme.inkColor.copy(alpha = 0.14f),
                    onCommit = onScrub,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onOpenToc),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = "Table of contents",
                            tint = theme.inkColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = chapterTitle?.let { "Ch. ${ui.chapterIndex + 1} · $it" }
                                ?: "Chapter ${ui.chapterIndex + 1} of ${ui.chapterCount}",
                            color = theme.inkColor.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "$pageLabel$timeLeft",
                        color = theme.inkColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                returnLabel?.let { label ->
                    TextButton(
                        onClick = onReturn,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(label, color = accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The book's fore-edge as a control: a hairline showing position through the
 * whole spine; drag or tap anywhere on it to travel. Commits on release so
 * the reader can aim before the page actually moves.
 */
@Composable
private fun SpineScrubber(
    fraction: Float,
    accent: Color,
    track: Color,
    onCommit: (Float) -> Unit,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onCommit((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let(onCommit)
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.CenterStart).background(track))
            Box(Modifier.fillMaxWidth(shown).height(3.dp).align(Alignment.CenterStart).background(accent))
            Box(
                modifier = Modifier
                    .align(BiasAlignment(shown * 2f - 1f, 0f))
                    .size(if (dragFraction != null) 14.dp else 8.dp)
                    .background(accent, CircleShape),
            )
        }
    }
}

/**
 * Warmth curve for evening mode: neutral through the day, ramping up between
 * 17:00 and 21:00, fully warm overnight, ramping back down 05:00–07:00.
 */
private fun eveningWarmthNow(): Float {
    val now = LocalTime.now()
    val minutes = now.hour * 60 + now.minute
    return when {
        minutes >= 21 * 60 || minutes < 5 * 60 -> 1f
        minutes >= 17 * 60 -> (minutes - 17 * 60) / (4f * 60)
        minutes < 7 * 60 -> 1f - (minutes - 5 * 60) / (2f * 60)
        else -> 0f
    }
}
