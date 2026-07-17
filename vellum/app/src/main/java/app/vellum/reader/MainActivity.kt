package app.vellum.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.vellum.reader.comic.ComicReaderScreen
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.theme.LocalNavAnimatedVisibilityScope
import app.vellum.reader.core.theme.LocalSharedTransitionScope
import app.vellum.reader.core.theme.VellumTheme
import app.vellum.reader.home.ReadingScreen
import app.vellum.reader.insights.InsightsScreen
import app.vellum.reader.library.BookImporter
import app.vellum.reader.library.LibraryScreen
import app.vellum.reader.notes.NotesScreen
import app.vellum.reader.pdf.PdfReaderScreen
import app.vellum.reader.reader.ui.ReaderScreen
import app.vellum.reader.search.SearchScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: the activity's intent survives rotation and
        // process recreation, which would re-run the import on every rebuild.
        if (savedInstanceState == null) handleImportIntent(intent)
        setContent {
            VellumTheme {
                VellumNavHost()
            }
        }
    }

    /** "Open with Vellum" (VIEW) and share-to-Vellum (SEND) EPUB imports. */
    private fun handleImportIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            val app = application as VellumApp
            // Guarded: appScope has no exception handler, so an importer bug
            // must degrade to a notice, never a process crash.
            app.appScope.launch {
                try {
                    BookImporter(app).importFromUri(uri)
                } catch (e: Exception) {
                    android.util.Log.e("VellumImport", "Intent import failed for $uri", e)
                    app.importNotices.tryEmit("Couldn't import that file")
                }
            }
        }
    }
}

private data class VellumTab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    VellumTab("library", "Library", Icons.AutoMirrored.Outlined.MenuBook),
    VellumTab("reading", "Reading", Icons.Outlined.AutoStories),
    VellumTab("notes", "Notes", Icons.Outlined.FormatQuote),
    VellumTab("insights", "Insights", Icons.Outlined.Insights),
)

private fun readerRouteFor(format: String, uuid: String): String = when (format) {
    "pdf" -> "pdf/$uuid"
    "cbz", "cbr", "comic-epub" -> "comic/$uuid"
    else -> "reader/$uuid"
}

/** Exposes the destination's transition scope so shared elements can animate. */
@Composable
private fun AnimatedContentScope.ProvideNavAnimation(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this, content = content)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun VellumNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openBook: (BookEntity) -> Unit = { book ->
        navController.navigate(readerRouteFor(book.format, book.uuid))
    }

    // The nav bar only exists on the four top-level tabs; readers and search
    // keep the full screen. contentWindowInsets is zeroed so those full-bleed
    // destinations receive no phantom bottom padding.
    SharedTransitionLayout {
    CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (Tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo("library") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            // Calm fades — motion comes from the cover container transform.
            enterTransition = { fadeIn(tween(260)) },
            exitTransition = { fadeOut(tween(260)) },
            popEnterTransition = { fadeIn(tween(260)) },
            popExitTransition = { fadeOut(tween(260)) },
        ) {
            composable("library") {
                ProvideNavAnimation {
                    LibraryScreen(
                        onOpenBook = openBook,
                        onOpenSearch = { navController.navigate("search") },
                    )
                }
            }
            composable("reading") {
                ProvideNavAnimation {
                    ReadingScreen(onOpenBook = openBook)
                }
            }
            composable("notes") {
                NotesScreen(
                    onOpenPassage = { uuid, chapter, offset ->
                        navController.navigate("reader/$uuid?chapter=$chapter&offset=$offset")
                    },
                )
            }
            composable("insights") {
                InsightsScreen()
            }
            composable(
                route = "comic/{bookUuid}",
                arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
            ) { entry ->
                val bookUuid = entry.arguments?.getString("bookUuid") ?: return@composable
                ProvideNavAnimation {
                    ComicReaderScreen(bookUuid = bookUuid, onBack = { navController.popBackStack() })
                }
            }
            composable(
                route = "pdf/{bookUuid}",
                arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
            ) { entry ->
                val bookUuid = entry.arguments?.getString("bookUuid") ?: return@composable
                ProvideNavAnimation {
                    PdfReaderScreen(bookUuid = bookUuid, onBack = { navController.popBackStack() })
                }
            }
            composable(
                route = "search?bookUuid={bookUuid}",
                arguments = listOf(navArgument("bookUuid") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                SearchScreen(
                    scopeBookUuid = entry.arguments?.getString("bookUuid"),
                    onOpenBook = { uuid, format -> navController.navigate(readerRouteFor(format, uuid)) },
                    onOpenPassage = { uuid, chapter, offset ->
                        navController.navigate("reader/$uuid?chapter=$chapter&offset=$offset")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "reader/{bookUuid}?chapter={chapter}&offset={offset}",
                arguments = listOf(
                    navArgument("bookUuid") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("offset") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { entry ->
                val bookUuid = entry.arguments?.getString("bookUuid") ?: return@composable
                ProvideNavAnimation {
                    ReaderScreen(
                        bookUuid = bookUuid,
                        initialChapter = entry.arguments?.getInt("chapter") ?: -1,
                        initialOffset = entry.arguments?.getInt("offset") ?: -1,
                        onBack = { navController.popBackStack() },
                        onSearchInBook = { uuid -> navController.navigate("search?bookUuid=$uuid") },
                    )
                }
            }
        }
    }
    }
    }
}
