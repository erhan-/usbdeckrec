#include "FlacEncoder.h"
#include <android/log.h>

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// FLAC encoding is done via Android MediaCodec on the Kotlin side
// per the approved plan. These stubs are kept for future native
// encoding if needed (e.g., offline processing).

bool FlacEncoder::Open(const char* filePath, int32_t sampleRate,
                        int32_t channelCount, int32_t bitDepth) {
    LOGI("FlacEncoder::Open stub — native FLAC encoding not yet implemented");
    LOGE("FlacEncoder::Open: use Kotlin MediaCodec for FLAC encoding");
    return false;
}

bool FlacEncoder::Write(const float* data, int32_t numFrames) {
    LOGE("FlacEncoder::Write: use Kotlin MediaCodec for FLAC encoding");
    return false;
}

bool FlacEncoder::Close() {
    LOGI("FlacEncoder::Close stub");
    return true;
}
