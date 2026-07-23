package app.vellum.reader.shared

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.BuildConfig
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.theme.Fraunces
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLibraryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as VellumApp
    val context = LocalContext.current
    val viewModel: SharedLibraryViewModel = viewModel { SharedLibraryViewModel(app) }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var createLibraryOpen by rememberSaveable { mutableStateOf(false) }
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var publishOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val notice = state.error ?: state.message
        if (notice != null) {
            snackbar.showSnackbar(notice)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to My Library")
                    }
                },
                title = {
                    if (state.account is SharedAccountState.SignedIn && state.libraries.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { sourceMenuOpen = true }) {
                                Text(
                                    state.activeLibrary?.name ?: "Shared Libraries",
                                    fontFamily = Fraunces,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Filled.ExpandMore, contentDescription = "Choose shared library")
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false },
                            ) {
                                state.libraries.forEach { library ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(library.name)
                                                Text(
                                                    "${library.publicationCount} books · ${library.memberCount} members",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            if (library.uuid == state.activeLibrary?.uuid) {
                                                Icon(Icons.Filled.Check, contentDescription = null)
                                            }
                                        },
                                        onClick = {
                                            sourceMenuOpen = false
                                            viewModel.selectLibrary(library)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Create another library") },
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    onClick = {
                                        sourceMenuOpen = false
                                        createLibraryOpen = true
                                    },
                                )
                            }
                        }
                    } else {
                        Text("Shared Libraries", fontFamily = Fraunces)
                    }
                },
                actions = {
                    val active = state.activeLibrary
                    if (active?.role == SharedLibraryRole.OWNER || active?.role == SharedLibraryRole.LIBRARIAN) {
                        IconButton(onClick = { inviteOpen = true }) {
                            Icon(Icons.Filled.GroupAdd, contentDescription = "Invite someone")
                        }
                    }
                    if (state.account is SharedAccountState.SignedIn) {
                        IconButton(onClick = viewModel::signOut) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Sign out")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.activeLibrary?.role?.canPublish == true && state.publications.isNotEmpty()) {
                FloatingActionButton(onClick = { publishOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Publish books")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.configured -> NotConfigured()
                state.account is SharedAccountState.Loading -> CenteredProgress()
                state.account is SharedAccountState.SignedOut -> SignedOutPanel(
                    loading = state.loading,
                    emailSentTo = state.emailSentTo,
                    onSendMagicLink = viewModel::sendMagicLink,
                )
                state.pendingInvitation != null -> InvitationReady(
                    loading = state.loading,
                    onJoin = viewModel::acceptPendingInvitation,
                )
                state.libraries.isEmpty() -> EmptyLibraries(
                    email = (state.account as SharedAccountState.SignedIn).session.email,
                    onCreate = { createLibraryOpen = true },
                )
                state.activeLibrary != null -> SharedCatalogue(
                    state = state,
                    onQueryChange = viewModel::search,
                    onSearch = viewModel::submitSearch,
                    onDownload = viewModel::download,
                    onPublish = { publishOpen = true },
                )
            }
            if (state.loading && state.account is SharedAccountState.SignedIn) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            state.transfer?.let { transfer ->
                TransferCard(transfer, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (createLibraryOpen) {
        CreateLibraryDialog(
            loading = state.loading,
            onDismiss = { createLibraryOpen = false },
            onCreate = { name, description ->
                createLibraryOpen = false
                viewModel.createLibrary(name, description)
            },
        )
    }
    if (inviteOpen) {
        InvitationDialog(
            libraryName = state.activeLibrary?.name.orEmpty(),
            invitation = state.createdInvitation,
            loading = state.loading,
            onCreate = viewModel::createInvitation,
            onShare = { invitation ->
                val text = buildString {
                    append("You’re invited to ${invitation.libraryName} in Vellum.\n\n")
                    append("Open this link on the device with Vellum:\n${invitation.deepLink}")
                }
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        "Share Vellum invitation",
                    ),
                )
            },
            onDismiss = {
                inviteOpen = false
                viewModel.clearCreatedInvitation()
            },
        )
    }
    if (publishOpen) {
        PublishBooksDialog(
            books = state.localBooks,
            selected = state.selectedLocalBooks,
            loading = state.loading,
            onToggle = viewModel::toggleLocalBook,
            onDismiss = {
                publishOpen = false
                viewModel.clearLocalSelection()
            },
            onPublish = { viewModel.publishSelected { publishOpen = false } },
        )
    }
}

@Composable
private fun SignedOutPanel(
    loading: Boolean,
    emailSentTo: String?,
    onSendMagicLink: (String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().height(176.dp),
            ) {
                Box(
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ),
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(64.dp).align(Alignment.Center),
                    )
                }
            }
        }
        item {
            Text(
                "A private reading room for people you trust.",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = Fraunces,
            )
        }
        item {
            Text(
                "Share DRM-free books with your household while every download remains a normal, offline book in My Library. Your reading progress, notes, and highlights stay private.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (emailSentTo == null) {
            item {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { onSendMagicLink(email) },
                    enabled = email.contains('@') && !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Send secure sign-in link")
                }
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Check your email", style = MaterialTheme.typography.titleLarge, fontFamily = Fraunces)
                        Text("We sent a one-time Vellum link to $emailSentTo. Open it on this device to continue.")
                        TextButton(onClick = { onSendMagicLink(emailSentTo) }, enabled = !loading) {
                            Text("Send again")
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Sign-in is only for Shared Libraries. My Library remains account-free.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InvitationReady(loading: Boolean, onJoin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.GroupAdd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text("Your invitation is ready", style = MaterialTheme.typography.headlineMedium, fontFamily = Fraunces)
        Spacer(Modifier.height(10.dp))
        Text(
            "Join the private library with this signed-in account. Only its catalogue is shared; your reading stays yours.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onJoin, enabled = !loading) { Text("Join library") }
    }
}

@Composable
private fun EmptyLibraries(email: String, onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(20.dp))
        Text("Begin a shared library", style = MaterialTheme.typography.headlineMedium, fontFamily = Fraunces)
        Spacer(Modifier.height(10.dp))
        Text(
            "Signed in as $email. Create one calm, private place for your household’s books.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) { Text("Create private library") }
    }
}

@Composable
private fun SharedCatalogue(
    state: SharedLibraryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDownload: (SharedPublication) -> Unit,
    onPublish: () -> Unit,
) {
    val downloaded = state.localBooks.mapNotNull { it.sourcePublicationUuid }.toSet()
    val accessToken = (state.account as? SharedAccountState.SignedIn)?.session?.accessToken.orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = if (maxWidth >= 700.dp) 40.dp else 20.dp
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = horizontal, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.activeLibrary?.description?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search title, author, genre…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilledTonalButton(onClick = onSearch) { Text("Search") }
                    }
                    item {
                        Text(
                            "${state.publications.size} available",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            if (state.publications.isEmpty() && !state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (state.searchQuery.isBlank()) "The shelves are waiting." else "No books match that search.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = Fraunces,
                        textAlign = TextAlign.Center,
                    )
                    if (state.searchQuery.isBlank() && state.activeLibrary?.role?.canPublish == true) {
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onPublish) { Text("Publish the first books") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = horizontal, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(state.publications, key = { it.uuid }) { publication ->
                        PublicationCard(
                            publication = publication,
                            downloaded = publication.uuid in downloaded,
                            busy = state.transfer?.publicationUuid == publication.uuid,
                            accessToken = accessToken,
                            onDownload = { onDownload(publication) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicationCard(
    publication: SharedPublication,
    downloaded: Boolean,
    busy: Boolean,
    accessToken: String,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val coverRequest = remember(publication.libraryUuid, publication.uuid, accessToken) {
        ImageRequest.Builder(context)
            .data(
                "${BuildConfig.SHARED_BOOKS_API_URL.trimEnd('/')}/v1/libraries/" +
                    "${publication.libraryUuid}/publications/${publication.uuid}/cover",
            )
            .addHeader("Authorization", "Bearer $accessToken")
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(true)
            .build()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.67f),
        ) {
            Box(
                Modifier.background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                ),
            ) {
                Text(
                    publication.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = Fraunces,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.Center),
                )
                AsyncImage(
                    model = coverRequest,
                    contentDescription = "Cover of ${publication.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(topStart = 8.dp),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text(
                        publication.format.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            publication.title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = Fraunces,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            publication.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (downloaded) {
            Text(
                "In My Library",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            FilledTonalButton(
                onClick = onDownload,
                enabled = !busy && publication.status == "ready",
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(" Download and add", maxLines = 1)
            }
        }
    }
}

@Composable
private fun CreateLibraryDialog(
    loading: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("Family Library") }
    var description by rememberSaveable { mutableStateOf("Books we keep and share together.") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a private library", fontFamily = Fraunces) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Library name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only people you invite can find or open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description.ifBlank { null }) },
                enabled = name.isNotBlank() && !loading,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InvitationDialog(
    libraryName: String,
    invitation: SharedInvitation?,
    loading: Boolean,
    onCreate: (String, SharedLibraryRole) -> Unit,
    onShare: (SharedInvitation) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (invitation == null) "Invite to $libraryName" else "Invitation ready",
                fontFamily = Fraunces,
            )
        },
        text = {
            if (invitation == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Their email address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "This invitation is locked to that email, expires in seven days, and grants reading and download access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Share the private link with ${invitation.inviteeEmail}. They will sign in once and return directly to $libraryName.",
                )
            }
        },
        confirmButton = {
            if (invitation == null) {
                Button(
                    onClick = { onCreate(email, SharedLibraryRole.READER) },
                    enabled = email.contains('@') && !loading,
                ) { Text("Create invitation") }
            } else {
                Button(onClick = { onShare(invitation) }) { Text("Share link") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PublishBooksDialog(
    books: List<BookEntity>,
    selected: Set<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onPublish: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish from My Library", fontFamily = Fraunces) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose one or more books. Vellum carries across the title, author, category, genres, series, and cover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (books.isEmpty()) {
                    Text("My Library has no books yet.")
                } else {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(books, key = { it.uuid }) { book ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(book.uuid) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(width = 38.dp, height = 54.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(book.title.take(1).uppercase(), fontFamily = Fraunces)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        book.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (book.uuid in selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    if (book.uuid in selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(5.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onPublish,
                enabled = selected.isNotEmpty() && !loading,
            ) { Text("Publish ${selected.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TransferCard(transfer: SharedTransferProgress, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(20.dp).widthIn(max = 520.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(transfer.message, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NotConfigured() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Shared Libraries are being prepared", style = MaterialTheme.typography.headlineMedium, fontFamily = Fraunces)
        Spacer(Modifier.height(10.dp))
        Text(
            "This build does not yet contain the private service configuration. My Library is unaffected.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
