package com.usbdeckrec.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.preference.PreferenceManager
import com.usbdeckrec.usb.DjmDeviceInitializer
import com.usbdeckrec.util.DebugLogger

/**
 * Lifecycle manager for the native audio capture engine.
 *
 * Supports three modes of audio capture:
 * 1. **Oboe/AAudio** — For standard USB Audio Class (UAC) devices visible to
 *    Android's AudioManager.getDevices().
 * 2. **Direct USB Isochronous** — For vendor-specific USB class (0xFF) devices
 *    that use isochronous audio streaming (e.g., DJM-900NXS, DJM-A9, DJM-V10).
 * 3. **DJM Bulk (MIDI-over-Bulk)** — For Pioneer DJM devices
 *    that use bulk endpoints for MIDI/status communication (MIDI only, not PCM audio).
 *
 * Profile resolution uses a three-tier strategy:
 * 1. **User override** — Checks SharedPreferences for a manually configured profile
 * 2. **Auto-detection** — Matches the device VID/PID against [MixerProfileDatabase]
 * 3. **Fallback** — Uses [MixerProfileDatabase.getGenericProfile] (2ch, master 1-2)
 *
 * Delegates all native audio operations to [AudioEngineBridge].
 */
class AudioEngineManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AudioEngineManager? = null

        fun getInstance(context: Context): AudioEngineManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioEngineManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val bridge = AudioEngineBridge()
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private var currentProfile: MixerProfile? = null
    private var activeUsbDevice: UsbDevice? = null

    // Direct USB connection state (for vendor-specific class 0xFF devices)
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    private var activeUsbFd: Int = -1
    private var activeEpAddress: Int = -1

    /**
     * Check whether a USB device requires direct capture instead of
     * Oboe/AAudio. This is true when the device uses vendor-specific USB class
     * (0xFF) for its audio/MIDI interface, making it invisible to Android's
     * USB Audio HAL and thus AudioManager.getDevices().
     *
     * NOTE: Android's UsbDevice.getInterface() only exposes alternate setting 0
     * for each interface. On devices like the DJM-900NXS, interface 0 alt 0 has
     * class 0xFF but ZERO endpoints — the bulk IN endpoint (0x87) only exists
     * in alt setting 1. So we must detect by class alone, not by endpoint.
     */
    private fun needsDirectUsb(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            DebugLogger.log("AudioEngineManager",
                "needsDirectUsb: IF %d, class=0x%02X, subclass=0x%02X, alt=%d, endpoints=%d"
                    .format(i, iface.interfaceClass, iface.interfaceSubclass,
                            iface.alternateSetting, iface.endpointCount))
            if (iface.interfaceClass == 0xFF) { // USB_CLASS_VEND_SPEC
                DebugLogger.log("AudioEngineManager",
                    "Device ${device.productName}: found vendor-specific IF %d — needs direct USB"
                        .format(i))
                return true
            }
        }
        DebugLogger.log("AudioEngineManager",
            "Device ${device.productName}: no vendor-specific interface found, using Oboe path")
        return false
    }

    /**
     * Find the isochronous IN endpoint address for direct USB capture.
     * Returns the full endpoint address including direction bit
     * (e.g., 0x86 for endpoint 6 IN), or -1 if not found.
     *
     * NOTE: The USBDEVFS URB endpoint field requires the full address:
     *   - Bit 7: direction (0=OUT, 1=IN)
     *   - Bits 3-0: endpoint number
     *   ep.endpointNumber returns only the raw number (e.g., 6),
     *   so we must OR with USB_DIR_IN to get the full address (e.g., 0x86).
     */
    private fun findIsochronousInEndpoint(device: UsbDevice): Int {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (epIdx in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(epIdx)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    return ep.endpointNumber or UsbConstants.USB_DIR_IN
                }
            }
        }
        return -1
    }

    /**
     * Check if a given product ID corresponds to a bulk-transfer Pioneer DJM device.
     */
    private fun isBulkDevice(productId: Int): Boolean =
        MixerProfileDatabase.isBulkTransferDevice(productId)

    /**
     * Resolve a [MixerProfile] for the given USB device without starting capture.
     * This is used by the UI polling loop to show device info before recording starts.
     *
     * Same three-tier resolution strategy as [startCapture]:
     * 1. Manual override from SharedPreferences
     * 2. Auto-detect by USB VID/PID
     * 3. Generic 2-channel fallback
     */
    fun resolveProfileForDevice(device: UsbDevice): MixerProfile {
        val manualOverride = prefs.getString("manual_mixer_profile", null)

        // Tier 1: User override from Settings
        if (manualOverride != null) {
            val customProfile = parseManualProfile(manualOverride)
            if (customProfile != null) {
                DebugLogger.log("AudioEngineManager", "Using manual override: $manualOverride")
                return customProfile
            }
        }

        // Tier 2: Auto-detect by USB vendor/product ID
        val detected = MixerProfileDatabase.getProfile(device.vendorId, device.productId)
        if (detected != null) {
            DebugLogger.log("AudioEngineManager",
                "Auto-detected: ${detected.modelName} (VID=0x%04X, PID=0x%04X)"
                    .format(device.vendorId, device.productId))
            return detected
        }

        // Tier 3: Fallback to generic profile
        DebugLogger.log("AudioEngineManager",
            "Unknown device (VID=0x%04X, PID=0x%04X) — using generic 2ch profile"
                .format(device.vendorId, device.productId))
        return MixerProfileDatabase.getGenericProfile()
    }

    /**
     * Start audio capture for the given USB device.
     *
     * Automatically selects between Oboe/AAudio and direct USB isochronous
     * based on the device profile. Devices with bulk-transfer-only profiles
     * (MIDI without PCM audio) are rejected.
     *
     * @return true if capture started successfully, false otherwise
     */
    fun startCapture(usbDevice: UsbDevice): Boolean {
        val profile = resolveProfileForDevice(usbDevice)
        currentProfile = profile
        activeUsbDevice = usbDevice

        // Bulk-transfer-only devices (MIDI without PCM audio) cannot record.
        // Some devices like DJM-900NXS have BOTH isochronous audio + bulk MIDI
        // (usesBulkTransfer=false captures the isochronous audio path).
        // Purely bulk devices (if any) are caught here.
        if (profile.usesBulkTransfer) {
            DebugLogger.log("AudioEngineManager",
                "Cannot record: ${profile.modelName} uses bulk MIDI only — " +
                "no PCM audio available over USB. Monitoring is available " +
                "for VU meter display only.")
            return false
        }

        // Stop any monitoring engine before starting capture to avoid
        // conflicting native streams. Without this, the monitoring thread
        // (Oboe or direct USB) may still be running when the capture engine
        // tries to claim the same audio device, causing a crash on the first
        // recording attempt after monitoring.
        bridge.stopCapture()
        bridge.stopUsbCapture()
        bridge.stopDjmBulkCapture()

        // Detect if this device needs direct USB isochronous capture
        if (needsDirectUsb(usbDevice)) {
            DebugLogger.log("AudioEngineManager",
                "Using direct USB isochronous capture for ${profile.modelName}")
            return startUsbCaptureInternal(usbDevice, profile)
        }

        // Standard Oboe/AAudio path
        val audioDeviceId = resolveAudioDeviceId(usbDevice, profile)
        DebugLogger.log("AudioEngineManager",
            "Start capture: USB deviceId=%d, audioDeviceId=%d, model=%s, ch=%d, L=%d, R=%d"
                .format(usbDevice.deviceId, audioDeviceId, profile.modelName,
                        profile.totalChannels, profile.masterLeftChannel, profile.masterRightChannel))

        logAllInputDevices()

        val success = bridge.startCapture(audioDeviceId, profile)
        if (!success) {
            val nativeError = bridge.getLastErrorMessage()
            DebugLogger.log("AudioEngineManager",
                "CAPTURE FAILED — native error: $nativeError")
        }
        return success
    }

    /**
     * Internal: Start DJM bulk MIDI capture using the DjmBulkCaptureEngine.
     *
     * This path is used for Pioneer DJM devices that communicate via
     * bulk endpoints carrying USB MIDI event packets (EP 0x87).
     * NOTE: DJM-900NXS uses isochronous endpoints (EP 0x86) for PCM audio —
     * this bulk path is only for MIDI/status data. Audio capture routes
     * through [startUsbCaptureInternal] instead.
     *
     * The initialization sequence is:
     * 1. Open USB device connection
     * 2. Claim interface 0 (vendor-specific)
     * 3. Select alternate setting 1 on IF 0 (activates bulk endpoints)
     * 4. Select alternate setting 0 on IF 2 (MIDI streaming)
     * 5. Send vendor SET_FEATURE handshake (5 control transfers)
     * 6. Verify with GET_STATUS
     * 7. Pass FD+EP to native bulk capture engine
     */
    private fun startDjmBulkCaptureInternal(
        usbDevice: UsbDevice,
        profile: MixerProfile
    ): Boolean {
        // Stop any existing engines first
        bridge.stopDjmBulkCapture()
        bridge.stopUsbCapture()
        releaseUsbConnection()

        val connection = usbManager.openDevice(usbDevice)
            ?: run {
                DebugLogger.log("AudioEngineManager", "Failed to open USB device")
                return false
            }

        // ── Step 1: Claim and configure interfaces ──────────────────
        val vendorInterface = usbDevice.getInterface(0) // IF 0 = vendor-specific
        if (vendorInterface == null) {
            DebugLogger.log("AudioEngineManager", "Vendor interface (IF 0) not found")
            connection.close()
            return false
        }

        // Claim IF 0 (vendor-specific interface)
        if (!connection.claimInterface(vendorInterface, true)) {
            DebugLogger.log("AudioEngineManager", "Failed to claim vendor interface (IF 0)")
            connection.close()
            return false
        }
        DebugLogger.log("AudioEngineManager", "Claimed vendor interface (IF 0)")

        // Select alternate setting 1 on IF 0 (activates bulk endpoints EP 0x07 + 0x87)
        val altSetRet = DjmDeviceInitializer.setInterfaceAltSetting(connection, vendorInterface, 1)
        if (!altSetRet) {
            DebugLogger.log("AudioEngineManager",
                "setInterface(IF 0, alt=1) failed — continuing anyway")
        } else {
            DebugLogger.log("AudioEngineManager",
                "Selected alternate setting 1 on IF 0 (bulk endpoints active)")
        }

        // Claim and configure MIDI interface (IF 2)
        if (usbDevice.interfaceCount > 2) {
            val midiInterface = usbDevice.getInterface(2) // IF 2 = MIDI streaming
            if (midiInterface != null) {
                if (connection.claimInterface(midiInterface, true)) {
                    DebugLogger.log("AudioEngineManager",
                        "Claimed MIDI interface (IF 2)")
                    DjmDeviceInitializer.setInterfaceAltSetting(connection, midiInterface, 0)
                    DebugLogger.log("AudioEngineManager",
                        "Selected alt setting 0 on IF 2")
                }
            }
        }

        // ── Step 2: Perform vendor SET_FEATURE handshake ────────────
        DebugLogger.log("AudioEngineManager",
            "Performing vendor SET_FEATURE handshake for PID=0x%04X"
                .format(usbDevice.productId))

        val handshakeSuccess = DjmDeviceInitializer.performHandshake(
            connection = connection,
            productId = usbDevice.productId
        )

        if (!handshakeSuccess) {
            DebugLogger.log("AudioEngineManager",
                "Vendor SET_FEATURE handshake FAILED — device may not respond")
            // Continue anyway — some firmware versions may work without it
        } else {
            DebugLogger.log("AudioEngineManager",
                "Vendor SET_FEATURE handshake completed successfully")
        }

        // ── Step 3: Get FD and start the native bulk engine ─────────
        val fd = connection.getFileDescriptor()
        val epAddress = profile.bulkInEpAddress // e.g., 0x87

        DebugLogger.log("AudioEngineManager",
            "Starting DJM bulk capture: FD=%d, EP=0x%02X, profile=%s"
                .format(fd, epAddress, profile.modelName))

        val success = bridge.startDjmBulkCapture(fd, epAddress, profile)
        if (success) {
            activeConnection = connection
            activeInterface = vendorInterface
            activeUsbFd = fd
            activeEpAddress = epAddress
            DebugLogger.log("AudioEngineManager",
                "DJM bulk capture started successfully")
        } else {
            val nativeError = bridge.getLastErrorMessage()
            DebugLogger.log("AudioEngineManager",
                "DJM bulk capture FAILED: $nativeError")
            connection.releaseInterface(vendorInterface)
            connection.close()
        }
        return success
    }

    /**
     * Internal: Start direct USB isochronous capture using UsbDeviceConnection.
     *
     * Performs the complete Pioneer initialization sequence before starting
     * the native isochronous capture engine:
     *
     * 1. Open connection and claim all 3 interfaces
     * 2. Activate MIDI streaming (IF 2 alt 0)
     * 3. Activate vendor audio endpoints (IF 0 alt 1) → enables EP 0x05 + EP 0x86
     * 4. Perform SET_FEATURE handshake (5 vendor control transfers)
     * 5. Activate Audio Control (IF 1 alt 0) and SET_CUR sample rate → 44100 Hz
     * 6. Pass FD to native UsbIsochronousCaptureEngine
     *
     * @see DjmDeviceInitializer.performCompleteInit
     */
    private fun startUsbCaptureInternal(usbDevice: UsbDevice, profile: MixerProfile): Boolean {
        // Stop any running native engines first
        bridge.stopUsbCapture()
        bridge.stopDjmBulkCapture()

        // Reuse existing connection from monitoring if available
        val connection: UsbDeviceConnection
        var ownsConnection: Boolean
        if (activeConnection != null) {
            connection = activeConnection!!
            ownsConnection = false
            DebugLogger.log("AudioEngineManager",
                "Reusing existing USB connection from monitoring")
        } else {
            connection = usbManager.openDevice(usbDevice)
                ?: run {
                    DebugLogger.log("AudioEngineManager", "Failed to open USB device")
                    return false
                }
            ownsConnection = true
        }

        try {
            // ── Step 1: Get the 3 interfaces ─────────────────────────────
            val vendorInterface = usbDevice.getInterface(0)
            val audioControlInterface = usbDevice.getInterface(1)
            val midiInterface = if (usbDevice.interfaceCount > 2)
                usbDevice.getInterface(2) else null

            if (vendorInterface == null || audioControlInterface == null) {
                DebugLogger.log("AudioEngineManager",
                    "Required interfaces not found: vendor=%s, audioCtrl=%s"
                        .format(vendorInterface, audioControlInterface))
                return false
            }

            // ── Step 2: Perform complete Pioneer init sequence ───────────
            val initSuccess = DjmDeviceInitializer.performCompleteInit(
                connection = connection,
                productId = usbDevice.productId,
                vendorInterface = vendorInterface,
                audioControlInterface = audioControlInterface,
                midiInterface = midiInterface,
                sampleRate = 44100
            )

            if (!initSuccess) {
                DebugLogger.log("AudioEngineManager",
                    "Pioneer init sequence FAILED")
                return false
            }

            DebugLogger.log("AudioEngineManager",
                "Pioneer init complete — starting isochronous capture on EP 0x86")

            // ── Step 3: Get FD and start native isochronous capture ──────
            // NOTE: performCompleteInit() no longer claims IF 0 — the native
            // engine claims it directly via ioctl(USBDEVFS_CLAIMINTERFACE).
            val fd = connection.getFileDescriptor()
            val epAddress = 0x86

            DebugLogger.log("AudioEngineManager",
                "Starting USB isochronous capture: FD=%d, EP=0x%02X, profile=%s, rate=44100"
                    .format(fd, epAddress, profile.modelName))

            val success = bridge.startUsbCapture(fd, epAddress, profile, sampleRate = 44100)
            if (success) {
                activeConnection = connection
                activeInterface = null
                activeUsbFd = fd
                activeEpAddress = epAddress
                ownsConnection = false // transfer ownership to activeConnection
                DebugLogger.log("AudioEngineManager",
                    "Direct USB isochronous capture started successfully (44100 Hz, %dch)"
                        .format(profile.totalChannels))
            } else {
                DebugLogger.log("AudioEngineManager",
                    "Direct USB capture FAILED: ${bridge.getLastErrorMessage()}")
            }
            return success
        } finally {
            // Close fresh connection on any failure path (we still own it)
            if (ownsConnection) {
                connection.close()
            }
        }
    }

    /**
     * Internal: Start DJM bulk monitoring (VU meter only, no recording).
     */
    private fun startDjmBulkMonitoringInternal(
        usbDevice: UsbDevice,
        profile: MixerProfile
    ): Boolean {
        if (bridge.isDjmBulkMonitoring()) return true
        if (bridge.isDjmBulkRecording()) return false

        bridge.stopDjmBulkCapture()
        releaseUsbConnection()

        val connection = usbManager.openDevice(usbDevice)
            ?: return false

        // ── Step 1: Claim and configure interfaces ──────────────────
        // Per the Wireshark analysis (Phase 4):
        //   T+0.052s: SET_INTERFACE intf=2 alt=0 (MIDI streaming)
        //   T+0.053s: SET_INTERFACE intf=0 alt=1 (vendor, activates bulk endpoints)
        // Both are required before the device will send MIDI clock data.

        val vendorInterface = usbDevice.getInterface(0)
        if (vendorInterface != null) {
            connection.claimInterface(vendorInterface, true)
            DjmDeviceInitializer.setInterfaceAltSetting(connection, vendorInterface, 1)
        }

        // Claim MIDI interface (IF 2) — required for bulk IN endpoint data flow
        if (usbDevice.interfaceCount > 2) {
            val midiInterface = usbDevice.getInterface(2)
            if (midiInterface != null) {
                connection.claimInterface(midiInterface, true)
                DjmDeviceInitializer.setInterfaceAltSetting(connection, midiInterface, 0)
            }
        }

        // ── Step 2: Perform vendor SET_FEATURE handshake ────────────
        DjmDeviceInitializer.performHandshake(connection, usbDevice.productId)

        // ── Step 3: Get FD and start native monitoring engine ───────
        val fd = connection.getFileDescriptor()
        val epAddress = profile.bulkInEpAddress

        val success = bridge.startDjmBulkMonitoring(fd, epAddress, profile)
        if (success) {
            activeConnection = connection
            activeInterface = vendorInterface
            activeUsbFd = fd
            activeEpAddress = epAddress
        } else {
            connection.close()
        }
        return success
    }

    /**
     * Internal: Start direct USB monitoring (VU meter only, no recording).
     *
     * Performs the complete Pioneer initialization sequence (same as capture)
     * before starting the native isochronous monitoring engine.
     *
     * @see startUsbCaptureInternal
     */
    private fun startUsbMonitoringInternal(usbDevice: UsbDevice, profile: MixerProfile): Boolean {
        if (bridge.isUsbMonitoring()) return true
        if (bridge.isUsbRecording()) return false

        bridge.stopUsbCapture()
        releaseUsbConnection()

        val connection = usbManager.openDevice(usbDevice)
            ?: return false

        // ── Step 1: Get the 3 interfaces ─────────────────────────────
        val vendorInterface = usbDevice.getInterface(0) // IF 0 = vendor-specific
        val audioControlInterface = usbDevice.getInterface(1) // IF 1 = Audio Control
        val midiInterface = if (usbDevice.interfaceCount > 2)
            usbDevice.getInterface(2) else null // IF 2 = MIDI streaming

        if (vendorInterface == null || audioControlInterface == null) {
            DebugLogger.log("AudioEngineManager",
                "Monitoring: Required interfaces not found")
            connection.close()
            return false
        }

        // ── Step 2: Perform complete Pioneer init sequence ───────────
        // Same init as for capture: MIDI streaming, vendor alt settings,
        // SET_FEATURE handshake, and Audio Control activation
        val initSuccess = DjmDeviceInitializer.performCompleteInit(
            connection = connection,
            productId = usbDevice.productId,
            vendorInterface = vendorInterface,
            audioControlInterface = audioControlInterface,
            midiInterface = midiInterface,
            sampleRate = 44100
        )

        if (!initSuccess) {
            DebugLogger.log("AudioEngineManager",
                "Monitoring: Pioneer init sequence FAILED")
            connection.close()
            return false
        }

        // ── Step 3: Get FD and start native monitoring engine ────────
        // NOTE: No releaseInterface() call needed here. performCompleteInit()
        // no longer claims IF 0 — the native engine claims it directly via
        // ioctl(USBDEVFS_CLAIMINTERFACE).
        val fd = connection.getFileDescriptor()
        val epAddress = 0x86 // Isochronous IN endpoint

        val success = bridge.startUsbMonitoring(fd, epAddress, profile, sampleRate = 44100)
        if (success) {
            activeConnection = connection
            activeInterface = null
            activeUsbFd = fd
            activeEpAddress = epAddress
            DebugLogger.log("AudioEngineManager",
                "Direct USB monitoring started successfully (44100 Hz)")
        } else {
            DebugLogger.log("AudioEngineManager",
                "Direct USB monitoring FAILED: ${bridge.getLastErrorMessage()}")
            connection.close()
        }
        return success
    }

    /**
     * Release the USB device connection and interface.
     */
    private fun releaseUsbConnection() {
        if (activeConnection != null) {
            try {
                if (activeInterface != null) {
                    activeConnection!!.releaseInterface(activeInterface!!)
                }
            } catch (_: Exception) { }
            try {
                activeConnection!!.close()
            } catch (_: Exception) { }
        }
        activeConnection = null
        activeInterface = null
        activeUsbFd = -1
        activeEpAddress = -1
    }

    /**
     * Log all input audio devices reported by AudioManager for debugging.
     */
    private fun logAllInputDevices() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        DebugLogger.log("AudioEngineManager",
            "--- All AudioManager input devices (${inputDevices.size}) ---")
        for (d in inputDevices) {
            DebugLogger.log("AudioEngineManager",
                "  id=%d, type=%d(%s), product=%s, addr=%s, ch=%s"
                    .format(d.id, d.type, deviceTypeName(d.type),
                            d.productName?.toString() ?: "?",
                            d.address,
                            d.channelCounts.joinToString(",")))
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        else -> "OTHER($type)"
    }

    /**
     * Resolve the Oboe-compatible audio device ID for a given USB device.
     */
    private fun resolveAudioDeviceId(usbDevice: UsbDevice, profile: MixerProfile): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val productName = usbDevice.productName?.lowercase() ?: ""

        // Tier 1: Match by product name
        if (productName.isNotEmpty()) {
            for (device in inputDevices) {
                val deviceProduct = device.productName?.toString()?.lowercase() ?: ""
                if (deviceProduct.contains(productName) ||
                    productName.contains(deviceProduct)) {
                    return device.id
                }
            }
        }

        // Tier 2: Match Pioneer/DJM by manufacturer name
        for (device in inputDevices) {
            val deviceProduct = device.productName?.toString()?.lowercase() ?: ""
            if (deviceProduct.contains("pioneer") || deviceProduct.contains("djm")) {
                return device.id
            }
        }

        // Tier 3: Any USB-class input device with sufficient channels
        for (device in inputDevices) {
            if ((device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                 device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                 device.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                 device.type == AudioDeviceInfo.TYPE_LINE_DIGITAL) &&
                device.channelCounts.isNotEmpty() &&
                device.channelCounts.any { it >= profile.totalChannels }) {
                return device.id
            }
        }

        // Tier 4: Any non-builtin-mic input device
        for (device in inputDevices) {
            if (device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return device.id
            }
        }

        // Tier 5: Built-in mic (last resort)
        if (inputDevices.isNotEmpty()) return inputDevices[0].id

        return usbDevice.deviceId
    }

    /**
     * Parse a user-defined manual mixer profile from a SharedPreferences string.
     */
    private fun parseManualProfile(value: String): MixerProfile? {
        val parts = value.split(":")
        if (parts.size < 3) return null
        val channels = parts[0].toIntOrNull() ?: return null
        val left = parts[1].toIntOrNull() ?: return null
        val right = parts[2].toIntOrNull() ?: return null

        return MixerProfile(
            modelName = "Custom Profile",
            vendorIds = emptyList(),
            productIds = emptyList(),
            totalChannels = channels,
            masterLeftChannel = left,
            masterRightChannel = right,
            hasDedicatedRecBus = false,
            midiInterfaceIndex = -1,
            notes = "User-defined"
        )
    }

    /**
     * Open an audio stream in monitoring mode for live VU metering.
     * Automatically selects between Oboe, direct USB, and DJM bulk.
     */
    fun startMonitoring(usbDevice: UsbDevice): Boolean {
        val profile = resolveProfileForDevice(usbDevice)
        currentProfile = profile
        activeUsbDevice = usbDevice

        if (needsDirectUsb(usbDevice)) {
            if (profile.usesBulkTransfer) {
                DebugLogger.log("AudioEngineManager",
                    "Using DJM bulk monitoring for ${profile.modelName}")
                return startDjmBulkMonitoringInternal(usbDevice, profile)
            }
            DebugLogger.log("AudioEngineManager",
                "Using direct USB monitoring for ${profile.modelName}")
            return startUsbMonitoringInternal(usbDevice, profile)
        }

        val audioDeviceId = resolveAudioDeviceId(usbDevice, profile)
        val success = bridge.startMonitoring(audioDeviceId, profile)
        if (!success) {
            val nativeError = bridge.getLastErrorMessage()
            DebugLogger.log("AudioEngineManager",
                "MONITORING FAILED — native error: $nativeError")
        }
        return success
    }

    /**
     * Stop audio capture AND/OR monitoring, releasing native engine resources
     * and USB connections. Safe to call regardless of current state.
     */
    fun stopCapture() {
        bridge.stopCapture()
        releaseUsbConnection()
        currentProfile = null
        activeUsbDevice = null
        DebugLogger.log("AudioEngineManager", "Capture/Monitoring stopped")
    }

    fun isRecording(): Boolean = bridge.isRecording()
    fun isMonitoring(): Boolean = bridge.isMonitoring()
    fun isDjmBulkRecording(): Boolean = bridge.isDjmBulkRecording()
    fun isDjmBulkMonitoring(): Boolean = bridge.isDjmBulkMonitoring()

    /**
     * Returns the current audio capture mode as a human-readable string.
     */
    fun getCaptureMode(): String {
        if (bridge.isDjmBulkRecording() || bridge.isDjmBulkMonitoring()) return "DJM Bulk"
        if (bridge.isUsbRecording() || bridge.isUsbMonitoring()) return "USB Direct"
        if (bridge.isRecording() || bridge.isMonitoring()) return "Oboe/AAudio"
        return "Idle"
    }

    fun getLastErrorMessage(): String = bridge.getLastErrorMessage()
    fun getCurrentProfile(): MixerProfile? = currentProfile
    fun getActiveUsbDevice(): UsbDevice? = activeUsbDevice
    fun getLevelData(): FloatArray = bridge.getLevelData()

    /**
     * Returns the number of audio buffer overruns (dropped frames) detected
     * by the currently active native capture engine.
     *
     * - USB isochronous mode: queries [AudioEngineBridge.getUsbOverrunCount]
     * - DJM bulk mode: queries [AudioEngineBridge.getDjmBulkOverrunCount]
     * - Oboe/AAudio mode: returns 0 (overrun tracking not implemented for this path)
     */
    fun getOverrunCount(): Int {
        return when {
            bridge.isDjmBulkRecording() || bridge.isDjmBulkMonitoring() ->
                bridge.getDjmBulkOverrunCount()
            bridge.isUsbRecording() || bridge.isUsbMonitoring() ->
                bridge.getUsbOverrunCount()
            else -> 0
        }
    }
}
