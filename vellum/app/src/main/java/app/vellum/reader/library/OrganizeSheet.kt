package app.vellum.reader.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * Batch collections/tags for the whole selection. A chip is "on" when every
 * selected book is a member; toggling applies that membership to all of them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OrganizeSheet(
    state: LibraryState,
    selectedUuids: Set<String>,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    var newCollection by remember { mutableStateOf("") }
    var newGenre by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    val selectedBooks = state.allBooks.filter { it.uuid in selectedUuids }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Organize ${selectedUuids.size} ${if (selectedUuids.size == 1) "book" else "books"}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
            )

            Text("Primary category", style = MaterialTheme.typography.titleSmall)
            Text(
                "A book has one broad home. This applies to every selected book.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BookCategories.all.forEach { category ->
                    val allMatch = selectedBooks.isNotEmpty() && selectedBooks.all { it.category == category }
                    FilterChip(
                        selected = allMatch,
                        onClick = { viewModel.setCategoryForBooks(category, selectedUuids) },
                        label = { Text(category) },
                    )
                }
            }

            Text("Genres", style = MaterialTheme.typography.titleSmall)
            Text(
                "Genres can overlap, so a book can appear on more than one shelf.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.genres.forEach { genre ->
                    val allMembers = selectedUuids.all { genre.uuid in state.genresByBook[it].orEmpty() }
                    FilterChip(
                        selected = allMembers,
                        onClick = { viewModel.setGenreForBooks(genre.uuid, selectedUuids, !allMembers) },
                        label = { Text(genre.name) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newGenre,
                    onValueChange = { newGenre = it },
                    label = { Text("New genre") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newGenre.isNotBlank()) {
                            viewModel.createGenre(newGenre, emptyList())
                            newGenre = ""
                        }
                    },
                ) { Text("Create") }
            }

            Text("Collections", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.collections.forEach { collection ->
                    val allMembers = selectedUuids.all {
                        collection.uuid in state.collectionsByBook[it].orEmpty()
                    }
                    FilterChip(
                        selected = allMembers,
                        onClick = {
                            viewModel.setCollectionForBooks(collection.uuid, selectedUuids, !allMembers)
                        },
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
                            viewModel.createCollection(newCollection, selectedUuids)
                            newCollection = ""
                        }
                    },
                ) { Text("Add") }
            }

            Text("Personal tags", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tags.forEach { tag ->
                    val allMembers = selectedUuids.all { tag.uuid in state.tagsByBook[it].orEmpty() }
                    FilterChip(
                        selected = allMembers,
                        onClick = { viewModel.setTagForBooks(tag.uuid, selectedUuids, !allMembers) },
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
                            viewModel.createTag(newTag, selectedUuids)
                            newTag = ""
                        }
                    },
                ) { Text("Add") }
            }
        }
    }
}
