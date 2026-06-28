#include "UsbIsochronousCaptureEngine.h"
#include <sys/ioctl.h>
#include <sys/poll.h>
#include <sys/eventfd.h>
#include <linux/usbdevice_fs.h>
#include <unistd.h>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <android/log.h>

// USBDEVFS_SETINTERFACE ioctl definition (may not be in older uapi headers)
#ifndef USBDEVFS_SETINTERFACE
struct usbdevfs_setinterface {
    unsigned int interface;
    unsigned int altsetting;
};
#define USBDEVFS_SETINTERFACE _IOR('U', 4, struct usbdevfs_setinterface)
#endif

#ifndef USBDEVFS_CLAIMINTERFACE
#define USBDEVFS_CLAIMINTERFACE _IOR('U', 15, unsigned int)
#endif

#ifndef USBDEVFS_RELEASEINTERFACE
#define USBDEVFS_RELEASEINTERFACE _IOR('U', 16, unsigned int)
#endif

#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 14, void *)
#endif

#define LOG_TAG "DeckRec_UsbIso"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// Also log errors under the main DeckRec_Audio tag for visibility in user's logcat filter
#define LOG_MAIN(...) __android_log_print(ANDROID_LOG_ERROR, "DeckRec_Audio", __VA_ARGS__)

// Ensure USBDEVFS_URB_TYPE_ISO is defined (it's 0 in practice)
#ifndef USBDEVFS_URB_TYPE_ISO
#define USBDEVFS_URB_TYPE_ISO 0
#endif

UsbIsochronousCaptureEngine::UsbIsochronousCaptureEngine()
    : mQueue(65536)
{
}

UsbIsochronousCaptureEngine::~UsbIsochronousCaptureEngine() {
    StopCapture();
}

// ── IN URB Pool (Capture, EP 0x86) ──────────────────────────────────────

bool UsbIsochronousCaptureEngine::SetupUrbPool(int fd, int epAddress) {
    size_t packetSize = 1024; // max packet size from descriptor

    // Sanity check: fd must be > 2 (not stdin/stdout/stderr)
    if (fd <= 2) {
        LOGE("SetupInUrbPool: invalid FD=%d", fd);
        LOG_MAIN("SetupInUrbPool: invalid FD=%d", fd);
        mLastErrorMessage = "Invalid USB file descriptor";
        return false;
    }

    for (int i = 0; i < kUrbPoolSize; i++) {
        size_t bufSize = kIsoPacketsPerUrb * packetSize;
        mInUrbPool[i].buffer = new uint8_t[bufSize]();  // Zero-initialized to prevent garbage decode on first reap

        size_t urbSize = sizeof(struct usbdevfs_urb) +
                         kIsoPacketsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
        mInUrbPool[i].urb = (struct usbdevfs_urb*)calloc(1, urbSize);

        struct usbdevfs_urb* urb = mInUrbPool[i].urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = static_cast<unsigned int>(epAddress);
        urb->buffer = mInUrbPool[i].buffer;
        urb->buffer_length = bufSize;
        urb->number_of_packets = kIsoPacketsPerUrb;
        urb->usercontext = &mInUrbPool[i];

        // Initialize iso packet descriptors: each packet requests max bytes
        for (int j = 0; j < kIsoPacketsPerUrb; j++) {
            urb->iso_frame_desc[j].length = packetSize;
        }
    }

    // Submit all URBs initially
    for (int i = 0; i < kUrbPoolSize; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, mInUrbPool[i].urb) < 0) {
            int err = errno;
            LOGE("Failed to submit initial IN URB %d: %s (errno=%d)", i, strerror(err), err);
            LOG_MAIN("USBDEVFS_SUBMITURB failed for IN URB %d on FD=%d EP=0x%02X: %s (errno=%d)",
                     i, fd, epAddress, strerror(err), err);
            return false;
        }
    }
    LOGI("IN URB pool setup: FD=%d, %d URBs × %d iso packets, %zu bytes each, EP=0x%02X",
         fd, kUrbPoolSize, kIsoPacketsPerUrb, packetSize, epAddress);
    LOG_MAIN("IN URB pool setup OK: FD=%d, EP=0x%02X", fd, epAddress);
    return true;
}

void UsbIsochronousCaptureEngine::DestroyInUrbPool() {
    for (int i = 0; i < kUrbPoolSize; i++) {
        // Discard the URB before freeing its memory to prevent use-after-free
        // if the URB is still submitted. Safe to call even if already unlinked.
        if (mInUrbPool[i].urb) {
            int fdCopy = -1;
            {
                std::lock_guard<std::mutex> lock(mUsbFdMutex);
                fdCopy = mUsbFd;
            }
            if (fdCopy >= 0) {
                int ret = ioctl(fdCopy, USBDEVFS_DISCARDURB, mInUrbPool[i].urb);
                if (ret < 0 && errno != ENODEV && errno != EINVAL) {
                    LOGE("DestroyInUrbPool: DISCARDURB[%d] failed: %s (errno=%d)",
                         i, strerror(errno), errno);
                }
            }

            free(mInUrbPool[i].urb);
            mInUrbPool[i].urb = nullptr;
        }
        if (mInUrbPool[i].buffer) {
            delete[] mInUrbPool[i].buffer;
            mInUrbPool[i].buffer = nullptr;
        }
    }
}

// Define M_PI if not available (Android NDK may not define it in strict C++ mode)
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ── OUT URB Pool (Sync/Playback, EP 0x05 — Silence for implicit feedback) ──

bool UsbIsochronousCaptureEngine::SetupOutUrbPool(int fd) {
    // Allocate a buffer that is plenty large enough, e.g., 4096 bytes per URB.
    size_t maxBufSize = 4096;

    if (fd <= 2) {
        LOGE("SetupOutUrbPool: invalid FD=%d", fd);
        return false;
    }

    mOutFrameCounter = 0;
    mOutFramesAccumulator = 0.0;
    double framesPerPacket = (double)mSampleRate / 8000.0;

    for (int i = 0; i < kOutUrbPoolSize; i++) {
        mOutUrbPool[i].buffer = new uint8_t[maxBufSize](); // Zero-initialized (silence)

        size_t urbSize = sizeof(struct usbdevfs_urb) +
                         kIsoPacketsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
        mOutUrbPool[i].urb = (struct usbdevfs_urb*)calloc(1, urbSize);

        struct usbdevfs_urb* urb = mOutUrbPool[i].urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = kOutEpAddress; // 0x05 (OUT, no direction bit)
        urb->buffer = mOutUrbPool[i].buffer;
        urb->number_of_packets = kIsoPacketsPerUrb;
        urb->usercontext = &mOutUrbPool[i];

        size_t currentOffset = 0;
        for (int j = 0; j < kIsoPacketsPerUrb; j++) {
            mOutFramesAccumulator += framesPerPacket;
            int framesToSend = (int)mOutFramesAccumulator;
            mOutFramesAccumulator -= framesToSend;

            int packetLength = framesToSend * mBytesPerFrame;
            urb->iso_frame_desc[j].length = packetLength;
            currentOffset += packetLength;
        }
        urb->buffer_length = currentOffset;
    }

    // Submit all OUT URBs initially
    for (int i = 0; i < kOutUrbPoolSize; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, mOutUrbPool[i].urb) < 0) {
            int err = errno;
            LOGE("Failed to submit initial OUT URB %d: %s (errno=%d)", i, strerror(err), err);
            LOG_MAIN("USBDEVFS_SUBMITURB failed for OUT URB %d on FD=%d EP=0x%02X: %s (errno=%d)",
                     i, fd, kOutEpAddress, strerror(err), err);
        }
    }
    LOGI("OUT URB pool setup: FD=%d, %d URBs × %d iso packets (silence)",
         fd, kOutUrbPoolSize, kIsoPacketsPerUrb);
    LOG_MAIN("OUT URB pool setup OK: FD=%d, EP=0x%02X (silence for implicit feedback)",
             fd, kOutEpAddress);
    return true;
}

void UsbIsochronousCaptureEngine::DestroyOutUrbPool() {
    for (int i = 0; i < kOutUrbPoolSize; i++) {
        // Discard the URB before freeing its memory to prevent use-after-free
        if (mOutUrbPool[i].urb) {
            int fdCopy = -1;
            {
                std::lock_guard<std::mutex> lock(mUsbFdMutex);
                fdCopy = mUsbFd;
            }
            if (fdCopy >= 0) {
                int ret = ioctl(fdCopy, USBDEVFS_DISCARDURB, mOutUrbPool[i].urb);
                if (ret < 0 && errno != ENODEV && errno != EINVAL) {
                    LOGE("DestroyOutUrbPool: DISCARDURB[%d] failed: %s (errno=%d)",
                         i, strerror(errno), errno);
                }
            }

            free(mOutUrbPool[i].urb);
            mOutUrbPool[i].urb = nullptr;
        }
        if (mOutUrbPool[i].buffer) {
            delete[] mOutUrbPool[i].buffer;
            mOutUrbPool[i].buffer = nullptr;
        }
    }
}

// ── Capture Loop (Uses blocking REAPURB for reliability) ────────────────
//
// Uses the proven blocking USBDEVFS_REAPURB approach:
// - Blocks on REAPURB until a URB completes (kernel delivers it)
// - After processing each URB, re-submits it for continuous capture
// - Checks mRunning + eventfd to determine if we should stop
// - StopCapture() signals stop via eventfd + DISCARDURB to unblock REAPURB
// - NOTE: We do NOT process the eventfd via poll(). Instead after each
//   URB completion we check both mRunning and the eventfd value.
//
// Thread shutdown sequence:
//   1. StopCapture writes 1 to mStopEventFd
//   2. StopCapture sets mRunning = false
//   3. StopCapture calls DISCARDURB on all submitted URBs
//   4. Each DISCARDURB causes a URB to complete → REAPURB returns
//   5. CaptureLoop sees mRunning is false, does NOT re-submit, exits
//   6. Thread joins

void UsbIsochronousCaptureEngine::CaptureLoop() {
    LOGI("Capture thread started (monitoring=%d, FD=%d, eventfd=%d)",
         mMonitoring.load(), mUsbFd, mStopEventFd);

    int64_t loopCount = 0;
    int64_t inUrbCount = 0;
    int64_t outUrbCount = 0;
    int64_t totalInBytes = 0;
    int64_t totalInFrames = 0;
    int64_t nextLogTime = 0;

    while (mRunning) {
        // Blocking URB reap — proven reliable approach
        // Blocks until a URB completes or the fd is disrupted
        struct usbdevfs_urb* urb = nullptr;

        int ret = ioctl(mUsbFd, USBDEVFS_REAPURB, &urb);

        if (ret < 0 || !urb) {
            if (!mRunning) {
                // Stop was requested, exit cleanly
                break;
            }
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN) {
                // Should not happen with blocking REAPURB, but handle gracefully
                continue;
            }
            // ENODEV, EINVAL, or other: device disconnected or fd invalidated
            if (mRunning) {
                LOGE("REAPURB failed on FD=%d: %s (errno=%d)", mUsbFd, strerror(errno), errno);
                LOG_MAIN("Capture loop REAPURB failed on FD=%d: %s (errno=%d)",
                         mUsbFd, strerror(errno), errno);
            }
            break;
        }

        loopCount++;

        // Safety: Validate URB metadata before use.
        // If the kernel returns a stale completion with corrupted data (e.g. NULL
        // usercontext), log a clear diagnostic instead of crashing on dereference.
        if (!urb->usercontext) {
            LOGE("URB has NULL usercontext! urb=%p, endpoint=0x%02X, status=%d",
                 urb, urb->endpoint, urb->status);
            LOG_MAIN("URB has NULL usercontext! urb=%p, endpoint=0x%02X, status=%d",
                     urb, urb->endpoint, urb->status);
            break;
        }
        UrbNode* node = static_cast<UrbNode*>(urb->usercontext);
        if (!node->buffer || !node->urb) {
            LOGE("UrbNode has null buffer=%p or urb=%p!", node->buffer, node->urb);
            LOG_MAIN("UrbNode has null buffer=%p or urb=%p!", node->buffer, node->urb);
            break;
        }

        // Determine if this is an IN or OUT URB by checking endpoint address
        // IN endpoints have bit 7 set (0x86), OUT endpoints have bit 7 clear (0x05)
        bool isOutUrb = ((urb->endpoint & 0x80) == 0);

        if (!isOutUrb) {
            inUrbCount++;
            // ── IN URB: process captured audio data ────────────────────────
            int urbTotalBytes = 0;
            int urbPacketsWithData = 0;
            int urbPacketsWithError = 0;
            int urbFramesDecoded = 0;

            for (int i = 0; i < kIsoPacketsPerUrb; i++) {
                auto& frame = urb->iso_frame_desc[i];

                // Determine how many bytes of data are in this packet.
                // `actual_length` is the authoritative value from USB controller,
                // but some Android USB host controllers fail to set it for
                // isochronous IN transfers even though data IS written to the
                // buffer. We verify by scanning for non-zero bytes.
                int bytesInPacket = 0;
                if (frame.status == 0) {
                    if (frame.actual_length > 0) {
                        bytesInPacket = frame.actual_length;
                    } else {
                        // actual_length is 0 but status is OK — scan buffer for data
                        // This works around Android USB controller bugs that don't
                        // report actual_length for isochronous transfers
                        const uint8_t* pktBuf = node->buffer + i * 1024;
                        // Scan up to packetSize bytes for the first non-zero frame
                        size_t scanLimit = (1024 / mBytesPerFrame) * mBytesPerFrame;
                        for (size_t off = 0; off + mBytesPerFrame - 1 < scanLimit; off += mBytesPerFrame) {
                            bool allZero = true;
                            for (int b = 0; b < mBytesPerFrame; b++) {
                                if (pktBuf[off + b] != 0) { allZero = false; break; }
                            }
                            if (!allZero) {
                                // Found non-zero data starting at byte offset `off`
                                bytesInPacket = scanLimit - off;
                                if (bytesInPacket > 1024) bytesInPacket = 1024;
                                if (inUrbCount <= 16) {
                                    LOGI("IN URB #%lld packet %d: actual_length=0 but %d bytes detected at offset %zu (first non-zero frame)",
                                         (long long)inUrbCount, i, bytesInPacket, off);
                                }
                                break;
                            }
                        }
                    }
                }

                if (bytesInPacket > 0) {
                    urbTotalBytes += bytesInPacket;
                    urbPacketsWithData++;
                    const uint8_t* packetData = node->buffer + i * 1024;
                    int framesInPacket = bytesInPacket / mBytesPerFrame;
                    urbFramesDecoded += framesInPacket;
                    totalInFrames += framesInPacket;

                    // Temporary buffer for decoded channels (max 10 channels)
                    int32_t channels[10];

                    // Decode S24_3LE and extract master L/R
                    for (int f = 0; f < framesInPacket; f++) {
                        const uint8_t* src = packetData + f * mBytesPerFrame;

                        // Decode all channels from S24_3LE (3 bytes each, LE signed)
                        int numCh = mChannelCount > 10 ? 10 : mChannelCount;
                        for (int c = 0; c < numCh; c++) {
                            int offset = c * 3;
                            channels[c] = ((int32_t)(int8_t)src[offset + 2] << 24)
                                        | (src[offset + 1] << 16)
                                        | (src[offset] << 8);
                        }

                        // Extract master stereo based on configurable channel mapping
                        // mMasterLeftCh and mMasterRightCh specify which decoded channels
                        // map to left/right (0-indexed within the N-channel stream)
                        int leftIdx = mMasterLeftCh < numCh ? mMasterLeftCh : 0;
                        int rightIdx = mMasterRightCh < numCh ? mMasterRightCh : (numCh > 1 ? 1 : 0);

                        float sampleL = channels[leftIdx] / 2147483648.0f; // normalize to [-1.0, 1.0)
                        float sampleR = channels[rightIdx] / 2147483648.0f;

                        // Update levels
                        UpdatePeak(mLeftPeak, sampleL);
                        UpdatePeak(mRightPeak, sampleR);

                        // Use compare_exchange_weak for float atomics (no fetch_add on atomic<float>)
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

                        // Write to queue always (both recording and monitoring)
                        float stereo[2] = {sampleL, sampleR};
                        if (!mQueue.Write(stereo, 2)) {
                            mOverrunCount.fetch_add(1);
                        }
                    }
                } else if (frame.status != 0) {
                    urbPacketsWithError++;
                }
            }

            totalInBytes += urbTotalBytes;

            // Log stats every ~1 second (assume ~8 URBs/sec at 48kHz)
            if (inUrbCount % 16 == 0) {
                LOGV("IN URB #%lld: %d packets with data, %d errors, %d bytes, %d frames decoded "
                     "(total: %lld frames, %lld bytes)",
                     (long long)inUrbCount, urbPacketsWithData, urbPacketsWithError,
                     urbTotalBytes, urbFramesDecoded,
                     (long long)totalInFrames, (long long)totalInBytes);
            }
        } else {
            outUrbCount++;
            // Log OUT URB stats every 16 reaps
            if (outUrbCount % 16 == 0) {
                LOGV("OUT URB #%lld reaped successfully", (long long)outUrbCount);
            }
        }

        // Check if stop was requested via eventfd
        // Do this BEFORE re-submitting so we don't queue new work we'd have to discard
        if (!mRunning) {
            // Do not re-submit — thread is exiting
            break;
        }
        if (mStopEventFd >= 0) {
            uint64_t eventfdVal = 0;
            ssize_t rd = read(mStopEventFd, &eventfdVal, sizeof(eventfdVal));
            if (rd > 0) {
                // Stop eventfd was signalled — exit without re-submitting this URB
                LOGI("Capture loop: stop eventfd signalled, exiting");
                break;
            }
        }

        // Re-submit the URB (works for both IN and OUT)
        // NOTE: The URB struct was filled by the kernel on reap. We must reset
        // status/actual_length fields before re-submitting.
        urb->status = 0;
        urb->actual_length = 0;
        for (int j = 0; j < kIsoPacketsPerUrb; j++) {
            urb->iso_frame_desc[j].status = 0;
            urb->iso_frame_desc[j].actual_length = 0;
        }

        // Clear the data buffer for IN URBs to prevent stale data
        // from triggering false positives in the actual_length workaround
        if (!isOutUrb && node->buffer && urb->buffer_length > 0) {
            std::memset(node->buffer, 0, urb->buffer_length);
        }

        if (isOutUrb) {
            // Dynamically fill and configure the OUT URB before re-submitting
            double framesPerPacket = (double)mSampleRate / 8000.0;
            size_t currentOffset = 0;

            for (int j = 0; j < kIsoPacketsPerUrb; j++) {
                mOutFramesAccumulator += framesPerPacket;
                int framesToSend = (int)mOutFramesAccumulator;
                mOutFramesAccumulator -= framesToSend;

                int packetLength = framesToSend * mBytesPerFrame;
                // Safety: Validate OUT packet length before submission
                if (packetLength < 0 || packetLength > 4096) {
                    LOGE("OUT URB packet %d has invalid packetLength=%d (framesToSend=%d, mBytesPerFrame=%d)",
                         j, packetLength, framesToSend, mBytesPerFrame);
                    LOG_MAIN("OUT URB packet %d invalid packetLength=%d!", j, packetLength);
                }
                urb->iso_frame_desc[j].length = packetLength;
                currentOffset += packetLength;
            }
            urb->buffer_length = static_cast<unsigned int>(currentOffset);
            // Fill with silence (zeros)
            if (node->buffer && currentOffset > 0) {
                std::memset(node->buffer, 0, currentOffset);
            }
        }

        if (ioctl(mUsbFd, USBDEVFS_SUBMITURB, urb) < 0) {
            if (errno != ENODEV && mRunning) {
                LOGE("Failed to re-submit URB (EP=0x%02X): %s", urb->endpoint, strerror(errno));
                LOG_MAIN("SUBMITURB failed: urb=%p, endpoint=0x%02X, errno=%d (%s)",
                         urb, urb->endpoint, errno, strerror(errno));
            }
            break;
        }
    }

    // Final stats
    LOGI("Capture thread exiting: loops=%lld, IN URBs=%lld, OUT URBs=%lld, total IN bytes=%lld, total IN frames=%lld",
         (long long)loopCount, (long long)inUrbCount, (long long)outUrbCount,
         (long long)totalInBytes, (long long)totalInFrames);

    // Thread is exiting — clear running flag so IsRecording/IsMonitoring returns false
    mRunning.store(false, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);
    LOGI("Capture thread exited (FD=%d)", mUsbFd);
    LOG_MAIN("Capture thread exited.");
}

// ── UAC2 Sampling Frequency Control ──────────────────────────────────────
//
// Some Class 0xFF vendor-specific audio devices still respond to standard
// UAC2 control requests. The kernel's snd_usb_init_sample_rate() sends
// SET_CUR requesting 48kHz sampling frequency after selecting alt setting.
// We try this via USBDEVFS_CONTROL ioctl — harmless if the device ignores it.

static bool SendUac2SetSamplingRate(int fd, int interfaceNum, int sampleRate) {
    // UAC2 SET_CUR: SAMPLING_FREQ_CONTROL
    // bmRequestType = 0x01 (host-to-device, class, interface)
    // bRequest = 0x01 (SET_CUR)
    // wValue = (CS_CONTROL_SAM_FREQ << 8) | channel = (0x01 << 8) | 0 = 0x0100
    // wIndex = interface number (low byte), entity 0 (high byte)
    // data: 3-byte little-endian sample rate
    uint8_t data[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };

    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = 0x01;  // host-to-device, class, interface
    ctrl.bRequest = 0x01;      // SET_CUR
    ctrl.wValue = 0x0100;      // SAMPLING_FREQ_CONTROL, channel 0
    ctrl.wIndex = static_cast<uint16_t>(interfaceNum);  // interface 0
    ctrl.wLength = 3;          // 3 bytes for UAC2 frequency
    ctrl.timeout = 1000;       // 1 second timeout
    ctrl.data = data;

    int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        LOGI("UAC2 SET_CUR sample rate ignored on IF=%d: %s (errno=%d) — continuing anyway",
             interfaceNum, strerror(errno), errno);
        // Not a failure — device may be truly vendor-specific without UAC2
        return false;
    }
    LOGI("UAC2 SET_CUR sample rate=%dHz on IF=%d: returned %d bytes",
         sampleRate, interfaceNum, ret);
    LOG_MAIN("UAC2 SET_CUR sample rate %dHz on IF %d: %d bytes", sampleRate, interfaceNum, ret);
    return true;
}

// ── Internal helper: stop the capture thread safely ──────────────────────
//
// Called internally when transitioning between monitoring and capture.
// Does NOT acquire mMutex (must not be called while holding mMutex).
// Used to avoid the self-deadlock where StartCapture() holds mMutex
// and calls StopCapture() which also tries to acquire mMutex.
void UsbIsochronousCaptureEngine::StopCaptureLocked() {
    if (!mRunning.load(std::memory_order_acquire)) {
        return;
    }

    int currentFd = -1;
    int currentEventFd = -1;

    // Capture FDs under mutex, then signal the thread
    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        currentFd = mUsbFd;
        currentEventFd = mStopEventFd;
    }

    LOGI("Stopping USB capture (FD=%d, monitoring=%d, overruns=%d)",
         currentFd, mMonitoring.load(), mOverrunCount.load());

    // Step 1: Signal the eventfd to tell the loop to stop
    if (currentEventFd >= 0) {
        uint64_t val = 1;
        ssize_t written = write(currentEventFd, &val, sizeof(val));
        if (written < 0) {
            LOGE("Failed to signal stop eventfd: %s (errno=%d)", strerror(errno), errno);
        }
    }

    // Step 2: Set mRunning false so the loop stops re-submitting after reaping
    mRunning.store(false, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);

    // Step 3: Discard all IN URBs in flight — this causes REAPURB to return
    // for each URB, allowing the thread to process them and exit.
    for (int i = 0; i < kUrbPoolSize; i++) {
        if (mInUrbPool[i].urb && currentFd >= 0) {
            int ret = ioctl(currentFd, USBDEVFS_DISCARDURB, mInUrbPool[i].urb);
            if (ret < 0 && errno != ENODEV && errno != EINVAL && errno != ENOENT) {
                LOGE("StopCapture: DISCARDURB[IN %d] on FD=%d: errno=%d",
                     i, currentFd, errno);
            }
        }
    }

    // Discard all OUT URBs too
    for (int i = 0; i < kOutUrbPoolSize; i++) {
        if (mOutUrbPool[i].urb && currentFd >= 0) {
            int ret = ioctl(currentFd, USBDEVFS_DISCARDURB, mOutUrbPool[i].urb);
            if (ret < 0 && errno != ENODEV && errno != EINVAL && errno != ENOENT) {
                LOGE("StopCapture: DISCARDURB[OUT %d] on FD=%d: errno=%d",
                     i, currentFd, errno);
            }
        }
    }

    // Step 4: Join the capture thread
    if (mCaptureThread.joinable()) {
        mCaptureThread.join();
    }

    // Step 4b: Drain any remaining URB completions from the kernel.
    //
    // CRITICAL FIX: When we call DISCARDURB on in-flight URBs, the kernel queues
    // completion events for ALL discarded URBs. The capture loop typically only
    // reaps the first one (which unblocks REAPURB) then exits because mRunning=false.
    // The remaining completions stay in the kernel's internal queue, still pointing
    // to our URB memory addresses.
    //
    // If we free and reallocate URBs at the same addresses (via calloc/new), the
    // next capture loop's REAPURB will return these stale completions with corrupted
    // data (wrong endpoint, null usercontext, etc.), causing a crash.
    //
    // We drain using non-blocking REAPURBNDELAY until EAGAIN (queue empty).
    // This runs after the thread has exited so there's no race.
    //
    // Limit to (kUrbPoolSize + kOutUrbPoolSize) attempts as a safety bound to
    // prevent infinite loops if EAGAIN is never returned.
    if (currentFd >= 0) {
        int urbsDrained = 0;
        const int maxExpected = kUrbPoolSize + kOutUrbPoolSize;
        for (int attempt = 0; attempt < maxExpected; attempt++) {
            struct usbdevfs_urb* staleUrb = nullptr;
            int ret = ioctl(currentFd, USBDEVFS_REAPURBNDELAY, &staleUrb);
            if (ret < 0) {
                if (errno == EAGAIN) {
                    // Queue is empty — this is the normal case
                    break;
                }
                if (errno == ENODEV || errno == EINVAL) {
                    // Device disconnected or fd invalidated — stop draining
                    break;
                }
                // EINTR or other transient — continue trying
                continue;
            }
            if (staleUrb) {
                urbsDrained++;
                LOGV("StopCaptureLocked: drained stale URB completion #%d: urb=%p, endpoint=0x%02X",
                     attempt, staleUrb, staleUrb->endpoint);
            }
        }
        if (urbsDrained > 0) {
            LOGI("StopCaptureLocked: drained %d stale URB completions from kernel queue", urbsDrained);
            LOG_MAIN("StopCaptureLocked: drained %d stale URB completions", urbsDrained);
        }
    }

    // Step 5: Now that the thread has exited, clean up URBs and FDs

    // Release USB interface via usbfs (cleanup, may fail on already-closed FD)
    if (currentFd >= 0) {
        int iface_num = 0;
        ioctl(currentFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
    }

    DestroyInUrbPool();
    DestroyOutUrbPool();

    // Close the eventfd
    if (currentEventFd >= 0) {
        close(currentEventFd);
    }

    {
        std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
        mUsbFd = -1;
        mStopEventFd = -1;
    }
    mEpAddress = -1;

    LOGI("USB capture stopped (was FD=%d)", currentFd);
    LOG_MAIN("USB capture stopped cleanly (was FD=%d)", currentFd);
}

// ── StartCapture ────────────────────────────────────────────────────────

bool UsbIsochronousCaptureEngine::StartCapture(
    int usbFd, int epAddress, int channelCount,
    int masterLeftCh, int masterRightCh, int sampleRate)
{
    std::lock_guard<std::mutex> lock(mMutex);

    // Always stop first to clean up any stale URBs/threads
    // NOTE: We call StopCaptureLocked() NOT StopCapture() to avoid
    // deadlock — StopCapture() also acquires mMutex.
    if (mRunning) {
        if (mMonitoring.load(std::memory_order_acquire)) {
            LOGI("Monitoring → capture: stopping monitoring, will restart with FD=%d", usbFd);
        } else {
            LOGE("StartCapture called while already running — stopping first");
        }
        StopCaptureLocked();  // does NOT lock mMutex — we already hold it
    }

    LOGI("StartCapture: setting up on FD=%d, EP=0x%02X", usbFd, epAddress);
    LOG_MAIN("StartCapture: setting up on FD=%d, EP=0x%02X", usbFd, epAddress);

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
    mBytesPerFrame = channelCount * 3; // N channels × 3 bytes S24_3LE
    // DJM-900NXS: 8 channels × 3 bytes = 24 bytes per frame (USB 1/2 through USB 7/8)
    LOGI("mBytesPerFrame=%d (ch=%d × 3 bytes S24_3LE)", mBytesPerFrame, channelCount);

    // ── Step 1: Claim interface 0 and select alternate setting 1 ──────
    // The Kotlin-side claimInterface() may not work correctly for alt 1
    // selection, and Android's controlTransfer for SET_INTERFACE can be
    // blocked or silently fail. We do it here via usbfs ioctls instead.
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

    // Select alternate setting 1 (where EP 0x86 and EP 0x05 live)
    struct usbdevfs_setinterface alt_setting = {};
    alt_setting.interface = 0;
    alt_setting.altsetting = 1;
    int alt_ret = ioctl(usbFd, USBDEVFS_SETINTERFACE, &alt_setting);
    if (alt_ret < 0) {
        LOGE("USBDEVFS_SETINTERFACE alt=1 failed on FD=%d: %s (errno=%d)",
             usbFd, strerror(errno), errno);
        LOG_MAIN("FAILED to select USB alt setting 1: %s", strerror(errno));
        mLastErrorMessage = std::string("Set alt setting failed: ") + strerror(errno);
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }
    LOGI("Selected alternate setting 1 (EP 0x86 and EP 0x05 now active)");
    LOG_MAIN("Selected USB alt setting 1 OK");

    // ── Step 2: Send UAC2 SET_CUR sampling frequency (best-effort) ────
    bool rateSet = SendUac2SetSamplingRate(usbFd, iface_num, mSampleRate);
    LOGI("UAC2 sample rate init: %s", rateSet ? "ACK'ed by device" : "ignored (OK)");

    // Reset level data
    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mOverrunCount.store(0, std::memory_order_release);
    mOutFrameCounter = 0;
    mOutFramesAccumulator = 0.0;
    mQueue.Reset();
    mLastErrorMessage.clear();

    // ── Step 3: Set up IN URB pool (capture on EP 0x86) ──────────────
    if (!SetupUrbPool(usbFd, epAddress)) {
        mLastErrorMessage = "Failed to set up IN URB pool";
        LOGE("%s", mLastErrorMessage.c_str());
        LOG_MAIN("%s", mLastErrorMessage.c_str());
        DestroyInUrbPool();
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }

    // ── Step 4: Set up OUT URB pool (silence on EP 0x05 for implicit feedback) ──
    if (!SetupOutUrbPool(usbFd)) {
        LOGE("Failed to set up OUT URB pool — continuing anyway (implicit feedback may not work)");
        LOG_MAIN("OUT URB pool setup failed — continuing without silence sync data");
        // Don't fail — the OUT URBs are best-effort for implicit feedback
    }

    // Set mRunning BEFORE starting the thread so the thread sees it immediately
    mRunning.store(true, std::memory_order_release);
    mMonitoring.store(false, std::memory_order_release);
    mCaptureThread = std::thread(&UsbIsochronousCaptureEngine::CaptureLoop, this);

    LOGI("USB capture started: FD=%d, EP=0x%02X, ch=%d, L=%d, R=%d, rate=%d",
         usbFd, epAddress, channelCount, masterLeftCh, masterRightCh, sampleRate);
    LOG_MAIN("USB capture started OK: FD=%d, EP=0x%02X, ch=%d, rate=%d",
             usbFd, epAddress, channelCount, sampleRate);
    return true;
}

// ── StartMonitoring ─────────────────────────────────────────────────────

bool UsbIsochronousCaptureEngine::StartMonitoring(
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
        StopCaptureLocked();  // does NOT lock mMutex — we already hold it
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
    mBytesPerFrame = channelCount * 3; // N channels × 3 bytes S24_3LE
    LOGI("StartMonitoring: mBytesPerFrame=%d (ch=%d × 3 bytes S24_3LE)", mBytesPerFrame, channelCount);

    // ── Claim interface 0 and select alternate setting 1 ──────────────
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

    struct usbdevfs_setinterface alt_setting = {};
    alt_setting.interface = 0;
    alt_setting.altsetting = 1;
    int alt_ret = ioctl(usbFd, USBDEVFS_SETINTERFACE, &alt_setting);
    if (alt_ret < 0) {
        LOGE("USBDEVFS_SETINTERFACE alt=1 failed on FD=%d: %s (errno=%d)",
             usbFd, strerror(errno), errno);
        LOG_MAIN("FAILED to select USB alt setting 1 for monitoring: %s", strerror(errno));
        mLastErrorMessage = std::string("Set alt setting failed: ") + strerror(errno);
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }
    LOGI("Selected alternate setting 1 for monitoring");
    LOG_MAIN("Selected USB alt setting 1 OK (monitoring)");

    // Reset level data
    mLeftPeak.store(0.0f, std::memory_order_release);
    mRightPeak.store(0.0f, std::memory_order_release);
    mLeftRmsSum.store(0.0f, std::memory_order_release);
    mRightRmsSum.store(0.0f, std::memory_order_release);
    mRmsCount.store(0, std::memory_order_release);
    mOutFrameCounter = 0;
    mOutFramesAccumulator = 0.0;
    mQueue.Reset();
    mLastErrorMessage.clear();

    if (!SetupUrbPool(usbFd, epAddress)) {
        mLastErrorMessage = "Failed to set up IN URB pool for monitoring";
        LOGE("%s", mLastErrorMessage.c_str());
        LOG_MAIN("%s", mLastErrorMessage.c_str());
        DestroyInUrbPool();
        ioctl(usbFd, USBDEVFS_RELEASEINTERFACE, &iface_num);
        close(mStopEventFd);
        mStopEventFd = -1;
        {
            std::lock_guard<std::mutex> fdLock(mUsbFdMutex);
            mUsbFd = -1;
        }
        return false;
    }

    // Set up OUT URBs for implicit feedback (best-effort)
    if (!SetupOutUrbPool(usbFd)) {
        LOGE("Failed to set up OUT URB pool for monitoring — continuing anyway");
        LOG_MAIN("OUT URB pool setup failed for monitoring — continuing without sync data");
    }

    // Set mRunning BEFORE starting the thread
    mRunning.store(true, std::memory_order_release);
    mMonitoring.store(true, std::memory_order_release);
    mCaptureThread = std::thread(&UsbIsochronousCaptureEngine::CaptureLoop, this);

    LOGI("USB monitoring started: FD=%d, EP=0x%02X", usbFd, epAddress);
    LOG_MAIN("USB monitoring started OK: FD=%d, EP=0x%02X", usbFd, epAddress);
    return true;
}

// ── StopCapture (tears down both IN and OUT pools) ──────────────────────

void UsbIsochronousCaptureEngine::StopCapture() {
    std::lock_guard<std::mutex> lock(mMutex);
    StopCaptureLocked();
}

// ── Query methods (unchanged semantics) ─────────────────────────────────

bool UsbIsochronousCaptureEngine::IsRecording() const {
    return mRunning.load(std::memory_order_acquire) &&
           !mMonitoring.load(std::memory_order_acquire);
}

bool UsbIsochronousCaptureEngine::IsMonitoring() const {
    return mRunning.load(std::memory_order_acquire) &&
           mMonitoring.load(std::memory_order_acquire);
}

int32_t UsbIsochronousCaptureEngine::ReadCapturedData(float* outputBuffer,
                                                       int32_t maxFrames) {
    int32_t samplesToRead = maxFrames * 2;
    return mQueue.Read(outputBuffer, samplesToRead) / 2;
}

void UsbIsochronousCaptureEngine::GetLevelData(float* outLeftPeak,
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

const char* UsbIsochronousCaptureEngine::GetLastErrorMessage() const {
    return mLastErrorMessage.c_str();
}

void UsbIsochronousCaptureEngine::UpdatePeak(std::atomic<float>& peak, float value) {
    float current = peak.load(std::memory_order_relaxed);
    while (value > current &&
           !peak.compare_exchange_weak(current, value,
                                         std::memory_order_release,
                                         std::memory_order_relaxed));
}
