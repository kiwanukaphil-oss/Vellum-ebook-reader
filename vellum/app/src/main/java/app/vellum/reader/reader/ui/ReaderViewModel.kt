package app.vellum.reader.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.ReadingPositionEntity
import app.vellum.reader.epub.OpenedEpub
import app.vellum.reader.epub.TocEntry
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import app.vellum.reader.reader.html.BlockKind
import app.vellum.reader.reader.html.ContentBlock
import app.vellum.reader.reader.html.HtmlBlockParser
import app.vellum.reader.reader.layout.ChapterPaginator
import app.vellum.reader.reader.layout.PaginatedChapter
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.settings.ElevenLabsModel
import app.vellum.reader.core.settings.NarrationProvider
import app.vellum.reader.reader.tts.CachedElevenLabsNarration
import app.vellum.reader.reader.tts.ElevenLabsApiException
import app.vellum.reader.reader.tts.ElevenLabsGenerationRequest
import app.vellum.reader.reader.tts.ElevenLabsPlayback
import app.vellum.reader.reader.tts.ElevenLabsSubscription
import app.vellum.reader.reader.tts.ElevenLabsVoice
import app.vellum.reader.reader.tts.KokoroEngine
import app.vellum.reader.reader.tts.KokoroVoicePack
import kotlinx.coroutines.isActive
import app.vellum.reader.core.session.ActiveReadingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Identity of one rendered page; layoutVersion changes force page re-resolution. */
data class PageKey(val layoutVersion: Int, val chapterIndex: Int, val pageIndex: Int)

/** Resolved destination of a page turn, known before any animation starts. */
data class TurnTarget(val chapterIndex: Int, val pageIndex: Int)

/** A reading position remembered before a TOC/scrub jump, offset-anchored. */
data class ReturnAnchor(val chapterIndex: Int, val charOffset: Int)

/** An active text selection, as chapter character offsets. */
data class SelectionRange(val startChar: Int, val endChar: Int)

enum class TtsStatus { OFF, PREPARING, PLAYING, PAUSED }

/** Sleep timer choices, cycled from the TTS bar. */
enum class TtsSleep(val label: String, val compactLabel: String, val minutes: Int?) {
    OFF("Sleep: off", "Off", null),
    MIN15("Sleep: 15 minutes", "15m", 15),
    MIN30("Sleep: 30 minutes", "30m", 30),
    MIN60("Sleep: 60 minutes", "60m", 60),
    END_OF_CHAPTER("Sleep: end of chapter", "Ch.", null),
}

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val chapterCount: Int = 0,
    val pageCount: Int = 0,
    val forward: Boolean = true,
    val chromeVisible: Boolean = false,
    val layoutVersion: Int = 0,
) {
    val pageKey: PageKey get() = PageKey(layoutVersion, chapterIndex, pageIndex)
}

data class ElevenLabsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val voices: List<ElevenLabsVoice> = emptyList(),
    val subscription: ElevenLabsSubscription? = null,
    val error: String? = null,
)

data class ElevenLabsCacheStatus(
    val cachedPassages: Int = 0,
    val totalPassages: Int = 0,
    val storedBytes: Long = 0,
) {
    val chapterFullyCached: Boolean get() = totalPassages > 0 && cachedPassages == totalPassages
}

private data class ElevenLabsSegment(
    val base: Int,
    val text: String,
    val narration: CachedElevenLabsNarration,
    val relativeStartChar: Int,
)

/**
 * Drives one reading session: opens the EPUB, paginates chapters on demand,
 * turns pages (including across chapter boundaries), and persists the position
 * as a locator so it survives reflow and app restarts.
 */
class ReaderViewModel(
    private val app: VellumApp,
    private val bookUuid: String,
    /** ≥0 jumps straight to this locator (search results) instead of the saved position. */
    private val initialChapter: Int = -1,
    private val initialOffset: Int = -1,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui

    val annotations: StateFlow<List<AnnotationEntity>> = app.annotationDao
        .observeForBook(bookUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selection = MutableStateFlow<SelectionRange?>(null)
    val selection: StateFlow<SelectionRange?> = _selection

    /** Chapters whose annotations were already re-anchor-verified this session. */
    private val reanchoredChapters = mutableSetOf<Int>()

    // ---- Session recording (feeds insights) ------------------------------
    private val readingSession = ActiveReadingSession()
    private var pagesTurned = 0

    // ---- Text-to-speech ---------------------------------------------------
    val ttsStatus = MutableStateFlow(TtsStatus.OFF)
    val ttsRange = MutableStateFlow<SelectionRange?>(null)
    val ttsSleep = MutableStateFlow(TtsSleep.OFF)
    val ttsSpeed = MutableStateFlow(1.0f)

    /** Offline voices of the active engine, best quality first. */
    val ttsVoices = MutableStateFlow<List<android.speech.tts.Voice>>(emptyList())

    // Kokoro neural engine state
    val kokoroInstalled = MutableStateFlow(app.let { KokoroVoicePack.isInstalled(it) })
    val kokoroDownloadProgress = MutableStateFlow<Float?>(null)
    private val elevenLabsCredentials = app.elevenLabsCredentials
    val elevenLabsState = MutableStateFlow(
        ElevenLabsUiState(connected = elevenLabsCredentials.hasKey()),
    )
    val elevenLabsCacheStatus = MutableStateFlow(ElevenLabsCacheStatus())
    private var kokoro: KokoroEngine? = null
    private var kokoroJob: kotlinx.coroutines.Job? = null
    private val elevenLabsClient = app.elevenLabsClient
    private val elevenLabsCache = app.elevenLabsCache
    private val elevenLabsPlayback = ElevenLabsPlayback(app)
    private var elevenLabsJob: kotlinx.coroutines.Job? = null
    private var kokoroSid = 0
    private var tts: TextToSpeech? = null
    private var ttsChunks: List<Pair<Int, String>> = emptyList()
    private var ttsChunkIndex = 0
    private var ttsResumeOffset: Int? = null
    private var sleepJob: kotlinx.coroutines.Job? = null

    /** Flattened nav-doc contents; drives the TOC sheet and chapter labels. */
    val toc = MutableStateFlow<List<TocEntry>>(emptyList())

    /** Where the reader was before a TOC/scrub jump — the "return to" chip. */
    val returnAnchor = MutableStateFlow<ReturnAnchor?>(null)

    private var epub: OpenedEpub? = null
    private var paginator: ChapterPaginator? = null
    private val blocksCache = ConcurrentHashMap<Int, List<ContentBlock>>()

    private val paginatedCache = ConcurrentHashMap<Int, PaginatedChapter>()

    /** Decoded chapter images (src → bitmap), downscaled to the column width. */
    private val imagesCache = ConcurrentHashMap<Int, Map<String, ImageBitmap>>()
    private val paginationMutex = Mutex()

    fun imagesFor(chapterIndex: Int): Map<String, ImageBitmap> = imagesCache[chapterIndex] ?: emptyMap()

    /** Character offset the current/next layout should scroll to. */
    private var pendingCharOffset = 0

    private var turning = false

    /** Pages advanced per turn: 1 in portrait, 2 in two-page landscape. */
    private var spreadSize = 1

    private fun alignToSpread(page: Int) = page - page % spreadSize

    init {
        if (elevenLabsCredentials.hasKey()) refreshElevenLabs()
        viewModelScope.launch {
            val book = app.bookDao.byUuid(bookUuid)
            if (book == null) {
                _ui.update { it.copy(error = "Book not found", loading = false) }
                return@launch
            }
            val opened = app.epubOpener.open(File(app.booksDir, book.fileName))
            if (opened == null) {
                _ui.update { it.copy(error = "Could not open \"${book.title}\"", loading = false) }
                return@launch
            }
            epub = opened
            toc.value = opened.tableOfContents()
            val startChapter: Int
            if (initialChapter >= 0) {
                startChapter = initialChapter.coerceIn(0, opened.chapterCount - 1)
                pendingCharOffset = initialOffset.coerceAtLeast(0)
            } else {
                val position = app.bookDao.positionFor(bookUuid)
                startChapter = (position?.chapterIndex ?: 0).coerceIn(0, opened.chapterCount - 1)
                pendingCharOffset = position?.charOffset ?: 0
            }
            _ui.update {
                it.copy(
                    bookTitle = book.title,
                    chapterIndex = startChapter,
                    chapterCount = opened.chapterCount,
                )
            }
            app.bookDao.markOpened(bookUuid, System.currentTimeMillis())
            repaginateCurrentChapter()
        }
    }

    /** Called whenever viewport size or typography changes; re-layouts in place. */
    fun onViewportChanged(newPaginator: ChapterPaginator) {
        // Keep the reader anchored to the first visible character across reflow.
        currentPage()?.let { pendingCharOffset = it.startChar }
        paginator = newPaginator
        spreadSize = newPaginator.columns
        paginatedCache.clear()
        imagesCache.clear() // decoded for the old column width
        repaginateCurrentChapter()
    }

    fun paginatedFor(chapterIndex: Int): PaginatedChapter? = paginatedCache[chapterIndex]

    fun nextPage() = turnPage(forward = true)

    fun setSessionActive(active: Boolean) = readingSession.setActive(active)

    fun prevPage() = turnPage(forward = false)

    /**
     * Resolves where a turn would land without changing state — the curl
     * animation needs both pages rendered before the first frame. Paginates
     * the neighboring chapter if the turn crosses a boundary. Null at the
     * edges of the book.
     */
    suspend fun peekTurnTarget(forward: Boolean): TurnTarget? {
        val state = _ui.value
        if (state.loading || state.error != null) return null
        val withinChapter =
            if (forward) state.pageIndex + spreadSize < state.pageCount
            else state.pageIndex - spreadSize >= 0
        if (withinChapter) {
            return TurnTarget(state.chapterIndex, state.pageIndex + if (forward) spreadSize else -spreadSize)
        }
        val direction = if (forward) 1 else -1
        val start = state.chapterIndex + direction
        if (start !in 0 until state.chapterCount) return null
        val (chapter, paginated) = firstReadableChapterFrom(start, direction) ?: return null
        return TurnTarget(chapter, if (forward) 0 else alignToSpread(paginated.pages.lastIndex))
    }

    /** Estimated minutes to finish this chapter at this session's pace. */
    fun minutesLeftInChapter(): Int? {
        if (pagesTurned < 2) return null
        val state = _ui.value
        val remaining = (state.pageCount - state.pageIndex - 1).coerceAtLeast(0)
        if (remaining == 0) return null
        val avgMsPerPage = readingSession.elapsedMs() / pagesTurned
        return ((remaining * avgMsPerPage) / 60_000L).toInt().coerceAtLeast(1)
    }

    /** Applies a turn the curl animation has already played out visually. */
    fun commitTurn(target: TurnTarget, forward: Boolean) {
        pagesTurned++
        val pageCount = paginatedCache[target.chapterIndex]?.pages?.size ?: return
        _ui.update {
            it.copy(
                chapterIndex = target.chapterIndex,
                pageIndex = target.pageIndex,
                pageCount = pageCount,
                forward = forward,
            )
        }
        persistPosition()
    }

    fun toggleChrome() {
        _ui.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    /**
     * Moves one page in either direction, paginating the neighboring chapter in
     * the background when the turn crosses a chapter boundary.
     */
    private fun turnPage(forward: Boolean) {
        val state = _ui.value
        if (state.loading || turning) return
        val withinChapter =
            if (forward) state.pageIndex + spreadSize < state.pageCount
            else state.pageIndex - spreadSize >= 0
        if (withinChapter) {
            val newIndex = state.pageIndex + if (forward) spreadSize else -spreadSize
            pagesTurned++
            _ui.update { it.copy(pageIndex = newIndex, forward = forward) }
            persistPosition()
            return
        }
        val direction = if (forward) 1 else -1
        val targetChapter = state.chapterIndex + direction
        if (targetChapter !in 0 until state.chapterCount) return
        turning = true
        viewModelScope.launch {
            try {
                // Image-only chapters (e.g. cover pages) paginate to zero pages
                // in Phase 1; keep moving in the same direction past them.
                val (chapter, paginated) = firstReadableChapterFrom(targetChapter, direction) ?: return@launch
                val page = if (forward) 0 else alignToSpread(paginated.pages.lastIndex)
                _ui.update {
                    it.copy(
                        chapterIndex = chapter,
                        pageIndex = page,
                        pageCount = paginated.pages.size,
                        forward = forward,
                    )
                }
                persistPosition()
            } finally {
                turning = false
            }
        }
    }

    /** Walks chapters in [direction] until one has visible pages. */
    private suspend fun firstReadableChapterFrom(
        start: Int,
        direction: Int,
    ): Pair<Int, PaginatedChapter>? {
        var chapter = start
        while (chapter in 0 until _ui.value.chapterCount) {
            val paginated = ensurePaginated(chapter) ?: return null
            if (paginated.pages.isNotEmpty()) return chapter to paginated
            chapter += direction
        }
        return null
    }

    private fun repaginateCurrentChapter() {
        val chapter = _ui.value.chapterIndex
        if (epub == null || paginator == null) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            // Skip past empty (image-only) chapters so opening a book never
            // lands on a blank page; falls back to searching backward at the end.
            val readable = firstReadableChapterFrom(chapter, 1)
                ?: firstReadableChapterFrom(chapter - 1, -1)
            if (readable == null) {
                _ui.update { it.copy(error = "Could not lay out chapter", loading = false) }
                return@launch
            }
            val (resolvedChapter, paginated) = readable
            if (resolvedChapter != chapter) pendingCharOffset = 0
            val page = alignToSpread(paginated.pageIndexFor(pendingCharOffset))
            _ui.update {
                it.copy(
                    loading = false,
                    chapterIndex = resolvedChapter,
                    pageIndex = page,
                    pageCount = paginated.pages.size,
                    layoutVersion = it.layoutVersion + 1,
                )
            }
        }
    }

    private suspend fun ensurePaginated(chapterIndex: Int): PaginatedChapter? {
        paginatedCache[chapterIndex]?.let { return it }
        return paginationMutex.withLock {
            paginatedCache[chapterIndex]?.let { return@withLock it }
            val opened = epub ?: return@withLock null
            val pager = paginator ?: return@withLock null
            val result = withContext(Dispatchers.Default) {
                val blocks = blocksCache[chapterIndex] ?: run {
                    val html = opened.chapterHtml(chapterIndex) ?: return@withContext null
                    HtmlBlockParser.parse(html).also { blocksCache[chapterIndex] = it }
                }
                val images = imagesCache[chapterIndex] ?: loadChapterImages(
                    opened, chapterIndex, blocks, pager.contentWidthPx,
                ).also { imagesCache[chapterIndex] = it }
                pager.paginate(blocks, images.mapValues { (_, bitmap) -> IntSize(bitmap.width, bitmap.height) })
            }
            result?.also {
                paginatedCache[chapterIndex] = it
                trimChapterCache(blocksCache, 6)
                trimChapterCache(paginatedCache, 6)
                trimChapterCache(imagesCache, 3)
                reanchorAnnotations(chapterIndex)
            }
        }
    }

    private fun <T> trimChapterCache(cache: ConcurrentHashMap<Int, T>, maximum: Int) {
        while (cache.size > maximum) {
            val current = _ui.value.chapterIndex
            val victim = cache.keys.maxByOrNull { kotlin.math.abs(it - current) } ?: return
            if (victim == current && cache.size > 1) {
                cache.keys.firstOrNull { it != current }?.let(cache::remove)
            } else {
                cache.remove(victim)
            }
        }
    }

    /**
     * Fetches and decodes every image a chapter references, subsampled to at
     * most the column width — full-resolution decodes of illustration scans
     * would dwarf the text caches. Unresolvable images just don't render.
     */
    private suspend fun loadChapterImages(
        opened: OpenedEpub,
        chapterIndex: Int,
        blocks: List<ContentBlock>,
        maxWidthPx: Int,
    ): Map<String, ImageBitmap> {
        val sources = blocks.filter { it.kind == BlockKind.IMAGE }.mapNotNull { it.imageSrc }.distinct()
        if (sources.isEmpty()) return emptyMap()
        val chapterHref = opened.chapterHref(chapterIndex)
        val decoded = mutableMapOf<String, ImageBitmap>()
        sources.forEach { src ->
            val bytes = opened.resourceBytes(chapterHref, src) ?: return@forEach
            decodeSubsampled(bytes, maxWidthPx)?.let { decoded[src] = it }
        }
        return decoded
    }

    private fun decodeSubsampled(bytes: ByteArray, maxWidthPx: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / (inSampleSize * 2) >= maxWidthPx.coerceAtLeast(1)) inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }

    private fun currentPage() =
        paginatedCache[_ui.value.chapterIndex]?.pages?.getOrNull(_ui.value.pageIndex)

    // ---- Selection & annotations -----------------------------------------

    /** Starts a selection at the word containing [chapterOffset]. */
    fun beginSelectionAt(chapterOffset: Int) {
        val paginated = paginatedCache[_ui.value.chapterIndex] ?: return
        val block = paginated.measured.lastOrNull { it.charStart <= chapterOffset } ?: return
        val textLength = block.layout.layoutInput.text.length
        if (textLength == 0) return
        val local = (chapterOffset - block.charStart).coerceIn(0, textLength - 1)
        val word = block.layout.getWordBoundary(local)
        if (word.start == word.end) return
        _selection.value = SelectionRange(block.charStart + word.start, block.charStart + word.end)
    }

    /** Moves whichever selection edge is closer to [chapterOffset]. */
    fun extendSelectionTo(chapterOffset: Int) {
        val current = _selection.value ?: return
        val mid = (current.startChar + current.endChar) / 2
        _selection.value =
            if (chapterOffset < mid) {
                SelectionRange(chapterOffset.coerceAtLeast(0), current.endChar)
            } else {
                SelectionRange(current.startChar, chapterOffset.coerceAtLeast(current.startChar + 1))
            }
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** The selected text, straight from the chapter's block content. */
    fun selectedText(): String? {
        val sel = _selection.value ?: return null
        val text = chapterText(_ui.value.chapterIndex) ?: return null
        return text.substring(sel.startChar.coerceIn(0, text.length), sel.endChar.coerceIn(0, text.length))
    }

    /** Persists the current selection as a highlight (optionally with a note). */
    fun saveHighlight(colorId: String, note: String?) {
        val sel = _selection.value ?: return
        val opened = epub ?: return
        val quote = selectedText() ?: return
        val chapter = _ui.value.chapterIndex
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            app.annotationDao.upsert(
                AnnotationEntity(
                    uuid = UUID.randomUUID().toString(),
                    bookUuid = bookUuid,
                    chapterIndex = chapter,
                    chapterHref = opened.chapterHref(chapter),
                    startChar = sel.startChar,
                    endChar = sel.endChar,
                    quote = quote,
                    colorId = colorId,
                    note = note?.ifBlank { null },
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
        _selection.value = null
    }

    fun updateAnnotation(annotation: AnnotationEntity, colorId: String, note: String?) {
        viewModelScope.launch {
            app.annotationDao.upsert(
                annotation.copy(
                    colorId = colorId,
                    note = note?.ifBlank { null },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteAnnotation(uuid: String) {
        viewModelScope.launch { app.annotationDao.softDelete(uuid, System.currentTimeMillis()) }
    }

    /** The annotation covering [chapterOffset] on the current chapter, if any. */
    fun annotationAt(chapterOffset: Int): AnnotationEntity? =
        annotations.value.firstOrNull {
            it.chapterIndex == _ui.value.chapterIndex &&
                chapterOffset in it.startChar until it.endChar
        }

    /** Jump within the already-open book (annotation list, in-book search). */
    fun jumpTo(chapterIndex: Int, charOffset: Int) {
        pendingCharOffset = charOffset.coerceAtLeast(0)
        _ui.update { it.copy(chapterIndex = chapterIndex.coerceIn(0, it.chapterCount - 1)) }
        repaginateCurrentChapter()
    }

    /** TOC navigation: like [jumpTo], but leaves a breadcrumb to return to. */
    fun navigateFromToc(chapterIndex: Int) {
        rememberReturnAnchor()
        jumpTo(chapterIndex, 0)
    }

    /**
     * Scrubber commit: [bookFraction] (0..1) across the spine lands on the
     * proportional page of the proportional chapter, skipping unreadable
     * chapters the same way page turns do.
     */
    fun scrubTo(bookFraction: Float) {
        val state = _ui.value
        if (state.chapterCount == 0 || state.loading) return
        rememberReturnAnchor()
        val scaled = bookFraction.coerceIn(0f, 0.9999f) * state.chapterCount
        val targetChapter = scaled.toInt().coerceIn(0, state.chapterCount - 1)
        val fractionInChapter = scaled - targetChapter
        viewModelScope.launch {
            val (chapter, paginated) = firstReadableChapterFrom(targetChapter, 1)
                ?: firstReadableChapterFrom(targetChapter - 1, -1)
                ?: return@launch
            val page = alignToSpread(
                (fractionInChapter * paginated.pages.size).toInt().coerceIn(0, paginated.pages.lastIndex),
            )
            _ui.update {
                it.copy(chapterIndex = chapter, pageIndex = page, pageCount = paginated.pages.size)
            }
            persistPosition()
        }
    }

    fun returnToAnchor() {
        val anchor = returnAnchor.value ?: return
        returnAnchor.value = null
        jumpTo(anchor.chapterIndex, anchor.charOffset)
    }

    /** First jump of a chain owns the breadcrumb; later jumps keep it. */
    private fun rememberReturnAnchor() {
        if (returnAnchor.value != null) return
        returnAnchor.value = ReturnAnchor(
            chapterIndex = _ui.value.chapterIndex,
            charOffset = currentPage()?.startChar ?: pendingCharOffset,
        )
    }

    private fun chapterText(chapterIndex: Int): String? =
        blocksCache[chapterIndex]?.joinToString("") { it.text.text }

    /** The plain text of one laid-out page — the reader's TalkBack surface. */
    fun pageTextFor(chapterIndex: Int, pageIndex: Int): String? {
        val page = paginatedCache[chapterIndex]?.pages?.getOrNull(pageIndex) ?: return null
        val text = chapterText(chapterIndex) ?: return null
        return text.substring(
            page.startChar.coerceIn(0, text.length),
            page.endChar.coerceIn(0, text.length),
        )
    }

    /**
     * Quote-first re-anchoring: if a chapter's text no longer matches an
     * annotation's stored offsets (file replaced, parser change), find the
     * quote in the text and repair the offsets. Runs once per chapter session.
     */
    private suspend fun reanchorAnnotations(chapterIndex: Int) {
        if (!reanchoredChapters.add(chapterIndex)) return
        val text = chapterText(chapterIndex) ?: return
        annotations.value.filter { it.chapterIndex == chapterIndex }.forEach { annotation ->
            val stored = text.substring(
                annotation.startChar.coerceIn(0, text.length),
                annotation.endChar.coerceIn(0, text.length),
            )
            if (stored != annotation.quote) {
                val found = text.indexOf(annotation.quote)
                if (found >= 0) {
                    app.annotationDao.reanchor(
                        annotation.uuid, found, found + annotation.quote.length, System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private fun persistPosition() {
        val state = _ui.value
        val page = currentPage() ?: return
        val opened = epub ?: return
        val total = paginatedCache[state.chapterIndex]?.totalChars ?: 0
        pendingCharOffset = page.startChar
        viewModelScope.launch {
            app.bookDao.upsertPosition(
                ReadingPositionEntity(
                    bookUuid = bookUuid,
                    chapterIndex = state.chapterIndex,
                    chapterHref = opened.chapterHref(state.chapterIndex),
                    charOffset = page.startChar,
                    progression = overallProgression(state, page.startChar, total),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun overallProgression(state: ReaderUiState, charOffset: Int, chapterChars: Int): Double {
        if (state.chapterCount <= 0) return 0.0
        val chapterProgress = when {
            state.pageCount > 0 && state.pageIndex + spreadSize >= state.pageCount -> 1.0
            chapterChars > 0 -> charOffset.toDouble() / chapterChars
            else -> 0.0
        }
        return ((state.chapterIndex + chapterProgress) / state.chapterCount).coerceIn(0.0, 1.0)
    }

    // ---- Text-to-speech ---------------------------------------------------

    /**
     * Starts speaking from the top of the current page. Chunked by sentence
     * boundaries (system TTS caps utterance length); word-level callbacks
     * drive the reader highlight and auto page-turns.
     */
    fun startTts() {
        // Resume where listening left off — but only while still on that page;
        // if the reader has moved on, start from the top of the current page.
        val page = currentPage() ?: return
        val resume = ttsResumeOffset
        val startOffset =
            if (resume != null && resume >= page.startChar && resume < page.endChar) resume
            else page.startChar
        ttsResumeOffset = null
        viewModelScope.launch {
            val settings = app.settingsStore.settings.first()
            kokoroSid = settings.kokoroVoice
            when (settings.narrationProvider) {
                NarrationProvider.SYSTEM -> ensureTtsEngine { beginSpeaking(startOffset) }
                NarrationProvider.KOKORO -> {
                    if (KokoroVoicePack.isInstalled(app)) {
                        startKokoro(startOffset)
                    } else {
                        notify("Kokoro is not installed — using system speech")
                        ensureTtsEngine { beginSpeaking(startOffset) }
                    }
                }
                NarrationProvider.ELEVENLABS -> {
                    val apiKey = elevenLabsCredentials.read()
                    val voiceId = settings.elevenLabsVoiceId
                    if (apiKey == null || voiceId == null) {
                        notify("ElevenLabs isn't ready — using system speech")
                        ensureTtsEngine { beginSpeaking(startOffset) }
                    } else {
                        startElevenLabs(
                            fromOffset = startOffset,
                            apiKey = apiKey,
                            voiceId = voiceId,
                            model = settings.elevenLabsModel,
                        )
                    }
                }
            }
        }
    }

    /**
     * Neural path, pipelined: a producer synthesizes sentences ahead into a
     * small channel while the consumer plays — synthesis overlaps playback, so
     * inter-sentence gaps vanish once the first sentence is rolling. Highlight
     * is per sentence and set exactly when its audio starts.
     */
    private fun startKokoro(fromOffset: Int) {
        val text = chapterText(_ui.value.chapterIndex) ?: return
        // Cold start is a 2-4s model load plus first-sentence synthesis; be
        // honest about it instead of showing a "Pause" that pauses nothing.
        ttsStatus.value = TtsStatus.PREPARING
        kokoroJob?.cancel()
        kokoro?.cancel()
        kokoroJob = viewModelScope.launch(Dispatchers.Default) {
            val engine = kokoro ?: try {
                KokoroEngine(KokoroVoicePack.modelDir(app)).also { kokoro = it }
            } catch (e: Exception) {
                android.util.Log.e("VellumTts", "Kokoro engine init failed", e)
                withContext(Dispatchers.Main) {
                    notify("Neural voice failed to load — try re-downloading the voice pack")
                    stopTts()
                }
                return@launch
            }
            val session = engine.beginSession()
            val channel = kotlinx.coroutines.channels.Channel<Triple<Int, Int, ShortArray>>(capacity = 2)
            val producer = launch {
                try {
                    for ((base, sentence) in sentenceChunks(text, fromOffset)) {
                        if (!isActive) break
                        val pcm = engine.synthesize(sentence, kokoroSid, ttsSpeed.value, session) ?: continue
                        channel.send(Triple(base, sentence.length, pcm))
                    }
                } finally {
                    channel.close()
                }
            }
            for ((base, length, pcm) in channel) {
                withContext(Dispatchers.Main) {
                    if (ttsStatus.value == TtsStatus.PREPARING) ttsStatus.value = TtsStatus.PLAYING
                    ttsRange.value = SelectionRange(base, base + length)
                    val page = currentPage()
                    if (page != null && base >= page.endChar) nextPage()
                }
                if (!engine.playBlocking(pcm, session)) {
                    producer.cancel()
                    return@launch
                }
            }
            onNarrationChapterComplete()
        }
    }

    /**
     * Cloud path: generate one short passage ahead, keep it in the bounded cache, then
     * play locally. Character timestamps drive word highlighting while local
     * playback speed avoids paid regeneration when the listener changes rate.
     */
    private fun startElevenLabs(
        fromOffset: Int,
        apiKey: String,
        voiceId: String,
        model: ElevenLabsModel,
    ) {
        val text = chapterText(_ui.value.chapterIndex) ?: return
        // Always segment from the chapter origin. A resume seeks into the
        // first stable passage instead of changing the paid request shape.
        val allChunks = sentenceChunks(text, 0)
        val firstChunkIndex = allChunks.indexOfFirst { (base, passage) -> fromOffset < base + passage.length }
        if (firstChunkIndex < 0) return
        val chunks = allChunks.drop(firstChunkIndex)
        if (chunks.isEmpty()) return
        ttsStatus.value = TtsStatus.PREPARING
        elevenLabsJob?.cancel()
        elevenLabsPlayback.cancel()
        elevenLabsPlayback.resetCancel()
        elevenLabsJob = viewModelScope.launch(Dispatchers.IO) {
            updateElevenLabsCacheStatus(allChunks, voiceId, model)
            val channel = Channel<ElevenLabsSegment>(capacity = 1)
            val producer = launch {
                try {
                    chunks.forEachIndexed { index, (base, passage) ->
                        val stableIndex = firstChunkIndex + index
                        val request = elevenLabsRequest(allChunks, stableIndex, voiceId, model)
                        val wasCached = elevenLabsCache.contains(request)
                        val cached = elevenLabsCache.getOrGenerate(request) {
                            elevenLabsClient.generate(apiKey, request)
                        }.also {
                            if (!wasCached) {
                                elevenLabsCacheStatus.update { status ->
                                    status.copy(
                                        cachedPassages = (status.cachedPassages + 1)
                                            .coerceAtMost(status.totalPassages),
                                        storedBytes = elevenLabsCache.sizeBytes(),
                                    )
                                }
                            }
                        }
                        channel.send(
                            ElevenLabsSegment(
                                base = base,
                                text = passage,
                                narration = cached,
                                relativeStartChar = if (index == 0) {
                                    (fromOffset - base).coerceIn(0, passage.lastIndex.coerceAtLeast(0))
                                } else 0,
                            ),
                        )
                    }
                    channel.close()
                } catch (e: Throwable) {
                    channel.close(e)
                }
            }
            try {
                for (segment in channel) {
                    if (ttsStatus.value == TtsStatus.PREPARING) {
                        ttsStatus.value = TtsStatus.PLAYING
                    }
                    var lastRange: SelectionRange? = null
                    val completed = elevenLabsPlayback.play(
                        narration = segment.narration,
                        speed = ttsSpeed.value,
                        startPositionMs = segment.narration.characterStartSeconds
                            .getOrNull(segment.relativeStartChar)
                            ?.times(1000.0)
                            ?.toInt()
                            ?: 0,
                    ) { positionMs ->
                        val charIndex = alignmentIndex(
                            segment.narration.characterStartSeconds,
                            positionMs / 1000.0,
                        ).coerceIn(0, segment.text.lastIndex.coerceAtLeast(0))
                        val bounds = wordBounds(segment.text, charIndex)
                        val range = SelectionRange(segment.base + bounds.first, segment.base + bounds.last + 1)
                        if (range != lastRange) {
                            lastRange = range
                            ttsRange.value = range
                            val page = currentPage()
                            if (page != null && range.startChar >= page.endChar) {
                                viewModelScope.launch(Dispatchers.Main) { nextPage() }
                            }
                        }
                    }
                    if (!completed) {
                        producer.cancel()
                        return@launch
                    }
                }
                onNarrationChapterComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VellumTts", "ElevenLabs narration failed", e)
                notify(elevenLabsError(e))
                withContext(Dispatchers.Main) { stopTts() }
            } finally {
                producer.cancel()
                channel.cancel()
            }
        }
    }

    private fun alignmentIndex(starts: List<Double>, positionSeconds: Double): Int {
        if (starts.isEmpty()) return 0
        val found = starts.binarySearch(positionSeconds)
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    private fun elevenLabsRequest(
        chunks: List<Pair<Int, String>>,
        index: Int,
        voiceId: String,
        model: ElevenLabsModel,
    ): ElevenLabsGenerationRequest {
        val passage = chunks[index].second
        return ElevenLabsGenerationRequest(
            voiceId = voiceId,
            modelId = model.id,
            text = passage,
            previousText = chunks.getOrNull(index - 1)?.second,
            nextText = chunks.getOrNull(index + 1)?.second,
        )
    }

    private fun updateElevenLabsCacheStatus(
        chunks: List<Pair<Int, String>>,
        voiceId: String,
        model: ElevenLabsModel,
    ) {
        val requests = chunks.indices.map { elevenLabsRequest(chunks, it, voiceId, model) }
        elevenLabsCacheStatus.value = ElevenLabsCacheStatus(
            cachedPassages = requests.count(elevenLabsCache::contains),
            totalPassages = requests.size,
            storedBytes = elevenLabsCache.sizeBytes(),
        )
    }

    fun refreshElevenLabsCacheStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = app.settingsStore.settings.first()
            val voiceId = settings.elevenLabsVoiceId
            val text = chapterText(_ui.value.chapterIndex)
            if (voiceId == null || text == null) {
                elevenLabsCacheStatus.value = ElevenLabsCacheStatus(
                    storedBytes = elevenLabsCache.sizeBytes(),
                )
                return@launch
            }
            val chunks = sentenceChunks(text, 0)
            updateElevenLabsCacheStatus(chunks, voiceId, settings.elevenLabsModel)
        }
    }

    fun clearElevenLabsCache() {
        if (ttsStatus.value != TtsStatus.OFF) stopTts()
        viewModelScope.launch(Dispatchers.IO) {
            elevenLabsCache.clear()
            elevenLabsCacheStatus.value = elevenLabsCacheStatus.value.copy(
                cachedPassages = 0,
                storedBytes = 0,
            )
        }
    }

    private fun wordBounds(text: String, index: Int): IntRange {
        if (text.isEmpty()) return 0..0
        var start = index.coerceIn(text.indices)
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = index.coerceIn(text.indices)
        while (end + 1 < text.length && !text[end + 1].isWhitespace()) end++
        return start..end
    }

    /** Sentence-boundary utterances ≤ ~400 chars, tagged with their offsets. */
    private fun sentenceChunks(text: String, from: Int): List<Pair<Int, String>> {
        val chunks = mutableListOf<Pair<Int, String>>()
        var cursor = from.coerceIn(0, text.length)
        while (cursor < text.length) {
            var end = (cursor + 400).coerceAtMost(text.length)
            val stop = text.indexOfAny(charArrayOf('.', '!', '?', '\n'), cursor)
            if (stop in cursor until end) {
                end = stop + 1
            } else if (end < text.length) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > cursor + 40) end = space
            }
            val sentence = text.substring(cursor, end)
            if (sentence.isNotBlank()) chunks.add(cursor to sentence)
            cursor = end
        }
        return chunks
    }

    fun setNarrationProvider(provider: NarrationProvider) {
        viewModelScope.launch {
            app.settingsStore.setNarrationProvider(provider)
            if (ttsStatus.value == TtsStatus.PLAYING || ttsStatus.value == TtsStatus.PREPARING) {
                pauseTts()
                startTts()
            } else {
                tts?.stop()
                kokoroJob?.cancel()
                kokoro?.cancel()
                elevenLabsJob?.cancel()
                elevenLabsPlayback.cancel()
            }
        }
    }

    fun connectElevenLabs(apiKey: String) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            elevenLabsState.value = elevenLabsState.value.copy(error = "Enter an API key")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            elevenLabsState.value = elevenLabsState.value.copy(loading = true, error = null)
            try {
                val subscription = elevenLabsClient.getSubscription(key)
                val voices = elevenLabsClient.getVoices(key)
                elevenLabsCredentials.save(key)
                elevenLabsState.value = ElevenLabsUiState(
                    connected = true,
                    voices = voices,
                    subscription = subscription,
                )
            } catch (e: Exception) {
                elevenLabsState.value = ElevenLabsUiState(
                    connected = false,
                    error = elevenLabsError(e),
                )
            }
        }
    }

    fun refreshElevenLabs() {
        val key = elevenLabsCredentials.read() ?: run {
            elevenLabsState.value = ElevenLabsUiState()
            return
        }
        if (elevenLabsState.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            elevenLabsState.value = elevenLabsState.value.copy(connected = true, loading = true, error = null)
            try {
                elevenLabsState.value = ElevenLabsUiState(
                    connected = true,
                    voices = elevenLabsClient.getVoices(key),
                    subscription = elevenLabsClient.getSubscription(key),
                )
            } catch (e: Exception) {
                elevenLabsState.value = elevenLabsState.value.copy(
                    connected = true,
                    loading = false,
                    error = elevenLabsError(e),
                )
            }
        }
    }

    fun disconnectElevenLabs() {
        if (ttsStatus.value != TtsStatus.OFF) stopTts()
        elevenLabsCredentials.clear()
        elevenLabsState.value = ElevenLabsUiState()
        viewModelScope.launch {
            val settings = app.settingsStore.settings.first()
            if (settings.narrationProvider == NarrationProvider.ELEVENLABS) {
                app.settingsStore.setNarrationProvider(NarrationProvider.SYSTEM)
            }
        }
    }

    fun setElevenLabsVoice(voice: ElevenLabsVoice) {
        viewModelScope.launch {
            app.settingsStore.setElevenLabsVoice(voice.id, voice.name)
            refreshElevenLabsCacheStatus()
            if (ttsStatus.value == TtsStatus.PLAYING || ttsStatus.value == TtsStatus.PREPARING) {
                pauseTts()
                startTts()
            }
        }
    }

    fun setElevenLabsModel(model: ElevenLabsModel) {
        viewModelScope.launch {
            app.settingsStore.setElevenLabsModel(model)
            refreshElevenLabsCacheStatus()
            if (ttsStatus.value == TtsStatus.PLAYING || ttsStatus.value == TtsStatus.PREPARING) {
                pauseTts()
                startTts()
            }
        }
    }

    fun setKokoroVoice(sid: Int) {
        kokoroSid = sid
        viewModelScope.launch {
            app.settingsStore.setKokoroVoice(sid) // persisted before restart reads it
            if (ttsStatus.value == TtsStatus.PLAYING) {
                pauseTts()
                startTts()
                return@launch
            }
            sampleKokoroVoice(sid)
        }
    }

    private fun sampleKokoroVoice(sid: Int) {
        if (KokoroVoicePack.isInstalled(app)) {
            // Speak a short sample in the newly chosen voice.
            kokoroJob?.cancel()
            kokoro?.cancel()
            kokoroJob = viewModelScope.launch(Dispatchers.Default) {
                val engine = kokoro ?: try {
                    KokoroEngine(KokoroVoicePack.modelDir(app)).also { kokoro = it }
                } catch (e: Exception) {
                    return@launch
                }
                val session = engine.beginSession()
                engine.synthesize(
                    "The interface disappears, and only the book remains.", sid, ttsSpeed.value, session,
                )?.let { engine.playBlocking(it, session) }
            }
        }
    }

    fun downloadKokoro() {
        if (kokoroDownloadProgress.value != null) return
        viewModelScope.launch {
            kokoroDownloadProgress.value = 0f
            KokoroVoicePack.install(app) { progress -> kokoroDownloadProgress.value = progress }
            kokoroDownloadProgress.value = null
            kokoroInstalled.value = KokoroVoicePack.isInstalled(app)
        }
    }

    /** Creates the engine once, restores the saved voice, then runs [onReady]. */
    private fun ensureTtsEngine(onReady: () -> Unit) {
        if (tts != null) {
            onReady()
            return
        }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTtsListener()
                viewModelScope.launch {
                    refreshVoices()
                    app.settingsStore.settings.first().ttsVoice?.let(::applyVoiceByName)
                    onReady()
                }
            } else {
                notify("Read-aloud isn't available on this device")
                ttsStatus.value = TtsStatus.OFF
            }
        }
    }

    /** Transient user-facing notice from a background failure. */
    private fun notify(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun elevenLabsError(error: Throwable): String = when (error) {
        is ElevenLabsApiException -> when (error.statusCode) {
            401 -> "ElevenLabs rejected this API key"
            402 -> "ElevenLabs credits are exhausted"
            429 -> "ElevenLabs usage limit reached — try again later"
            else -> error.message ?: "ElevenLabs request failed"
        }
        is IOException -> "ElevenLabs needs an internet connection"
        else -> error.message ?: "ElevenLabs request failed"
    }

    /** Populates the picker: offline voices for the user's language. */
    fun prepareVoices() {
        ensureTtsEngine { }
        if (elevenLabsCredentials.hasKey()) refreshElevenLabs()
        refreshElevenLabsCacheStatus()
    }

    private fun refreshVoices() {
        val language = java.util.Locale.getDefault().language
        ttsVoices.value = try {
            (tts?.voices ?: emptySet())
                .filter { !it.isNetworkConnectionRequired && it.locale.language == language }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun applyVoiceByName(name: String) {
        try {
            tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it }
        } catch (e: Exception) {
            // Voice gone (engine update) — the default carries on.
        }
    }

    /**
     * Switches the system voice. Persist completes BEFORE any restart so the
     * restart can't read a stale choice; mid-playback the new voice picks up
     * from the current word, otherwise a sample plays (engine spun up if
     * needed — the picker works without the player running).
     */
    fun setTtsVoice(name: String) {
        viewModelScope.launch {
            app.settingsStore.setTtsVoice(name)
            ensureTtsEngine {
                applyVoiceByName(name)
                if (ttsStatus.value == TtsStatus.PLAYING) {
                    pauseTts()
                    startTts()
                } else {
                    tts?.speak(
                        "The interface disappears, and only the book remains.",
                        TextToSpeech.QUEUE_FLUSH, null, "vellum:sample",
                    )
                }
            }
        }
    }

    fun pauseTts() {
        ttsResumeOffset = ttsRange.value?.startChar ?: currentPage()?.startChar
        tts?.stop()
        kokoroJob?.cancel()
        kokoro?.cancel()
        elevenLabsJob?.cancel()
        elevenLabsPlayback.cancel()
        ttsRange.value = null
        ttsStatus.value = TtsStatus.PAUSED
    }

    fun stopTts() {
        // Keep the listening position: a later play resumes here if the
        // reader is still on the same page.
        ttsResumeOffset = ttsRange.value?.startChar ?: ttsResumeOffset
        tts?.stop()
        kokoroJob?.cancel()
        kokoro?.cancel()
        elevenLabsJob?.cancel()
        elevenLabsPlayback.cancel()
        ttsRange.value = null
        ttsStatus.value = TtsStatus.OFF
        sleepJob?.cancel()
        ttsSleep.value = TtsSleep.OFF
    }

    /** Applies a directly selected listening speed to subsequent speech. */
    fun setTtsSpeed(speed: Float) {
        ttsSpeed.value = speed.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(ttsSpeed.value)
        elevenLabsPlayback.setSpeed(ttsSpeed.value)
    }

    fun cycleSleepTimer() {
        val next = TtsSleep.entries[(ttsSleep.value.ordinal + 1) % TtsSleep.entries.size]
        ttsSleep.value = next
        sleepJob?.cancel()
        next.minutes?.let { minutes ->
            sleepJob = viewModelScope.launch {
                kotlinx.coroutines.delay(minutes * 60_000L)
                stopTts()
            }
        }
    }

    /** Continues into the next readable chapter unless the chapter timer was selected. */
    private suspend fun onNarrationChapterComplete() {
        if (ttsSleep.value == TtsSleep.END_OF_CHAPTER) {
            withContext(Dispatchers.Main) { stopTts() }
            return
        }
        val target = peekTurnTarget(forward = true)
        if (target == null) {
            withContext(Dispatchers.Main) { stopTts() }
            return
        }
        withContext(Dispatchers.Main) {
            ttsRange.value = null
            ttsResumeOffset = null
            commitTurn(target, forward = true)
            startTts()
        }
    }

    private fun beginSpeaking(fromOffset: Int) {
        val text = chapterText(_ui.value.chapterIndex) ?: return
        val start = fromOffset.coerceIn(0, text.length)
        ttsChunks = chunkForSpeech(text, start)
        ttsChunkIndex = 0
        if (ttsChunks.isEmpty()) return
        ttsStatus.value = TtsStatus.PLAYING
        tts?.setSpeechRate(ttsSpeed.value)
        speakChunk(0)
    }

    /** Sentence-boundary chunks ≤ ~2800 chars, each tagged with its offset. */
    private fun chunkForSpeech(text: String, start: Int): List<Pair<Int, String>> {
        val chunks = mutableListOf<Pair<Int, String>>()
        var cursor = start
        while (cursor < text.length) {
            var end = (cursor + 2800).coerceAtMost(text.length)
            if (end < text.length) {
                val sentenceEnd = text.lastIndexOfAny(charArrayOf('.', '!', '?'), end)
                if (sentenceEnd > cursor + 200) end = sentenceEnd + 1
            }
            chunks.add(cursor to text.substring(cursor, end))
            cursor = end
        }
        return chunks
    }

    private fun speakChunk(index: Int) {
        val (base, chunk) = ttsChunks.getOrNull(index) ?: return
        tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "vellum:$base")
    }

    private fun configureTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val base = utteranceId?.removePrefix("vellum:")?.toIntOrNull() ?: return
                viewModelScope.launch {
                    ttsRange.value = SelectionRange(base + start, base + end)
                    // Speech has crossed the page boundary — turn with it.
                    val page = currentPage() ?: return@launch
                    if (base + start >= page.endChar) nextPage()
                }
            }

            override fun onDone(utteranceId: String?) {
                viewModelScope.launch {
                    if (ttsStatus.value != TtsStatus.PLAYING) return@launch
                    ttsChunkIndex++
                    if (ttsChunkIndex < ttsChunks.size) {
                        speakChunk(ttsChunkIndex)
                    } else {
                        onNarrationChapterComplete()
                    }
                }
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                android.util.Log.e("VellumTts", "System TTS error for $utteranceId")
                notify("Read-aloud stopped — the voice reported an error")
                viewModelScope.launch { stopTts() }
            }
        })
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        kokoroJob?.cancel()
        kokoro?.release()
        elevenLabsJob?.cancel()
        elevenLabsPlayback.cancel()
        // Record the sitting for insights — but only real ones (30s+).
        val session = readingSession.finish(bookUuid, pagesTurned)
        val state = _ui.value
        val page = currentPage()
        val opened = epub
        val position = if (page != null && opened != null) {
            val total = paginatedCache[state.chapterIndex]?.totalChars ?: 0
            ReadingPositionEntity(
                bookUuid = bookUuid,
                chapterIndex = state.chapterIndex,
                chapterHref = opened.chapterHref(state.chapterIndex),
                charOffset = page.startChar,
                progression = overallProgression(state, page.startChar, total),
                updatedAt = System.currentTimeMillis(),
            )
        } else null
        if (session != null || position != null) {
            app.appScope.launch(Dispatchers.IO) {
                session?.let { app.sessionDao.upsert(it) }
                position?.let { app.bookDao.upsertPosition(it) }
            }
        }
        epub?.close()
    }
}
