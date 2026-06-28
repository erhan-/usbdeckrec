#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <memory>
#include "AudioCaptureEngine.h"
#include "UsbIsochronousCaptureEngine.h"
#include "DjmBulkCaptureEngine.h"

#define LOG_TAG "DeckRec_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Singleton engine instances managed via JNI
static std::unique_ptr<AudioCaptureEngine> gEngineInstance = nullptr;
static std::unique_ptr<UsbIsochronousCaptureEngine> gUsbEngineInstance = nullptr;
static std::unique_ptr<DjmBulkCaptureEngine> gDjmBulkEngineInstance = nullptr;

static AudioCaptureEngine* GetEngineInstance() {
    if (!gEngineInstance) {
        gEngineInstance = std::make_unique<AudioCaptureEngine>();
        LOGI("Created AudioCaptureEngine singleton");
    }
    return gEngineInstance.get();
}

static UsbIsochronousCaptureEngine* GetUsbEngineInstance() {
    if (!gUsbEngineInstance) {
        gUsbEngineInstance = std::make_unique<UsbIsochronousCaptureEngine>();
        LOGI("Created UsbIsochronousCaptureEngine singleton");
    }
    return gUsbEngineInstance.get();
}

static DjmBulkCaptureEngine* GetDjmBulkEngineInstance() {
    if (!gDjmBulkEngineInstance) {
        gDjmBulkEngineInstance = std::make_unique<DjmBulkCaptureEngine>();
        LOGI("Created DjmBulkCaptureEngine singleton");
    }
    return gDjmBulkEngineInstance.get();
}

extern "C" {

// ── Oboe Capture ───────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartCapture(
    JNIEnv* env, jobject thiz, jint usbDeviceId,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel)
{
    AudioEngineConfig config;
    config.sampleRate = 48000;
    config.channelCount = static_cast<int32_t>(channelCount);
    config.masterLeftChannel = static_cast<int32_t>(masterLeftChannel);
    config.masterRightChannel = static_cast<int32_t>(masterRightChannel);
    config.format = oboe::AudioFormat::Float;

    LOGI("nativeStartCapture: deviceId=%d, ch=%d, L=%d, R=%d",
         usbDeviceId, channelCount, masterLeftChannel, masterRightChannel);

    auto* engine = GetEngineInstance();
    return engine->StartCapture(static_cast<int32_t>(usbDeviceId), config)
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartMonitoring(
    JNIEnv* env, jobject thiz, jint usbDeviceId,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel)
{
    AudioEngineConfig config;
    config.sampleRate = 48000;
    config.channelCount = static_cast<int32_t>(channelCount);
    config.masterLeftChannel = static_cast<int32_t>(masterLeftChannel);
    config.masterRightChannel = static_cast<int32_t>(masterRightChannel);
    config.format = oboe::AudioFormat::Float;

    LOGI("nativeStartMonitoring: deviceId=%d, ch=%d, L=%d, R=%d",
         usbDeviceId, channelCount, masterLeftChannel, masterRightChannel);

    auto* engine = GetEngineInstance();
    return engine->StartMonitoring(static_cast<int32_t>(usbDeviceId), config)
               ? JNI_TRUE : JNI_FALSE;
}

// ── USB Direct Capture (for vendor-specific class 0xFF devices) ────

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartUsbCapture(
    JNIEnv* env, jobject thiz, jint usbFd, jint epAddress,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel, jint sampleRate)
{
    LOGI("nativeStartUsbCapture: FD=%d, EP=0x%02X, ch=%d, L=%d, R=%d, rate=%d",
         usbFd, epAddress, channelCount, masterLeftChannel, masterRightChannel, sampleRate);

    auto* engine = GetUsbEngineInstance();
    return engine->StartCapture(usbFd, epAddress, channelCount,
                                 masterLeftChannel, masterRightChannel, sampleRate)
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartUsbMonitoring(
    JNIEnv* env, jobject thiz, jint usbFd, jint epAddress,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel, jint sampleRate)
{
    LOGI("nativeStartUsbMonitoring: FD=%d, EP=0x%02X", usbFd, epAddress);

    auto* engine = GetUsbEngineInstance();
    return engine->StartMonitoring(usbFd, epAddress, channelCount,
                                    masterLeftChannel, masterRightChannel, sampleRate)
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStopUsbCapture(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeStopUsbCapture called");
    if (gUsbEngineInstance) {
        gUsbEngineInstance->StopCapture();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsUsbRecording(
    JNIEnv* env, jobject thiz)
{
    return gUsbEngineInstance && gUsbEngineInstance->IsRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsUsbMonitoring(
    JNIEnv* env, jobject thiz)
{
    return gUsbEngineInstance && gUsbEngineInstance->IsMonitoring() ? JNI_TRUE : JNI_FALSE;
}

// ── DJM Bulk Capture (MIDI-over-Bulk for Pioneer DJM devices) ─

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartDjmBulkCapture(
    JNIEnv* env, jobject thiz, jint usbFd, jint epAddress,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel, jint sampleRate)
{
    LOGI("nativeStartDjmBulkCapture: FD=%d, EP=0x%02X, ch=%d, L=%d, R=%d, rate=%d",
         usbFd, epAddress, channelCount, masterLeftChannel, masterRightChannel, sampleRate);

    auto* engine = GetDjmBulkEngineInstance();
    return engine->StartCapture(usbFd, epAddress, channelCount,
                                 masterLeftChannel, masterRightChannel, sampleRate)
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStartDjmBulkMonitoring(
    JNIEnv* env, jobject thiz, jint usbFd, jint epAddress,
    jint channelCount, jint masterLeftChannel, jint masterRightChannel, jint sampleRate)
{
    LOGI("nativeStartDjmBulkMonitoring: FD=%d, EP=0x%02X", usbFd, epAddress);

    auto* engine = GetDjmBulkEngineInstance();
    return engine->StartMonitoring(usbFd, epAddress, channelCount,
                                    masterLeftChannel, masterRightChannel, sampleRate)
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStopDjmBulkCapture(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeStopDjmBulkCapture called");
    if (gDjmBulkEngineInstance) {
        gDjmBulkEngineInstance->StopCapture();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsDjmBulkRecording(
    JNIEnv* env, jobject thiz)
{
    return gDjmBulkEngineInstance && gDjmBulkEngineInstance->IsRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsDjmBulkMonitoring(
    JNIEnv* env, jobject thiz)
{
    return gDjmBulkEngineInstance && gDjmBulkEngineInstance->IsMonitoring() ? JNI_TRUE : JNI_FALSE;
}

// ── Shared Functions (routed to whichever engine is active) ────────

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsMonitoring(
    JNIEnv* env, jobject thiz)
{
    if (gDjmBulkEngineInstance && gDjmBulkEngineInstance->IsMonitoring()) {
        return JNI_TRUE;
    }
    if (gUsbEngineInstance && gUsbEngineInstance->IsMonitoring()) {
        return JNI_TRUE;
    }
    auto* engine = GetEngineInstance();
    return engine->IsMonitoring() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeGetLastErrorMessage(
    JNIEnv* env, jobject thiz)
{
    // Prefer DJM bulk engine error
    if (gDjmBulkEngineInstance) {
        const char* djmMsg = gDjmBulkEngineInstance->GetLastErrorMessage();
        if (djmMsg && djmMsg[0] != '\0') {
            return env->NewStringUTF(djmMsg);
        }
    }
    // Then try USB isochronous engine error
    if (gUsbEngineInstance) {
        const char* usbMsg = gUsbEngineInstance->GetLastErrorMessage();
        if (usbMsg && usbMsg[0] != '\0') {
            return env->NewStringUTF(usbMsg);
        }
    }
    // Fall back to Oboe engine error
    auto* engine = GetEngineInstance();
    const char* msg = engine->GetLastErrorMessage();
    if (msg && msg[0] != '\0') {
        return env->NewStringUTF(msg);
    }
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeStopCapture(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeStopCapture called (stops all engines)");
    if (gDjmBulkEngineInstance) {
        gDjmBulkEngineInstance->StopCapture();
    }
    if (gUsbEngineInstance) {
        gUsbEngineInstance->StopCapture();
    }
    auto* engine = GetEngineInstance();
    engine->StopCapture();
}

JNIEXPORT jboolean JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeIsRecording(
    JNIEnv* env, jobject thiz)
{
    if (gDjmBulkEngineInstance && gDjmBulkEngineInstance->IsRecording()) {
        return JNI_TRUE;
    }
    if (gUsbEngineInstance && gUsbEngineInstance->IsRecording()) {
        return JNI_TRUE;
    }
    auto* engine = GetEngineInstance();
    return engine->IsRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeReadCapturedData(
    JNIEnv* env, jobject thiz, jobject byteBuffer, jint capacityBytes)
{
    float* bufferPtr = static_cast<float*>(env->GetDirectBufferAddress(byteBuffer));
    if (!bufferPtr) {
        LOGE("nativeReadCapturedData: GetDirectBufferAddress returned null");
        return 0;
    }

    // Read from the DJM bulk engine if it is actively recording or monitoring
    if (gDjmBulkEngineInstance &&
        (gDjmBulkEngineInstance->IsRecording() || gDjmBulkEngineInstance->IsMonitoring())) {
        int32_t maxFrames = capacityBytes / (2 * static_cast<int32_t>(sizeof(float)));
        int32_t framesRead = gDjmBulkEngineInstance->ReadCapturedData(bufferPtr, maxFrames);
        return framesRead;
    }

    // Read from the USB isochronous engine if it is actively recording or monitoring
    if (gUsbEngineInstance &&
        (gUsbEngineInstance->IsRecording() || gUsbEngineInstance->IsMonitoring())) {
        int32_t maxFrames = capacityBytes / (2 * static_cast<int32_t>(sizeof(float)));
        int32_t framesRead = gUsbEngineInstance->ReadCapturedData(bufferPtr, maxFrames);
        return framesRead;
    }

    // Otherwise read from the Oboe engine
    auto* engine = GetEngineInstance();
    int32_t maxFrames = capacityBytes / (2 * static_cast<int32_t>(sizeof(float)));
    int32_t framesRead = engine->ReadCapturedData(bufferPtr, maxFrames);
    return framesRead;
}

JNIEXPORT void JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeGetLevelData(
    JNIEnv* env, jobject thiz, jfloatArray outLevels)
{
    float levels[4] = {0.0f, 0.0f, 0.0f, 0.0f};

    // Prefer levels from the DJM bulk engine if it's active
    if (gDjmBulkEngineInstance && (gDjmBulkEngineInstance->IsRecording() ||
                                    gDjmBulkEngineInstance->IsMonitoring())) {
        gDjmBulkEngineInstance->GetLevelData(&levels[0], &levels[1], &levels[2], &levels[3]);
    }
    // Prefer levels from the USB isochronous engine if it's active
    else if (gUsbEngineInstance && (gUsbEngineInstance->IsRecording() ||
                                     gUsbEngineInstance->IsMonitoring())) {
        gUsbEngineInstance->GetLevelData(&levels[0], &levels[1], &levels[2], &levels[3]);
    } else {
        auto* engine = GetEngineInstance();
        engine->GetLevelData(&levels[0], &levels[1], &levels[2], &levels[3]);
    }

    env->SetFloatArrayRegion(outLevels, 0, 4, levels);
}

// ── Overrun Reporting ─────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeGetUsbOverrunCount(
    JNIEnv* env, jobject thiz)
{
    if (gUsbEngineInstance) {
        return gUsbEngineInstance->GetOverrunCount();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_usbdeckrec_audio_AudioEngineBridge_nativeGetDjmBulkOverrunCount(
    JNIEnv* env, jobject thiz)
{
    if (gDjmBulkEngineInstance) {
        return gDjmBulkEngineInstance->GetOverrunCount();
    }
    return 0;
}

} // extern "C"
