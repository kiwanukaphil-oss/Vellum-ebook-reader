package app.vellum.reader.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.ChapterSearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** One passage result, ready to jump to the exact page via its locator. */
data class PassageResult(
    val bookUuid: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val snippet: String,
    val charOffset: Int,
)

data class SearchState(
    val query: String = "",
    val bookMatches: List<BookEntity> = emptyList(),
    val passageMatches: List<PassageResult> = emptyList(),
    val searching: Boolean = false,
)

/** Full-text search for the library or a single open book. */
class SearchViewModel(
    private val app: VellumApp,
    private val scopeBookUuid: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queryFlow.debounce(250).collectLatest { runSearch(it) }
        }
    }

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
    }

    private suspend fun runSearch(query: String) {
        val term = query.trim()
        if (term.length < 2) {
            _state.value = _state.value.copy(
                bookMatches = emptyList(),
                passageMatches = emptyList(),
                searching = false,
            )
            return
        }
        _state.value = _state.value.copy(searching = true)
        // Quoting makes user text a phrase query, inert to FTS operator syntax.
        val ftsQuery = "\"${term.replace("\"", "")}\""
        val chapters = if (scopeBookUuid != null) {
            app.searchDao.searchInBook(scopeBookUuid, ftsQuery)
        } else {
            app.searchDao.searchAllBooks(ftsQuery)
        }
        val passages = expandPassageHits(chapters, term)
        val books = if (scopeBookUuid == null) {
            app.bookDao.searchByTitleOrAuthor(escapeLike(term))
        } else {
            emptyList()
        }
        _state.value = _state.value.copy(
            bookMatches = books,
            passageMatches = passages,
            searching = false,
        )
    }
}

internal fun escapeLike(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/** Expands each matching FTS chapter into one result per occurrence. */
internal fun expandPassageHits(
    chapters: List<ChapterSearchHit>,
    term: String,
): List<PassageResult> = buildList {
    if (term.isBlank()) return@buildList
    val rankedChapters = chapters.sortedWith(
        compareByDescending<ChapterSearchHit> { occurrenceCount(it.body, term) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.bookTitle }
            .thenBy { it.chapterIndex.toIntOrNull() ?: Int.MAX_VALUE },
    )
    for (chapter in rankedChapters) {
        var from = 0
        while (size < MAX_PASSAGE_RESULTS) {
            val offset = chapter.body.indexOf(term, startIndex = from, ignoreCase = true)
            if (offset < 0) break
            val start = (offset - 54).coerceAtLeast(0)
            val end = (offset + term.length + 72).coerceAtMost(chapter.body.length)
            val before = chapter.body.substring(start, offset).trimStart()
            val match = chapter.body.substring(offset, offset + term.length)
            val after = chapter.body.substring(offset + term.length, end).trimEnd()
            add(
                PassageResult(
                    bookUuid = chapter.bookUuid,
                    bookTitle = chapter.bookTitle,
                    chapterIndex = chapter.chapterIndex.toIntOrNull() ?: 0,
                    snippet = buildString {
                        if (start > 0) append('…')
                        append(before).append('⟪').append(match).append('⟫').append(after)
                        if (end < chapter.body.length) append('…')
                    },
                    charOffset = offset,
                ),
            )
            from = offset + term.length.coerceAtLeast(1)
        }
        if (size >= MAX_PASSAGE_RESULTS) break
    }
}

private const val MAX_PASSAGE_RESULTS = 100

private fun occurrenceCount(body: String, term: String): Int {
    var count = 0
    var from = 0
    while (from < body.length) {
        val offset = body.indexOf(term, startIndex = from, ignoreCase = true)
        if (offset < 0) break
        count++
        from = offset + term.length.coerceAtLeast(1)
    }
    return count
}
