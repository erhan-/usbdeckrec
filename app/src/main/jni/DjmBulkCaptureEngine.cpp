#include "DjmBulkCaptureEngine.h"
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <sys/eventfd.h>
#include <linux/usbdevice_fs.h>
#include <unistd.h>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <android/log.h>

#define LOG_TAG "DeckRec_DjmBulk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOG_MAIN(...) __android_log_print(ANDROID_LOG_ERROR, "DeckRec_Audio", __VA_ARGS__)

// USBDEVFS ioctl helpers (may not be in older NDK uapi headers)
#ifndef USBDEVFS_CLAIMINTERFACE
#define USBDEVFS_CLAIMINTERFACE _IOR('U', 15, unsigned int)
#endif
#ifndef USBDEVFS_RELEASEINTERFACE
#define USBDEVFS_RELEASEINTERFACE _IOR('U', 16, unsigned int)
#endif

#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 14, void *)
#endif

DjmBulkCaptureEngine::DjmBulkCaptureEngine()
    : mQueue(65536)
{
}

DjmBulkCaptureEngine::~DjmBulkCaptureEngine() {
    StopCapture();
}

// ── URB Pool (Bulk IN, EP 0x87) ────────────────────────────────────────

bool DjmBulkCaptureEngine::SetupUrbPool(int fd, int epAddress) {
    if (fd <= 2) {
        LOGE("SetupUrbPool: invalid FD=%d", fd);
        mLastErrorMessage = "Invalid USB file descriptor";
        return false;
    }

    for (int i = 0; i < kUrbPoolSize; i++) {
        mUrbPool[i].buffer = new uint8_t[kBulkPacketSize]();  // Zero-initialized to prevent stale data decode on first reap

        size_t urbSize = sizeof(struct usbdevfs_urb);
        mUrbPool[i].urb = (struct usbdevfs_urb*)calloc(1, urbSize);

        struct usbdevfs_urb* urb = mUrbPool[i].urb;
        urb->type = USBDEVFS_URB_TYPE_BULK;  // Bulk transfer, not ISO
        urb->endpoint = static_cast<unsigned int>(epAddress);  // e.g., 0x87 (IN)
        urb->buffer = mUrbPool[i].buffer;
        urb->buffer_length = kBulkPacketSize;
        urb->number_of_packets = 1;  // Bulk = 1 packet per URB
        urb->usercontext = &mUrbPool[i];
    }

    // Submit all URBs initially
    for (int i = 0; i < kUrbPoolSize; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, mUrbPool[i].urb) < 0) {
            int err = errno;
            LOGE("Failed to submit initial bulk URB %d: %s (errno=%d)",
                 i, strerror(err), err);
            LOG_MAIN("USBDEVFS_SUBMITURB failed for bulk URB %d on FD=%d EP=0x%02X: %s",
                     i, fd, epAddress, strerror(err));
            return false;
        }
    }
    LOGI("Bulk URB pool setup: FD=%d, %d URBs x %d bytes, EP=0x%02X",
         fd, kUrbPoolSize, kBulkPacketSize, epAddress);
    LOG_MAIN("Bulk URB pool setup OK: FD=%d, EP=0x%02X", fd, epAddress);
    return true;
}

void DjmBulkCaptureEngine::DestroyUrbPool() {
    for (int i = 0; i < kUrbPoolSize; i++) {
        // Discard the URB before freeing its memory to prevent use-after-free
        if (mUrbPool[i].urb) {
            int fdCopy = -1;
            {
                std::lock_guard<std::mutex> lock(mUsbFdMutex);
                fdCopy = mUsbFd;
            }
            if (fdCopy >= 0) {
                int ret = ioctl(fdCopy, USBDEVFS_DISCARDURB, mUrbPool[i].urb);
                if (ret < 0 && errno != ENODEV && errno != EINVAL) {
                    LOGE("DestroyUrbPool: DISCARDURB[%d] failed: %s (errno=%d)",
                         i, strerror(errno), errno);
                }
            }

            free(mUrbPool[i].urb);
            mUrbPool[i].urb = nullptr;
        }
        if (mUrbPool[i].buffer) {
            delete[] mUrbPool[i].buffer;
            mUrbPool[i].buffer = nullptr;
        }
    }
}

// ── MIDI Packet Parsing ─────────────────────────────────────────────────
//
// USB MIDI Event Packet (4 bytes):
//   Byte 0: [CableNumber(4 bits) | CodeIndexNumber(4 bits)]
//   Byte 1: MIDI Status byte (or first data byte for SysEx)
//   Byte 2: MIDI Data byte 1
//   Byte 3: MIDI Data byte 2
//
// Code Index Number (CIN) identifies the MIDI message type:
//   0x8 = Note Off
//   0x9 = Note On
//   0xB = Control Change (CC)
//   0xF = System Common / System Real-Time (single byte)
//
// DJM-900NXS sends:
// - MIDI Timing Clock (0xF8) every ~21ms: CIN=0xF, status=0xF8, data=0x00, 0x00
// - MIDI CC messages for fader/VU levels
// - SysEx for proprietary state

void DjmBulkCaptureEngine::ParseMidiPacket(const uint8_t* midiPacket) {
    uint8_t cin = midiPacket[0] & 0x0F;  // Code Index Number (lower nibble)
    uint8_t status = midiPacket[1];
    uint8_t data1 = midiPacket[2];
    uint8_t data2 = midiPacket[3];

    // Check for Control Change (CC) messages — these carry level/VU data
    if (cin == 0x0B || (status >= 0xB0 && status <= 0xBF)) {
        // CC#7 = Main Volume (channel fader)
        // CC#10 = Pan
        // Pioneer proprietary CC numbers for VU levels:
        // Channel 1 level often on CC#16-17, Channel 2 on CC#18-19
        uint8_t ccNumber = data1;
        uint8_t ccValue = data2;  // 0-127

        // Map CC value 0-127 to float 0.0-1.0
        float level = ccValue / 127.0f;

        // Simple channel assignment based on observed Pioneer MIDI maps
        // Channel 1 VU ≈ CC#16-17, Channel 2 VU ≈ CC#18-19
        if (ccNumber == 16 || ccNumber == 17) {
            mMidiLevelL = level;
        } else if (ccNumber == 18 || ccNumber == 19) {
            mMidiLevelR = level;
        } else if (ccNumber == 7) {
            // Main volume CC — use as fallback for both channels
            if (mPacketsSinceLastLevel > 100) {
                mMidiLevelL = level;
                mMidiLevelR = level;
            }
        }
        mPacketsSinceLastLevel = 0;
    }

    // MIDI Timing Clock (0xF8): single-byte real-time message
    // Cin=0x0F, status=0xF8 — used for tempo sync, no level data
    // System Real Time messages have CIN=0x0F and no following data bytes

    mPacketsSinceLastLevel++;
}

// ── Capture Loop ────────────────────────────────────────────────────────
//
// Uses blocking REAPURB for reliability. After each URB is reaped, checks
// the eventfd for stop signal and mRunning flag before re-submitting.

void DjmBulkCaptureEngine::CaptureLoop() {
    LOGI("Bulk capture thread started (monitoring=%d, FD=%d, eventfd=%d)",
         mMonitoring.load(), mUsbFd, mStopEventFd);

    int64_t urbCount = 0;
    int64_t totalBytes = 0;
    int64_t midiPacketCount = 0;

    while (mRunning) {
        // Blocking URB reap
        struct usbdevfs_urb* urb = nullptr;
        int ret = ioctl(mUsbFd, USBDEVFS_REAPURB, &urb);
        if (ret < 0 || !urb) {
            if (!mRunning) {
                break;
            }
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN) {
                continue;
            }
            if (mRunning) {
                LOGE("REAPURB failed on FD=%d: %s (errno=%d)",
                     mUsbFd, strerror(errno), errno);
                LOG_MAIN("Bulk capture REAPURB failed on FD=%d: %s",
                         mUsbFd, strerror(errno));
            }
            break;
        }

        urbCount++;
        UrbNode* node = static_cast<UrbNode*>(urb->usercontext);

        // Process completed bulk IN transfer
        int actualLength = urb->actual_length;
        if (actualLength > 0 && urb->status == 0) {
            totalBytes += actualLength;
            uint8_t* data = node->buffer;

            // Parse USB MIDI packets from the bulk data
            // USB MIDI event packets are 4 bytes each
            int numPackets = actualLength / 4;
            for (int p = 0; p < numPackets; p++) {
                const uint8_t* midiPacket = data + (p * 4);
                midiPacketCount++;

                // Update peak levels from MIDI CC
                ParseMidiPacket(midiPacket);

                // Write to queue if recording (not just monitoring)
                if (!mMonitoring) {
                    // Store raw MIDI byte as float for queue compatibility
                    // (the Kotlin side will reinterpret as MIDI bytes)
                    float midiBytes[4] = {
                        static_cast<float>(midiPacket[0]),
                        static_cast<float>(midiPacket[1]),
                        static_cast<float>(midiPacket[2]),
                        static_cast<float>(midiPacket[3])
                    };
                    if (!mQueue.Write(midiBytes, 4)) {
                        mOverrunCount.fetch_add(1);
                    }
                }
            }

            // Derive audio-like level data from MIDI VU values for the VU meter
            // This gives visual feedback even though we're capturing MIDI, not PCM
            float sampleL = mMidiLevelL;
            float sampleR = mMidiLevelR;

            UpdatePeak(mLeftPeak, sampleL);
            UpdatePeak(mRightPeak, sampleR);

            // RMS tracking from MIDI VU levels
            {
                float expected = mLeftRmsSum.load(std::memory_order_relaxed);
                float desired = expected + sampleL * sampleL;
                while (!mLeftRmsSum.compare_exchange_weak(expected, desired,
                            std::memory_order_release, std::memory_order_relaxed)) {
                    desired = expected + sampleL * sampleL;
                }
            }
            {
                float expected = mRightRmsSum.load(std::memory_order_relaxed);
                float desired = expected + sampleR * sampleR;
                while (!mRightRmsSum.compare_exchange_weak(expected, desired,
                            std::memory_order_release, std::memory_order_relaxed)) {
                    desired = expected + sampleR * sampleR;
                }
            }
            {
                int32_t expected = mRmsCount.load(std::memory_order_relaxed);
                while (!mRmsCount.compare_exchange_weak(expected, expected + 1,
                            std::memory_order_release, std::memory_order_relaxed));
            }
        }

        // Log periodic stats
        if (urbCount % 64 == 0) {
            LOGI("Bulk URB #%lld: %lld bytes, %lld MIDI packets, levels L=%.3f R=%.3f",
                 (long long)urbCount, (long long)totalBytes,
                 (long long)midiPacketCount, mMidiLevelL, mMidiLevelR);
        }

        // Check if stop was requested via eventfd BEFORE re-submitting
        if (!mRunning) {
            break;
        }
        if (mStopEventFd >= 0) {
            uint64_t eventfdVal = 0;
            ssize_t rd = read(mStopEventFd, &eventfdVal, sizeof(eventfdVal));
            if (rd > 0) {
                LOGI("Bulk capture loop: stop eventfd signalled, exiting");
                break;
            }
        }

        // Re-submit the URB for continuous capture
        urb->status = 0;
        urb->actual_length = 0;

        if (ioctl(mUsbFd, USBDEVFS_SUBMITURB, urb) < 0) {
            if (errno != ENODEV && mRunning) {
                LOGE("Failed to re-submit bulk URB (EP=0x%02X): %s",
                     urb->endpoint, strerror(errno));
            }
            break;
        }
    }

    LOGI("Bulk capture thread exiting: URBs=%lld, bytes=%lld, MIDI packets=%lld",
         (long long)urbCount, (long long)totalBytes, (long long)midiPacketCount);
    LOG_MAIN("Bulk capture thread stats: URBs=%lld, bytes=%lld, MIDI packets=%lld",
             (long long)urbCount, (long long)totalBytes, (long long)midiPacketCount);

    mRunning.store(false, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);
    LOGI("Bulk capture thread exited (FD=%d)", mUsbFd);
}

// ── Internal helper: stop without acquiring mMutex ──────────────────────

void DjmBulkCaptureEngine::StopCaptureLocked() {
    if (!mRunning.load(std::memory_order_acquire)) return;

    int currentFd = -1;
    int currentEventFd = -1;

    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        currentFd = mUsbFd;
        currentEventFd = mStopEventFd;
    }

    LOGI("Stopping bulk capture (FD=%d, monitoring=%d, overruns=%d)",
         currentFd, mMonitoring.load(), mOverrunCount.load());

    // Signal the eventfd
    if (currentEventFd >= 0) {
        uint64_t val = 1;
        ssize_t written = write(currentEventFd, &val, sizeof(val));
        if (written < 0) {
            LOGE("Failed to signal stop eventfd: %s (errno=%d)", strerror(errno), errno);
        }
    }

    mRunning.store(false, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);

    // Discard all URBs to unblock REAPURB
    for (int i = 0; i < kUrbPoolSize; i++) {
        if (mUrbPool[i].urb && currentFd >= 0) {
            int ret = ioctl(currentFd, USBDEVFS_DISCARDURB, mUrbPool[i].urb);
            if (ret < 0 && errno != ENODEV && errno != EINVAL && errno != ENOENT) {
                LOGE("StopCapture: DISCARDURB[%d] on FD=%d: errno=%d", i, currentFd, errno);
            }
        }
    }

    if (mCaptureThread.joinable()) {
        mCaptureThread.join();
    }

    // Release USB interface
    if (currentFd >= 0) {
        int iface_num = 0;
        ioctl(currentFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
    }

    DestroyUrbPool();

    if (currentEventFd >= 0) {
        close(currentEventFd);
    }

    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        mUsbFd = -1;
        mStopEventFd = -1;
    }
    mEpAddress = -1;

    LOGI("Bulk capture stopped (was FD=%d)", currentFd);
    LOG_MAIN("DjmBulkCaptureEngine stopped cleanly (was FD=%d)", currentFd);
}

// ── Public API ──────────────────────────────────────────────────────────

bool DjmBulkCaptureEngine::StartCapture(
    int usbFd, int epAddress, int channelCount,
    int masterLeftCh, int masterRightCh, int sampleRate)
{
    std::lock_guard<std::mutex> lock(mMutex);

    if (mRunning) {
        LOGI("Already running — stopping first");
        StopCaptureLocked();
    }

    LOGI("StartCapture: setting up on FD=%d, bulk EP=0x%02X", usbFd, epAddress);
    LOG_MAIN("DjmBulkCaptureEngine: setting up on FD=%d, bulk EP=0x%02X", usbFd, epAddress);

    // Create the stop eventfd before starting the thread
    mStopEventFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (mStopEventFd < 0) {
        LOGE("Failed to create eventfd: %s (errno=%d)", strerror(errno), errno);
        mLastErrorMessage = std::string("Failed to create eventfd: ") + strerror(errno);
        return false;
    }

    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        mUsbFd = usbFd;
    }

    mEpAddress = epAddress;
    mChannelCount = channelCount;
    mMasterLeftCh = masterLeftCh;
    mMasterRightCh = masterRightCh;
    mSampleRate = sampleRate;

    // Claim interface 0 so we can submit URBs
    int iface_num = 0;
    int claim_ret = ioctl(usbFd, USBDEVFS_CLAIMINTERFACE, &iface_num);
    if (claim_ret < 0) {
        LOGE("USBDEVFS_CLAIMINTERFACE failed on FD=%d: %s (errno=%d)",
             usbFd, strerror(errno), errno);
        LOG_MAIN("FAILED to claim USB interface 0: %s", strerror(errno));
        mLastErrorMessage = std::string("Claim interface failed: ") + strerror(errno);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }
    LOGI("Claimed interface %d on FD=%d", iface_num, usbFd);

    // Reset level data
    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mOverrunCount.store(0, std::memory_order_release);
    mMidiLevelL = 0.0f;
    mMidiLevelR = 0.0f;
    mPacketsSinceLastLevel = 0;
    mQueue.Reset();
    mLastErrorMessage.clear();

    // Set up bulk IN URB pool
    if (!SetupUrbPool(usbFd, epAddress)) {
        mLastErrorMessage = "Failed to set up bulk URB pool";
        LOGE("%s", mLastErrorMessage.c_str());
        LOG_MAIN("%s", mLastErrorMessage.c_str());
        DestroyUrbPool();
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }

    // Set mRunning BEFORE starting the thread
    mRunning.store(true, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);
    mCaptureThread = std::thread(&DjmBulkCaptureEngine::CaptureLoop, this);

    LOGI("Bulk capture started: FD=%d, EP=0x%02X, ch=%d, rate=%d",
         usbFd, epAddress, channelCount, sampleRate);
    LOG_MAIN("DjmBulkCaptureEngine started OK: FD=%d, EP=0x%02X", usbFd, epAddress);
    return true;
}

bool DjmBulkCaptureEngine::StartMonitoring(
    int usbFd, int epAddress, int channelCount,
    int masterLeftCh, int masterRightCh, int sampleRate)
{
    std::lock_guard<std::mutex> lock(mMutex);

    if (mRunning) {
        if (mMonitoring.load(std::memory_order_acquire)) {
            LOGI("Already monitoring, updating params");
            return true;
        }
        LOGE("StartMonitoring called while recording — stopping first");
        StopCaptureLocked();
    }

    // Create the stop eventfd
    mStopEventFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (mStopEventFd < 0) {
        LOGE("StartMonitoring: Failed to create eventfd: %s (errno=%d)", strerror(errno), errno);
        mLastErrorMessage = std::string("Failed to create eventfd: ") + strerror(errno);
        return false;
    }

    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        mUsbFd = usbFd;
    }

    mEpAddress = epAddress;
    mChannelCount = channelCount;
    mMasterLeftCh = masterLeftCh;
    mMasterRightCh = masterRightCh;
    mSampleRate = sampleRate;

    int iface_num = 0;
    int claim_ret = ioctl(usbFd, USBDEVFS_CLAIMINTERFACE, &iface_num);
    if (claim_ret < 0) {
        LOGE("USBDEVFS_CLAIMINTERFACE failed on FD=%d: %s (errno=%d)",
             usbFd, strerror(errno), errno);
        LOG_MAIN("FAILED to claim USB interface 0 for monitoring: %s", strerror(errno));
        mLastErrorMessage = std::string("Claim interface failed: ") + strerror(errno);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }
    LOGI("Claimed interface %d for monitoring on FD=%d", iface_num, usbFd);

    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mMidiLevelL = 0.0f;
    mMidiLevelR = 0.0f;
    mPacketsSinceLastLevel = 0;
    mQueue.Reset();
    mLastErrorMessage.clear();

    if (!SetupUrbPool(usbFd, epAddress)) {
        mLastErrorMessage = "Failed to set up bulk URB pool for monitoring";
        LOGE("%s", mLastErrorMessage.c_str());
        LOG_MAIN("%s", mLastErrorMessage.c_str());
        DestroyUrbPool();
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }

    // Set mRunning BEFORE starting the thread
    mRunning.store(true, std::memory_order_release);
    mMonitoring.store(true, std::memory_order_release);
    mCaptureThread = std::thread(&DjmBulkCaptureEngine::CaptureLoop, this);

    LOGI("Bulk monitoring started: FD=%d, EP=0x%02X", usbFd, epAddress);
    LOG_MAIN("DjmBulkCaptureEngine monitoring started: FD=%d, EP=0x%02X", usbFd, epAddress);
    return true;
}

void DjmBulkCaptureEngine::StopCapture() {
    std::lock_guard<std::mutex> lock(mMutex);
    StopCaptureLocked();
}

bool DjmBulkCaptureEngine::IsRecording() const {
    return mRunning.load(std::memory_order_acquire) &&
           !mMonitoring.load(std::memory_order_acquire);
}

bool DjmBulkCaptureEngine::IsMonitoring() const {
    return mRunning.load(std::memory_order_acquire) &&
           mMonitoring.load(std::memory_order_acquire);
}

int32_t DjmBulkCaptureEngine::ReadCapturedData(float* outputBuffer,
                                                int32_t maxFrames) {
    // For bulk capture, "frames" = MIDI packets (4 floats each)
    int32_t samplesToRead = maxFrames * 4;
    return mQueue.Read(outputBuffer, samplesToRead) / 4;
}

void DjmBulkCaptureEngine::GetLevelData(float* outLeftPeak,
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

const char* DjmBulkCaptureEngine::GetLastErrorMessage() const {
    return mLastErrorMessage.c_str();
}

void DjmBulkCaptureEngine::UpdatePeak(std::atomic<float>& peak, float value) {
    float current = peak.load(std::memory_order_relaxed);
    while (value > current &&
           !peak.compare_exchange_weak(current, value,
                                         std::memory_order_release,
                                         std::memory_order_relaxed));
}
