package app.vellum.reader.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Folder-bundle sync controls. The folder is meant to live inside whatever
 * the user already syncs between devices (Syncthing, Drive, OneDrive) —
 * Vellum itself never talks to a network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(
    folderConfigured: Boolean,
    lastSyncAt: Long,
    status: String?,
    syncing: Boolean,
    onChooseFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sync", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Text(
                "Vellum mirrors your library, positions, highlights, and ink into a folder " +
                    "you choose. Point it at a folder synced by Syncthing, Drive, or OneDrive " +
                    "and your devices converge — no accounts, nothing leaves your own storage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folderConfigured && lastSyncAt > 0) {
                Text(
                    "Last synced ${DateFormat.getDateTimeInstance().format(Date(lastSyncAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onChooseFolder) {
                    Text(if (folderConfigured) "Change folder" else "Choose folder")
                }
                Button(onClick = onSyncNow, enabled = folderConfigured && !syncing) {
                    Text(if (syncing) "Syncing…" else "Sync now")
                }
            }
        }
    }
}
