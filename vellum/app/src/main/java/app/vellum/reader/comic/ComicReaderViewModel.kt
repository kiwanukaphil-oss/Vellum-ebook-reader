package app.vellum.reader.comic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.ComicPanelEntity
import app.vellum.reader.core.data.ReadingPositionEntity
import app.vellum.reader.core.data.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ComicUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val pageCount: Int = 0,
    val startPage: Int = 0,
    val rtl: Boolean = false,
    val chromeVisible: Boolean = false,
    val panelEditMode: Boolean = false,
)

/**
 * Drives one comic session: page bitmaps from whichever container the book
 * came in, per-book reading direction, and the guided-view panel rects.
 */
class ComicReaderViewModel(
    private val app: VellumApp,
    private val bookUuid: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(ComicUiState())
    val ui: StateFlow<ComicUiState> = _ui

    var store: ComicPageStore? = null
        private set

    private val sessionStartedAt = System.currentTimeMillis()
    private var pagesTurned = 0
    private var lastPersistedPage = -1

    val panelsByPage: StateFlow<Map<Int, List<ComicPanelEntity>>> = app.comicPanelDao
        .observeForBook(bookUuid)
        .map { panels -> panels.groupBy { it.pageIndex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            val book = app.bookDao.byUuid(bookUuid)
            if (book == null) {
                _ui.update { it.copy(error = "Book not found", loading = false) }
                return@launch
            }
            val file = File(app.booksDir, book.fileName)
            val source: ComicSource? = try {
                when (book.format) {
                    "cbz" -> CbzComicSource(file)
                    "cbr" -> CbrComicSource(file)
                    "comic-epub" -> app.epubOpener.open(file)?.let { FixedEpubComicSource(it) }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (source == null || source.pageCount == 0) {
                source?.close()
                _ui.update { it.copy(error = "Could not open \"${book.title}\"", loading = false) }
                return@launch
            }
            store = ComicPageStore(source)
            val position = app.bookDao.positionFor(bookUuid)
            _ui.update {
                it.copy(
                    loading = false,
                    bookTitle = book.title,
                    pageCount = source.pageCount,
                    startPage = (position?.chapterIndex ?: 0).coerceIn(0, source.pageCount - 1),
                    rtl = book.comicRtl ?: false,
                )
            }
            app.bookDao.markOpened(bookUuid, System.currentTimeMillis())
        }
    }

    fun toggleChrome() = _ui.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun setPanelEditMode(enabled: Boolean) = _ui.update { it.copy(panelEditMode = enabled) }

    /** Flips reading direction and remembers it on the book. */
    fun setRtl(rtl: Boolean) {
        _ui.update { it.copy(rtl = rtl) }
        viewModelScope.launch { app.bookDao.setComicRtl(bookUuid, rtl, System.currentTimeMillis()) }
    }

    fun persistPage(pageIndex: Int) {
        if (lastPersistedPage != -1 && pageIndex != lastPersistedPage) pagesTurned++
        lastPersistedPage = pageIndex
        viewModelScope.launch {
            app.bookDao.upsertPosition(
                ReadingPositionEntity(
                    bookUuid = bookUuid,
                    chapterIndex = pageIndex,
                    chapterHref = "page:$pageIndex",
                    charOffset = 0,
                    progression = if (_ui.value.pageCount > 0) pageIndex.toDouble() / _ui.value.pageCount else 0.0,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Prefetch neighbors so page flips land on a warm cache. */
    fun prefetchAround(pageIndex: Int, targetWidthPx: Int) {
        viewModelScope.launch {
            store?.page(pageIndex + 1, targetWidthPx)
            store?.page(pageIndex - 1, targetWidthPx)
        }
    }

    fun addPanel(pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
        if (right - left < 0.03f || bottom - top < 0.03f) return // ignore accidental taps
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            app.comicPanelDao.insert(
                ComicPanelEntity(
                    uuid = UUID.randomUUID().toString(),
                    bookUuid = bookUuid,
                    pageIndex = pageIndex,
                    ord = app.comicPanelDao.nextOrdinal(bookUuid, pageIndex),
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    fun undoPanel(pageIndex: Int) {
        viewModelScope.launch {
            app.comicPanelDao.latestForPage(bookUuid, pageIndex)?.let {
                app.comicPanelDao.softDelete(it.uuid, System.currentTimeMillis())
            }
        }
    }

    override fun onCleared() {
        val elapsed = System.currentTimeMillis() - sessionStartedAt
        if (elapsed >= 30_000) {
            runBlocking {
                app.sessionDao.insert(
                    ReadingSessionEntity(
                        uuid = UUID.randomUUID().toString(),
                        bookUuid = bookUuid,
                        startedAt = sessionStartedAt,
                        endedAt = sessionStartedAt + elapsed,
                        msRead = elapsed,
                        pagesTurned = pagesTurned,
                    ),
                )
            }
        }
        store?.close()
    }
}
