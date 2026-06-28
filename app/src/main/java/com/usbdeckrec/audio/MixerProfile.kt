package com.usbdeckrec.audio

data class MixerProfile(
    val modelName: String,
    val vendorIds: List<Int>,
    val productIds: List<Int>,
    val totalChannels: Int,
    val masterLeftChannel: Int,
    val masterRightChannel: Int,
    val hasDedicatedRecBus: Boolean,
    val midiInterfaceIndex: Int,
    val notes: String = "",
    /** True if the device uses BULK transfers (like DJM-900NXS MIDI),
     *  false if it uses isochronous audio streaming. */
    val usesBulkTransfer: Boolean = false,
    /** USB bulk IN endpoint address (e.g., 0x87 for DJM-900NXS).
     *  Only valid when [usesBulkTransfer] is true. */
    val bulkInEpAddress: Int = 0,
    /** USB bulk OUT endpoint address (e.g., 0x07 for DJM-900NXS).
     *  Only valid when [usesBulkTransfer] is true. */
    val bulkOutEpAddress: Int = 0,
    /** True if this device requires the Pioneer vendor SET_FEATURE
     *  handshake during initialization. */
    val requiresVendorHandshake: Boolean = false
)

object MixerProfileDatabase {

    private val profilesByPid = mapOf(
        0x0155 to MixerProfile(
            modelName = "DJM-900Nexus",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x0155),
            totalChannels = 4,
            masterLeftChannel = 0,
            masterRightChannel = 1,
            hasDedicatedRecBus = false,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true,
            notes = "Vendor handshake required — uses isochronous audio"
        ),
        0x0158 to MixerProfile(
            modelName = "DJM-900NXS",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x0158),
            // DJM-900NXS has both isochronous audio AND bulk MIDI:
            // - EP 0x05 OUT: isochronous, audio playback to mixer
            // - EP 0x86 IN:  isochronous, audio recording from mixer (24-bit PCM, 4ch, 44100Hz)
            // - EP 0x87 IN:  bulk, MIDI data (timing clock 0xF8, CC, SysEx)
            // Audio endpoints are on Interface 0 alt 1 (vendor-specific class 0xFF).
            // MIDI endpoint is on Interface 2 alt 0 (MIDI streaming class).
            // Requires full Pioneer init: SET_FEATURE handshake + Audio Control SET_CUR sample rate.
            // Also covers DJM-900NXS2 (10ch) with same PID.
            totalChannels = 8,
            masterLeftChannel = 6,
            masterRightChannel = 7,
            hasDedicatedRecBus = true,
            midiInterfaceIndex = 2,
            usesBulkTransfer = false,  // Audio is isochronous, not bulk
            bulkInEpAddress = 0x87,    // MIDI bulk IN (for MIDI data, not audio)
            bulkOutEpAddress = 0x07,   // MIDI bulk OUT
            requiresVendorHandshake = true,
            notes = "Isochronous audio EP 0x86 IN (8ch 24-bit 44100Hz) + bulk MIDI EP 0x87 IN. REC OUT on USB 7/8 (default). Requires SET_FEATURE handshake + Audio Control init."
        ),
        0x0156 to MixerProfile(
            modelName = "DJM-900SRT",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x0156),
            totalChannels = 2,
            masterLeftChannel = 0,
            masterRightChannel = 1,
            hasDedicatedRecBus = false,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true,
            notes = "Serato mixer — limited MIDI"
        ),
        0x0157 to MixerProfile(
            modelName = "DJM-750MK2",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x0157),
            totalChannels = 4,
            masterLeftChannel = 0,
            masterRightChannel = 1,
            hasDedicatedRecBus = false,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true
        ),
        0x016A to MixerProfile(
            modelName = "DJM-A9",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x016A),
            totalChannels = 10,
            masterLeftChannel = 8,
            masterRightChannel = 9,
            hasDedicatedRecBus = true,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true
        ),
        0x0163 to MixerProfile(
            modelName = "DJM-V10",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x0163),
            totalChannels = 10,
            masterLeftChannel = 8,
            masterRightChannel = 9,
            hasDedicatedRecBus = true,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true
        ),
        0x016C to MixerProfile(
            modelName = "DJM-S11",
            vendorIds = listOf(0x04B4, 0x2B73, 0x08E4),
            productIds = listOf(0x016C),
            totalChannels = 10,
            masterLeftChannel = 8,
            masterRightChannel = 9,
            hasDedicatedRecBus = true,
            midiInterfaceIndex = 1,
            usesBulkTransfer = false,
            requiresVendorHandshake = true,
            notes = "Serato DJ mixer with dual USB ports"
        )
    )

    fun getProfile(vendorId: Int, productId: Int): MixerProfile? {
        return profilesByPid[productId]?.takeIf { vendorId in it.vendorIds }
    }

    fun getGenericProfile(): MixerProfile = MixerProfile(
        modelName = "Generic USB Audio Device",
        vendorIds = emptyList(),
        productIds = emptyList(),
        totalChannels = 2,
        masterLeftChannel = 0,
        masterRightChannel = 1,
        hasDedicatedRecBus = false,
        midiInterfaceIndex = -1,
        notes = "Auto-detected — verify channel mapping"
    )

    fun getAllProfiles(): Collection<MixerProfile> = profilesByPid.values

    /**
     * Check whether a given product ID corresponds to a device that uses
     * bulk transfers (MIDI-over-bulk) instead of isochronous audio.
     */
    fun isBulkTransferDevice(productId: Int): Boolean =
        profilesByPid[productId]?.usesBulkTransfer == true

    /**
     * Check whether a given product ID requires the Pioneer vendor
     * SET_FEATURE handshake for initialization.
     */
    fun requiresVendorHandshake(productId: Int): Boolean =
        profilesByPid[productId]?.requiresVendorHandshake == true
}
