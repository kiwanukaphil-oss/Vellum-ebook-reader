package app.vellum.reader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiMetadataDao {
    @Query(
        "SELECT * FROM ai_metadata_suggestions " +
            "ORDER BY createdAt DESC",
    )
    fun observeAll(): Flow<List<AiMetadataSuggestionEntity>>

    @Query(
        "SELECT * FROM ai_metadata_suggestions " +
            "WHERE bookUuid = :bookUuid ORDER BY createdAt DESC LIMIT 1",
    )
    suspend fun latestForBook(bookUuid: String): AiMetadataSuggestionEntity?

    @Query("SELECT * FROM ai_metadata_suggestions WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): AiMetadataSuggestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: AiMetadataSuggestionEntity)

    @Query(
        "UPDATE ai_metadata_suggestions SET status = 'applied', beforeTitle = :beforeTitle, " +
        "beforeAuthor = :beforeAuthor, beforeCategory = :beforeCategory, beforeGenresJson = :beforeGenresJson, " +
            "beforeSeriesName = :beforeSeriesName, beforeSeriesIndex = :beforeSeriesIndex, " +
            "appliedTitle = :appliedTitle, appliedAuthor = :appliedAuthor, appliedCategory = :appliedCategory, " +
            "appliedGenresJson = :appliedGenresJson, appliedSeriesName = :appliedSeriesName, " +
            "appliedSeriesIndex = :appliedSeriesIndex, appliedAt = :appliedAt " +
            "WHERE uuid = :uuid",
    )
    suspend fun markApplied(
        uuid: String,
        beforeTitle: String,
        beforeAuthor: String,
        beforeCategory: String?,
        beforeGenresJson: String,
        beforeSeriesName: String?,
        beforeSeriesIndex: Float?,
        appliedTitle: String,
        appliedAuthor: String,
        appliedCategory: String?,
        appliedGenresJson: String,
        appliedSeriesName: String?,
        appliedSeriesIndex: Float?,
        appliedAt: Long,
    )

    @Query("UPDATE ai_metadata_suggestions SET status = 'dismissed' WHERE uuid = :uuid AND status = 'pending'")
    suspend fun dismiss(uuid: String)

    @Query(
        "UPDATE ai_metadata_suggestions SET status = 'reverted', revertedAt = :revertedAt " +
            "WHERE uuid = :uuid AND status = 'applied'",
    )
    suspend fun markReverted(uuid: String, revertedAt: Long)
}
