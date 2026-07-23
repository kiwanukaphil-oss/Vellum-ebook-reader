package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** One matching FTS chapter; occurrences are expanded in the ViewModel. */
data class ChapterSearchHit(
    val bookUuid: String,
    val bookTitle: String,
    val chapterIndex: String,
    val body: String,
)

@Dao
interface SearchDao {

    @Insert
    suspend fun insertChapterText(row: BookTextFts)

    @Query("DELETE FROM book_text_fts WHERE bookUuid = :bookUuid")
    suspend fun deleteForBook(bookUuid: String)

    @Query("SELECT COUNT(*) FROM book_text_fts WHERE bookUuid = :bookUuid")
    suspend fun chapterCountForBook(bookUuid: String): Int

    @Query(
        "SELECT substr(body, 1, :characters) FROM book_text_fts " +
            "WHERE bookUuid = :bookUuid ORDER BY CAST(chapterIndex AS INTEGER) LIMIT 2",
    )
    suspend fun excerptForBook(bookUuid: String, characters: Int = 2500): List<String>

    /** Delete-then-insert is one transaction, so search never sees half an index. */
    @Transaction
    suspend fun replaceForBook(bookUuid: String, rows: List<BookTextFts>) {
        deleteForBook(bookUuid)
        rows.forEach { insertChapterText(it) }
    }

    @Query(
        """
        SELECT f.bookUuid AS bookUuid, b.title AS bookTitle,
               f.chapterIndex AS chapterIndex, f.body AS body
        FROM book_text_fts AS f
        JOIN books AS b ON b.uuid = f.bookUuid
        WHERE book_text_fts MATCH :ftsQuery AND b.deletedAt IS NULL
        LIMIT 60
        """,
    )
    suspend fun searchAllBooks(ftsQuery: String): List<ChapterSearchHit>

    @Query(
        """
        SELECT f.bookUuid AS bookUuid, b.title AS bookTitle,
               f.chapterIndex AS chapterIndex, f.body AS body
        FROM book_text_fts AS f
        JOIN books AS b ON b.uuid = f.bookUuid
        WHERE book_text_fts MATCH :ftsQuery AND f.bookUuid = :bookUuid AND b.deletedAt IS NULL
        LIMIT 60
        """,
    )
    suspend fun searchInBook(bookUuid: String, ftsQuery: String): List<ChapterSearchHit>
}
