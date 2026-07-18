package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ReadingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ReadingSessionEntity>)

    @Query("SELECT * FROM reading_sessions")
    suspend fun allRaw(): List<ReadingSessionEntity>
}
