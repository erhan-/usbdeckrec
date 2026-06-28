#ifndef AUDIO_CAPTURE_ENGINE_H
#define AUDIO_CAPTURE_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include "LockFreeQueue.h"

struct AudioEngineConfig {
    int32_t sampleRate = 48000;
    int32_t channelCount = 2;
    int32_t masterLeftChannel = 0;
    int32_t masterRightChannel = 1;
    oboe::AudioFormat format = oboe::AudioFormat::Float;
};

class AudioCaptureEngine : public oboe::AudioStreamDataCallback,
                            public oboe::AudioStreamErrorCallback {
public:
    AudioCaptureEngine();
    ~AudioCaptureEngine() { StopCapture(); }

    bool StartCapture(int32_t usbDeviceId, const AudioEngineConfig& config);
    void StopCapture();
    bool IsRecording() const;
    int32_t ReadCapturedData(float* outputBuffer, int32_t maxFrames);
    void GetLevelData(float* outLeftPeak, float* outRightPeak,
                      float* outLeftRms, float* outRightRms);

    // Monitoring mode: opens stream and processes audio for levels
    // but does NOT write to the capture queue. Use StartCapture() after
    // monitoring to begin queueing data on the already-open stream.
    bool StartMonitoring(int32_t usbDeviceId, const AudioEngineConfig& config);
    bool IsMonitoring() const;

    // Get the last error message from the native engine
    const char* GetLastErrorMessage() const;

    // AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

    // AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* oboeStream,
                           oboe::Result error) override;

private:
    bool OpenAndStartStream(int32_t usbDeviceId, bool startRecording);
    void UpdatePeak(std::atomic<float>& peak, float value);
    void ProcessAudioData(void* audioData, int32_t numFrames, bool writeToQueue);

    std::shared_ptr<oboe::AudioStream> mStream;

    // Guard access to mStream to prevent deadlock between StopCapture()
    // and onErrorAfterClose() (which runs on the audio thread).
    std::mutex mStreamMutex;

    // Set by onErrorAfterClose() when a stream error occurs.
    // StopCapture() checks this flag to handle cleanup without trying to
    // close the stream from the error callback (which Oboe forbids).
    std::atomic<bool> mStreamError{false};

    AudioEngineConfig mConfig;
    int32_t mBufferSizeFrames = 0;
    std::atomic<bool> mIsRecording{false};
    std::atomic<bool> mIsMonitoring{false};

    // Protected by mErrorMutex for thread-safe read/write from JNI and audio callback
    mutable std::mutex mErrorMutex;
    std::string mLastErrorMessage;

    LockFreeQueue<float> mQueue;

    // Atomic level tracking for non-blocking JNI polling
    std::atomic<float> mLeftPeak{0.0f};
    std::atomic<float> mRightPeak{0.0f};
    std::atomic<float> mLeftRmsSum{0.0f};
    std::atomic<float> mRightRmsSum{0.0f};
    std::atomic<int32_t> mRmsCount{0};
};

#endif // AUDIO_CAPTURE_ENGINE_H
