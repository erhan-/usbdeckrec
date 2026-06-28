#ifndef LOCK_FREE_QUEUE_H
#define LOCK_FREE_QUEUE_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <cassert>

/**
 * Lock-free Single-Producer Single-Consumer queue for float PCM data.
 * This is designed for audio callback use — no allocations, no locks.
 *
 * Uses uint32_t for indices (unsigned overflow wraps mod 2^32, well-defined in C++).
 * mCapacity MUST be a power of 2 for the bitmask-based modulo optimization.
 * This prevents signed integer overflow (UB) after ~12 hours of continuous recording
 * and replaces expensive modulo with a cheap bitwise AND.
 */
template<typename T>
class LockFreeQueue {
public:
    explicit LockFreeQueue(int32_t capacity) : mCapacity(capacity), mBitmask(static_cast<uint32_t>(capacity - 1)) {
        // Must be power of 2 so we can use (index & mBitmask) instead of (index % mCapacity)
        assert((capacity & (capacity - 1)) == 0 && "Capacity must be a power of 2");
        mBuffer = new T[capacity];
    }

    ~LockFreeQueue() {
        delete[] mBuffer;
    }

    bool Write(const T* data, int32_t count) {
        uint32_t currentWrite = mWriteIndex.load(std::memory_order_relaxed);
        uint32_t currentRead = mReadIndex.load(std::memory_order_acquire);
        uint32_t used = currentWrite - currentRead;
        if (used + static_cast<uint32_t>(count) > static_cast<uint32_t>(mCapacity)) return false;

        uint32_t pos = currentWrite & mBitmask;
        uint32_t firstCopy = (pos + static_cast<uint32_t>(count) <= static_cast<uint32_t>(mCapacity))
                             ? static_cast<uint32_t>(count)
                             : static_cast<uint32_t>(mCapacity - pos);
        std::memcpy(&mBuffer[pos], data, firstCopy * sizeof(T));
        if (firstCopy < static_cast<uint32_t>(count)) {
            std::memcpy(mBuffer, &data[firstCopy], (static_cast<uint32_t>(count) - firstCopy) * sizeof(T));
        }
        mWriteIndex.store(currentWrite + static_cast<uint32_t>(count), std::memory_order_release);
        return true;
    }

    int32_t Read(T* output, int32_t maxCount) {
        uint32_t currentRead = mReadIndex.load(std::memory_order_relaxed);
        uint32_t currentWrite = mWriteIndex.load(std::memory_order_acquire);
        uint32_t available = currentWrite - currentRead;
        uint32_t toRead = (available < static_cast<uint32_t>(maxCount)) ? available : static_cast<uint32_t>(maxCount);

        uint32_t pos = currentRead & mBitmask;
        uint32_t firstCopy = (pos + toRead <= static_cast<uint32_t>(mCapacity))
                             ? toRead
                             : static_cast<uint32_t>(mCapacity - pos);
        std::memcpy(output, &mBuffer[pos], firstCopy * sizeof(T));
        if (firstCopy < toRead) {
            std::memcpy(&output[firstCopy], mBuffer, (toRead - firstCopy) * sizeof(T));
        }
        mReadIndex.store(currentRead + toRead, std::memory_order_release);
        return static_cast<int32_t>(toRead);
    }

    void Reset() {
        mReadIndex.store(0, std::memory_order_release);
        mWriteIndex.store(0, std::memory_order_release);
    }

    int32_t Available() const {
        return static_cast<int32_t>(
            mWriteIndex.load(std::memory_order_acquire) -
            mReadIndex.load(std::memory_order_acquire));
    }

private:
    T* mBuffer;
    int32_t mCapacity;
    uint32_t mBitmask;
    std::atomic<uint32_t> mWriteIndex{0};
    std::atomic<uint32_t> mReadIndex{0};
};

#endif // LOCK_FREE_QUEUE_H
