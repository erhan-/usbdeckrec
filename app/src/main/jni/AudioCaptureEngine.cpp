#include "AudioCaptureEngine.h"
#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <cmath>
#include <cstdlib>
#include <vector>

struct StreamStrategy {
    const char* name;
    oboe::AudioFormat format;
    int32_t channelCount;
    int32_t sampleRate;
    oboe::PerformanceMode perfMode;
    oboe::SharingMode sharingMode;
    oboe::InputPreset inputPreset;
};

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioCaptureEngine::AudioCaptureEngine()
    : mQueue(65536) // 64k samples capacity
{
}

const char* AudioCaptureEngine::GetLastErrorMessage() const {
    std::lock_guard<std::mutex> lock(mErrorMutex);
    return mLastErrorMessage.c_str();
}

bool AudioCaptureEngine::StartCapture(int32_t usbDeviceId,
                                       const AudioEngineConfig& config) {
    // If monitoring, the stream is already open — just start writing to queue
    if (mStream && mIsMonitoring.load(std::memory_order_acquire)) {
        mConfig = config;
        mQueue.Reset();
        mIsMonitoring.store(false, std::memory_order_release);
        mIsRecording.store(true, std::memory_order_release);
        mStreamError.store(false, std::memory_order_release);
        LOGI("Monitoring → capture: ch=%d, L=%d, R=%d",
             config.channelCount, config.masterLeftChannel, config.masterRightChannel);
        return true;
    }

    // Fresh start: open a new stream
    mConfig = config;
    mQueue.Reset();

    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mStreamError.store(false, std::memory_order_release);

    return OpenAndStartStream(usbDeviceId, true);
}

bool AudioCaptureEngine::StartMonitoring(int32_t usbDeviceId,
                                          const AudioEngineConfig& config) {
    StopCapture();

    mConfig = config;
    mQueue.Reset();

    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mStreamError.store(false, std::memory_order_release);

    bool opened = OpenAndStartStream(usbDeviceId, false);
    if (opened) {
        mIsMonitoring.store(true, std::memory_order_release);
        LOGI("Monitoring started on device %d", usbDeviceId);
    }
    return opened;
}

// Helper: configure a builder with parameters for a single open attempt
static oboe::Result TryOpenStream(
    std::shared_ptr<oboe::AudioStream>& stream,
    int32_t deviceId,
    oboe::AudioFormat format,
    int32_t channelCount,
    int32_t sampleRate,
    oboe::PerformanceMode perfMode,
    oboe::SharingMode sharingMode,
    oboe::InputPreset inputPreset,
    oboe::AudioStreamDataCallback* dataCb,
    oboe::AudioStreamErrorCallback* errorCb)
{
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
           ->setPerformanceMode(perfMode)
           ->setSharingMode(sharingMode)
           ->setFormat(format)
           ->setChannelCount(channelCount)
           ->setSampleRate(sampleRate)
           ->setDeviceId(deviceId)
           ->setInputPreset(inputPreset)
           ->setDataCallback(dataCb)
           ->setErrorCallback(errorCb)
           ->setFormatConversionAllowed(true);
    return builder.openStream(stream);
}

bool AudioCaptureEngine::OpenAndStartStream(int32_t usbDeviceId, bool startRecording) {
    // Try opening the stream with progressively relaxed settings.
    // USB audio devices on Android often have compatibility constraints.
    
    std::vector<StreamStrategy> strategies = {
        // Try VoiceRecognition preset first (works well with USB audio on most devices)
        {"Float+VRec+Exclusive+LL", oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::LowLatency, oboe::SharingMode::Exclusive, oboe::InputPreset::VoiceRecognition},
        {"Float+VRec+Shared+LL",    oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::LowLatency, oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition},
        {"Float+VRec+Shared+None",  oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::None,       oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition},

        // Fallback to Generic preset
        {"Float+Gen+Exclusive+LL",  oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::LowLatency, oboe::SharingMode::Exclusive, oboe::InputPreset::Generic},
        {"Float+Gen+Shared+LL",     oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::LowLatency, oboe::SharingMode::Shared, oboe::InputPreset::Generic},
        {"Float+Gen+Shared+None",   oboe::AudioFormat::Float, mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::None,       oboe::SharingMode::Shared, oboe::InputPreset::Generic},

        // Try Unspecified rate with each preset
        {"Float+VRec+UnspecRate",   oboe::AudioFormat::Float, mConfig.channelCount, oboe::Unspecified,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition},
        {"Float+Gen+UnspecRate",    oboe::AudioFormat::Float, mConfig.channelCount, oboe::Unspecified,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::Generic},

        // Try I16 format with each preset
        {"I16+VRec+Shared+None",   oboe::AudioFormat::I16,   mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::VoiceRecognition},
        {"I16+Gen+Shared+None",    oboe::AudioFormat::I16,   mConfig.channelCount, mConfig.sampleRate,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::Generic},

        // Unspecified channels and rate - last resort
        {"Float+Gen+UnspecCh",     oboe::AudioFormat::Float, oboe::Unspecified, oboe::Unspecified,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::Generic},
        {"I16+Gen+Stereo",         oboe::AudioFormat::I16,   2,                    oboe::Unspecified,
         oboe::PerformanceMode::None, oboe::SharingMode::Shared, oboe::InputPreset::Generic},
    };

    bool opened = false;
    for (const auto& strategy : strategies) {
        oboe::Result result = TryOpenStream(mStream, usbDeviceId,
                                            strategy.format, strategy.channelCount, strategy.sampleRate,
                                            strategy.perfMode, strategy.sharingMode,
                                            strategy.inputPreset,
                                            this, this);
        if (result == oboe::Result::OK) {
            LOGI("SUCCESS '%s': %d ch, %d Hz, format=%s",
                 strategy.name, mStream->getChannelCount(), mStream->getSampleRate(),
                 oboe::convertToText(mStream->getFormat()));
            opened = true;
            break;
        } else {
            LOGE("FAIL '%s': %s", strategy.name, oboe::convertToText(result));
        }
    }

    if (!opened) {
        std::lock_guard<std::mutex> lock(mErrorMutex);
        mLastErrorMessage = "All stream open strategies failed";
        LOGE("%s", mLastErrorMessage.c_str());
        return false;
    }

    // Stream opened successfully — now start it
    mBufferSizeFrames = mStream->getBufferSizeInFrames();

    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        {
            std::lock_guard<std::mutex> lock(mErrorMutex);
            mLastErrorMessage = std::string("Oboe requestStart failed: ")
                              + oboe::convertToText(result);
            LOGE("%s", mLastErrorMessage.c_str());
        }
        {
            std::lock_guard<std::mutex> lock(mStreamMutex);
            mStream->close();
            mStream.reset();
        }
        return false;
    }

    LOGI("Stream started successfully: buffer=%d frames", mBufferSizeFrames);
    {
        std::lock_guard<std::mutex> lock(mErrorMutex);
        mLastErrorMessage.clear();
    }
    if (startRecording) {
        mIsRecording.store(true, std::memory_order_release);
    }
    return true;
}

void AudioCaptureEngine::StopCapture() {
    mIsRecording.store(false, std::memory_order_release);
    mIsMonitoring.store(false, std::memory_order_release);

    // Lock the stream mutex to prevent concurrent access with the
    // error callback (which runs on the audio thread).
    std::lock_guard<std::mutex> lock(mStreamMutex);

    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }

    mStreamError.store(false, std::memory_order_release);
    LOGI("Capture/Monitoring stopped");
}

bool AudioCaptureEngine::IsRecording() const {
    return mIsRecording.load(std::memory_order_acquire);
}

bool AudioCaptureEngine::IsMonitoring() const {
    return mIsMonitoring.load(std::memory_order_acquire);
}

int32_t AudioCaptureEngine::ReadCapturedData(float* outputBuffer,
                                               int32_t maxFrames) {
    int32_t samplesToRead = maxFrames * 2;
    return mQueue.Read(outputBuffer, samplesToRead) / 2;
}

void AudioCaptureEngine::GetLevelData(float* outLeftPeak,
                                       float* outRightPeak,
                                       float* outLeftRms,
                                       float* outRightRms) {
    *outLeftPeak = mLeftPeak.exchange(0.0f, std::memory_order_acq_rel);
    *outRightPeak = mRightPeak.exchange(0.0f, std::memory_order_acq_rel);

    float leftRmsSum = mLeftRmsSum.exchange(0.0f, std::memory_order_acq_rel);
    float rightRmsSum = mRightRmsSum.exchange(0.0f, std::memory_order_acq_rel);
    int32_t count = mRmsCount.exchange(0, std::memory_order_acq_rel);

    if (count > 0) {
        *outLeftRms = std::sqrt(leftRmsSum / count);
        *outRightRms = std::sqrt(rightRmsSum / count);
    } else {
        *outLeftRms = 0.0f;
        *outRightRms = 0.0f;
    }
}

void AudioCaptureEngine::ProcessAudioData(void* audioData, int32_t numFrames,
                                           bool writeToQueue) {
    // NOTE: We do NOT lock mStreamMutex here to avoid deadlock with StopCapture().
    // StopCapture() holds mStreamMutex while calling mStream->requestStop()/close(),
    // and Oboe's close() may wait for the audio callback to complete. If we tried
    // to acquire mStreamMutex from the audio callback thread, we'd deadlock.
    // The shared_ptr control block is thread-safe for atomic reference counting,
    // so using mStream directly here is safe.
    auto stream = mStream;
    if (!stream) return;

    const int32_t stride = stream->getChannelCount();
    int32_t leftIdx = mConfig.masterLeftChannel;
    int32_t rightIdx = mConfig.masterRightChannel;

    if (leftIdx >= stride || rightIdx >= stride) {
        leftIdx = 0;
        rightIdx = stride > 1 ? 1 : 0;
    }

    const bool isFloat = (stream->getFormat() == oboe::AudioFormat::Float);

    // Log first frame sample values for debugging channel mapping
    if (numFrames > 0 && (rand() % 100) == 0) {
        if (isFloat) {
            float* floatData = static_cast<float*>(audioData);
            LOGI("CHANNEL CHECK: stride=%d, frames=%d, masterL[%d]=%.4f, masterR[%d]=%.4f, "
                 "ch[0]=%.4f, ch[1]=%.4f, ch[6]=%.4f, ch[7]=%.4f, ch[8]=%.4f, ch[9]=%.4f",
                 stride, numFrames,
                 leftIdx, floatData[0 * stride + leftIdx],
                 rightIdx, floatData[0 * stride + rightIdx],
                 floatData[0 * stride + 0], floatData[0 * stride + 1],
                 (stride > 6) ? floatData[0 * stride + 6] : 0.0f,
                 (stride > 7) ? floatData[0 * stride + 7] : 0.0f,
                 (stride > 8) ? floatData[0 * stride + 8] : 0.0f,
                 (stride > 9) ? floatData[0 * stride + 9] : 0.0f);
        } else {
            int16_t* i16Data = static_cast<int16_t*>(audioData);
            LOGI("CHANNEL CHECK(I16): stride=%d, frames=%d, masterL[%d]=%d, masterR[%d]=%d, "
                 "ch[0]=%d, ch[1]=%d, ch[6]=%d, ch[7]=%d, ch[8]=%d, ch[9]=%d",
                 stride, numFrames,
                 leftIdx, i16Data[0 * stride + leftIdx],
                 rightIdx, i16Data[0 * stride + rightIdx],
                 i16Data[0 * stride + 0], i16Data[0 * stride + 1],
                 (stride > 6) ? i16Data[0 * stride + 6] : 0,
                 (stride > 7) ? i16Data[0 * stride + 7] : 0,
                 (stride > 8) ? i16Data[0 * stride + 8] : 0,
                 (stride > 9) ? i16Data[0 * stride + 9] : 0);
        }
    }

    float localLeftPeak = 0.0f;
    float localRightPeak = 0.0f;
    float localLeftRmsSum = 0.0f;
    float localRightRmsSum = 0.0f;

    for (int32_t i = 0; i < numFrames; ++i) {
        float leftMaster = 0.0f;
        float rightMaster = 0.0f;

        if (isFloat) {
            float* floatData = static_cast<float*>(audioData);
            leftMaster  = floatData[i * stride + leftIdx];
            rightMaster = floatData[i * stride + rightIdx];
        } else {
            int16_t* i16Data = static_cast<int16_t*>(audioData);
            leftMaster  = i16Data[i * stride + leftIdx] / 32768.0f;
            rightMaster = i16Data[i * stride + rightIdx] / 32768.0f;
        }

        const float clampedLeft  = (leftMaster  > 1.0f) ? 1.0f
                                  : (leftMaster  < -1.0f) ? -1.0f : leftMaster;
        const float clampedRight = (rightMaster > 1.0f) ? 1.0f
                                  : (rightMaster < -1.0f) ? -1.0f : rightMaster;

        if (writeToQueue) {
            float frame[2] = {clampedLeft, clampedRight};
            mQueue.Write(frame, 2);
        }

        float absLeft = std::abs(clampedLeft);
        float absRight = std::abs(clampedRight);
        if (absLeft > localLeftPeak) localLeftPeak = absLeft;
        if (absRight > localRightPeak) localRightPeak = absRight;

        localLeftRmsSum += clampedLeft * clampedLeft;
        localRightRmsSum += clampedRight * clampedRight;
    }

    UpdatePeak(mLeftPeak, localLeftPeak);
    UpdatePeak(mRightPeak, localRightPeak);

    {
        float expected = mLeftRmsSum.load(std::memory_order_relaxed);
        while (!mLeftRmsSum.compare_exchange_weak(expected, expected + localLeftRmsSum,
                                                    std::memory_order_release,
                                                    std::memory_order_relaxed));
    }
    {
        float expected = mRightRmsSum.load(std::memory_order_relaxed);
        while (!mRightRmsSum.compare_exchange_weak(expected, expected + localRightRmsSum,
                                                     std::memory_order_release,
                                                     std::memory_order_relaxed));
    }
    {
        int32_t expected = mRmsCount.load(std::memory_order_relaxed);
        while (!mRmsCount.compare_exchange_weak(expected, expected + numFrames,
                                                   std::memory_order_release,
                                                   std::memory_order_relaxed));
    }
}

// --- AudioStreamDataCallback ---
oboe::DataCallbackResult AudioCaptureEngine::onAudioReady(
        oboe::AudioStream* /*audioStream*/,
        void* audioData,
        int32_t numFrames) {
    if (IsMonitoring()) {
        // Monitoring mode: process for levels and write to queue for phone playback
        ProcessAudioData(audioData, numFrames, true);
        return oboe::DataCallbackResult::Continue;
    }

    if (!IsRecording()) return oboe::DataCallbackResult::Stop;

    ProcessAudioData(audioData, numFrames, true);
    return oboe::DataCallbackResult::Continue;
}

// --- AudioStreamErrorCallback ---
//
// Oboe documentation states: "Do not close the stream from the error callback."
// The stream is already closed by Oboe when this callback fires.
// We just log, set a flag, and update the error message.
void AudioCaptureEngine::onErrorAfterClose(oboe::AudioStream* /*oboeStream*/,
                                            oboe::Result error) {
    {
        std::lock_guard<std::mutex> lock(mErrorMutex);
        mLastErrorMessage = std::string("Stream error after close: ")
                          + oboe::convertToText(error);
    }
    LOGE("%s", mLastErrorMessage.c_str());

    // Set stream error flag so StopCapture() can handle cleanup without
    // trying to close the already-closed stream.
    mStreamError.store(true, std::memory_order_release);
    mIsRecording.store(false, std::memory_order_release);
    mIsMonitoring.store(false, std::memory_order_release);
}

void AudioCaptureEngine::UpdatePeak(std::atomic<float>& peak, float value) {
    float current = peak.load(std::memory_order_relaxed);
    while (value > current &&
           !peak.compare_exchange_weak(current, value,
                                         std::memory_order_release,
                                         std::memory_order_relaxed));
}
