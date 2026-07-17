package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE bookUuid = :bookUuid AND deletedAt IS NULL ORDER BY chapterIndex, startChar")
    fun observeForBook(bookUuid: String): Flow<List<AnnotationEntity>>

    /** Every live annotation across the library — feeds the Notes tab. */
    @Query("SELECT * FROM annotations WHERE deletedAt IS NULL ORDER BY chapterIndex, startChar")
    fun observeAllLive(): Flow<List<AnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(annotation: AnnotationEntity)

    @Query("UPDATE annotations SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, deletedAt: Long)

    @Query("UPDATE annotations SET startChar = :startChar, endChar = :endChar, updatedAt = :updatedAt WHERE uuid = :uuid")
    suspend fun reanchor(uuid: String, startChar: Int, endChar: Int, updatedAt: Long)

    @Query("UPDATE annotations SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE bookUuid = :bookUuid AND deletedAt IS NULL")
    suspend fun softDeleteForBook(bookUuid: String, deletedAt: Long)

    @Query("SELECT * FROM annotations")
    suspend fun allRaw(): List<AnnotationEntity>
}
