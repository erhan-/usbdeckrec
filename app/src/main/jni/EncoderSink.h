#ifndef ENCODER_SINK_H
#define ENCODER_SINK_H

#include <cstdint>

class EncoderSink {
public:
    virtual ~EncoderSink() = default;

    virtual bool Open(const char* filePath, int32_t sampleRate,
                      int32_t channelCount, int32_t bitDepth) = 0;
    virtual bool Write(const float* data, int32_t numFrames) = 0;
    virtual bool Close() = 0;
};

#endif // ENCODER_SINK_H
