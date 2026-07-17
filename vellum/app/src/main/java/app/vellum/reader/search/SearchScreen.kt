package app.vellum.reader.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp

/**
 * Library-wide or in-book search. Passage hits carry a locator, so tapping one
 * opens the reader on the exact page containing the match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    scopeBookUuid: String?,
    onOpenBook: (uuid: String, format: String) -> Unit,
    onOpenPassage: (bookUuid: String, chapterIndex: Int, charOffset: Int) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: SearchViewModel = viewModel(key = "search-${scopeBookUuid ?: "all"}") {
        SearchViewModel(app, scopeBookUuid)
    }
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (scopeBookUuid == null) "Search library" else "Search in book", fontFamily = FontFamily.Serif) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text("Search titles, authors, and text…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
            )
            if (state.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn {
                if (state.bookMatches.isNotEmpty()) {
                    item {
                        Text(
                            "Books",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(state.bookMatches, key = { "b" + it.uuid }) { book ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBook(book.uuid, book.format) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(book.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleMedium)
                            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                if (state.passageMatches.isNotEmpty()) {
                    item {
                        Text(
                            "Passages",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(state.passageMatches, key = { "p${it.bookUuid}-${it.chapterIndex}" }) { passage ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPassage(passage.bookUuid, passage.chapterIndex, passage.charOffset) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(
                                highlightedSnippet(passage.snippet, MaterialTheme.colorScheme.secondary),
                                fontFamily = FontFamily.Serif,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${passage.bookTitle} · Chapter ${passage.chapterIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
                if (state.query.length < 2) {
                    item {
                        Text(
                            if (scopeBookUuid == null) {
                                "Search your whole library — titles, authors, and the full text of every book."
                            } else {
                                "Search the full text of this book."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                if (state.query.length >= 2 && !state.searching &&
                    state.bookMatches.isEmpty() && state.passageMatches.isEmpty()
                ) {
                    item {
                        Text(
                            "No matches for “${state.query.trim()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Maps the FTS snippet's ⟪…⟫ match markers onto a bold accent span. */
private fun highlightedSnippet(snippet: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var depth = 0
    snippet.forEach { ch ->
        when (ch) {
            '⟪' -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent))
                depth++
            }
            '⟫' -> if (depth > 0) {
                pop()
                depth--
            }
            else -> append(ch)
        }
    }
    repeat(depth) { pop() }
}
