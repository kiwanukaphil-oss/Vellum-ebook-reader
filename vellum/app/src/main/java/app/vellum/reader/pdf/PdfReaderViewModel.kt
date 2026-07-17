package app.vellum.reader.pdf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.PdfStrokeEntity
import app.vellum.reader.core.data.ReadingPositionEntity
import app.vellum.reader.core.data.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class PdfUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val pageCount: Int = 0,
    val startPage: Int = 0,
    val chromeVisible: Boolean = false,
    val markupMode: Boolean = false,
    val markupColorId: String = "rose",
)

/**
 * Drives one PDF session: page bitmaps via [PdfPageRenderer], position
 * persistence in the shared locator table (chapterIndex = page), and the
 * freehand ink layer with normalized-coordinate strokes.
 */
class PdfReaderViewModel(
    private val app: VellumApp,
    private val bookUuid: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(PdfUiState())
    val ui: StateFlow<PdfUiState> = _ui

    var renderer: PdfPageRenderer? = null
        private set

    private val sessionStartedAt = System.currentTimeMillis()
    private var pagesTurned = 0
    private var lastPersistedPage = -1

    /** pageIndex → strokes, live from the database. */
    val strokesByPage: StateFlow<Map<Int, List<PdfStrokeEntity>>> = app.pdfStrokeDao
        .observeForBook(bookUuid)
        .map { strokes -> strokes.groupBy { it.pageIndex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            val book = app.bookDao.byUuid(bookUuid)
            if (book == null) {
                _ui.update { it.copy(error = "Book not found", loading = false) }
                return@launch
            }
            try {
                renderer = PdfPageRenderer(File(app.booksDir, book.fileName))
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Could not open \"${book.title}\"", loading = false) }
                return@launch
            }
            val position = app.bookDao.positionFor(bookUuid)
            _ui.update {
                it.copy(
                    loading = false,
                    bookTitle = book.title,
                    pageCount = renderer?.pageCount ?: 0,
                    startPage = (position?.chapterIndex ?: 0).coerceIn(0, (renderer?.pageCount ?: 1) - 1),
                )
            }
            app.bookDao.markOpened(bookUuid, System.currentTimeMillis())
        }
    }

    fun toggleChrome() = _ui.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun setMarkupMode(enabled: Boolean) = _ui.update { it.copy(markupMode = enabled) }

    fun setMarkupColor(colorId: String) = _ui.update { it.copy(markupColorId = colorId) }

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

    /** Persists one finished stroke; points already normalized to 0..1. */
    fun commitStroke(pageIndex: Int, normalizedPoints: List<Offset>, widthNormalized: Float) {
        if (normalizedPoints.size < 2) return
        val now = System.currentTimeMillis()
        val serialized = normalizedPoints.joinToString(";") { "%.4f,%.4f".format(it.x, it.y) }
        viewModelScope.launch {
            app.pdfStrokeDao.insert(
                PdfStrokeEntity(
                    uuid = UUID.randomUUID().toString(),
                    bookUuid = bookUuid,
                    pageIndex = pageIndex,
                    colorId = _ui.value.markupColorId,
                    strokeWidth = widthNormalized,
                    points = serialized,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    /** Removes the most recent stroke on the page — the undo button. */
    fun undoStroke(pageIndex: Int) {
        viewModelScope.launch {
            app.pdfStrokeDao.latestForPage(bookUuid, pageIndex)?.let {
                app.pdfStrokeDao.softDelete(it.uuid, System.currentTimeMillis())
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
        renderer?.close()
    }

    companion object {
        fun parsePoints(serialized: String): List<Offset> = serialized.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            Offset(x, y)
        }
    }
}
