#ifndef FLAC_ENCODER_H
#define FLAC_ENCODER_H

#include "EncoderSink.h"

class FlacEncoder : public EncoderSink {
public:
    FlacEncoder() = default;
    ~FlacEncoder() override { Close(); }

    bool Open(const char* filePath, int32_t sampleRate,
              int32_t channelCount, int32_t bitDepth) override;
    bool Write(const float* data, int32_t numFrames) override;
    bool Close() override;
};

#endif // FLAC_ENCODER_H
