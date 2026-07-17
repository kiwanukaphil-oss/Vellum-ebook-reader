package app.vellum.reader.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.vellum.reader.core.data.BookEntity

/**
 * Long-press sheet for one book: metadata + series editing, collection and tag
 * membership, and (soft) deletion. Saving commits only on the Save button so
 * half-typed edits never write through.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailsSheet(
    book: BookEntity,
    state: LibraryState,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    var title by remember(book.uuid) { mutableStateOf(book.title) }
    var author by remember(book.uuid) { mutableStateOf(book.author) }
    var seriesName by remember(book.uuid) { mutableStateOf(book.seriesName ?: "") }
    var seriesIndex by remember(book.uuid) { mutableStateOf(book.seriesIndex?.toString() ?: "") }
    var newCollection by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Edit book", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    label = { Text("Series") },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = seriesIndex,
                    onValueChange = { seriesIndex = it },
                    label = { Text("#") },
                    modifier = Modifier.width(80.dp),
                )
            }
            Button(
                onClick = {
                    viewModel.updateMetadata(
                        book.uuid, title, author,
                        seriesName.ifBlank { null },
                        seriesIndex.toFloatOrNull(),
                    )
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save") }

            Text("Collections", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.collections.forEach { collection ->
                    val isMember = collection.uuid in state.collectionsByBook[book.uuid].orEmpty()
                    FilterChip(
                        selected = isMember,
                        onClick = { viewModel.toggleCollection(book.uuid, collection.uuid, isMember) },
                        label = { Text(collection.name) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newCollection,
                    onValueChange = { newCollection = it },
                    label = { Text("New collection") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newCollection.isNotBlank()) {
                            viewModel.createCollection(newCollection, listOf(book.uuid))
                            newCollection = ""
                        }
                    },
                ) { Text("Add") }
            }

            Text("Tags", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tags.forEach { tag ->
                    val isMember = tag.uuid in state.tagsByBook[book.uuid].orEmpty()
                    FilterChip(
                        selected = isMember,
                        onClick = { viewModel.toggleTag(book.uuid, tag.uuid, isMember) },
                        label = { Text("#${tag.name}") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("New tag") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newTag.isNotBlank()) {
                            viewModel.createTag(newTag, listOf(book.uuid))
                            newTag = ""
                        }
                    },
                ) { Text("Add") }
            }

            TextButton(onClick = { confirmDelete = true }) {
                Text("Remove from library", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove \"${book.title}\" from the library?") },
            text = { Text("Highlights, notes, and reading progress will be deleted. Original files on your device aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
