package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions")
    suspend fun allRaw(): List<ReadingSessionEntity>
}
