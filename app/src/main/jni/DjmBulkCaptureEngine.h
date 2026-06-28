#ifndef DJM_BULK_CAPTURE_ENGINE_H
#define DJM_BULK_CAPTURE_ENGINE_H

#include <atomic>
#include <string>
#include <thread>
#include <mutex>
#include "LockFreeQueue.h"

/**
 * Bulk-transfer capture engine for Pioneer DJM devices that use vendor-specific
 * bulk endpoints for MIDI/status communication instead of isochronous audio.
 *
 * ## DJM-900NXS (PID 0x0158)
 *
 * The DJM-900NXS uses **bulk** transfers on its vendor interface (IF 0 alt 1):
 * - EP 0x07 (OUT) — Host→Device: vendor control commands
 * - EP 0x87 (IN) — Device→Host: MIDI data (4-byte USB MIDI packets) + status
 *
 * Unlike standard USB audio class devices, the NXS does NOT stream PCM audio
 * over USB. Instead, it sends 4-byte USB MIDI event packets containing:
 * - MIDI Timing Clock (0xF8) every ~21ms for tempo sync
 * - MIDI Control Change (CC) messages for level/VU data
 * - System Exclusive (SysEx) for proprietary mixer state
 *
 * This engine reads raw USB MIDI packets from the bulk IN endpoint and
 * stores them in a LockFreeQueue for Kotlin-side processing.
 *
 * ## Architecture
 *
 * - Single capture thread with 4 bulk URBs (quadruple-buffered for pipeline)
 * - Decodes 4-byte USB MIDI packets → stores raw MIDI bytes in queue
 * - Peak/RMS level tracking derived from MIDI CC VU messages
 * - VU-meter-only monitoring mode (no queue writes)
 * - Robust thread shutdown via poll() + eventfd + USBDEVFS_DISCARDURB
 *
 * @note This replaces the UsbIsochronousCaptureEngine for devices like the
 *       DJM-900NXS that use bulk transfers instead of isochronous.
 */
class DjmBulkCaptureEngine {
public:
    DjmBulkCaptureEngine();
    ~DjmBulkCaptureEngine();

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

    /** Returns the number of overrun events since last StartCapture(). */
    int32_t GetOverrunCount() const { return mOverrunCount.load(std::memory_order_acquire); }

private:
    void CaptureLoop();
    bool SetupUrbPool(int fd, int epAddress);
    void DestroyUrbPool();
    void UpdatePeak(std::atomic<float>& peak, float value);

    // Internal: stop without acquiring mMutex (prevents self-deadlock
    // when StartCapture/StartMonitoring call StopCapture while holding mMutex)
    void StopCaptureLocked();

    // Parse a USB MIDI packet (4 bytes) and extract level data from CC messages
    void ParseMidiPacket(const uint8_t* midiPacket);

    static constexpr int kUrbPoolSize = 4;    // 4 bulk URBs for pipeline
    static constexpr int kBulkPacketSize = 512; // wMaxPacketSize for EP 0x87

    struct UrbNode {
        struct usbdevfs_urb* urb = nullptr;
        uint8_t* buffer = nullptr;
    };

    // USB FD management — guarded by mUsbFdMutex to prevent TOCTOU races
    std::mutex mUsbFdMutex;
    int mUsbFd = -1;
    int mUsbFdDup = -1;       // dup() of mUsbFd, used by CaptureLoop's poll()

    // Eventfd for signalling CaptureLoop to stop (unblocks poll())
    int mStopEventFd = -1;

    int mEpAddress = -1;
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mMonitoring{false};
    std::thread mCaptureThread;
    std::mutex mMutex;

    UrbNode mUrbPool[kUrbPoolSize];
    int32_t mChannelCount = 2;
    int32_t mMasterLeftCh = 0;
    int32_t mMasterRightCh = 1;
    int32_t mSampleRate = 48000;

    LockFreeQueue<float> mQueue;
    std::string mLastErrorMessage;

    // Level tracking simulated from MIDI CC values
    std::atomic<float> mLeftPeak{0.0f};
    std::atomic<float> mRightPeak{0.0f};
    std::atomic<float> mLeftRmsSum{0.0f};
    std::atomic<float> mRightRmsSum{0.0f};
    std::atomic<int32_t> mRmsCount{0};
    std::atomic<int32_t> mOverrunCount{0};

    // MIDI CC VU level cache (CC values 0-127 mapped to 0.0-1.0)
    // Pioneer DJM mixers typically send channel fader levels on CC#7
    // and VU/PFL levels on manufacturer-specific CC numbers.
    float mMidiLevelL = 0.0f;
    float mMidiLevelR = 0.0f;
    int mPacketsSinceLastLevel = 0;
};

#endif // DJM_BULK_CAPTURE_ENGINE_H
