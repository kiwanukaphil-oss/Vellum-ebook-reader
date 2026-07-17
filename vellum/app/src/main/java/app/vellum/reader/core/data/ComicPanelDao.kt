package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicPanelDao {

    @Query("SELECT * FROM comic_panels WHERE bookUuid = :bookUuid AND deletedAt IS NULL ORDER BY pageIndex, ord")
    fun observeForBook(bookUuid: String): Flow<List<ComicPanelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(panel: ComicPanelEntity)

    @Query("SELECT COALESCE(MAX(ord), -1) + 1 FROM comic_panels WHERE bookUuid = :bookUuid AND pageIndex = :pageIndex AND deletedAt IS NULL")
    suspend fun nextOrdinal(bookUuid: String, pageIndex: Int): Int

    @Query("SELECT * FROM comic_panels WHERE bookUuid = :bookUuid AND pageIndex = :pageIndex AND deletedAt IS NULL ORDER BY ord DESC LIMIT 1")
    suspend fun latestForPage(bookUuid: String, pageIndex: Int): ComicPanelEntity?

    @Query("UPDATE comic_panels SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, deletedAt: Long)

    @Query("UPDATE comic_panels SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE bookUuid = :bookUuid AND deletedAt IS NULL")
    suspend fun softDeleteForBook(bookUuid: String, deletedAt: Long)

    @Query("SELECT * FROM comic_panels")
    suspend fun allRaw(): List<ComicPanelEntity>
}
