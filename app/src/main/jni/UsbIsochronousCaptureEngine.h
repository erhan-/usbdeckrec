#ifndef USB_ISOCHRONOUS_CAPTURE_ENGINE_H
#define USB_ISOCHRONOUS_CAPTURE_ENGINE_H

#include <atomic>
#include <string>
#include <thread>
#include <mutex>
#include "LockFreeQueue.h"

/**
 * Direct USB isochronous audio capture engine for vendor-specific class devices
 * (e.g., Pioneer DJM-900NXS with USB Class 0xFF).
 *
 * Bypasses Oboe/AAudio entirely by talking directly to the USB device via
 * ioctl() on the /dev/bus/usb file descriptor obtained from UsbDeviceConnection.
 *
 * Architecture:
 * - IN URB pool: 16 URBs × 16 iso packets each = 32 ms buffer (capture, EP 0x86)
 * - OUT URB pool: 4 URBs × 16 iso packets each = silence sync data (EP 0x05 for implicit feedback)
 * - Single capture thread: reap IN/OUT URBs, decode S24_3LE → float, re-submit both
 * - LockFreeQueue<float> (65536 capacity) for SPSC PCM transfer
 * - Peak/RMS level tracking for VU meter (monitoring mode skips queue writes)
 * - Robust thread shutdown via poll() + eventfd + USBDEVFS_DISCARDURB
 *
 * Thread safety:
 * - mUsbFd is guarded by mUsbFdMutex; accessed via dup'd fd in CaptureLoop
 * - mStopEventFd signals the capture loop to exit cleanly
 * - URBs are discarded before URB memory is freed to prevent use-after-free
 */
class UsbIsochronousCaptureEngine {
public:
    UsbIsochronousCaptureEngine();
    ~UsbIsochronousCaptureEngine();

    bool StartCapture(int usbFd, int epAddress, int channelCount,
                      int masterLeftCh, int masterRightCh, int sampleRate);
    void StopCapture();
    bool IsRecording() const;
    int32_t ReadCapturedData(float* outputBuffer, int32_t maxFrames);
    void GetLevelData(float* outLeftPeak, float* outRightPeak,
                      float* outLeftRms, float* outRightRms);

    // VU-meter-only mode (no queue writes)
    bool StartMonitoring(int usbFd, int epAddress, int channelCount,
                         int masterLeftCh, int masterRightCh, int sampleRate);
    bool IsMonitoring() const;
    const char* GetLastErrorMessage() const;

    /** Returns the number of overrun (dropped frame) events since last StartCapture(). */
    int32_t GetOverrunCount() const { return mOverrunCount.load(std::memory_order_acquire); }

private:
    void CaptureLoop();
    bool SetupUrbPool(int fd, int epAddress);
    void DestroyInUrbPool();
    bool SetupOutUrbPool(int fd);
    void DestroyOutUrbPool();
    void UpdatePeak(std::atomic<float>& peak, float value);

    // Internal: stop without acquiring mMutex (used by StartCapture/StartMonitoring
    // to avoid self-deadlock when transitioning between modes)
    void StopCaptureLocked();

    static constexpr int kUrbPoolSize = 16;
    static constexpr int kIsoPacketsPerUrb = 16;
    static constexpr int kOutUrbPoolSize = 4;  // fewer URBs for silence output
    static constexpr int kOutEpAddress = 0x05; // playback/sync endpoint for DJM-900NXS

    struct UrbNode {
        struct usbdevfs_urb* urb = nullptr;
        uint8_t* buffer = nullptr;
    };

    // USB FD management — guarded by mUsbFdMutex to prevent TOCTOU races
    std::mutex mUsbFdMutex;
    int mUsbFd = -1;           // USB device fd (capture loop reads this after thread start)

    int mEpAddress = -1;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mMonitoring{false};
    int mStopEventFd = -1;     // Only used in StartCapture/StopCapture (not poll-based anymore)
    std::thread mCaptureThread;
    std::mutex mMutex;

    UrbNode mInUrbPool[kUrbPoolSize];    // capture URBs (IN, EP 0x86)
    UrbNode mOutUrbPool[kOutUrbPoolSize]; // sync URBs (OUT, EP 0x05 - silence for implicit feedback)
    int32_t mChannelCount = 2;
    int32_t mMasterLeftCh = 0;
    int32_t mMasterRightCh = 1;
    int32_t mSampleRate = 48000;
    int32_t mBytesPerFrame = 6; // 2ch × 3 bytes S24_3LE
    int64_t mOutFrameCounter = 0;
    double mOutFramesAccumulator = 0.0;

    LockFreeQueue<float> mQueue;
    std::string mLastErrorMessage;

    // Level tracking (same interface as AudioCaptureEngine)
    std::atomic<float> mLeftPeak{0.0f};
    std::atomic<float> mRightPeak{0.0f};
    std::atomic<float> mLeftRmsSum{0.0f};
    std::atomic<float> mRightRmsSum{0.0f};
    std::atomic<int32_t> mRmsCount{0};
    std::atomic<int32_t> mOverrunCount{0};
};

#endif // USB_ISOCHRONOUS_CAPTURE_ENGINE_H
