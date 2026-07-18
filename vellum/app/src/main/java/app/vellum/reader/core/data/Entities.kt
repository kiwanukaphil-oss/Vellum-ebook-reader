package app.vellum.reader.core.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * All entities carry {uuid, updatedAt, deletedAt} per the build plan's
 * sync-ready rule: Phase 9 sync becomes a transport problem, not a migration.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val uuid: String,
    val title: String,
    val author: String,
    /** File name inside the app-private books directory. */
    val fileName: String,
    val format: String,
    /** Absolute path of the extracted cover image, if the EPUB embeds one. */
    val coverPath: String?,
    val seriesName: String?,
    val seriesIndex: Float?,
    /** Comics only: read right-to-left (manga). Null for non-comics. */
    val comicRtl: Boolean?,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val lastOpenedAt: Long?,
)

/**
 * Reading position as a locator, not a page number: chapter + character offset
 * survive typography changes and device sizes; progression feeds insights later.
 */
@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey val bookUuid: String,
    val chapterIndex: Int,
    val chapterHref: String,
    val charOffset: Int,
    val progression: Double,
    val updatedAt: Long,
)

/**
 * A highlight or note. Anchored primarily by the exact [quote] text; the char
 * range is a hint that survives typography changes (offsets are layout-free)
 * and lets re-anchoring recover if the underlying file ever changes.
 */
@Entity(tableName = "annotations", indices = [Index("bookUuid")])
data class AnnotationEntity(
    @PrimaryKey val uuid: String,
    val bookUuid: String,
    val chapterIndex: Int,
    val chapterHref: String,
    val startChar: Int,
    val endChar: Int,
    val quote: String,
    val colorId: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/**
 * One sitting with one book, any format. Insights (streaks, time, pages/hour)
 * are all derived from these rows; nothing is computed destructively.
 */
@Entity(tableName = "reading_sessions", indices = [Index("bookUuid")])
data class ReadingSessionEntity(
    @PrimaryKey val uuid: String,
    val bookUuid: String,
    val startedAt: Long,
    val endedAt: Long,
    val msRead: Long,
    val pagesTurned: Int,
)

/**
 * A reader-defined panel rectangle on a comic page (normalized 0..1), in
 * reading order — the source of the guided panel-by-panel view.
 */
@Entity(tableName = "comic_panels", indices = [Index(value = ["bookUuid", "pageIndex"])])
data class ComicPanelEntity(
    @PrimaryKey val uuid: String,
    val bookUuid: String,
    val pageIndex: Int,
    val ord: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/**
 * One freehand ink stroke on a PDF page. Points are normalized (0..1) page
 * coordinates serialized as "x,y;x,y;…" — zoom- and renderer-independent.
 */
@Entity(tableName = "pdf_strokes", indices = [Index(value = ["bookUuid", "pageIndex"])])
data class PdfStrokeEntity(
    @PrimaryKey val uuid: String,
    val bookUuid: String,
    val pageIndex: Int,
    val colorId: String,
    val strokeWidth: Float,
    val points: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "book_collections", primaryKeys = ["bookUuid", "collectionUuid"])
data class BookCollectionCrossRef(
    val bookUuid: String,
    val collectionUuid: String,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "book_tags", primaryKeys = ["bookUuid", "tagUuid"])
data class BookTagCrossRef(
    val bookUuid: String,
    val tagUuid: String,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/**
 * Full-text index: one row per chapter, body built by the same block
 * concatenation the paginator uses — so a match offset in [body] IS a reader
 * character offset, letting search results jump to the exact page.
 * (All columns are strings: FTS4 tables are untyped TEXT.)
 */
@Fts4
@Entity(tableName = "book_text_fts")
data class BookTextFts(
    val bookUuid: String,
    val chapterIndex: String,
    val body: String,
)
