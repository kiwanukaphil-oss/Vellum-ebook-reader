package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections WHERE deletedAt IS NULL ORDER BY name")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM tags WHERE deletedAt IS NULL ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM book_collections WHERE deletedAt IS NULL")
    fun observeBookCollections(): Flow<List<BookCollectionCrossRef>>

    @Query("SELECT * FROM book_tags WHERE deletedAt IS NULL")
    fun observeBookTags(): Flow<List<BookTagCrossRef>>

    @Query("SELECT * FROM genres WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeGenres(): Flow<List<GenreEntity>>

    @Query("SELECT * FROM book_genres WHERE deletedAt IS NULL")
    fun observeBookGenres(): Flow<List<BookGenreCrossRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookCollection(link: BookCollectionCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookTag(link: BookTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGenre(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookGenre(link: BookGenreCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(rows: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(rows: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookCollections(rows: List<BookCollectionCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookTags(rows: List<BookTagCrossRef>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGenres(rows: List<GenreEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookGenres(rows: List<BookGenreCrossRef>)

    // ---- Sync: raw table dumps including tombstones ----------------------
    @Query("SELECT * FROM collections")
    suspend fun allCollectionsRaw(): List<CollectionEntity>

    @Query("SELECT * FROM tags")
    suspend fun allTagsRaw(): List<TagEntity>

    @Query("SELECT * FROM book_collections")
    suspend fun allBookCollectionsRaw(): List<BookCollectionCrossRef>

    @Query("SELECT * FROM book_tags")
    suspend fun allBookTagsRaw(): List<BookTagCrossRef>

    @Query("SELECT * FROM genres")
    suspend fun allGenresRaw(): List<GenreEntity>

    @Query("SELECT * FROM book_genres")
    suspend fun allBookGenresRaw(): List<BookGenreCrossRef>

    @Query("UPDATE book_genres SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE bookUuid = :bookUuid")
    suspend fun softDeleteGenresForBook(bookUuid: String, deletedAt: Long)

    @Query("DELETE FROM collections WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeCollectionTombstones(cutoff: Long)

    @Query("DELETE FROM tags WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeTagTombstones(cutoff: Long)

    @Query("DELETE FROM book_collections WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeBookCollectionTombstones(cutoff: Long)

    @Query("DELETE FROM book_tags WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeBookTagTombstones(cutoff: Long)

    @Query("DELETE FROM genres WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeGenreTombstones(cutoff: Long)

    @Query("DELETE FROM book_genres WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeBookGenreTombstones(cutoff: Long)
}
