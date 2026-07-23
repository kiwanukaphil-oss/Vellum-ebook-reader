package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE deletedAt IS NULL ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun observeShelf(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): BookEntity?

    @Query("SELECT fileName FROM books")
    suspend fun allFileNames(): List<String>

    @Query("SELECT * FROM books WHERE fileName = :fileName LIMIT 1")
    suspend fun byFileName(fileName: String): BookEntity?

    @Query(
        "SELECT * FROM books WHERE sourceLibraryUuid = :libraryUuid " +
            "AND sourcePublicationUuid = :publicationUuid AND deletedAt IS NULL LIMIT 1",
    )
    suspend fun bySharedPublication(libraryUuid: String, publicationUuid: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(books: List<BookEntity>)

    // Deliberately does NOT bump updatedAt: sync resolves the whole row by
    // that timestamp, so merely opening a book must not beat a real metadata
    // edit made on another device. lastOpenedAt may diverge across devices.
    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE uuid = :uuid")
    suspend fun markOpened(uuid: String, openedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE bookUuid = :bookUuid")
    suspend fun positionFor(bookUuid: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_positions")
    fun observePositions(): Flow<List<ReadingPositionEntity>>

    @Query("DELETE FROM reading_positions WHERE bookUuid = :bookUuid")
    suspend fun deletePosition(bookUuid: String)

    @Query(
        "UPDATE books SET title = :title, author = :author, seriesName = :seriesName, " +
            "seriesIndex = :seriesIndex, updatedAt = :updatedAt WHERE uuid = :uuid",
    )
    suspend fun updateMetadata(
        uuid: String,
        title: String,
        author: String,
        seriesName: String?,
        seriesIndex: Float?,
        updatedAt: Long,
    )

    @Query("UPDATE books SET category = :category, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun updateCategory(uuid: String, category: String?, updatedAt: Long)

    @Query("UPDATE books SET coverPath = :coverPath, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun setCover(uuid: String, coverPath: String?, updatedAt: Long)

    @Query("UPDATE books SET comicRtl = :rtl, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun setComicRtl(uuid: String, rtl: Boolean, updatedAt: Long)

    @Query(
        "UPDATE books SET sourceLibraryUuid = :libraryUuid, sourcePublicationUuid = :publicationUuid, " +
            "updatedAt = :updatedAt WHERE uuid = :uuid",
    )
    suspend fun setSharedProvenance(
        uuid: String,
        libraryUuid: String,
        publicationUuid: String,
        updatedAt: Long,
    )

    /** Soft delete: tombstone for future sync; the caller removes the files. */
    @Query("UPDATE books SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, deletedAt: Long)

    /** Every live book, uncapped — for backfill/title-map uses, NOT search. */
    @Query("SELECT * FROM books WHERE deletedAt IS NULL")
    suspend fun allActive(): List<BookEntity>

    @Query(
        "SELECT * FROM books WHERE deletedAt IS NULL AND " +
            "(title LIKE '%' || :term || '%' ESCAPE '\\' OR author LIKE '%' || :term || '%' ESCAPE '\\') LIMIT 30",
    )
    suspend fun searchByTitleOrAuthor(term: String): List<BookEntity>

    @Query("DELETE FROM books WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeTombstones(cutoff: Long)

    // ---- Sync: raw table dumps including tombstones ----------------------
    @Query("SELECT * FROM books")
    suspend fun allRaw(): List<BookEntity>

    @Query("SELECT * FROM reading_positions")
    suspend fun allPositionsRaw(): List<ReadingPositionEntity>
}
