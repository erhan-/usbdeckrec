package com.usbdeckrec.model

enum class RecordingStatus {
    Idle,
    Connected,
    Recording,
    Paused,
    Error
}

data class RecordingState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val connectedDeviceName: String? = null,
    val totalChannels: Int = 2,
    val masterChannels: String = "Ch 1/2",
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val elapsedTimeMs: Long = 0L,
    val errorMessage: String? = null
)
