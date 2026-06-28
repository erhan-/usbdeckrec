package com.usbdeckrec.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val contentUri: String? = null,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val format: String,
    val dateCreated: Long,
    val isSession: Boolean = true,
    val mixerModel: String? = null,
    val mixerProfileJson: String? = null,
    val tags: String? = null
)
