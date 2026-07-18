package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfStrokeDao {

    @Query("SELECT * FROM pdf_strokes WHERE bookUuid = :bookUuid AND deletedAt IS NULL ORDER BY createdAt")
    fun observeForBook(bookUuid: String): Flow<List<PdfStrokeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: PdfStrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<PdfStrokeEntity>)

    @Query("SELECT * FROM pdf_strokes WHERE bookUuid = :bookUuid AND pageIndex = :pageIndex AND deletedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForPage(bookUuid: String, pageIndex: Int): PdfStrokeEntity?

    @Query("UPDATE pdf_strokes SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, deletedAt: Long)

    @Query("UPDATE pdf_strokes SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE bookUuid = :bookUuid AND deletedAt IS NULL")
    suspend fun softDeleteForBook(bookUuid: String, deletedAt: Long)

    @Query("SELECT * FROM pdf_strokes")
    suspend fun allRaw(): List<PdfStrokeEntity>

    @Query("DELETE FROM pdf_strokes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun purgeTombstones(cutoff: Long)
}
