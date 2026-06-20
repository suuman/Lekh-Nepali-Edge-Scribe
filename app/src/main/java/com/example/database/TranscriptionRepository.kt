package com.example.database

import kotlinx.coroutines.flow.Flow

class TranscriptionRepository(private val transcriptionDao: TranscriptionDao) {
    val allTranscriptions: Flow<List<TranscriptionEntity>> = transcriptionDao.getAllTranscriptions()

    suspend fun insert(transcription: TranscriptionEntity) {
        transcriptionDao.insertTranscription(transcription)
    }

    suspend fun delete(transcription: TranscriptionEntity) {
        transcriptionDao.deleteTranscription(transcription)
    }

    suspend fun clearAll() {
        transcriptionDao.clearAll()
    }
}
