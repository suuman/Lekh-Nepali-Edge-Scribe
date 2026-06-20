package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(transcription: TranscriptionEntity)

    @Delete
    suspend fun deleteTranscription(transcription: TranscriptionEntity)

    @Query("DELETE FROM transcriptions")
    suspend fun clearAll()
}
