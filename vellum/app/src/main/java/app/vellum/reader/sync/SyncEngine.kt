package app.vellum.reader.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.AnnotationEntity
import app.vellum.reader.core.data.BookCollectionCrossRef
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.core.data.BookTagCrossRef
import app.vellum.reader.core.data.CollectionEntity
import app.vellum.reader.core.data.ComicPanelEntity
import app.vellum.reader.core.data.GenreEntity
import app.vellum.reader.core.data.BookGenreCrossRef
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
            val now = System.currentTimeMillis()
            val deviceId = app.settingsStore.syncDeviceId()
            val remote = readBundle(dir)
            val merged = mergeAll(remote, deviceId, now)
            applyLocally(merged)
            val (pulled, pushed) = transferBookFiles(dir, merged.books)
            val cutoff = acknowledgedTombstoneCutoff(merged, now)
            val compacted = compactTombstones(merged, cutoff)
            writeBundle(dir, compacted)
            if (cutoff != Long.MIN_VALUE) purgeLocalTombstones(cutoff)
            SyncResult(pulled, pushed)
        } catch (e: Exception) {
            SyncResult(0, 0, e.message ?: "Sync failed")
        }
    }

    // ---- Snapshot & merge -------------------------------------------------

    private data class Bundle(
        val books: List<BookEntity> = emptyList(),
        val positions: List<ReadingPositionEntity> = emptyList(),
        val annotations: List<AnnotationEntity> = emptyList(),
        val collections: List<CollectionEntity> = emptyList(),
        val tags: List<TagEntity> = emptyList(),
        val bookCollections: List<BookCollectionCrossRef> = emptyList(),
        val bookTags: List<BookTagCrossRef> = emptyList(),
        val genres: List<GenreEntity> = emptyList(),
        val bookGenres: List<BookGenreCrossRef> = emptyList(),
        val panels: List<ComicPanelEntity> = emptyList(),
        val strokes: List<PdfStrokeEntity> = emptyList(),
        val sessions: List<ReadingSessionEntity> = emptyList(),
        val deviceAcks: Map<String, Long> = emptyMap(),
    )

    private fun <T> mergeRows(local: List<T>, remote: List<T>, key: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val byKey = HashMap<String, T>()
        (local + remote).forEach { row ->
            val k = key(row)
            val existing = byKey[k]
            if (existing == null || updatedAt(row) > updatedAt(existing) ||
                (updatedAt(row) == updatedAt(existing) && row.toString() > existing.toString())
            ) {
                byKey[k] = row
            }
        }
        return byKey.values.toList()
    }

    private suspend fun mergeAll(remote: Bundle, deviceId: String, now: Long): Bundle {
        // A badly skewed peer clock must not create a row that wins every merge
        // indefinitely. A small allowance covers normal clock drift.
        val futureCeiling = now + MAX_CLOCK_SKEW_MS
        val merged = Bundle(
            books = mergeRows(app.bookDao.allRaw(), remote.books.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            positions = mergeRows(app.bookDao.allPositionsRaw(), remote.positions.filter { it.updatedAt <= futureCeiling }, { it.bookUuid }, { it.updatedAt }),
            annotations = mergeRows(app.annotationDao.allRaw(), remote.annotations.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            collections = mergeRows(app.collectionDao.allCollectionsRaw(), remote.collections.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            tags = mergeRows(app.collectionDao.allTagsRaw(), remote.tags.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            bookCollections = mergeRows(
                app.collectionDao.allBookCollectionsRaw(), remote.bookCollections.filter { it.updatedAt <= futureCeiling },
                { "${it.bookUuid}/${it.collectionUuid}" }, { it.updatedAt },
            ),
            bookTags = mergeRows(
                app.collectionDao.allBookTagsRaw(), remote.bookTags.filter { it.updatedAt <= futureCeiling },
                { "${it.bookUuid}/${it.tagUuid}" }, { it.updatedAt },
            ),
            genres = mergeRows(
                app.collectionDao.allGenresRaw(), remote.genres.filter { it.updatedAt <= futureCeiling },
                { it.uuid }, { it.updatedAt },
            ),
            bookGenres = mergeRows(
                app.collectionDao.allBookGenresRaw(), remote.bookGenres.filter { it.updatedAt <= futureCeiling },
                { "${it.bookUuid}/${it.genreUuid}" }, { it.updatedAt },
            ),
            panels = mergeRows(app.comicPanelDao.allRaw(), remote.panels.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            strokes = mergeRows(app.pdfStrokeDao.allRaw(), remote.strokes.filter { it.updatedAt <= futureCeiling }, { it.uuid }, { it.updatedAt }),
            sessions = mergeRows(app.sessionDao.allRaw(), remote.sessions, { it.uuid }, { 0L }),
            deviceAcks = remote.deviceAcks,
        )
        val latestTombstone = listOfNotNull(
            merged.books.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.annotations.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.collections.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.tags.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.bookCollections.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.bookTags.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.genres.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.bookGenres.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.panels.mapNotNull { it.deletedAt }.maxOrNull(),
            merged.strokes.mapNotNull { it.deletedAt }.maxOrNull(),
        ).maxOrNull() ?: Long.MIN_VALUE
        val priorAck = remote.deviceAcks[deviceId]
        val acks = if (priorAck == null || latestTombstone > priorAck) {
            remote.deviceAcks + (deviceId to now)
        } else remote.deviceAcks
        return merged.copy(deviceAcks = acks)
    }

    private fun acknowledgedTombstoneCutoff(bundle: Bundle, now: Long): Long {
        val oldestAcknowledgement = bundle.deviceAcks.values.minOrNull() ?: return Long.MIN_VALUE
        return minOf(oldestAcknowledgement, now - TOMBSTONE_GRACE_MS)
    }

    private fun compactTombstones(bundle: Bundle, cutoff: Long): Bundle {
        if (cutoff == Long.MIN_VALUE) return bundle
        return bundle.copy(
            books = bundle.books.filter { it.deletedAt == null || it.deletedAt > cutoff },
            annotations = bundle.annotations.filter { it.deletedAt == null || it.deletedAt > cutoff },
            collections = bundle.collections.filter { it.deletedAt == null || it.deletedAt > cutoff },
            tags = bundle.tags.filter { it.deletedAt == null || it.deletedAt > cutoff },
            bookCollections = bundle.bookCollections.filter { it.deletedAt == null || it.deletedAt > cutoff },
            bookTags = bundle.bookTags.filter { it.deletedAt == null || it.deletedAt > cutoff },
            genres = bundle.genres.filter { it.deletedAt == null || it.deletedAt > cutoff },
            bookGenres = bundle.bookGenres.filter { it.deletedAt == null || it.deletedAt > cutoff },
            panels = bundle.panels.filter { it.deletedAt == null || it.deletedAt > cutoff },
            strokes = bundle.strokes.filter { it.deletedAt == null || it.deletedAt > cutoff },
        )
    }

    private suspend fun purgeLocalTombstones(cutoff: Long) {
        app.database.withTransaction {
            app.annotationDao.purgeTombstones(cutoff)
            app.comicPanelDao.purgeTombstones(cutoff)
            app.pdfStrokeDao.purgeTombstones(cutoff)
            app.collectionDao.purgeBookCollectionTombstones(cutoff)
            app.collectionDao.purgeBookTagTombstones(cutoff)
            app.collectionDao.purgeBookGenreTombstones(cutoff)
            app.collectionDao.purgeGenreTombstones(cutoff)
            app.collectionDao.purgeCollectionTombstones(cutoff)
            app.collectionDao.purgeTagTombstones(cutoff)
            app.bookDao.purgeTombstones(cutoff)
        }
    }

    private suspend fun applyLocally(merged: Bundle) {
        val localBooks = app.bookDao.allRaw().associateBy { it.uuid }
        // Covers are device-local (never in the bundle); keep ours on overwrite.
        val books = merged.books.map { book ->
            book.copy(coverPath = localBooks[book.uuid]?.coverPath)
        }
        app.database.withTransaction {
            app.bookDao.upsertAll(books)
            merged.positions.forEach { app.bookDao.upsertPosition(it) }
            app.annotationDao.upsertAll(merged.annotations)
            app.collectionDao.upsertCollections(merged.collections)
            app.collectionDao.upsertTags(merged.tags)
            app.collectionDao.upsertBookCollections(merged.bookCollections)
            app.collectionDao.upsertBookTags(merged.bookTags)
            app.collectionDao.upsertGenres(merged.genres)
            app.collectionDao.upsertBookGenres(merged.bookGenres)
            app.comicPanelDao.insertAll(merged.panels)
            app.pdfStrokeDao.insertAll(merged.strokes)
            app.sessionDao.upsertAll(merged.sessions)
            books.filter { it.deletedAt != null }.forEach { app.searchDao.deleteForBook(it.uuid) }
        }
        // A remote tombstone must clean the same device-local derivatives as a
        // deletion performed on this device.
        books.filter { it.deletedAt != null }.forEach { book ->
            File(app.booksDir, book.fileName).delete()
            localBooks[book.uuid]?.coverPath?.let { File(it).delete() }
        }
    }

    /** Pulls files this device lacks; pushes files the folder lacks. */
    private fun transferBookFiles(dir: DocumentFile, books: List<BookEntity>): Pair<Int, Int> {
        val booksDir = dir.findFile("books")?.takeIf { it.isDirectory } ?: dir.createDirectory("books")
            ?: return 0 to 0
        var pulled = 0
        var pushed = 0
        books.filter { it.deletedAt != null }.forEach { book ->
            booksDir.findFile(book.fileName)?.delete()
            booksDir.findFile("${book.fileName}.part")?.delete()
        }
        books.filter { it.deletedAt == null }.forEach { book ->
            val local = File(app.booksDir, book.fileName)
            val remote = booksDir.findFile(book.fileName)
            // Both directions stage into a .part file and rename into place:
            // an interrupted copy must never leave a truncated file that
            // `exists()` and is therefore never re-transferred.
            if (!local.exists() && remote != null) {
                val staging = File(app.booksDir, "${book.fileName}.part")
                val copied = try {
                    app.contentResolver.openInputStream(remote.uri)?.use { input ->
                        staging.outputStream().use { input.copyTo(it) }
                        true
                    } ?: false
                } catch (e: Exception) {
                    false
                }
                if (copied && staging.renameTo(local)) pulled++ else staging.delete()
            } else if (local.exists() && remote == null) {
                booksDir.findFile("${book.fileName}.part")?.delete()
                booksDir.createFile("application/octet-stream", "${book.fileName}.part")?.let { staging ->
                    val copied = try {
                        app.contentResolver.openOutputStream(staging.uri)?.use { output ->
                            local.inputStream().use { it.copyTo(output) }
                            true
                        } ?: false
                    } catch (e: Exception) {
                        false
                    }
                    if (copied && staging.renameTo(book.fileName)) pushed++ else staging.delete()
                }
            }
        }
        return pulled to pushed
    }

    // ---- Bundle I/O -------------------------------------------------------

    private fun readBundle(dir: DocumentFile): Bundle {
        val file = dir.findFile("vellum-sync.json")
            ?: return Bundle()
        val json = app.contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText() ?: "{}"
        // A torn or corrupt bundle must not brick sync forever: treat it as
        // empty — the LWW merge rebuilds it from local rows, and the other
        // device re-contributes its rows on its next sync.
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
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
            genres = arr("genres").map {
                GenreEntity(
                    it.getString("uuid"), it.getString("name"), it.getLong("createdAt"),
                    it.getLong("updatedAt"), it.optLongOrNull("deletedAt"),
                )
            },
            bookGenres = arr("bookGenres").map {
                BookGenreCrossRef(
                    it.getString("bookUuid"), it.getString("genreUuid"),
                    it.getLong("updatedAt"), it.optLongOrNull("deletedAt"),
                )
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
            deviceAcks = root.optJSONObject("deviceAcks")?.let { acks ->
                acks.keys().asSequence().associateWith { acks.optLong(it, 0L) }
            }.orEmpty(),
        )
    }

    private fun writeBundle(dir: DocumentFile, bundle: Bundle) {
        val root = JSONObject()
        root.put("version", 1)
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
            "genres",
            JSONArray(
                bundle.genres.map {
                    JSONObject().put("uuid", it.uuid).put("name", it.name).put("createdAt", it.createdAt)
                        .put("updatedAt", it.updatedAt).putOpt("deletedAt", it.deletedAt)
                },
            ),
        )
        root.put(
            "bookGenres",
            JSONArray(
                bundle.bookGenres.map {
                    JSONObject().put("bookUuid", it.bookUuid).put("genreUuid", it.genreUuid)
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
        root.put("deviceAcks", JSONObject(bundle.deviceAcks))
        val existing = dir.findFile("vellum-sync.json")?.let { file ->
            app.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }
        if (existing != null && sameBundleContent(existing, root)) return
        root.put("exportedAt", System.currentTimeMillis())
        // Stage-then-swap: an in-place overwrite torn by a crash or by the
        // paired sync tool shipping a half-write leaves invalid JSON that
        // would fail every later sync. A *missing* bundle is safely rebuilt,
        // so the worst interruption here costs nothing.
        dir.findFile("vellum-sync.json.tmp")?.delete()
        val staging = dir.createFile("application/json", "vellum-sync.json.tmp") ?: return
        val written = try {
            app.contentResolver.openOutputStream(staging.uri, "wt")?.bufferedWriter()
                ?.use { it.write(root.toString()) } != null
        } catch (e: Exception) {
            false
        }
        if (!written) {
            staging.delete()
            return
        }
        dir.findFile("vellum-sync.json")?.delete()
        staging.renameTo("vellum-sync.json")
    }

    private fun sameBundleContent(existing: String, replacement: JSONObject): Boolean = try {
        val current = JSONObject(existing).apply { remove("exportedAt") }
        canonicalJson(current) == canonicalJson(replacement)
    } catch (_: Exception) {
        false
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).map { canonicalJson(value.opt(it)) }
            .sorted().joinToString(",", "[", "]")
        JSONObject.NULL, null -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
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
        .put("format", format).putOpt("category", category)
        .putOpt("seriesName", seriesName).putOpt("seriesIndex", seriesIndex)
        .putOpt("comicRtl", comicRtl).put("addedAt", addedAt).put("updatedAt", updatedAt)
        .putOpt("deletedAt", deletedAt).putOpt("lastOpenedAt", lastOpenedAt)
        .putOpt("sourceLibraryUuid", sourceLibraryUuid)
        .putOpt("sourcePublicationUuid", sourcePublicationUuid)

    private fun JSONObject.toBook(): BookEntity = BookEntity(
        uuid = getString("uuid"),
        title = getString("title"),
        author = getString("author"),
        fileName = getString("fileName"),
        format = getString("format"),
        category = optStringOrNull("category"),
        coverPath = null, // covers are device-local; regenerated after pull
        seriesName = optStringOrNull("seriesName"),
        seriesIndex = optFloatOrNull("seriesIndex"),
        comicRtl = optBooleanOrNull("comicRtl"),
        addedAt = getLong("addedAt"),
        updatedAt = getLong("updatedAt"),
        deletedAt = optLongOrNull("deletedAt"),
        lastOpenedAt = optLongOrNull("lastOpenedAt"),
        sourceLibraryUuid = optStringOrNull("sourceLibraryUuid"),
        sourcePublicationUuid = optStringOrNull("sourcePublicationUuid"),
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

    private companion object {
        const val TOMBSTONE_GRACE_MS = 30L * 24L * 60L * 60L * 1000L
        const val MAX_CLOCK_SKEW_MS = 5L * 60L * 1000L
    }
}
