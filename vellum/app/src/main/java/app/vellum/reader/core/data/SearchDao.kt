package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** One full-text hit: a chapter containing the term, with a preview snippet. */
data class PassageHit(
    val bookUuid: String,
    val chapterIndex: String,
    val snippet: String,
    /** 0-based character offset of the first match in the chapter body. */
    val firstMatchOffset: Int,
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
        """
        SELECT bookUuid, chapterIndex,
               snippet(book_text_fts, '⟪', '⟫', '…', 2, 12) AS snippet,
               (instr(lower(body), lower(:rawTerm)) - 1) AS firstMatchOffset
        FROM book_text_fts
        WHERE body MATCH :ftsQuery
        LIMIT 60
        """,
    )
    suspend fun searchAllBooks(ftsQuery: String, rawTerm: String): List<PassageHit>

    @Query(
        """
        SELECT bookUuid, chapterIndex,
               snippet(book_text_fts, '⟪', '⟫', '…', 2, 12) AS snippet,
               (instr(lower(body), lower(:rawTerm)) - 1) AS firstMatchOffset
        FROM book_text_fts
        WHERE body MATCH :ftsQuery AND bookUuid = :bookUuid
        LIMIT 60
        """,
    )
    suspend fun searchInBook(bookUuid: String, ftsQuery: String, rawTerm: String): List<PassageHit>
}
