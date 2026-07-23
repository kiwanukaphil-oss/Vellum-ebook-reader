package app.vellum.reader

import android.app.Application
import androidx.room.Room
import app.vellum.reader.core.data.AnnotationDao
import app.vellum.reader.core.data.BookDao
import app.vellum.reader.core.data.CollectionDao
import app.vellum.reader.core.data.ComicPanelDao
import app.vellum.reader.core.data.PdfStrokeDao
import app.vellum.reader.core.data.SearchDao
import app.vellum.reader.core.data.SessionDao
import app.vellum.reader.core.data.VellumDatabase
import app.vellum.reader.core.settings.ReaderSettingsStore
import app.vellum.reader.epub.EpubLibraryOpener
import app.vellum.reader.nearby.NearbyTransferManager
import app.vellum.reader.reader.tts.ElevenLabsAudioCache
import app.vellum.reader.reader.tts.ElevenLabsClient
import app.vellum.reader.reader.tts.ElevenLabsCredentialStore
import app.vellum.reader.shared.SharedLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/**
 * Application-scoped singletons, wired by hand. If the dependency graph grows
 * past a screenful, revisit with Hilt; for a personal app this stays legible.
 */
class VellumApp : Application() {

    val database: VellumDatabase by lazy {
        Room.databaseBuilder(this, VellumDatabase::class.java, "vellum.db")
            .addMigrations(
                VellumDatabase.MIGRATION_1_2,
                VellumDatabase.MIGRATION_2_3,
                VellumDatabase.MIGRATION_3_4,
                VellumDatabase.MIGRATION_4_5,
                VellumDatabase.MIGRATION_5_6,
                VellumDatabase.MIGRATION_6_7,
                VellumDatabase.MIGRATION_7_8,
                VellumDatabase.MIGRATION_8_9,
            )
            .build()
    }

    val bookDao: BookDao get() = database.bookDao()
    val collectionDao: CollectionDao get() = database.collectionDao()
    val searchDao: SearchDao get() = database.searchDao()
    val annotationDao: AnnotationDao get() = database.annotationDao()
    val pdfStrokeDao: PdfStrokeDao get() = database.pdfStrokeDao()
    val comicPanelDao: ComicPanelDao get() = database.comicPanelDao()
    val sessionDao: SessionDao get() = database.sessionDao()

    val settingsStore: ReaderSettingsStore by lazy { ReaderSettingsStore(this) }

    val epubOpener: EpubLibraryOpener by lazy { EpubLibraryOpener(this) }

    /** Temporary encrypted transfers between Vellum devices on the same Wi-Fi. */
    val nearbyTransferManager: NearbyTransferManager by lazy { NearbyTransferManager(this) }

    /** Process-wide so paid synthesis requests are shared across reader recreation. */
    val elevenLabsClient: ElevenLabsClient by lazy { ElevenLabsClient() }
    val elevenLabsCredentials: ElevenLabsCredentialStore by lazy { ElevenLabsCredentialStore(this) }
    val elevenLabsCache: ElevenLabsAudioCache by lazy { ElevenLabsAudioCache(this) }

    /** Optional account and network boundary for invitation-only household libraries. */
    val sharedLibraryRepository: SharedLibraryRepository by lazy { SharedLibraryRepository(this) }

    private val sharedLibraryNavigation = Channel<Unit>(Channel.CONFLATED)
    val sharedLibraryNavigationEvents = sharedLibraryNavigation.receiveAsFlow()

    fun openSharedLibraries() {
        sharedLibraryNavigation.trySend(Unit)
    }

    /** App-private home of every imported book file. */
    val booksDir: File by lazy { File(filesDir, "books").apply { mkdirs() } }

    /** Extracted cover images, named <bookUuid>.webp. */
    val coversDir: File by lazy { File(filesDir, "covers").apply { mkdirs() } }

    /** Outlives any screen — used by share/open-with imports. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One-line user-facing notices ("Added X", "Couldn't import…") from any
     * import entry point — picker, share sheet, or open-with. The library
     * screen surfaces them as snackbars; emissions are dropped if nothing is
     * listening yet, which is fine for transient notices.
     */
    val importNotices = MutableSharedFlow<String>(extraBufferCapacity = 8)
}
