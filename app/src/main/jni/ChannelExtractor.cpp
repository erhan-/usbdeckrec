#include "ChannelExtractor.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void ChannelExtractor::ExtractStereo(const float* input, int32_t numFrames,
                                      int32_t totalChannels,
                                      int32_t leftChannel,
                                      int32_t rightChannel,
                                      float* output) {
    // Validate channel indices
    if (leftChannel < 0 || rightChannel < 0 ||
        leftChannel >= totalChannels || rightChannel >= totalChannels) {
        LOGE("ExtractStereo: invalid channel indices L=%d R=%d (total=%d)",
             leftChannel, rightChannel, totalChannels);
        // Return without writing anything — output buffer remains unchanged
        return;
    }

    if (numFrames <= 0 || totalChannels <= 0 || !input || !output) {
        LOGE("ExtractStereo: invalid parameters (frames=%d, ch=%d)",
             numFrames, totalChannels);
        return;
    }

    // Extract the stereo pair: for each frame, copy left and right channels
    // into the interleaved stereo output (L, R, L, R, ...)
    for (int32_t i = 0; i < numFrames; ++i) {
        const int32_t inputOffset = i * totalChannels;
        const int32_t outputOffset = i * 2;
        output[outputOffset]     = input[inputOffset + leftChannel];
        output[outputOffset + 1] = input[inputOffset + rightChannel];
    }

    LOGI("ExtractStereo: extracted %d frames (%d ch -> stereo, L=%d R=%d)",
         numFrames, totalChannels, leftChannel, rightChannel);
}
