package com.usbdeckrec.audio

enum class AudioFormatType {
    FLAC,
    WAV
}

data class AudioFormat(
    val type: AudioFormatType = AudioFormatType.FLAC,
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val channelCount: Int = 2
)
