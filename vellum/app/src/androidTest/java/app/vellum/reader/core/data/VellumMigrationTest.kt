package app.vellum.reader.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VellumMigrationTest {
    @Test
    fun migrationTenToElevenAddsCachedContentFingerprintWithoutChangingBooks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-10-11-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(10) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE books (" +
                                    "uuid TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, fileName TEXT NOT NULL)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO books VALUES ('existing', 'Existing book', 'existing.epub')")

            VellumDatabase.MIGRATION_10_11.migrate(database)

            assertTrue(columnNames(database, "books").contains("contentSha256"))
            database.query(
                "SELECT title, fileName, contentSha256 FROM books WHERE uuid = 'existing'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing book", cursor.getString(0))
                assertEquals("existing.epub", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationNineToTenAddsReversibleAiLibrarianHistoryWithoutChangingBooks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-9-10-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE books (" +
                                    "uuid TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, fileName TEXT NOT NULL)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO books VALUES ('existing', 'Existing book', 'existing.epub')")

            VellumDatabase.MIGRATION_9_10.migrate(database)

            assertTrue(tableNames(database).contains("ai_metadata_suggestions"))
            assertTrue(
                indexNames(database, "ai_metadata_suggestions")
                    .contains("index_ai_metadata_suggestions_bookUuid"),
            )
            assertTrue(
                indexNames(database, "ai_metadata_suggestions")
                    .contains("index_ai_metadata_suggestions_status"),
            )
            database.query("SELECT title, fileName FROM books WHERE uuid = 'existing'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing book", cursor.getString(0))
                assertEquals("existing.epub", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationEightToNineAddsSharedProvenanceWithoutChangingExistingBooks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-8-9-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE books (" +
                                    "uuid TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, fileName TEXT NOT NULL)",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO books VALUES ('existing', 'Existing book', 'existing.epub')")

            VellumDatabase.MIGRATION_8_9.migrate(database)

            val columns = columnNames(database, "books")
            assertTrue(columns.contains("sourceLibraryUuid"))
            assertTrue(columns.contains("sourcePublicationUuid"))
            assertTrue(
                indexNames(database, "books")
                    .contains("index_books_sourceLibraryUuid_sourcePublicationUuid"),
            )
            database.query(
                "SELECT title, fileName, sourceLibraryUuid, sourcePublicationUuid " +
                    "FROM books WHERE uuid = 'existing'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing book", cursor.getString(0))
                assertEquals("existing.epub", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationSevenToEightAddsCategoriesAndGenresWithoutMisclassifyingBooks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-7-8-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE books (uuid TEXT PRIMARY KEY NOT NULL, format TEXT NOT NULL)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO books VALUES ('novel', 'epub')")
            database.execSQL("INSERT INTO books VALUES ('comic', 'cbz')")

            VellumDatabase.MIGRATION_7_8.migrate(database)

            assertTrue(columnNames(database, "books").contains("category"))
            assertTrue(tableNames(database).contains("genres"))
            assertTrue(tableNames(database).contains("book_genres"))
            assertTrue(indexNames(database, "book_genres").contains("index_book_genres_genreUuid"))
            database.query("SELECT category FROM books WHERE uuid = 'comic'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Comics & Manga", cursor.getString(0))
            }
            database.query("SELECT category FROM books WHERE uuid = 'novel'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationSixToSevenAddsIndicesAndNormalizesPlaceholderAuthors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-6-7-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE books (uuid TEXT PRIMARY KEY NOT NULL, author TEXT NOT NULL, format TEXT NOT NULL)")
                            db.execSQL("CREATE TABLE annotations (uuid TEXT PRIMARY KEY NOT NULL, bookUuid TEXT NOT NULL)")
                            db.execSQL("CREATE TABLE reading_sessions (uuid TEXT PRIMARY KEY NOT NULL, bookUuid TEXT NOT NULL)")
                            db.execSQL("CREATE TABLE comic_panels (uuid TEXT PRIMARY KEY NOT NULL, bookUuid TEXT NOT NULL, pageIndex INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE pdf_strokes (uuid TEXT PRIMARY KEY NOT NULL, bookUuid TEXT NOT NULL, pageIndex INTEGER NOT NULL)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO books VALUES ('pdf-book', 'PDF', 'pdf')")
            database.execSQL("INSERT INTO books VALUES ('epub-book', 'Real author', 'epub')")

            VellumDatabase.MIGRATION_6_7.migrate(database)

            assertTrue(indexNames(database, "annotations").contains("index_annotations_bookUuid"))
            assertTrue(indexNames(database, "reading_sessions").contains("index_reading_sessions_bookUuid"))
            assertTrue(indexNames(database, "comic_panels").contains("index_comic_panels_bookUuid_pageIndex"))
            assertTrue(indexNames(database, "pdf_strokes").contains("index_pdf_strokes_bookUuid_pageIndex"))
            database.query("SELECT author FROM books WHERE uuid = 'pdf-book'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Unknown author", cursor.getString(0))
            }
            database.query("SELECT author FROM books WHERE uuid = 'epub-book'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Real author", cursor.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun indexNames(database: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        database.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
        }
    }

    private fun columnNames(database: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
        }
    }

    private fun tableNames(database: SupportSQLiteDatabase): Set<String> = buildSet {
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }
}
