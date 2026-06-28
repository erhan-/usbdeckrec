#ifndef CHANNEL_EXTRACTOR_H
#define CHANNEL_EXTRACTOR_H

#include <cstdint>

class ChannelExtractor {
public:
    /**
     * Extract a stereo pair from an N-channel interleaved float buffer.
     * @param input Input buffer with interleaved multi-channel data
     * @param numFrames Number of frames in the input
     * @param totalChannels Total channels per frame
     * @param leftChannel 0-based index of the left master channel
     * @param rightChannel 0-based index of the right master channel
     * @param output Output buffer for interleaved stereo data (numFrames * 2)
     */
    static void ExtractStereo(const float* input, int32_t numFrames,
                              int32_t totalChannels,
                              int32_t leftChannel, int32_t rightChannel,
                              float* output);
};

#endif // CHANNEL_EXTRACTOR_H
