@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.vellum.reader.shared

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import app.vellum.reader.librarian.AiCollectionProposal
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import kotlinx.coroutines.delay

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
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var detailPublication by remember { mutableStateOf<SharedPublication?>(null) }
    var collectionToEdit by remember { mutableStateOf<SharedCollection?>(null) }
    var collectionEditorOpen by remember { mutableStateOf(false) }
    var aiReviewOpen by remember { mutableStateOf(false) }
    var archiveConfirmationOpen by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { accountOpen = true }) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Shared Library account")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (
                state.activeLibrary?.role?.canPublish == true &&
                state.allPublications.isNotEmpty() &&
                state.selectedPublicationUuids.isEmpty() &&
                state.catalogueTab != SharedCatalogueTab.ARCHIVED
            ) {
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
                    onTabChange = viewModel::setCatalogueTab,
                    onViewChange = viewModel::setCatalogueView,
                    onSortChange = viewModel::setCatalogueSort,
                    onFilterChange = viewModel::setCatalogueFilter,
                    onOpenCollection = viewModel::openCollection,
                    onClearCollectionFilter = viewModel::clearCollectionFilter,
                    onToggleSelection = viewModel::togglePublicationSelection,
                    onSelectAll = viewModel::selectAllVisiblePublications,
                    onClearSelection = viewModel::clearPublicationSelection,
                    onOpenPublication = { detailPublication = it },
                    onCreateCollection = {
                        collectionToEdit = null
                        collectionEditorOpen = true
                    },
                    onEditCollection = {
                        collectionToEdit = it
                        collectionEditorOpen = true
                    },
                    onArchiveCollection = viewModel::archiveCollection,
                    onArchiveSelected = { archiveConfirmationOpen = true },
                    onPrepareAi = {
                        viewModel.prepareAiCollectionReview { aiReviewOpen = true }
                    },
                    onRestore = viewModel::restorePublication,
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
            sharedBookUuids = state.sharedLocalBookUuids,
            selected = state.selectedLocalBooks,
            loading = state.loading,
            matchingSharedBooks = state.matchingSharedBooks,
            onToggle = viewModel::toggleLocalBook,
            onSelect = viewModel::selectLocalBooks,
            onClearSelection = viewModel::clearLocalSelection,
            onDismiss = {
                publishOpen = false
                viewModel.clearLocalSelection()
            },
            onPublish = { viewModel.publishSelected { publishOpen = false } },
        )
    }
    if (accountOpen) {
        val account = state.account as? SharedAccountState.SignedIn
        if (account != null) {
            SharedLibraryAccountDialog(
                email = account.session.email,
                loading = state.loading,
                onDismiss = { accountOpen = false },
                onSignOut = {
                    accountOpen = false
                    viewModel.signOut()
                },
            )
        }
    }
    detailPublication?.let { publication ->
        SharedPublicationDialog(
            publication = publication,
            canManage = state.activeLibrary?.role?.canPublish == true,
            downloaded = state.localBooks.any { it.sourcePublicationUuid == publication.uuid },
            loading = state.loading,
            onDismiss = { detailPublication = null },
            onDownload = { viewModel.download(publication) },
            onSave = { edit ->
                viewModel.updatePublication(publication, edit) { detailPublication = null }
            },
            onArchive = {
                viewModel.clearPublicationSelection()
                viewModel.togglePublicationSelection(publication.uuid)
                detailPublication = null
                archiveConfirmationOpen = true
            },
        )
    }
    if (collectionEditorOpen) {
        SharedCollectionEditorDialog(
            collection = collectionToEdit,
            publications = state.allPublications,
            initiallySelected = collectionToEdit?.publicationUuids?.toSet()
                ?: state.selectedPublicationUuids,
            loading = state.loading,
            onDismiss = {
                collectionEditorOpen = false
                collectionToEdit = null
            },
            onSave = { name, kind, description, publicationUuids ->
                viewModel.saveCollection(
                    collectionUuid = collectionToEdit?.uuid,
                    name = name,
                    kind = kind,
                    description = description,
                    publicationUuids = publicationUuids,
                ) {
                    collectionEditorOpen = false
                    collectionToEdit = null
                }
            },
        )
    }
    if (archiveConfirmationOpen) {
        val count = state.selectedPublicationUuids.size
        AlertDialog(
            onDismissRequest = { archiveConfirmationOpen = false },
            icon = { Icon(Icons.Filled.Archive, contentDescription = null) },
            title = { Text("Move to Archived?", fontFamily = Fraunces) },
            text = {
                Text(
                    "$count ${if (count == 1) "book" else "books"} will disappear from the shared shelves. " +
                        "Copies already downloaded to a device will remain there, and the shared books can be restored later.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.archivePublications { archiveConfirmationOpen = false }
                    },
                    enabled = count > 0 && !state.loading,
                ) { Text("Move to Archived") }
            },
            dismissButton = {
                TextButton(onClick = { archiveConfirmationOpen = false }) { Text("Cancel") }
            },
        )
    }
    if (aiReviewOpen) {
        SharedAiReviewDialog(
            proposals = state.aiCollectionProposals,
            publications = state.allPublications,
            loading = state.loading,
            onDismiss = {
                aiReviewOpen = false
                viewModel.clearAiCollectionReview()
            },
            onApply = { names ->
                viewModel.applyAiCollections(names) { aiReviewOpen = false }
            },
        )
    }
}

@Composable
private fun SharedLibraryAccountDialog(
    email: String,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        },
        title = { Text("Shared Library account", fontFamily = Fraunces) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Signed in as",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    email,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Signing out only disconnects Shared Libraries. Books already downloaded to My Library remain on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSignOut,
                enabled = !loading,
            ) {
                Text(
                    "Sign out",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SignedOutPanel(
    loading: Boolean,
    emailSentTo: String?,
    onSendMagicLink: (String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var retryAvailableAt by rememberSaveable { mutableStateOf(0L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAvailableAt) {
        while (retryAvailableAt > clock) {
            delay(1_000)
            clock = System.currentTimeMillis()
        }
    }
    val retrySeconds = ((retryAvailableAt - clock).coerceAtLeast(0L) + 999L) / 1_000L
    val requestLink: (String) -> Unit = { address ->
        clock = System.currentTimeMillis()
        retryAvailableAt = clock + MAGIC_LINK_RETRY_DELAY_MILLIS
        onSendMagicLink(address)
    }
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
                    onClick = { requestLink(email) },
                    enabled = email.contains('@') && !loading && retrySeconds == 0L,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(
                        if (retrySeconds > 0L) "Try again in ${retrySeconds}s" else "Send secure sign-in link",
                    )
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
                        TextButton(
                            onClick = { requestLink(emailSentTo) },
                            enabled = !loading && retrySeconds == 0L,
                        ) {
                            Text(if (retrySeconds > 0L) "Send again in ${retrySeconds}s" else "Send again")
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
private fun LegacySharedCatalogue(
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
                        LegacyPublicationCard(
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
private fun LegacyPublicationCard(
    publication: SharedPublication,
    downloaded: Boolean,
    busy: Boolean,
    accessToken: String,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val coverCacheKey = remember(publication.libraryUuid, publication.uuid, publication.sha256) {
        "shared-cover:${publication.libraryUuid}:${publication.uuid}:${publication.sha256}"
    }
    val coverRequest = remember(coverCacheKey, accessToken) {
        ImageRequest.Builder(context)
            .data(
                "${BuildConfig.SHARED_BOOKS_API_URL.trimEnd('/')}/v1/libraries/" +
                    "${publication.libraryUuid}/publications/${publication.uuid}/cover" +
                    "?v=${publication.sha256}",
            )
            .addHeader("Authorization", "Bearer $accessToken")
            .memoryCacheKey(coverCacheKey)
            .diskCacheKey(coverCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(120)
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
private fun SharedCatalogue(
    state: SharedLibraryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDownload: (SharedPublication) -> Unit,
    onPublish: () -> Unit,
    onTabChange: (SharedCatalogueTab) -> Unit,
    onViewChange: (SharedCatalogueView) -> Unit,
    onSortChange: (SharedCatalogueSort) -> Unit,
    onFilterChange: (SharedCatalogueFilter) -> Unit,
    onOpenCollection: (SharedCollection) -> Unit,
    onClearCollectionFilter: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenPublication: (SharedPublication) -> Unit,
    onCreateCollection: () -> Unit,
    onEditCollection: (SharedCollection) -> Unit,
    onArchiveCollection: (SharedCollection) -> Unit,
    onArchiveSelected: () -> Unit,
    onPrepareAi: () -> Unit,
    onRestore: (SharedPublication) -> Unit,
) {
    val downloaded = state.localBooks.mapNotNull { it.sourcePublicationUuid }.toSet()
    val accessToken = (state.account as? SharedAccountState.SignedIn)?.session?.accessToken.orEmpty()
    val canManage = state.activeLibrary?.role?.canPublish == true
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = if (maxWidth >= 700.dp) 40.dp else 20.dp
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = horizontal, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                state.activeLibrary?.description?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        SharedCatalogueTab.entries.filter {
                            it != SharedCatalogueTab.ARCHIVED || canManage
                        },
                        key = { it.name },
                    ) { tab ->
                        val count = when (tab) {
                            SharedCatalogueTab.BOOKS -> state.allPublications.size
                            SharedCatalogueTab.COLLECTIONS -> state.collections.size
                            SharedCatalogueTab.ARCHIVED -> state.archivedPublications.size
                        }
                        FilterChip(
                            selected = state.catalogueTab == tab,
                            onClick = { onTabChange(tab) },
                            label = { Text("${tab.label} $count") },
                        )
                    }
                }
            }
            when (state.catalogueTab) {
                SharedCatalogueTab.BOOKS -> SharedBooksPane(
                    state = state,
                    downloaded = downloaded,
                    accessToken = accessToken,
                    canManage = canManage,
                    horizontalPadding = horizontal,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onViewChange = onViewChange,
                    onSortChange = onSortChange,
                    onFilterChange = onFilterChange,
                    onClearCollectionFilter = onClearCollectionFilter,
                    onToggleSelection = onToggleSelection,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onOpenPublication = onOpenPublication,
                    onDownload = onDownload,
                    onCreateCollection = onCreateCollection,
                    onArchiveSelected = onArchiveSelected,
                    onPublish = onPublish,
                )

                SharedCatalogueTab.COLLECTIONS -> {
                    Column(Modifier.fillMaxSize()) {
                        if (canManage) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = horizontal),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                item {
                                    FilledTonalButton(onClick = onPrepareAi, enabled = !state.loading) {
                                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                                        Text(" AI Librarian")
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = onCreateCollection) {
                                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                                        Text(" New collection")
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (state.collections.isEmpty()) {
                            SharedEmptyState(
                                title = "No shared collections yet",
                                message = if (canManage) {
                                    "Create one yourself or let the AI Librarian discover series, authors, and thoughtful themes."
                                } else {
                                    "A librarian can shape series, author, and thematic shelves here."
                                },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = horizontal, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(state.collections, key = { it.uuid }) { collection ->
                                    SharedCollectionCard(
                                        collection = collection,
                                        publications = state.allPublications,
                                        accessToken = accessToken,
                                        canManage = canManage,
                                        onOpen = { onOpenCollection(collection) },
                                        onEdit = { onEditCollection(collection) },
                                        onArchive = { onArchiveCollection(collection) },
                                    )
                                }
                            }
                        }
                    }
                }

                SharedCatalogueTab.ARCHIVED -> {
                    if (state.archivedPublications.isEmpty()) {
                        SharedEmptyState(
                            title = "Archived is empty",
                            message = "Books removed from the shared shelves will remain safely restorable here.",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = horizontal, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Text(
                                    "Downloaded copies remain on their devices when a shared book is archived.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(state.archivedPublications, key = { it.uuid }) { publication ->
                                SharedArchivedRow(
                                    publication = publication,
                                    accessToken = accessToken,
                                    loading = state.loading,
                                    onRestore = { onRestore(publication) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedBooksPane(
    state: SharedLibraryUiState,
    downloaded: Set<String>,
    accessToken: String,
    canManage: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onViewChange: (SharedCatalogueView) -> Unit,
    onSortChange: (SharedCatalogueSort) -> Unit,
    onFilterChange: (SharedCatalogueFilter) -> Unit,
    onClearCollectionFilter: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenPublication: (SharedPublication) -> Unit,
    onDownload: (SharedPublication) -> Unit,
    onCreateCollection: () -> Unit,
    onArchiveSelected: () -> Unit,
    onPublish: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = {
                    onQueryChange(it)
                    onSearch()
                },
                placeholder = { Text("Search title, author, series, collection…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SharedCatalogueFilter.entries, key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.catalogueFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
                item {
                    Box {
                        OutlinedButton(onClick = { sortMenuOpen = true }) {
                            Text(state.catalogueSort.label)
                            Icon(Icons.Filled.ExpandMore, contentDescription = "Change sorting")
                        }
                        DropdownMenu(sortMenuOpen, { sortMenuOpen = false }) {
                            SharedCatalogueSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    leadingIcon = {
                                        if (state.catalogueSort == sort) Icon(Icons.Filled.Check, null)
                                    },
                                    onClick = {
                                        sortMenuOpen = false
                                        onSortChange(sort)
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    FilterChip(
                        selected = state.catalogueView == SharedCatalogueView.GRID,
                        onClick = { onViewChange(SharedCatalogueView.GRID) },
                        leadingIcon = { Icon(Icons.Filled.GridView, null) },
                        label = { Text("Grid") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.catalogueView == SharedCatalogueView.LIST,
                        onClick = { onViewChange(SharedCatalogueView.LIST) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        label = { Text("List") },
                    )
                }
            }
            state.collections.firstOrNull { it.uuid == state.activeCollectionUuid }?.let { collection ->
                FilterChip(
                    selected = true,
                    onClick = onClearCollectionFilter,
                    label = { Text(collection.name) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Show all books") },
                )
            }
            if (state.selectedPublicationUuids.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            IconButton(onClick = onClearSelection) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                            }
                        }
                        item {
                            Text(
                                "${state.selectedPublicationUuids.size} selected",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        item {
                            TextButton(onClick = onSelectAll) {
                                Icon(Icons.Filled.SelectAll, null)
                                Text(" All")
                            }
                        }
                        item {
                            TextButton(onClick = onCreateCollection) {
                                Icon(Icons.AutoMirrored.Filled.LibraryBooks, null)
                                Text(" Collection")
                            }
                        }
                        item {
                            TextButton(onClick = onArchiveSelected) {
                                Icon(Icons.Filled.Archive, null)
                                Text(" Archive")
                            }
                        }
                    }
                }
            }
            Text(
                "${state.publications.size} ${if (state.publications.size == 1) "book" else "books"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (state.publications.isEmpty() && !state.loading) {
            SharedEmptyState(
                title = if (state.allPublications.isEmpty()) "The shelves are waiting" else "No books match",
                message = if (state.allPublications.isEmpty()) {
                    "Publish the first books to begin this household catalogue."
                } else {
                    "Try clearing a filter, collection, or search."
                },
                action = if (state.allPublications.isEmpty() && canManage) {
                    { Button(onClick = onPublish) { Text("Publish the first books") } }
                } else {
                    null
                },
            )
        } else if (state.catalogueView == SharedCatalogueView.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(state.publications, key = { it.uuid }) { publication ->
                    ManagedPublicationCard(
                        publication = publication,
                        downloaded = publication.uuid in downloaded,
                        busy = state.transfer?.publicationUuid == publication.uuid,
                        selected = publication.uuid in state.selectedPublicationUuids,
                        selectionMode = state.selectedPublicationUuids.isNotEmpty(),
                        canManage = canManage,
                        accessToken = accessToken,
                        onClick = {
                            if (state.selectedPublicationUuids.isNotEmpty()) {
                                onToggleSelection(publication.uuid)
                            } else {
                                onOpenPublication(publication)
                            }
                        },
                        onLongClick = { onToggleSelection(publication.uuid) },
                        onDownload = { onDownload(publication) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.publications, key = { it.uuid }) { publication ->
                    PublicationListRow(
                        publication = publication,
                        downloaded = publication.uuid in downloaded,
                        busy = state.transfer?.publicationUuid == publication.uuid,
                        selected = publication.uuid in state.selectedPublicationUuids,
                        selectionMode = state.selectedPublicationUuids.isNotEmpty(),
                        canManage = canManage,
                        accessToken = accessToken,
                        onClick = {
                            if (state.selectedPublicationUuids.isNotEmpty()) {
                                onToggleSelection(publication.uuid)
                            } else {
                                onOpenPublication(publication)
                            }
                        },
                        onLongClick = { onToggleSelection(publication.uuid) },
                        onDownload = { onDownload(publication) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagedPublicationCard(
    publication: SharedPublication,
    downloaded: Boolean,
    busy: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    canManage: Boolean,
    accessToken: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = if (canManage) onLongClick else null,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 2.dp,
            border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.67f),
        ) {
            Box {
                SharedPublicationCover(publication, accessToken, Modifier.fillMaxSize())
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
                if (selected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(7.dp).size(19.dp),
                        )
                    }
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
            Text("Ready to read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else if (!selectionMode) {
            FilledTonalButton(
                onClick = onDownload,
                enabled = !busy && publication.status == "ready",
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CloudDownload, null, Modifier.size(17.dp))
                Text(" Download and add", maxLines = 1)
            }
        }
    }
}

@Composable
private fun PublicationListRow(
    publication: SharedPublication,
    downloaded: Boolean,
    busy: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    canManage: Boolean,
    accessToken: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = if (canManage) onLongClick else null,
        ),
    ) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SharedPublicationCover(publication, accessToken, Modifier.width(76.dp).aspectRatio(0.67f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    publication.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = Fraunces,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    publication.author,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                (publication.seriesName ?: publication.collectionNames.firstOrNull())?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
                if (downloaded) {
                    Text("Ready to read", color = MaterialTheme.colorScheme.primary)
                } else if (!selectionMode) {
                    FilledTonalButton(onClick = onDownload, enabled = !busy) {
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(17.dp))
                        Text(" Download")
                    }
                }
            }
            if (selected) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SharedPublicationCover(
    publication: SharedPublication,
    accessToken: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cacheKey = remember(publication.libraryUuid, publication.uuid, publication.sha256) {
        "shared-cover:${publication.libraryUuid}:${publication.uuid}:${publication.sha256}"
    }
    val request = remember(cacheKey, accessToken) {
        ImageRequest.Builder(context)
            .data(
                "${BuildConfig.SHARED_BOOKS_API_URL.trimEnd('/')}/v1/libraries/" +
                    "${publication.libraryUuid}/publications/${publication.uuid}/cover?v=${publication.sha256}",
            )
            .addHeader("Authorization", "Bearer $accessToken")
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(120)
            .build()
    }
    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ),
            RoundedCornerShape(8.dp),
        ),
    ) {
        Text(
            publication.title.take(1).uppercase(),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = Fraunces,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
            modifier = Modifier.align(Alignment.Center),
        )
        AsyncImage(
            model = request,
            contentDescription = "Cover of ${publication.title}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SharedCollectionCard(
    collection: SharedCollection,
    publications: List<SharedPublication>,
    accessToken: String,
    canManage: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    val books = collection.publicationUuids.mapNotNull { uuid -> publications.firstOrNull { it.uuid == uuid } }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (books.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.width(72.dp).aspectRatio(0.8f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.padding(20.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    books.take(2).forEach {
                        SharedPublicationCover(it, accessToken, Modifier.width(44.dp).aspectRatio(0.67f))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(collection.name, style = MaterialTheme.typography.titleLarge, fontFamily = Fraunces)
                Text(
                    "${collection.bookCount} ${if (collection.bookCount == 1) "book" else "books"} · " +
                        collection.kind.replaceFirstChar(Char::uppercase),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                collection.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canManage) {
                    Row {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(17.dp))
                            Text(" Edit")
                        }
                        TextButton(onClick = onArchive) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedArchivedRow(
    publication: SharedPublication,
    accessToken: String,
    loading: Boolean,
    onRestore: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SharedPublicationCover(publication, accessToken, Modifier.width(64.dp).aspectRatio(0.67f))
            Column(Modifier.weight(1f)) {
                Text(publication.title, style = MaterialTheme.typography.titleMedium, fontFamily = Fraunces)
                Text(publication.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRestore, enabled = !loading) {
                Icon(Icons.Filled.Restore, null, Modifier.size(18.dp))
                Text(" Restore")
            }
        }
    }
}

@Composable
private fun SharedEmptyState(
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontFamily = Fraunces, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
private fun SharedPublicationDialog(
    publication: SharedPublication,
    canManage: Boolean,
    downloaded: Boolean,
    loading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onSave: (SharedPublicationEdit) -> Unit,
    onArchive: () -> Unit,
) {
    var editing by remember(publication.uuid) { mutableStateOf(false) }
    var title by remember(publication.uuid) { mutableStateOf(publication.title) }
    var author by remember(publication.uuid) { mutableStateOf(publication.author) }
    var category by remember(publication.uuid) { mutableStateOf(publication.category) }
    var genres by remember(publication.uuid) { mutableStateOf(publication.genres.joinToString(", ")) }
    var seriesName by remember(publication.uuid) { mutableStateOf(publication.seriesName.orEmpty()) }
    var seriesIndex by remember(publication.uuid) {
        mutableStateOf(publication.seriesIndex?.toString().orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (editing) "Edit shared book" else publication.title, fontFamily = Fraunces)
        },
        text = {
            if (editing) {
                LazyColumn(
                    modifier = Modifier.height(480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text("Author") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text("Category", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                listOf("Fiction", "Non-fiction", "Comics & Manga", "Essays & Poetry"),
                            ) { choice ->
                                FilterChip(
                                    selected = category == choice,
                                    onClick = { category = if (category == choice) null else choice },
                                    label = { Text(choice) },
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = genres,
                            onValueChange = { genres = it },
                            label = { Text("Genres, separated by commas") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = seriesName,
                            onValueChange = { seriesName = it },
                            label = { Text("Series") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = seriesIndex,
                            onValueChange = { seriesIndex = it },
                            label = { Text("Book number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(publication.author, style = MaterialTheme.typography.titleMedium)
                    publication.seriesName?.let {
                        Text(
                            buildString {
                                append(it)
                                publication.seriesIndex?.let { index -> append(" · Book ${index.toString().removeSuffix(".0")}") }
                            },
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    val descriptors = (listOfNotNull(publication.category) + publication.genres).distinct()
                    if (descriptors.isNotEmpty()) {
                        Text(descriptors.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (publication.collectionNames.isNotEmpty()) {
                        Text(
                            "Collections: ${publication.collectionNames.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (downloaded) {
                            "Already available in My Library."
                        } else {
                            "Download adds a private reading copy to this device."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (editing) {
                Button(
                    onClick = {
                        onSave(
                            SharedPublicationEdit(
                                title = title,
                                author = author,
                                category = category,
                                genres = genres.split(',').map(String::trim).filter(String::isNotBlank).distinct(),
                                seriesName = seriesName.ifBlank { null },
                                seriesIndex = seriesIndex.toFloatOrNull(),
                            ),
                        )
                    },
                    enabled = title.isNotBlank() && author.isNotBlank() && !loading,
                ) { Text("Save changes") }
            } else if (!downloaded) {
                Button(onClick = onDownload, enabled = !loading) {
                    Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                    Text(" Download")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (editing) {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            } else if (canManage) {
                Row {
                    TextButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(17.dp))
                        Text(" Edit")
                    }
                    TextButton(onClick = onArchive) {
                        Text("Archive", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun SharedCollectionEditorDialog(
    collection: SharedCollection?,
    publications: List<SharedPublication>,
    initiallySelected: Set<String>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, Set<String>) -> Unit,
) {
    var name by remember(collection?.uuid) { mutableStateOf(collection?.name.orEmpty()) }
    var kind by remember(collection?.uuid) { mutableStateOf(collection?.kind ?: "manual") }
    var description by remember(collection?.uuid) { mutableStateOf(collection?.description.orEmpty()) }
    var selected by remember(collection?.uuid, initiallySelected) { mutableStateOf(initiallySelected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (collection == null) "New shared collection" else "Edit collection", fontFamily = Fraunces) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("manual", "series", "author", "theme")) { choice ->
                        FilterChip(
                            selected = kind == choice,
                            onClick = { kind = choice },
                            label = { Text(choice.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(280) },
                    label = { Text("Short description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${selected.size} selected", style = MaterialTheme.typography.labelLarge)
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(publications, key = { it.uuid }) { publication ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (publication.uuid in selected) {
                                        selected - publication.uuid
                                    } else {
                                        selected + publication.uuid
                                    }
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = publication.uuid in selected,
                                onCheckedChange = {
                                    selected = if (publication.uuid in selected) {
                                        selected - publication.uuid
                                    } else {
                                        selected + publication.uuid
                                    }
                                },
                            )
                            Column {
                                Text(publication.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    publication.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, kind, description.ifBlank { null }, selected) },
                enabled = name.isNotBlank() && !loading,
            ) { Text("Save collection") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SharedAiReviewDialog(
    proposals: List<AiCollectionProposal>,
    publications: List<SharedPublication>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var selected by remember(proposals) { mutableStateOf(proposals.map { it.name }.toSet()) }
    val publicationsById = remember(publications) { publications.associateBy { it.uuid } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.AutoAwesome, null) },
        title = { Text("The Librarian’s suggestions", fontFamily = Fraunces) },
        text = {
            if (proposals.isEmpty()) {
                Text("No new shared collections are needed right now.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Review before applying. Existing shared collections and downloaded books are never removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.height(420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(proposals, key = { "${it.kind}:${it.name}" }) { proposal ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selected = if (proposal.name in selected) {
                                        selected - proposal.name
                                    } else {
                                        selected + proposal.name
                                    }
                                },
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Checkbox(
                                        checked = proposal.name in selected,
                                        onCheckedChange = {
                                            selected = if (proposal.name in selected) {
                                                selected - proposal.name
                                            } else {
                                                selected + proposal.name
                                            }
                                        },
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(proposal.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${proposal.bookUuids.size} books · " +
                                                proposal.kind.wireValue.replaceFirstChar(Char::uppercase),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        Text(
                                            proposal.bookUuids.take(3).mapNotNull { publicationsById[it]?.title }
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            proposal.explanation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (proposals.isEmpty()) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(
                    onClick = { onApply(selected) },
                    enabled = selected.isNotEmpty() && !loading,
                ) { Text("Apply ${selected.size}") }
            }
        },
        dismissButton = {
            if (proposals.isNotEmpty()) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

private enum class PublishBookFilter(val label: String) {
    NOT_SHARED("Not shared"),
    SHARED("Already shared"),
    ALL("All"),
}

private enum class PublishBookSort(val label: String) {
    NEWEST("Newest added"),
    TITLE("Title A–Z"),
    AUTHOR("Author A–Z"),
}

@Composable
private fun PublishBooksDialog(
    books: List<BookEntity>,
    sharedBookUuids: Set<String>,
    selected: Set<String>,
    loading: Boolean,
    matchingSharedBooks: Boolean,
    onToggle: (String) -> Unit,
    onSelect: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
    onPublish: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PublishBookFilter.NOT_SHARED) }
    var sort by rememberSaveable { mutableStateOf(PublishBookSort.NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val notSharedCount = books.count { it.uuid !in sharedBookUuids }
    val sharedCount = books.size - notSharedCount
    val visibleBooks = remember(books, sharedBookUuids, query, filter, sort) {
        val normalizedQuery = query.trim().lowercase()
        books.asSequence()
            .filter { book ->
                when (filter) {
                    PublishBookFilter.NOT_SHARED -> book.uuid !in sharedBookUuids
                    PublishBookFilter.SHARED -> book.uuid in sharedBookUuids
                    PublishBookFilter.ALL -> true
                }
            }
            .filter { book ->
                normalizedQuery.isBlank() ||
                    book.title.lowercase().contains(normalizedQuery) ||
                    book.author.lowercase().contains(normalizedQuery) ||
                    book.seriesName?.lowercase()?.contains(normalizedQuery) == true
            }
            .let { candidates ->
                when (sort) {
                    PublishBookSort.NEWEST -> candidates.sortedWith(
                        compareByDescending<BookEntity> { it.addedAt }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.uuid },
                    )
                    PublishBookSort.TITLE -> candidates.sortedWith(
                        compareBy<BookEntity> { it.title.lowercase() }
                            .thenBy { it.author.lowercase() }
                            .thenBy { it.uuid },
                    )
                    PublishBookSort.AUTHOR -> candidates.sortedWith(
                        compareBy<BookEntity> { it.author.lowercase() }
                            .thenBy { it.title.lowercase() }
                            .thenBy { it.uuid },
                    )
                }
            }
            .toList()
    }
    val selectableVisible = visibleBooks.mapNotNullTo(mutableSetOf()) {
        it.uuid.takeUnless(sharedBookUuids::contains)
    }
    val allVisibleSelected = selectableVisible.isNotEmpty() && selectableVisible.all(selected::contains)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish from My Library", fontFamily = Fraunces) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose books that have not been shared here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search title, author, or series") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = filter == PublishBookFilter.NOT_SHARED,
                            onClick = { filter = PublishBookFilter.NOT_SHARED },
                            label = { Text("Not shared ($notSharedCount)") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == PublishBookFilter.SHARED,
                            onClick = { filter = PublishBookFilter.SHARED },
                            label = { Text("Already shared ($sharedCount)") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == PublishBookFilter.ALL,
                            onClick = { filter = PublishBookFilter.ALL },
                            label = { Text("All (${books.size})") },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${visibleBooks.size} shown",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Text(sort.label)
                            Icon(Icons.Filled.ExpandMore, contentDescription = "Change book order")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            PublishBookSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sort = option
                                        sortMenuOpen = false
                                    },
                                    leadingIcon = {
                                        if (sort == option) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (selectableVisible.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected.isEmpty()) "Select individual books or all shown." else "${selected.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (allVisibleSelected) onClearSelection() else onSelect(selectableVisible)
                            },
                            enabled = !loading && !matchingSharedBooks,
                        ) {
                            Text(if (allVisibleSelected) "Clear selection" else "Select all shown")
                        }
                    }
                }
                if (matchingSharedBooks) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Checking what is already shared…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (books.isEmpty()) {
                    Text("My Library has no books yet.")
                } else if (visibleBooks.isEmpty()) {
                    Text(
                        when {
                            query.isNotBlank() -> "No books match this search."
                            filter == PublishBookFilter.NOT_SHARED -> "Everything in My Library is already shared here."
                            filter == PublishBookFilter.SHARED -> "No books from My Library have been shared here yet."
                            else -> "There are no books to show."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(330.dp)) {
                        items(visibleBooks, key = { it.uuid }) { book ->
                            val alreadyShared = book.uuid in sharedBookUuids
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = !alreadyShared && !loading && !matchingSharedBooks,
                                    ) { onToggle(book.uuid) }
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
                                if (alreadyShared) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            "Shared",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        )
                                    }
                                } else {
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
            }
        },
        confirmButton = {
            Button(
                onClick = onPublish,
                enabled = selected.isNotEmpty() && !loading && !matchingSharedBooks,
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

private const val MAGIC_LINK_RETRY_DELAY_MILLIS = 60_000L
