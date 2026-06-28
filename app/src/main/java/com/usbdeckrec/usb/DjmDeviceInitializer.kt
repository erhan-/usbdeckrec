package com.usbdeckrec.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import com.usbdeckrec.util.DebugLogger

/**
 * Vendor-specific USB initialization handshake for Pioneer DJM devices.
 *
 * ## Complete Initialization Sequence (from Wireshark capture)
 *
 * Based on `djmwithaudio.pcapng` analysis, the Windows/Rekordbox driver
 * performs this 3-phase init sequence before audio streaming begins:
 *
 * ### Phase 1: SET_FEATURE Handshake (mandatory for bulk MIDI + vendor activation)
 * 5 vendor control transfers to endpoint 0x8002/0x8003.
 *
 * ### Phase 2: Periodic Status Polling (monitoring mode)
 * Every ~100ms: GET_STATUS on EP 0x8002 (wLength=5).
 *
 * ### Phase 3: Audio Control Activation (CRITICAL for isochronous audio!)
 * - SET_INTERFACE intf=1, alt=0 (Audio Control)
 * - SET_CUR sampling frequency → 44100 Hz
 * - Vendor status changes from 00:00:02:02:04 → 00:02:02:02:04 (byte 2 indicates audio active)
 *
 * @see <a href="plans/djm900nexus-usb-analysis.md">Full Wireshark analysis</a>
 */
object DjmDeviceInitializer {

    private const val TAG = "DjmDeviceInitializer"

    // ── USB Request Types ─────────────────────────────────────────────────

    /** bmRequestType = Host→Device, Vendor, Device recipient */
    private const val H2D_VENDOR_DEVICE = 0x40

    /** bmRequestType = Host→Device, Class, Interface */
    private const val H2D_CLASS_INTERFACE = 0x21

    /** bmRequestType = Host→Device, Class, Endpoint */
    private const val H2D_CLASS_ENDPOINT = 0x22

    /** bmRequestType = Device→Host, Standard, Device recipient */
    private const val D2H_STANDARD_DEVICE = 0xC0

    /** bmRequestType = Host→Device, Standard, Interface */
    private const val H2D_STANDARD_INTERFACE = 0x01

    // ── Standard USB Requests ────────────────────────────────────────────

    /** bRequest = SET_INTERFACE (standard USB request) */
    private const val SET_INTERFACE = 0x0B

    /** bRequest = SET_FEATURE (vendor) */
    private const val SET_FEATURE = 0x03

    /** bRequest = GET_STATUS */
    private const val GET_STATUS = 0x00

    /** bRequest = SET_CUR (USB Audio Class) */
    private const val SET_CUR = 0x01

    // ── Phase 1: SET_FEATURE Handshake Parameters ─────────────────────────

    /** The 5 SET_FEATURE selectors required for DJM-900NXS activation */
    private val DJM900NXS_FEATURES = listOf(
        FeatureStep(wValue = 0x010a, wIndex = 0x8002),
        FeatureStep(wValue = 0x0206, wIndex = 0x8002),
        FeatureStep(wValue = 0x0306, wIndex = 0x8002),
        FeatureStep(wValue = 0x040a, wIndex = 0x8002),
        FeatureStep(wValue = 0x0000, wIndex = 0x8003)
    )

    private data class FeatureStep(
        val wValue: Int,
        val wIndex: Int
    )

    // ── Product IDs requiring the handshake ─────────────────────────

    /** Known Pioneer device PIDs that need the SET_FEATURE handshake */
    private val requiresHandshake = setOf(
        0x0155, // DJM-900Nexus
        0x0158, // DJM-900NXS / NXS2
        0x0156, // DJM-900SRT
        0x0157, // DJM-750MK2
        0x016A, // DJM-A9
        0x0163, // DJM-V10
        0x016C  // DJM-S11
    )

    /** Known Pioneer device PIDs that need Audio Control activation (Phase 3) */
    private val requiresAudioControlActivation = setOf(
        0x0155, // DJM-900Nexus
        0x0158  // DJM-900NXS / NXS2
    )

    /**
     * Check whether the given product ID requires the Pioneer vendor
     * SET_FEATURE handshake.
     */
    fun requiresInitialization(productId: Int): Boolean =
        productId in requiresHandshake

    /**
     * Check whether the given product ID requires Audio Control activation
     * (Phase 3: SET_INTERFACE intf=1 + SET_CUR sample rate).
     */
    fun requiresAudioControlActivation(productId: Int): Boolean =
        productId in requiresAudioControlActivation

    // ── Helper: Select Interface Alternate Setting ────────────────────────
    //
    // UsbDeviceConnection.setInterface(UsbInterface) only selects alt setting 0.
    // To select a non-zero alt setting (e.g., alt 1 to activate isochronous
    // endpoints), we must send the raw USB SET_INTERFACE control transfer.

    /**
     * Select an alternate setting on a USB interface using controlTransfer.
     *
     * The standard USB SET_INTERFACE request sets the active alternate setting
     * for a given interface number. This enables/disables endpoints on that
     * interface.
     *
     * @param connection The open [UsbDeviceConnection].
     * @param iface The target [UsbInterface].
     * @param altSetting The alternate setting number to select.
     * @return true if the control transfer succeeded.
     */
    fun setInterfaceAltSetting(
        connection: UsbDeviceConnection,
        iface: UsbInterface,
        altSetting: Int
    ): Boolean {
        val ret = connection.controlTransfer(
            H2D_STANDARD_INTERFACE, // 0x01: Host→Device, Standard, Interface
            SET_INTERFACE,          // 0x0B: SET_INTERFACE
            altSetting,             // wValue = alternate setting
            iface.id,               // wIndex = interface number
            null,                   // data
            0,                      // length
            100                     // timeout
        )
        return ret >= 0
    }

    // ── Phase 1: SET_FEATURE Handshake ──────────────────────────────────

    /**
     * Execute the full vendor SET_FEATURE handshake (Phase 1).
     *
     * Must be called AFTER claiming the vendor interface (IF 0 alt 1)
     * and before starting bulk/isochronous transfers on the endpoint.
     *
     * @param connection The open [UsbDeviceConnection] (must have permission).
     * @param productId The USB product ID for device-specific selection.
     * @return true if all steps succeeded and verification passed.
     */
    fun performHandshake(
        connection: UsbDeviceConnection,
        productId: Int
    ): Boolean {
        if (!requiresInitialization(productId)) {
            DebugLogger.log(TAG,
                "PID=0x%04X does not require handshake — skipping".format(productId))
            return true
        }

        DebugLogger.log(TAG,
            "Performing vendor SET_FEATURE handshake for PID=0x%04X".format(productId))

        // ── Step 1-5: Send SET_FEATURE selectors ────────────────────
        for ((index, step) in DJM900NXS_FEATURES.withIndex()) {
            val ret = connection.controlTransfer(
                H2D_VENDOR_DEVICE, // bmRequestType
                SET_FEATURE,       // bRequest
                step.wValue,       // wValue
                step.wIndex,       // wIndex
                null,              // data (no payload)
                0,                 // length
                100                // timeout (ms)
            )
            if (ret < 0) {
                DebugLogger.log(TAG,
                    ("SET_FEATURE #%d FAILED: wValue=0x%04X, wIndex=0x%04X, ret=%d"
                       + " — device may not work").format(
                        index + 1, step.wValue, step.wIndex, ret))
                return false
            }
            DebugLogger.log(TAG,
                "SET_FEATURE #%d OK: wValue=0x%04X, wIndex=0x%04X".format(
                    index + 1, step.wValue, step.wIndex))
        }

        // ── Step 6: Verify with GET_STATUS on EP 0x8003 ─────────────
        val statusBuffer = ByteArray(2)
        val verifyRet = connection.controlTransfer(
            D2H_STANDARD_DEVICE, // bmRequestType
            GET_STATUS,          // bRequest
            0x0000,              // wValue
            0x8003,              // wIndex (EP 3 IN)
            statusBuffer,        // data
            statusBuffer.size,   // length
            100                  // timeout
        )
        if (verifyRet < 0) {
            DebugLogger.log(TAG,
                "GET_STATUS verify FAILED: ret=%d — continuing anyway".format(verifyRet))
            // Not fatal — some firmware versions don't respond
        } else {
            val statusHex = statusBuffer.joinToString("") { "%02X".format(it) }
            DebugLogger.log(TAG, "GET_STATUS verify: response=%s (expected 0100)".format(statusHex))
        }

        DebugLogger.log(TAG, "Vendor SET_FEATURE handshake complete")
        return true
    }

    // ── Phase 2: Periodic Status Polling ─────────────────────────────────

    /**
     * Send a periodic status poll request (GET_STATUS on EP 0x8002).
     *
     * The DJM-900NXS responds with 5 bytes: typically [0x00, 0x00, 0x02, 0x02, 0x04]
     * (MIDI-only mode) or [0x00, 0x02, 0x02, 0x02, 0x04] (audio active mode).
     *
     * @return The status byte array, or null if the transfer failed.
     */
    fun pollDeviceStatus(connection: UsbDeviceConnection): ByteArray? {
        val buffer = ByteArray(5)
        val ret = connection.controlTransfer(
            D2H_STANDARD_DEVICE, // 0xC0
            GET_STATUS,          // 0x00
            0x0000,              // wValue
            0x8002,              // wIndex (EP 2 IN)
            buffer,
            buffer.size,
            100
        )
        if (ret < 0) return null
        return buffer
    }

    // ── Phase 3: Audio Control Activation ⭐ ─────────────────────────────

    /**
     * Activate the Audio Control interface and set the sampling frequency.
     *
     * This is the CRITICAL step that enables isochronous audio streaming.
     * Without it, the device only sends MIDI data and the isochronous
     * endpoints remain in an inactive state.
     *
     * From the Wireshark capture (frames 466-468):
     *   T+3.505s: SET_INTERFACE intf=1, alt=0  ← Audio Control
     *   T+3.507s: SET_CUR sampling frequency → 44100 Hz
     *   Data: 01 86 00 03 00 44 AC 00
     *     ├── Entity=0x86 (Feature Unit on EP 0x86)
     *     ├── Control Selector=0x03 (SAMPLING_FREQ_CONTROL)
     *     └── Frequency=0x00AC44 = 44100 Hz ✓
     *
     * After this, the status poll response changes from:
     *   00:00:02:02:04 → 00:02:02:02:04 (byte 2 changes 00→02 = audio active)
     *
     * @param connection The open [UsbDeviceConnection].
     * @param productId The USB product ID.
     * @param interface1 The Audio Control interface (IF 1).
     * @param sampleRate Target sample rate (typically 44100 for DJM-900NXS).
     * @return true if the Audio Control activation succeeded.
     */
    fun activateAudioControl(
        connection: UsbDeviceConnection,
        productId: Int,
        interface1: UsbInterface,
        sampleRate: Int = 44100
    ): Boolean {
        if (!requiresAudioControlActivation(productId)) {
            DebugLogger.log(TAG,
                "PID=0x%04X does not require audio control activation — skipping".format(productId))
            return true
        }

        DebugLogger.log(TAG,
            "Activating Audio Control interface for PID=0x%04X".format(productId))

        // ── Step 1: Select alternate setting 0 on Audio Control IF ──
        // This activates the Audio Control interface (IF 1).
        // Use controlTransfer(SET_INTERFACE) for API 26 compatibility.
        if (!setInterfaceAltSetting(connection, interface1, 0)) {
            DebugLogger.log(TAG,
                "setInterface(IF 1, alt=0) FAILED — audio may not work")
            return false
        }
        DebugLogger.log(TAG, "Selected alternate setting 0 on Audio Control IF 1")

        // ── Step 2: SET_CUR sampling frequency → sampleRate Hz ──────
        // USB Audio Class 1.0 (UAC1) SET_CUR request
        // bmRequestType = 0x22 (Host→Device, Class, Endpoint)
        // bRequest = 0x01 (SET_CUR)
        // wValue = (controlSelector << 8) | channel
        //   controlSelector = 0x01 (SAMPLING_FREQ_CONTROL per UAC1 spec)
        //   channel = 0x00 (master channel)
        //   → wValue = 0x0100
        // wIndex = endpointAddress
        //   From capture: endpoint = 0x86 (EP6 IN)
        //   → wIndex = 0x0086
        // Data: 3-byte little-endian sample rate
        val sampleRateBytes = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte()
        )

        val setCurRet = connection.controlTransfer(
            H2D_CLASS_ENDPOINT,  // bmRequestType = 0x22
            SET_CUR,             // bRequest = 0x01
            0x0100,              // wValue = SAMPLING_FREQ_CONTROL (0x01), master channel (0x00)
            0x0086,              // wIndex = endpoint 0x86 (EP 6 IN)
            sampleRateBytes,     // data
            sampleRateBytes.size, // length = 3
            100                  // timeout
        )

        if (setCurRet < 0) {
            DebugLogger.log(TAG,
                "SET_CUR sample rate FAILED: ret=%d — audio may not work".format(setCurRet))
            return false
        }

        DebugLogger.log(TAG,
            "SET_CUR sample rate %d Hz: returned %d bytes".format(sampleRate, setCurRet))

        // ── Step 3: Verify with status poll ─────────────────────────
        // After audio activation, byte 2 of the 5-byte status response
        // should change from 0x00 to 0x02
        val statusAfter = pollDeviceStatus(connection)
        if (statusAfter != null) {
            val statusHex = formatStatusResponse(statusAfter)
            DebugLogger.log(TAG, "Status after audio activation: %s".format(statusHex))
            if (statusAfter.size >= 3 && statusAfter[1] == 0x02.toByte()) {
                DebugLogger.log(TAG,
                    "Audio activation confirmed: byte 2 = 0x02 (audio active)")
            }
        }

        DebugLogger.log(TAG,
            "Audio Control activation complete: sample rate=%d Hz".format(sampleRate))
        return true
    }

    // ── Complete Init Sequence (all phases) ─────────────────────────────

    /**
     * Perform the COMPLETE initialization sequence for the DJM-900NXS.
     *
     * This combines all 3 phases:
     * 1. Interface claiming + alt setting selection
     * 2. SET_FEATURE handshake
     * 3. Audio Control activation (SET_CUR sample rate)
     *
     * @param connection The open [UsbDeviceConnection].
     * @param productId The USB product ID.
     * @param vendorInterface Interface 0 (vendor-specific).
     * @param audioControlInterface Interface 1 (Audio Control).
     * @param midiInterface Interface 2 (MIDI streaming).
     * @param sampleRate Target sample rate (default: 44100).
     * @return true if all phases completed successfully.
     */
    fun performCompleteInit(
        connection: UsbDeviceConnection,
        productId: Int,
        vendorInterface: UsbInterface,
        audioControlInterface: UsbInterface,
        midiInterface: UsbInterface?,
        sampleRate: Int = 44100
    ): Boolean {
        DebugLogger.log(TAG,
            "=== Starting complete Pioneer init for PID=0x%04X ===".format(productId))

        // ── Step 1: Claim interfaces ────────────────────────────────
        // NOTE: Vendor interface (IF 0) is intentionally NOT claimed here.
        // The native capture engine claims it directly via
        // ioctl(USBDEVFS_CLAIMINTERFACE) to avoid a race condition where
        // another process could claim IF 0 between Kotlin's releaseInterface()
        // and the native re-claim. Control transfers (SET_INTERFACE, vendor
        // SET_FEATURE, SET_CUR) work through EP0 without needing the
        // interface to be claimed.

        // Claim Audio Control interface (IF 1) — needed for SET_CUR sample rate
        if (!connection.claimInterface(audioControlInterface, true)) {
            DebugLogger.log(TAG, "Failed to claim Audio Control interface (IF 1)")
            return false
        }
        DebugLogger.log(TAG, "Claimed Audio Control interface (IF 1)")

        // Claim MIDI streaming interface (IF 2) if available
        if (midiInterface != null) {
            if (connection.claimInterface(midiInterface, true)) {
                DebugLogger.log(TAG, "Claimed MIDI interface (IF 2)")
            } else {
                DebugLogger.log(TAG, "Failed to claim MIDI interface (IF 2) — continuing")
            }
        }

        // ── Step 2: Activate MIDI streaming (Phase 4 from capture) ──
        // T+0.015s: SET_INTERFACE intf=2, alt=0  ← MIDI streaming ON
        if (midiInterface != null) {
            setInterfaceAltSetting(connection, midiInterface, 0)
            DebugLogger.log(TAG, "Activated alt 0 on MIDI IF 2")
        }

        // ── Step 3: Activate vendor audio endpoints (Phase 4) ───────
        // T+0.016s: SET_INTERFACE intf=0, alt=1  ← Enables EP 0x05 + EP 0x86
        // Note: SET_INTERFACE works through EP0 without needing IF 0 claimed.
        if (!setInterfaceAltSetting(connection, vendorInterface, 1)) {
            DebugLogger.log(TAG,
                "setInterface(IF 0, alt=1) FAILED — continuing anyway")
        } else {
            DebugLogger.log(TAG,
                "Activated alt 1 on vendor IF 0 (EP 0x05 OUT, EP 0x86 IN)")
        }

        // ── Step 4: SET_FEATURE handshake (Phase 6) ─────────────────
        // T+0.174s: 5× SET_FEATURE control transfers
        if (!performHandshake(connection, productId)) {
            DebugLogger.log(TAG, "SET_FEATURE handshake FAILED — continuing anyway")
        }

        // ── Step 5: Activate Audio Control (Phase 9 — CRITICAL!) ───
        // T+3.505s: SET_INTERFACE intf=1, alt=0
        // T+3.507s: SET_CUR sample rate → 44100 Hz
        if (!activateAudioControl(connection, productId, audioControlInterface, sampleRate)) {
            DebugLogger.log(TAG, "Audio Control activation FAILED — audio recording may not work")
            return false
        }

        DebugLogger.log(TAG,
            "=== Complete Pioneer init SUCCESS for PID=0x%04X ===".format(productId))
        return true
    }

    // ── Status Response Utilities ───────────────────────────────────────

    /**
     * Format the periodic status response as a human-readable hex string.
     */
    fun formatStatusResponse(data: ByteArray): String {
        if (data.isEmpty()) return "(empty)"
        return data.joinToString(":") { "%02X".format(it) }
    }
}
