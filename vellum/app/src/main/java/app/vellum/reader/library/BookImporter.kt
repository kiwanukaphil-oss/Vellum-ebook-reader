package app.vellum.reader.library

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
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

    suspend fun importFromUri(uri: Uri): BookEntity? = withContext(Dispatchers.IO) {
        val temp = File(app.booksDir, "${UUID.randomUUID()}.tmp")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
        } catch (e: Exception) {
            temp.delete()
            return@withContext null
        }
        // Sniff the real format — file pickers often report octet-stream.
        val magic = temp.inputStream().use { stream -> ByteArray(4).also { stream.read(it) } }
        val magicText = magic.decodeToString()
        val extension = when {
            magicText.startsWith("%PDF") -> "pdf"
            magicText.startsWith("Rar!") -> "cbr"
            magicText.startsWith("PK") -> sniffZipKind(temp)
            else -> "epub"
        }
        val target = File(app.booksDir, "${temp.nameWithoutExtension}.$extension")
        temp.renameTo(target)
        registerFile(target, displayNameFor(uri)) ?: run {
            target.delete()
            null
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
        "epub"
    }

    /** The source file's human name — the only usable title most PDFs have. */
    private fun displayNameFor(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (e: Exception) {
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
        val imported = newcomers.count { registerFile(it, suggestedTitle = it.nameWithoutExtension) != null }
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
        val cover: Bitmap? = try {
            val source = if (format == "cbr") CbrComicSource(file) else CbzComicSource(file)
            if (source.pageCount == 0) {
                source.close()
                return null
            }
            val store = ComicPageStore(source)
            val bitmap = store.page(0, 400)
            store.close()
            bitmap
        } catch (e: Exception) {
            return null
        }
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val book = BookEntity(
            uuid = uuid,
            title = suggestedTitle?.takeIf { it.isNotBlank() && !it.looksLikeUuid() } ?: "Untitled comic",
            author = "Comic",
            fileName = file.name,
            format = format,
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
        val cover: Bitmap? = try {
            val renderer = PdfPageRenderer(file)
            val bitmap = if (renderer.pageCount > 0) renderer.renderPage(0, 400) else null
            renderer.close()
            bitmap
        } catch (e: Exception) {
            return null // unreadable PDF — don't register it
        }
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val book = BookEntity(
            uuid = uuid,
            title = suggestedTitle?.takeIf { it.isNotBlank() && !it.looksLikeUuid() } ?: "Untitled PDF",
            author = "PDF",
            fileName = file.name,
            format = "pdf",
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
                coverPath = saveCover(uuid, opened.coverBitmap()),
                seriesName = null,
                seriesIndex = null,
                comicRtl = if (isComic) false else null,
                addedAt = now,
                updatedAt = now,
                deletedAt = null,
                lastOpenedAt = null,
            )
            app.bookDao.upsert(book)
            if (!isComic) indexFullText(uuid, opened)
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
        val shelf = app.bookDao.searchByTitleOrAuthor("")
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
                    try {
                        val renderer = PdfPageRenderer(file)
                        val cover = if (renderer.pageCount > 0) renderer.renderPage(0, 400) else null
                        renderer.close()
                        saveCover(book.uuid, cover)?.let {
                            app.bookDao.setCover(book.uuid, it, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        // Unreadable now; the next scan retries.
                    }
                }
                "cbz", "cbr" -> if (needsCover) {
                    try {
                        val store = ComicPageStore(
                            if (book.format == "cbr") CbrComicSource(file) else CbzComicSource(file),
                        )
                        val cover = store.page(0, 400)
                        store.close()
                        saveCover(book.uuid, cover)?.let {
                            app.bookDao.setCover(book.uuid, it, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        // Unreadable now; the next scan retries.
                    }
                }
            }
        }
    }

    private fun saveCover(bookUuid: String, cover: Bitmap?): String? {
        if (cover == null) return null
        val file = File(app.coversDir, "$bookUuid.png")
        return try {
            file.outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
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
        app.searchDao.deleteForBook(bookUuid)
        for (chapter in 0 until opened.chapterCount) {
            val html = opened.chapterHtml(chapter) ?: continue
            val body = HtmlBlockParser.parse(html).joinToString("") { it.text.text }
            if (body.isNotBlank()) {
                app.searchDao.insertChapterText(BookTextFts(bookUuid, chapter.toString(), body))
            }
        }
    }
}
