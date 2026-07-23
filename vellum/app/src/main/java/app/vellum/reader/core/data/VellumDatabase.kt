package app.vellum.reader.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ReadingPositionEntity::class,
        AnnotationEntity::class,
        PdfStrokeEntity::class,
        ComicPanelEntity::class,
        ReadingSessionEntity::class,
        CollectionEntity::class,
        BookCollectionCrossRef::class,
        TagEntity::class,
        BookTagCrossRef::class,
        GenreEntity::class,
        BookGenreCrossRef::class,
        BookTextFts::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class VellumDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun collectionDao(): CollectionDao
    abstract fun searchDao(): SearchDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun pdfStrokeDao(): PdfStrokeDao
    abstract fun comicPanelDao(): ComicPanelDao
    abstract fun sessionDao(): SessionDao

    companion object {
        /** v8 -> v9: durable local provenance for Shared Library downloads. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceLibraryUuid` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `sourcePublicationUuid` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_books_sourceLibraryUuid_sourcePublicationUuid` " +
                        "ON `books` (`sourceLibraryUuid`, `sourcePublicationUuid`)",
                )
            }
        }

        /** v7 -> v8: primary categories and reusable, overlapping genres. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `category` TEXT")
                db.execSQL(
                    "UPDATE `books` SET `category` = 'Comics & Manga' " +
                        "WHERE `format` IN ('cbz', 'cbr', 'comic-epub')",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `genres` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                        "PRIMARY KEY(`uuid`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_genres` (`bookUuid` TEXT NOT NULL, `genreUuid` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`bookUuid`, `genreUuid`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_book_genres_genreUuid` ON `book_genres` (`genreUuid`)",
                )
            }
        }

        /** v6 -> v7: indices for high-frequency per-book child lookups. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotations_bookUuid` ON `annotations` (`bookUuid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_sessions_bookUuid` ON `reading_sessions` (`bookUuid`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_comic_panels_bookUuid_pageIndex` " +
                        "ON `comic_panels` (`bookUuid`, `pageIndex`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pdf_strokes_bookUuid_pageIndex` " +
                        "ON `pdf_strokes` (`bookUuid`, `pageIndex`)",
                )
                db.execSQL(
                    "UPDATE `books` SET `author` = 'Unknown author' " +
                        "WHERE (`format` = 'pdf' AND `author` = 'PDF') " +
                        "OR (`format` IN ('cbz', 'cbr', 'comic-epub') AND `author` = 'Comic')",
                )
            }
        }

        /** v1 → v2: series/cover columns, collections, tags, full-text index. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `coverPath` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `seriesName` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `seriesIndex` REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`uuid`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_collections` (`bookUuid` TEXT NOT NULL, `collectionUuid` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`bookUuid`, `collectionUuid`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`uuid`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_tags` (`bookUuid` TEXT NOT NULL, `tagUuid` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`bookUuid`, `tagUuid`))",
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `book_text_fts` USING FTS4(`bookUuid` TEXT NOT NULL, " +
                        "`chapterIndex` TEXT NOT NULL, `body` TEXT NOT NULL)",
                )
            }
        }

        /** v2 → v3: annotations (highlights + margin notes). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `annotations` (`uuid` TEXT NOT NULL, `bookUuid` TEXT NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, `chapterHref` TEXT NOT NULL, `startChar` INTEGER NOT NULL, " +
                        "`endChar` INTEGER NOT NULL, `quote` TEXT NOT NULL, `colorId` TEXT NOT NULL, `note` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`uuid`))",
                )
            }
        }

        /** v5 → v6: reading sessions — the insights substrate. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_sessions` (`uuid` TEXT NOT NULL, `bookUuid` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `msRead` INTEGER NOT NULL, " +
                        "`pagesTurned` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
                )
            }
        }

        /** v4 → v5: comics — per-book RTL flag, guided-view panel rects. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `comicRtl` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `comic_panels` (`uuid` TEXT NOT NULL, `bookUuid` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, `ord` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, " +
                        "`right` REAL NOT NULL, `bottom` REAL NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`uuid`))",
                )
            }
        }

        /** v3 → v4: freehand PDF ink strokes. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pdf_strokes` (`uuid` TEXT NOT NULL, `bookUuid` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, `colorId` TEXT NOT NULL, `strokeWidth` REAL NOT NULL, " +
                        "`points` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deletedAt` INTEGER, PRIMARY KEY(`uuid`))",
                )
            }
        }
    }
}
