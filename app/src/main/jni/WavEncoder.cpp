#include "WavEncoder.h"
#include <android/log.h>

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// WAV encoding is done via Android MediaCodec on the Kotlin side
// per the approved plan. These stubs are kept for future native
// encoding if needed (e.g., offline processing).

bool WavEncoder::Open(const char* filePath, int32_t sampleRate,
                       int32_t channelCount, int32_t bitDepth) {
    LOGI("WavEncoder::Open stub — native WAV encoding not yet implemented");
    LOGE("WavEncoder::Open: use Kotlin MediaCodec for WAV encoding");
    return false;
}

bool WavEncoder::Write(const float* data, int32_t numFrames) {
    LOGE("WavEncoder::Write: use Kotlin MediaCodec for WAV encoding");
    return false;
}

bool WavEncoder::Close() {
    LOGI("WavEncoder::Close stub");
    return true;
}
