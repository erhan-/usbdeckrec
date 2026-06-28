package com.usbdeckrec.audio

import java.nio.ByteBuffer

class AudioEngineBridge {
    companion object {
        init {
            System.loadLibrary("deckrec_audio")
        }
    }

    // ── Oboe Capture ────────────────────────────────────────────────

    private external fun nativeStartCapture(
        usbDeviceId: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int
    ): Boolean

    private external fun nativeStopCapture()
    private external fun nativeIsRecording(): Boolean

    private external fun nativeReadCapturedData(
        byteBuffer: ByteBuffer,
        capacityBytes: Int
    ): Int

    private external fun nativeGetLevelData(outLevels: FloatArray)

    // ── Oboe Monitoring ─────────────────────────────────────────────

    private external fun nativeStartMonitoring(
        usbDeviceId: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int
    ): Boolean

    private external fun nativeIsMonitoring(): Boolean

    // ── USB Direct Capture (vendor-specific class 0xFF devices) ──────

    private external fun nativeStartUsbCapture(
        usbFd: Int,
        epAddress: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int,
        sampleRate: Int
    ): Boolean

    private external fun nativeStartUsbMonitoring(
        usbFd: Int,
        epAddress: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int,
        sampleRate: Int
    ): Boolean

    private external fun nativeStopUsbCapture()
    private external fun nativeIsUsbRecording(): Boolean
    private external fun nativeIsUsbMonitoring(): Boolean

    // ── DJM Bulk Capture (MIDI-over-Bulk for Pioneer DJM devices) ─

    private external fun nativeStartDjmBulkCapture(
        usbFd: Int,
        epAddress: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int,
        sampleRate: Int
    ): Boolean

    private external fun nativeStartDjmBulkMonitoring(
        usbFd: Int,
        epAddress: Int,
        channelCount: Int,
        masterLeftChannel: Int,
        masterRightChannel: Int,
        sampleRate: Int
    ): Boolean

    private external fun nativeStopDjmBulkCapture()
    private external fun nativeIsDjmBulkRecording(): Boolean
    private external fun nativeIsDjmBulkMonitoring(): Boolean

    // ── Error reporting ─────────────────────────────────────────────

    private external fun nativeGetLastErrorMessage(): String

    // ── Public Oboe API ─────────────────────────────────────────────

    fun startCapture(usbDeviceId: Int, profile: MixerProfile): Boolean =
        nativeStartCapture(
            usbDeviceId,
            profile.totalChannels,
            profile.masterLeftChannel,
            profile.masterRightChannel
        )

    fun stopCapture() = nativeStopCapture()
    fun isRecording(): Boolean = nativeIsRecording()

    fun readCapturedData(byteBuffer: ByteBuffer): Int {
        if (!byteBuffer.isDirect) {
            throw IllegalArgumentException("ByteBuffer must be direct for zero-copy JNI transfer")
        }
        return nativeReadCapturedData(byteBuffer, byteBuffer.capacity())
    }

    fun getLevelData(): FloatArray {
        val levels = FloatArray(4)
        nativeGetLevelData(levels)
        return levels
    }

    /**
     * Open an Oboe audio stream in monitoring mode for live VU metering
     * without writing data to the capture queue.
     */
    fun startMonitoring(usbDeviceId: Int, profile: MixerProfile): Boolean =
        nativeStartMonitoring(
            usbDeviceId,
            profile.totalChannels,
            profile.masterLeftChannel,
            profile.masterRightChannel
        )

    fun isMonitoring(): Boolean = nativeIsMonitoring()

    /**
     * Get the last error message from the native engine.
     */
    fun getLastErrorMessage(): String = nativeGetLastErrorMessage()

    // ── Public USB Direct Capture API ──────────────────────────────

    /**
     * Start direct USB isochronous capture bypassing Oboe/AAudio.
     * Used for devices with vendor-specific USB class (0xFF) that
     * are invisible to Android's USB Audio HAL.
     *
     * @param usbFd File descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
     * @param epAddress USB endpoint address (e.g., 0x86 for isochronous IN endpoint)
     * @param profile Mixer profile defining channel count and master channels
     * @param sampleRate Sample rate in Hz (typically 48000)
     */
    fun startUsbCapture(
        usbFd: Int,
        epAddress: Int,
        profile: MixerProfile,
        sampleRate: Int = 48000
    ): Boolean = nativeStartUsbCapture(
        usbFd,
        epAddress,
        profile.totalChannels,
        profile.masterLeftChannel,
        profile.masterRightChannel,
        sampleRate
    )

    /**
     * Start direct USB isochronous monitoring (VU only, no queue writes).
     */
    fun startUsbMonitoring(
        usbFd: Int,
        epAddress: Int,
        profile: MixerProfile,
        sampleRate: Int = 48000
    ): Boolean = nativeStartUsbMonitoring(
        usbFd,
        epAddress,
        profile.totalChannels,
        profile.masterLeftChannel,
        profile.masterRightChannel,
        sampleRate
    )

    fun stopUsbCapture() = nativeStopUsbCapture()
    fun isUsbRecording(): Boolean = nativeIsUsbRecording()
    fun isUsbMonitoring(): Boolean = nativeIsUsbMonitoring()

    // ── Public DJM Bulk Capture API (MIDI data, not PCM audio) ──

    /**
     * Start direct USB bulk capture for Pioneer DJM devices.
     * These devices use bulk endpoints for MIDI/status data.
     * NOTE: DJM-900NXS PCM audio is captured via isochronous EP 0x86
     * (see [startUsbCapture]), not via this bulk path.
     *
     * @param usbFd File descriptor from [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
     * @param epAddress USB bulk IN endpoint address (e.g., 0x87 for DJM-900NXS)
     * @param profile Mixer profile defining channel count and master channels
     * @param sampleRate Sample rate in Hz (typically 48000)
     */
    fun startDjmBulkCapture(
        usbFd: Int,
        epAddress: Int,
        profile: MixerProfile,
        sampleRate: Int = 48000
    ): Boolean = nativeStartDjmBulkCapture(
        usbFd,
        epAddress,
        profile.totalChannels,
        profile.masterLeftChannel,
        profile.masterRightChannel,
        sampleRate
    )

    /**
     * Start DJM bulk monitoring (VU from MIDI CC only, no queue writes).
     */
    fun startDjmBulkMonitoring(
        usbFd: Int,
        epAddress: Int,
        profile: MixerProfile,
        sampleRate: Int = 48000
    ): Boolean = nativeStartDjmBulkMonitoring(
        usbFd,
        epAddress,
        profile.totalChannels,
        profile.masterLeftChannel,
        profile.masterRightChannel,
        sampleRate
    )

    fun stopDjmBulkCapture() = nativeStopDjmBulkCapture()
    fun isDjmBulkRecording(): Boolean = nativeIsDjmBulkRecording()
    fun isDjmBulkMonitoring(): Boolean = nativeIsDjmBulkMonitoring()

    // ── Overrun Reporting ───────────────────────────────────────────

    private external fun nativeGetUsbOverrunCount(): Int
    private external fun nativeGetDjmBulkOverrunCount(): Int

    /**
     * Returns the number of dropped/overrun audio frames since the last
     * [startUsbCapture] call for the USB isochronous engine.
     */
    fun getUsbOverrunCount(): Int = nativeGetUsbOverrunCount()

    /**
     * Returns the number of dropped/overrun MIDI packets since the last
     * [startDjmBulkCapture] call for the DJM bulk engine.
     */
    fun getDjmBulkOverrunCount(): Int = nativeGetDjmBulkOverrunCount()
}
