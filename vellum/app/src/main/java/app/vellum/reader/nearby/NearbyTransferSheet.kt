package app.vellum.reader.nearby

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vellum.reader.core.data.BookEntity
import java.util.Locale

enum class NearbyTransferMode { SEND, RECEIVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBooksSheet(
    onChooseFiles: () -> Unit,
    onReceiveNearby: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Text(
                "Add books",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Choose from this device") },
                supportingContent = { Text("Import one or more EPUB, PDF, CBZ, or CBR files") },
                leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onChooseFiles),
            )
            ListItem(
                headlineContent = { Text("Receive from nearby") },
                supportingContent = { Text("Transfer directly from another Vellum device on this Wi-Fi") },
                leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onReceiveNearby),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyTransferSheet(
    mode: NearbyTransferMode,
    manager: NearbyTransferManager,
    books: List<BookEntity> = emptyList(),
    onDismiss: () -> Unit,
) {
    val senderState by manager.senderState.collectAsState()
    val receiverState by manager.receiverState.collectAsState()

    LaunchedEffect(mode, books.map { it.uuid }) {
        when (mode) {
            NearbyTransferMode.SEND -> if (manager.senderState.value is NearbySenderState.Idle) manager.startSending(books)
            NearbyTransferMode.RECEIVE -> if (manager.receiverState.value is NearbyReceiverState.Idle) manager.startDiscovery()
        }
    }

    fun dismiss() {
        when (mode) {
            NearbyTransferMode.SEND -> manager.stopSending()
            NearbyTransferMode.RECEIVE -> manager.resetReceiver()
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::dismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (mode == NearbyTransferMode.SEND) "Share nearby" else "Receive nearby",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (mode == NearbyTransferMode.SEND) {
                SenderContent(senderState, onDone = ::dismiss, onTryAgain = { manager.startSending(books) })
            } else {
                ReceiverContent(manager, receiverState, onDone = ::dismiss)
            }
        }
    }
}

@Composable
private fun SenderContent(
    state: NearbySenderState,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
) {
    when (state) {
        NearbySenderState.Idle, NearbySenderState.Preparing -> TransferWorking("Preparing your books…")
        is NearbySenderState.Advertising -> {
            Text("On the other device, open Vellum and choose Add books → Receive from nearby.")
            PairingCode(state.code)
            Text(
                "Sharing ${bookCount(state.bookCount)} · ${formatBytes(state.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrivacyNote()
            Text("Waiting for the other device…", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is NearbySenderState.AwaitingAcceptance -> {
            PairingCode(state.code)
            Text("Connected. Waiting for the recipient to review and accept ${bookCount(state.books.size)}…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is NearbySenderState.Sending -> {
            Text("Sending ${state.bookIndex} of ${state.bookCount}", style = MaterialTheme.typography.labelLarge)
            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            TransferProgress(state.bytesSent, state.totalBytes)
        }
        is NearbySenderState.Complete -> TransferComplete(
            title = "Transfer complete",
            message = "${bookCount(state.bookCount)} sent successfully.",
            onDone = onDone,
        )
        NearbySenderState.Declined -> {
            Text("The recipient declined this transfer.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTryAgain) { Text("Share again") }
                Button(onClick = onDone) { Text("Done") }
            }
        }
        is NearbySenderState.Error -> TransferError(state.message, onTryAgain)
    }
}

@Composable
private fun ReceiverContent(
    manager: NearbyTransferManager,
    state: NearbyReceiverState,
    onDone: () -> Unit,
) {
    val devices by manager.devices.collectAsState()
    var selectedDevice by remember { mutableStateOf<NearbyDevice?>(null) }
    var code by remember { mutableStateOf("") }

    when (state) {
        NearbyReceiverState.Idle -> TransferWorking("Starting nearby discovery…")
        NearbyReceiverState.Discovering -> {
            if (selectedDevice == null) {
                Text("Choose the device that is sharing the book. Both devices must be on the same Wi-Fi.")
                if (devices.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.Devices, contentDescription = null, modifier = Modifier.size(32.dp))
                            Text("Looking for Vellum devices…", textAlign = TextAlign.Center)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column {
                        devices.forEach { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = { Text("Ready to connect") },
                                leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                                modifier = Modifier.clickable { selectedDevice = device },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Text("Connect to ${selectedDevice?.name}", style = MaterialTheme.typography.titleMedium)
                Text("Enter the code shown on the sending device.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { raw ->
                        val clean = raw.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).take(8)
                        code = if (clean.length > 4) clean.take(4) + "-" + clean.drop(4) else clean
                    },
                    label = { Text("Pairing code") },
                    placeholder = { Text("ABCD-EFGH") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedDevice = null; code = "" }) { Text("Back") }
                    Button(
                        onClick = { selectedDevice?.let { manager.connect(it, code) } },
                        enabled = code.count(Char::isLetterOrDigit) == 8,
                    ) { Text("Connect") }
                }
                PrivacyNote()
            }
        }
        is NearbyReceiverState.Connecting -> TransferWorking("Connecting securely to ${state.deviceName}…")
        is NearbyReceiverState.Offer -> {
            Text("${state.deviceName} wants to share:")
            LazyColumn(modifier = Modifier.height((state.books.size.coerceAtMost(3) * 72).dp)) {
                items(state.books) { book ->
                    ListItem(
                        headlineContent = { Text(book.title, maxLines = 1) },
                        supportingContent = { Text("${book.format.uppercase()} · ${formatBytes(book.size)}") },
                    )
                }
            }
            Text("Accepting imports these files into your library. Only accept books you trust.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = manager::declineOffer) { Text("Decline") }
                Button(onClick = manager::acceptOffer) { Text("Accept ${bookCount(state.books.size)}") }
            }
        }
        is NearbyReceiverState.Receiving -> {
            Text("Receiving ${state.bookIndex} of ${state.bookCount}", style = MaterialTheme.typography.labelLarge)
            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            TransferProgress(state.bytesReceived, state.totalBytes)
        }
        is NearbyReceiverState.Importing -> {
            Text("Adding ${state.bookIndex} of ${state.bookCount} to your library")
            Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is NearbyReceiverState.Complete -> TransferComplete(
            title = "Added to your library",
            message = "${bookCount(state.receivedCount)} received and verified.",
            onDone = onDone,
        )
        is NearbyReceiverState.Error -> TransferError(state.message) {
            selectedDevice = null
            code = ""
            manager.startDiscovery()
        }
    }
}

@Composable
private fun PairingCode(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("PAIRING CODE", style = MaterialTheme.typography.labelSmall)
            Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "The transfer is encrypted and stays on your local network. The pairing code expires when this sheet closes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferProgress(done: Long, total: Long) {
    val progress = if (total <= 0L) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Text(
        "${formatBytes(done)} of ${formatBytes(total)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TransferWorking(message: String) {
    Text(message)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TransferComplete(title: String, message: String, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun TransferError(message: String, onTryAgain: () -> Unit) {
    Text("Couldn't complete the transfer", style = MaterialTheme.typography.titleMedium)
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onTryAgain) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Try again")
    }
}

private fun bookCount(count: Int): String = if (count == 1) "1 book" else "$count books"

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(Locale.getDefault(), bytes / 1_024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(Locale.getDefault(), bytes / 1_048_576.0)
    else -> "%.1f GB".format(Locale.getDefault(), bytes / 1_073_741_824.0)
}
