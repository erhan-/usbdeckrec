package com.usbdeckrec.model

data class RecordedTrack(
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val contentUri: String? = null,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val format: String,
    val dateCreated: Long
)
