#ifndef WAV_ENCODER_H
#define WAV_ENCODER_H

#include "EncoderSink.h"

class WavEncoder : public EncoderSink {
public:
    WavEncoder() = default;
    ~WavEncoder() override { Close(); }

    bool Open(const char* filePath, int32_t sampleRate,
              int32_t channelCount, int32_t bitDepth) override;
    bool Write(const float* data, int32_t numFrames) override;
    bool Close() override;
};

#endif // WAV_ENCODER_H
