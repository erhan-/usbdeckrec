package com.usbdeckrec.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.usbdeckrec.audio.MixerProfileDatabase
import com.usbdeckrec.util.DebugLogger

/**
 * Manages USB audio device discovery and selection.
 *
 * Uses [UsbManager] to enumerate connected USB devices and identify
 * supported audio devices by matching against [MixerProfileDatabase].
 */
class UsbDeviceManager(private val context: Context) {

    private var selectedDevice: UsbDevice? = null

    /**
     * Get the system [UsbManager] service.
     */
    fun getUsbManager(): UsbManager {
        return context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /**
     * Return a list of connected USB devices that are potential audio devices.
     * Checks all connected devices via [UsbManager.deviceList] and filters
     * to those with a valid audio interface class.
     *
     * Debug logs the VID/PID of every connected USB device to help diagnose
     * detection issues.
     */
    fun getConnectedDevices(): List<UsbDevice> {
        val usbManager = getUsbManager()
        val allDevices = usbManager.deviceList

        // Debug: log all connected USB devices
        DebugLogger.log("UsbDeviceManager", "Total USB devices connected: ${allDevices.size}")
        for ((name, device) in allDevices) {
            val supported = if (isDeviceSupported(device)) " [SUPPORTED MIXER]" else ""
            DebugLogger.log("UsbDeviceManager",
                "  Device: name=%s, VID=0x%04X, PID=0x%04X, class=%d, sub=%d, ifaces=%d%s"
                    .format(name, device.vendorId, device.productId,
                            device.deviceClass, device.deviceSubclass,
                            device.interfaceCount, supported))
            // Log each interface
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                DebugLogger.log("UsbDeviceManager",
                    "    Interface[%d]: class=0x%02X, sub=0x%02X, protocol=0x%02X, endpoints=%d"
                        .format(i, iface.interfaceClass, iface.interfaceSubclass,
                                iface.interfaceProtocol, iface.endpointCount))
            }
        }

        val audioDevices = allDevices.values.filter { device ->
            isAudioDevice(device)
        }

        DebugLogger.log("UsbDeviceManager", "Audio-capable devices found: ${audioDevices.size}")
        return audioDevices
    }

    /**
     * Return the currently selected device, or null if none selected.
     */
    fun getSelectedDevice(): UsbDevice? = selectedDevice

    /**
     * Set the currently selected device.
     */
    fun setSelectedDevice(device: UsbDevice) {
        selectedDevice = device
    }

    /**
     * Clear the selected device.
     */
    fun clearSelectedDevice() {
        selectedDevice = null
    }

    /**
     * Check if the given device matches any known mixer profile in the database.
     */
    fun isDeviceSupported(device: UsbDevice): Boolean {
        return MixerProfileDatabase.getProfile(device.vendorId, device.productId) != null
    }

    /**
     * Determine if a USB device is an audio device by inspecting its interface descriptors.
     *
     * Accepted interface classes:
     * - 0x01 (AUDIO) — Standard USB Audio class
     * - 0xFF (VENDOR_SPECIFIC) — Some mixers use this for their audio interface
     *
     * Also checks if the device matches any known Pioneer mixer profile as a fallback.
     */
    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0x01) {
                DebugLogger.log("UsbDeviceManager",
                    "isAudioDevice=true: found Audio class (0x01) interface[%d]".format(i))
                return true
            }
            // Also accept vendor-specific interfaces — some mixers expose
            // audio through a vendor-specific interface
            if (iface.interfaceClass == 0xFF) {
                DebugLogger.log("UsbDeviceManager",
                    "isAudioDevice=true: found Vendor-Specific class (0xFF) interface[%d]".format(i))
                return true
            }
        }

        // Also check if the device matches any known mixer profile
        val isProfileMatch = isDeviceSupported(device)
        if (isProfileMatch) {
            DebugLogger.log("UsbDeviceManager",
                "isAudioDevice=true: matches known mixer profile (VID=0x%04X, PID=0x%04X)"
                    .format(device.vendorId, device.productId))
        }
        return isProfileMatch
    }
}
