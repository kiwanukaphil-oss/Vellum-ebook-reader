package app.vellum.reader.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.data.BookCollectionCrossRef
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookTagCrossRef
import app.vellum.reader.core.data.CollectionEntity
import app.vellum.reader.core.data.ComicPanelEntity
import app.vellum.reader.core.data.PdfStrokeEntity
import app.vellum.reader.core.data.ReadingPositionEntity
import app.vellum.reader.core.data.ReadingSessionEntity
import app.vellum.reader.core.data.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SyncResult(
    val pulledBooks: Int,
    val pushedBooks: Int,
    val error: String? = null,
)

/**
 * Folder-bundle sync: the chosen folder holds `vellum-sync.json` (every table,
 * tombstones included) plus a `books/` mirror of the library files. Pair the
 * folder with any file-sync tool (Syncthing, Drive, OneDrive) and devices
 * converge. Merge rule: per row, newest `updatedAt` wins — tombstones are just
 * rows whose winner has `deletedAt` set, so deletions propagate naturally.
 * Sessions are append-only and unioned. No accounts, no server, no network
 * calls from Vellum itself.
 */
class SyncEngine(private val app: VellumApp) {

    suspend fun sync(folderUri: Uri): SyncResult = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(app, folderUri)
            ?: return@withContext SyncResult(0, 0, "Sync folder is not accessible")
        try {
            val remote = readBundle(dir)
            val merged = mergeAll(remote)
            applyLocally(merged)
            val (pulled, pushed) = transferBookFiles(dir, merged.books)
            writeBundle(dir, merged)
            SyncResult(pulled, pushed)
        } catch (e: Exception) {
            SyncResult(0, 0, e.message ?: "Sync failed")
        }
    }

    // ---- Snapshot & merge -------------------------------------------------

    private class Bundle(
        val books: List<BookEntity>,
        val positions: List<ReadingPositionEntity>,
        val annotations: List<AnnotationEntity>,
        val collections: List<CollectionEntity>,
        val tags: List<TagEntity>,
        val bookCollections: List<BookCollectionCrossRef>,
        val bookTags: List<BookTagCrossRef>,
        val panels: List<ComicPanelEntity>,
        val strokes: List<PdfStrokeEntity>,
        val sessions: List<ReadingSessionEntity>,
    )

    private fun <T> mergeRows(local: List<T>, remote: List<T>, key: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val byKey = HashMap<String, T>()
        (local + remote).forEach { row ->
            val k = key(row)
            val existing = byKey[k]
            if (existing == null || updatedAt(row) > updatedAt(existing)) byKey[k] = row
        }
        return byKey.values.toList()
    }

    private suspend fun mergeAll(remote: Bundle): Bundle = Bundle(
        books = mergeRows(app.bookDao.allRaw(), remote.books, { it.uuid }, { it.updatedAt }),
        positions = mergeRows(app.bookDao.allPositionsRaw(), remote.positions, { it.bookUuid }, { it.updatedAt }),
        annotations = mergeRows(app.annotationDao.allRaw(), remote.annotations, { it.uuid }, { it.updatedAt }),
        collections = mergeRows(app.collectionDao.allCollectionsRaw(), remote.collections, { it.uuid }, { it.updatedAt }),
        tags = mergeRows(app.collectionDao.allTagsRaw(), remote.tags, { it.uuid }, { it.updatedAt }),
        bookCollections = mergeRows(
            app.collectionDao.allBookCollectionsRaw(), remote.bookCollections,
            { "${it.bookUuid}/${it.collectionUuid}" }, { it.updatedAt },
        ),
        bookTags = mergeRows(
            app.collectionDao.allBookTagsRaw(), remote.bookTags,
            { "${it.bookUuid}/${it.tagUuid}" }, { it.updatedAt },
        ),
        panels = mergeRows(app.comicPanelDao.allRaw(), remote.panels, { it.uuid }, { it.updatedAt }),
        strokes = mergeRows(app.pdfStrokeDao.allRaw(), remote.strokes, { it.uuid }, { it.updatedAt }),
        sessions = mergeRows(app.sessionDao.allRaw(), remote.sessions, { it.uuid }, { 0L }),
    )

    private suspend fun applyLocally(merged: Bundle) {
        // Covers are device-local (never in the bundle); keep ours on overwrite.
        merged.books.forEach { book ->
            val localCover = app.bookDao.byUuid(book.uuid)?.coverPath
            app.bookDao.upsert(book.copy(coverPath = book.coverPath ?: localCover))
        }
        merged.positions.forEach { app.bookDao.upsertPosition(it) }
        merged.annotations.forEach { app.annotationDao.upsert(it) }
        merged.collections.forEach { app.collectionDao.upsertCollection(it) }
        merged.tags.forEach { app.collectionDao.upsertTag(it) }
        merged.bookCollections.forEach { app.collectionDao.upsertBookCollection(it) }
        merged.bookTags.forEach { app.collectionDao.upsertBookTag(it) }
        merged.panels.forEach { app.comicPanelDao.insert(it) }
        merged.strokes.forEach { app.pdfStrokeDao.insert(it) }
        merged.sessions.forEach { app.sessionDao.upsert(it) }
    }

    /** Pulls files this device lacks; pushes files the folder lacks. */
    private fun transferBookFiles(dir: DocumentFile, books: List<BookEntity>): Pair<Int, Int> {
        val booksDir = dir.findFile("books")?.takeIf { it.isDirectory } ?: dir.createDirectory("books")
            ?: return 0 to 0
        var pulled = 0
        var pushed = 0
        books.filter { it.deletedAt == null }.forEach { book ->
            val local = File(app.booksDir, book.fileName)
            val remote = booksDir.findFile(book.fileName)
            if (!local.exists() && remote != null) {
                app.contentResolver.openInputStream(remote.uri)?.use { input ->
                    local.outputStream().use { input.copyTo(it) }
                }
                pulled++
            } else if (local.exists() && remote == null) {
                booksDir.createFile("application/octet-stream", book.fileName)?.let { target ->
                    app.contentResolver.openOutputStream(target.uri)?.use { output ->
                        local.inputStream().use { it.copyTo(output) }
                    }
                    pushed++
                }
            }
        }
        return pulled to pushed
    }

    // ---- Bundle I/O -------------------------------------------------------

    private fun readBundle(dir: DocumentFile): Bundle {
        val file = dir.findFile("vellum-sync.json")
            ?: return Bundle(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            )
        val json = app.contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText() ?: "{}"
        val root = JSONObject(json)
        fun arr(name: String): List<JSONObject> {
            val array = root.optJSONArray(name) ?: JSONArray()
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        return Bundle(
            books = arr("books").map { it.toBook() },
            positions = arr("positions").map { it.toPosition() },
            annotations = arr("annotations").map { it.toAnnotation() },
            collections = arr("collections").map {
                CollectionEntity(it.getString("uuid"), it.getString("name"), it.getLong("createdAt"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"))
            },
            tags = arr("tags").map {
                TagEntity(it.getString("uuid"), it.getString("name"), it.getLong("createdAt"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"))
            },
            bookCollections = arr("bookCollections").map {
                BookCollectionCrossRef(it.getString("bookUuid"), it.getString("collectionUuid"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"))
            },
            bookTags = arr("bookTags").map {
                BookTagCrossRef(it.getString("bookUuid"), it.getString("tagUuid"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"))
            },
            panels = arr("panels").map {
                ComicPanelEntity(
                    it.getString("uuid"), it.getString("bookUuid"), it.getInt("pageIndex"), it.getInt("ord"),
                    it.getDouble("left").toFloat(), it.getDouble("top").toFloat(),
                    it.getDouble("right").toFloat(), it.getDouble("bottom").toFloat(),
                    it.getLong("createdAt"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"),
                )
            },
            strokes = arr("strokes").map {
                PdfStrokeEntity(
                    it.getString("uuid"), it.getString("bookUuid"), it.getInt("pageIndex"), it.getString("colorId"),
                    it.getDouble("strokeWidth").toFloat(), it.getString("points"),
                    it.getLong("createdAt"), it.getLong("updatedAt"), it.optLongOrNull("deletedAt"),
                )
            },
            sessions = arr("sessions").map {
                ReadingSessionEntity(
                    it.getString("uuid"), it.getString("bookUuid"), it.getLong("startedAt"),
                    it.getLong("endedAt"), it.getLong("msRead"), it.getInt("pagesTurned"),
                )
            },
        )
    }

    private fun writeBundle(dir: DocumentFile, bundle: Bundle) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("books", JSONArray(bundle.books.map { it.toJson() }))
        root.put("positions", JSONArray(bundle.positions.map { it.toJson() }))
        root.put("annotations", JSONArray(bundle.annotations.map { it.toJson() }))
        root.put(
            "collections",
            JSONArray(
                bundle.collections.map {
                    JSONObject().put("uuid", it.uuid).put("name", it.name).put("createdAt", it.createdAt)
                        .put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "tags",
            JSONArray(
                bundle.tags.map {
                    JSONObject().put("uuid", it.uuid).put("name", it.name).put("createdAt", it.createdAt)
                        .put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "bookCollections",
            JSONArray(
                bundle.bookCollections.map {
                    JSONObject().put("bookUuid", it.bookUuid).put("collectionUuid", it.collectionUuid)
                        .put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "bookTags",
            JSONArray(
                bundle.bookTags.map {
                    JSONObject().put("bookUuid", it.bookUuid).put("tagUuid", it.tagUuid)
                        .put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "panels",
            JSONArray(
                bundle.panels.map {
                    JSONObject().put("uuid", it.uuid).put("bookUuid", it.bookUuid).put("pageIndex", it.pageIndex)
                        .put("ord", it.ord).put("left", it.left).put("top", it.top).put("right", it.right)
                        .put("bottom", it.bottom).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
                        .putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "strokes",
            JSONArray(
                bundle.strokes.map {
                    JSONObject().put("uuid", it.uuid).put("bookUuid", it.bookUuid).put("pageIndex", it.pageIndex)
                        .put("colorId", it.colorId).put("strokeWidth", it.strokeWidth).put("points", it.points)
                        .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "sessions",
            JSONArray(
                bundle.sessions.map {
                    JSONObject().put("uuid", it.uuid).put("bookUuid", it.bookUuid).put("startedAt", it.startedAt)
                        .put("endedAt", it.endedAt).put("msRead", it.msRead).put("pagesTurned", it.pagesTurned)
                },
            ),
        )
        val target = dir.findFile("vellum-sync.json") ?: dir.createFile("application/json", "vellum-sync.json")
        target?.let { file ->
            app.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(root.toString()) }
        }
    }

    // ---- JSON mapping helpers --------------------------------------------

    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null

    private fun JSONObject.optFloatOrNull(name: String): Float? =
        if (has(name) && !isNull(name)) getDouble(name).toFloat() else null

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) getBoolean(name) else null

    private fun BookEntity.toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("title", title).put("author", author).put("fileName", fileName)
        .put("format", format).putOpt("seriesName", seriesName).putOpt("seriesIndex", seriesIndex)
        .putOpt("comicRtl", comicRtl).put("addedAt", addedAt).put("updatedAt", updatedAt)
        .putOpt("deletedAt", deletedAt).putOpt("lastOpenedAt", lastOpenedAt)

    private fun JSONObject.toBook(): BookEntity = BookEntity(
        uuid = getString("uuid"),
        title = getString("title"),
        author = getString("author"),
        fileName = getString("fileName"),
        format = getString("format"),
        coverPath = null, // covers are device-local; regenerated after pull
        seriesName = optStringOrNull("seriesName"),
        seriesIndex = optFloatOrNull("seriesIndex"),
        comicRtl = optBooleanOrNull("comicRtl"),
        addedAt = getLong("addedAt"),
        updatedAt = getLong("updatedAt"),
        deletedAt = optLongOrNull("deletedAt"),
        lastOpenedAt = optLongOrNull("lastOpenedAt"),
    )

    private fun ReadingPositionEntity.toJson(): JSONObject = JSONObject()
        .put("bookUuid", bookUuid).put("chapterIndex", chapterIndex).put("chapterHref", chapterHref)
        .put("charOffset", charOffset).put("progression", progression).put("updatedAt", updatedAt)

    private fun JSONObject.toPosition(): ReadingPositionEntity = ReadingPositionEntity(
        bookUuid = getString("bookUuid"),
        chapterIndex = getInt("chapterIndex"),
        chapterHref = getString("chapterHref"),
        charOffset = getInt("charOffset"),
        progression = getDouble("progression"),
        updatedAt = getLong("updatedAt"),
    )

    private fun AnnotationEntity.toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("bookUuid", bookUuid).put("chapterIndex", chapterIndex)
        .put("chapterHref", chapterHref).put("startChar", startChar).put("endChar", endChar)
        .put("quote", quote).put("colorId", colorId).putOpt("note", note)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).putOpt("deletedAt", deletedAt)

    private fun JSONObject.toAnnotation(): AnnotationEntity = AnnotationEntity(
        uuid = getString("uuid"),
        bookUuid = getString("bookUuid"),
        chapterIndex = getInt("chapterIndex"),
        chapterHref = getString("chapterHref"),
        startChar = getInt("startChar"),
        endChar = getInt("endChar"),
        quote = getString("quote"),
        colorId = getString("colorId"),
        note = optStringOrNull("note"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
        deletedAt = optLongOrNull("deletedAt"),
    )
}
