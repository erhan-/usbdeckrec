#include "EncoderSink.h"
#include <android/log.h>

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// EncoderSink is a pure virtual interface (abstract base class).
// No implementation is needed here — encoding is done via MediaCodec
// on the Kotlin side per the approved plan.
// This file exists to ensure the build system has a compilation unit
// if any future encoder subclass references are needed.

void encoder_sink_placeholder() {
    LOGI("EncoderSink interface — encoding handled by Kotlin MediaCodec");
}
