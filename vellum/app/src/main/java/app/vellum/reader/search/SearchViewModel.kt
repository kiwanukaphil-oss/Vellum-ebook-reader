package app.vellum.reader.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * Full-text search over the FTS index. With [scopeBookUuid] set it searches
 * inside one book (reader search); otherwise the whole library, with
 * title/author matches listed above passage hits.
 */
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
            queryFlow.debounce(250).collect { runSearch(it) }
        }
    }

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
        queryFlow.value = query
    }

    private suspend fun runSearch(query: String) {
        val term = query.trim()
        if (term.length < 2) {
            _state.value = _state.value.copy(bookMatches = emptyList(), passageMatches = emptyList(), searching = false)
            return
        }
        _state.value = _state.value.copy(searching = true)
        // Quoting makes user text a phrase query, inert to FTS operator syntax.
        val ftsQuery = "\"${term.replace("\"", "")}\""
        val titlesByUuid = app.bookDao.allActive().associateBy({ it.uuid }, { it.title })
        val passages = (
            if (scopeBookUuid != null) app.searchDao.searchInBook(scopeBookUuid, ftsQuery, term)
            else app.searchDao.searchAllBooks(ftsQuery, term)
            ).mapNotNull { hit ->
            val title = titlesByUuid[hit.bookUuid] ?: return@mapNotNull null
            PassageResult(
                bookUuid = hit.bookUuid,
                bookTitle = title,
                chapterIndex = hit.chapterIndex.toIntOrNull() ?: 0,
                snippet = hit.snippet,
                charOffset = hit.firstMatchOffset.coerceAtLeast(0),
            )
        }
        val books =
            if (scopeBookUuid == null) app.bookDao.searchByTitleOrAuthor(term)
            else emptyList()
        _state.value = _state.value.copy(bookMatches = books, passageMatches = passages, searching = false)
    }
}
