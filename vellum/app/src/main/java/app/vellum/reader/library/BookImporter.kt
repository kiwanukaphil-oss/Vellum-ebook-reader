package app.vellum.reader.library

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookTextFts
import app.vellum.reader.comic.CbrComicSource
import app.vellum.reader.comic.CbzComicSource
import app.vellum.reader.comic.ComicPageStore
import app.vellum.reader.epub.OpenedEpub
import app.vellum.reader.pdf.PdfPageRenderer
import app.vellum.reader.reader.html.HtmlBlockParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Brings EPUB files into the app-private books directory and registers them,
 * extracting the embedded cover art and building the full-text index as part
 * of import. Covers come from the EPUB itself — no network, no tracking —
 * matching the privacy-first constraint.
 */
class BookImporter(private val app: VellumApp) {

    private companion object {
        const val TAG = "VellumImport"
    }

    suspend fun importFromUri(uri: Uri): BookEntity? = withContext(Dispatchers.IO) {
        val temp = File(app.booksDir, "${UUID.randomUUID()}.tmp")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                app.importNotices.tryEmit("Couldn't read that file")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import copy failed for $uri", e)
            temp.delete()
            app.importNotices.tryEmit("Couldn't read that file")
            return@withContext null
        }
        importPreparedTemp(temp, displayNameFor(uri), organize = true)
    }

    /**
     * Imports a file produced by another trusted app component, such as the
     * same-Wi-Fi receiver. Copying first lets the transfer cache be cleaned as
     * soon as this method returns.
     */
    suspend fun importFromFile(
        source: File,
        suggestedTitle: String? = null,
        organize: Boolean = true,
    ): BookEntity? =
        withContext(Dispatchers.IO) {
            val temp = File(app.booksDir, "${UUID.randomUUID()}.tmp")
            try {
                source.inputStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import copy failed for ${source.name}", e)
                temp.delete()
                app.importNotices.tryEmit("Couldn't read the received file")
                return@withContext null
            }
            importPreparedTemp(temp, suggestedTitle ?: source.nameWithoutExtension, organize)
        }

    private suspend fun importPreparedTemp(
        temp: File,
        suggestedTitle: String?,
        organize: Boolean,
    ): BookEntity? {
        // Same-content dedup: re-sharing a file (or a rotation replaying the
        // launch intent) must not create a second library entry.
        alreadyImportedCopy(temp)?.let { existing ->
            temp.delete()
            app.importNotices.tryEmit("Already in your library: “${existing.title}”")
            return existing
        }
        // Sniff the real format — file pickers often report octet-stream.
        val magic = temp.inputStream().use { stream ->
            val bytes = ByteArray(4)
            val read = stream.read(bytes)
            if (read <= 0) ByteArray(0) else bytes.copyOf(read)
        }
        val magicText = magic.decodeToString()
        val extension = when {
            magicText.startsWith("%PDF") -> "pdf"
            magicText.startsWith("Rar!") -> "cbr"
            magicText.startsWith("PK") -> sniffZipKind(temp)
            else -> "epub"
        }
        val target = File(app.booksDir, "${temp.nameWithoutExtension}.$extension")
        if (!moveIntoPlace(temp, target)) {
            temp.delete()
            app.importNotices.tryEmit("Couldn't store that file")
            return null
        }
        val registered = registerFile(target, suggestedTitle)
        return if (registered == null) {
            target.delete()
            app.importNotices.tryEmit("Couldn't import that file — it may be damaged or unsupported")
            null
        } else {
            app.importNotices.tryEmit("Added “${registered.title}”")
            if (organize) app.aiLibrarian.organizeInBackground(registered.uuid)
            registered
        }
    }

    private fun moveIntoPlace(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val complete = target.length() == source.length()
            if (complete) source.delete() else target.delete()
            complete
        } catch (e: Exception) {
            Log.e(TAG, "Could not move imported file into place", e)
            target.delete()
            false
        }
    }

    /**
     * Finds a live library book whose file is byte-identical to [candidate].
     * Length check first narrows the field cheaply; only length twins are
     * byte-compared. Returns null when no registered, undeleted twin exists.
     */
    private suspend fun alreadyImportedCopy(candidate: File): BookEntity? {
        val twin = try {
            app.booksDir.listFiles()
                ?.filter { it.isFile && it != candidate && it.extension.lowercase() != "tmp" && it.length() == candidate.length() }
                ?.firstOrNull { contentsMatch(it, candidate) }
                ?: return null
        } catch (exception: Exception) {
            Log.w(TAG, "Could not complete duplicate-file comparison", exception)
            return null
        }
        return app.bookDao.byFileName(twin.name)?.takeIf { it.deletedAt == null }
    }

    private fun contentsMatch(a: File, b: File): Boolean =
        a.inputStream().buffered().use { streamA ->
            b.inputStream().buffered().use { streamB ->
                val bufferA = ByteArray(DEFAULT_BUFFER_SIZE)
                val bufferB = ByteArray(DEFAULT_BUFFER_SIZE)
                var matches = true
                while (true) {
                    val countA = streamA.read(bufferA)
                    val countB = streamB.read(bufferB)
                    if (countA != countB) {
                        matches = false
                        break
                    }
                    if (countA < 0) break
                    for (index in 0 until countA) {
                        if (bufferA[index] != bufferB[index]) {
                            matches = false
                            break
                        }
                    }
                    if (!matches) break
                }
                matches
            }
        }

    /** A ZIP is an EPUB (has a mimetype/OPF entry) or a CBZ (bag of images). */
    private fun sniffZipKind(file: File): String = try {
        java.util.zip.ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name.lowercase() }.toList()
            when {
                names.any { it == "mimetype" || it.endsWith(".opf") } -> "epub"
                names.any { it.substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp") } -> "cbz"
                else -> "epub"
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not inspect ZIP container ${file.name}; trying EPUB", e)
        "epub"
    }

    /** The source file's human name — the only usable title most PDFs have. */
    private fun displayNameFor(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the shared file's display name", e)
        null
    }

    /**
     * Registers unknown EPUBs in the books folder AND backfills covers or
     * full-text indexes missing from books imported by earlier app versions.
     */
    suspend fun scanDropFolder(): Int = withContext(Dispatchers.IO) {
        val known = app.bookDao.allFileNames().toSet()
        val importableExtensions = setOf("epub", "pdf", "cbz", "cbr")
        val newcomers = app.booksDir.listFiles { file ->
            file.isFile && file.name !in known && file.extension.lowercase() in importableExtensions
        }.orEmpty()
        val imported = newcomers.mapNotNull {
            registerFile(it, suggestedTitle = it.nameWithoutExtension)
        }.onEach {
            app.aiLibrarian.organizeInBackground(it.uuid)
        }.size
        backfillMissingAssets()
        imported
    }

    private suspend fun registerFile(file: File, suggestedTitle: String?): BookEntity? =
        when (file.extension.lowercase()) {
            "pdf" -> registerPdf(file, suggestedTitle)
            "cbz", "cbr" -> registerComic(file, suggestedTitle)
            else -> registerEpub(file)
        }

    /** CBZ/CBR: title from the source name, cover from the first page. */
    private suspend fun registerComic(file: File, suggestedTitle: String?): BookEntity? {
        val format = file.extension.lowercase()
        var source: app.vellum.reader.comic.ComicSource? = null
        var store: ComicPageStore? = null
        val cover: Bitmap? = try {
            source = if (format == "cbr") CbrComicSource(file) else CbzComicSource(file)
            if (source.pageCount == 0) {
                return null
            }
            store = ComicPageStore(source)
            store.page(0, 400)
        } catch (e: Exception) {
            Log.e(TAG, "Comic registration failed for ${file.name}", e)
            return null
        } finally {
            if (store != null) store.close() else source?.close()
        }
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val book = BookEntity(
            uuid = uuid,
            title = suggestedTitle?.takeIf { it.isNotBlank() && !it.looksLikeUuid() } ?: "Untitled comic",
            author = "Unknown author",
            fileName = file.name,
            format = format,
            category = "Comics & Manga",
            coverPath = saveCover(uuid, cover),
            seriesName = null,
            seriesIndex = null,
            comicRtl = false,
            addedAt = now,
            updatedAt = now,
            deletedAt = null,
            lastOpenedAt = null,
        )
        app.bookDao.upsert(book)
        return book
    }

    /** PDFs: title from the source name, cover from page one, no text index. */
    private suspend fun registerPdf(file: File, suggestedTitle: String?): BookEntity? {
        var renderer: PdfPageRenderer? = null
        val cover: Bitmap? = try {
            renderer = PdfPageRenderer(file)
            val bitmap = if (renderer.pageCount > 0) renderer.renderPage(0, 400) else null
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "PDF registration failed for ${file.name}", e)
            return null // unreadable PDF — don't register it
        } finally {
            renderer?.close()
        }
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val book = BookEntity(
            uuid = uuid,
            title = suggestedTitle?.takeIf { it.isNotBlank() && !it.looksLikeUuid() } ?: "Untitled PDF",
            author = "Unknown author",
            fileName = file.name,
            format = "pdf",
            category = null,
            coverPath = saveCover(uuid, cover),
            seriesName = null,
            seriesIndex = null,
            comicRtl = null,
            addedAt = now,
            updatedAt = now,
            deletedAt = null,
            lastOpenedAt = null,
        )
        app.bookDao.upsert(book)
        return book
    }

    private fun String.looksLikeUuid(): Boolean =
        length == 36 && count { it == '-' } == 4

    /** Opens the file, then persists metadata + cover + text index together. */
    private suspend fun registerEpub(file: File): BookEntity? {
        val opened = app.epubOpener.open(file) ?: return null
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        try {
            // Fixed-layout EPUBs (manga, art books) belong to the comic reader.
            val isComic = opened.isFixedLayout
            val book = BookEntity(
                uuid = uuid,
                title = opened.title,
                author = opened.author,
                fileName = file.name,
                format = if (isComic) "comic-epub" else "epub",
                category = if (isComic) "Comics & Manga" else null,
                coverPath = saveCover(uuid, opened.coverBitmap()),
                seriesName = null,
                seriesIndex = null,
                comicRtl = if (isComic) false else null,
                addedAt = now,
                updatedAt = now,
                deletedAt = null,
                lastOpenedAt = null,
            )
            val rows = if (isComic) emptyList() else fullTextRows(uuid, opened)
            try {
                app.database.withTransaction {
                    app.bookDao.upsert(book)
                    if (!isComic) app.searchDao.replaceForBook(uuid, rows)
                }
            } catch (e: Exception) {
                book.coverPath?.let { File(it).delete() }
                Log.e(TAG, "EPUB registration transaction failed for ${file.name}", e)
                return null
            }
            return book
        } finally {
            opened.close()
        }
    }

    /** Public for post-sync repair: pulled books arrive without local assets. */
    suspend fun ensureAssets() = backfillMissingAssets()

    /**
     * Regenerates whatever a book row is missing on THIS device — covers for
     * every format, plus the FTS index for reflowable EPUBs. Runs on shelf
     * scans and after sync pulls.
     */
    private suspend fun backfillMissingAssets() {
        val shelf = app.bookDao.allActive()
        for (book in shelf) {
            val file = File(app.booksDir, book.fileName)
            if (!file.exists()) continue
            val needsCover = book.coverPath == null || !File(book.coverPath).exists()
            val needsFts = book.format == "epub" && app.searchDao.chapterCountForBook(book.uuid) == 0
            if (!needsCover && !needsFts) continue
            when (book.format) {
                "epub", "comic-epub" -> {
                    val opened = app.epubOpener.open(file) ?: continue
                    try {
                        if (needsCover) {
                            saveCover(book.uuid, opened.coverBitmap())?.let {
                                app.bookDao.setCover(book.uuid, it, System.currentTimeMillis())
                            }
                        }
                        if (needsFts) indexFullText(book.uuid, opened)
                    } finally {
                        opened.close()
                    }
                }
                "pdf" -> if (needsCover) {
                    var renderer: PdfPageRenderer? = null
                    try {
                        renderer = PdfPageRenderer(file)
                        val cover = if (renderer.pageCount > 0) renderer.renderPage(0, 400) else null
                        saveCover(book.uuid, cover)?.let {
                            app.bookDao.setCover(book.uuid, it, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "PDF asset repair failed for ${file.name}", e)
                    } finally {
                        renderer?.close()
                    }
                }
                "cbz", "cbr" -> if (needsCover) {
                    var store: ComicPageStore? = null
                    try {
                        store = ComicPageStore(
                            if (book.format == "cbr") CbrComicSource(file) else CbzComicSource(file),
                        )
                        val cover = store.page(0, 400)
                        saveCover(book.uuid, cover)?.let {
                            app.bookDao.setCover(book.uuid, it, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Comic asset repair failed for ${file.name}", e)
                    } finally {
                        store?.close()
                    }
                }
            }
        }
    }

    private fun saveCover(bookUuid: String, cover: Bitmap?): String? {
        if (cover == null) return null
        val file = File(app.coversDir, "$bookUuid.webp")
        return try {
            file.outputStream().use { cover.compress(Bitmap.CompressFormat.WEBP_LOSSY, 84, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Could not save cover for $bookUuid", e)
            file.delete()
            null
        }
    }

    /**
     * One FTS row per chapter. The body is the same no-separator concatenation
     * of block texts the paginator uses for char offsets, so a match position
     * in the body maps 1:1 onto a reader locator.
     */
    private suspend fun indexFullText(bookUuid: String, opened: OpenedEpub) {
        app.searchDao.replaceForBook(bookUuid, fullTextRows(bookUuid, opened))
    }

    private suspend fun fullTextRows(bookUuid: String, opened: OpenedEpub): List<BookTextFts> = buildList {
        for (chapter in 0 until opened.chapterCount) {
            val html = opened.chapterHtml(chapter) ?: continue
            val body = HtmlBlockParser.parse(html).joinToString("") { it.text.text }
            if (body.isNotBlank()) {
                add(BookTextFts(bookUuid, chapter.toString(), body))
            }
        }
    }
}
