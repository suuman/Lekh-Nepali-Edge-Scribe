package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val duration: String,
    val transcribedText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStrict: Boolean = true
) : Serializable
