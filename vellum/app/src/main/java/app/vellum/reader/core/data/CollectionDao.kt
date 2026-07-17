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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookCollection(link: BookCollectionCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookTag(link: BookTagCrossRef)

    // ---- Sync: raw table dumps including tombstones ----------------------
    @Query("SELECT * FROM collections")
    suspend fun allCollectionsRaw(): List<CollectionEntity>

    @Query("SELECT * FROM tags")
    suspend fun allTagsRaw(): List<TagEntity>

    @Query("SELECT * FROM book_collections")
    suspend fun allBookCollectionsRaw(): List<BookCollectionCrossRef>

    @Query("SELECT * FROM book_tags")
    suspend fun allBookTagsRaw(): List<BookTagCrossRef>
}
