package app.vellum.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.vellum.reader.comic.ComicReaderScreen
import app.vellum.reader.core.theme.VellumTheme
import app.vellum.reader.insights.InsightsScreen
import app.vellum.reader.library.BookImporter
import app.vellum.reader.library.LibraryScreen
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
            app.appScope.launch { BookImporter(app).importFromUri(uri) }
        }
    }
}

@Composable
private fun VellumNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { book ->
                    navController.navigate(
                        when (book.format) {
                            "pdf" -> "pdf/${book.uuid}"
                            "cbz", "cbr", "comic-epub" -> "comic/${book.uuid}"
                            else -> "reader/${book.uuid}"
                        },
                    )
                },
                onOpenSearch = { navController.navigate("search") },
                onOpenInsights = { navController.navigate("insights") },
            )
        }
        composable("insights") {
            InsightsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "comic/{bookUuid}",
            arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
        ) { entry ->
            val bookUuid = entry.arguments?.getString("bookUuid") ?: return@composable
            ComicReaderScreen(bookUuid = bookUuid, onBack = { navController.popBackStack() })
        }
        composable(
            route = "pdf/{bookUuid}",
            arguments = listOf(navArgument("bookUuid") { type = NavType.StringType }),
        ) { entry ->
            val bookUuid = entry.arguments?.getString("bookUuid") ?: return@composable
            PdfReaderScreen(bookUuid = bookUuid, onBack = { navController.popBackStack() })
        }
        composable(
            route = "search?bookUuid={bookUuid}",
            arguments = listOf(navArgument("bookUuid") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            SearchScreen(
                scopeBookUuid = entry.arguments?.getString("bookUuid"),
                onOpenBook = { uuid, format ->
                    navController.navigate(
                        when (format) {
                            "pdf" -> "pdf/$uuid"
                            "cbz", "cbr", "comic-epub" -> "comic/$uuid"
                            else -> "reader/$uuid"
                        },
                    )
                },
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
