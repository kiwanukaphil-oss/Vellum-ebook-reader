package app.vellum.reader.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vellum.reader.epub.TocEntry

/**
 * The table of contents: nav-doc entries indented by depth, with the entry
 * the reader is currently inside marked in the reading accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    entries: List<TocEntry>,
    currentChapter: Int,
    onSelect: (chapterIndex: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // "Current" is the deepest entry at or before the open chapter.
    val currentEntryIndex = entries.indexOfLast { it.chapterIndex <= currentChapter }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(entries) { index, entry ->
                val isCurrent = index == currentEntryIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry.chapterIndex) }
                        .padding(
                            start = (24 + entry.depth * 16).dp,
                            end = 24.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${entry.chapterIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
