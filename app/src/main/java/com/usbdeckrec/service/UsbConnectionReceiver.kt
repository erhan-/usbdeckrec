package com.usbdeckrec.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Broadcast receiver for USB device attach/detach events.
 *
 * On [UsbManager.ACTION_USB_DEVICE_ATTACHED]: Extracts the [UsbDevice] from
 * the intent and sends a local broadcast to notify the active activity.
 *
 * On [UsbManager.ACTION_USB_DEVICE_DETACHED]: Only sends a stop intent to
 * the recording service if the detached device matches the currently active
 * recording device (identified by vendor ID + product ID + device name).
 * This prevents a USB keyboard or other unrelated USB device being unplugged
 * from falsely stopping an active recording.
 */
class UsbConnectionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DEVICE_ATTACHED = "com.usbdeckrec.action.DEVICE_ATTACHED"
        const val ACTION_DEVICE_DETACHED = "com.usbdeckrec.action.DEVICE_DETACHED"
        const val EXTRA_DEVICE = "extra_device"

        /**
         * Identifier of the currently active recording device, set when
         * recording starts and cleared when it stops. Used to filter
         * detach events so only the recording device triggers a stop.
         *
         * Format: "VID:PID:DeviceName" (e.g., "0x08BB:0x0158:DJM-900NXS")
         */
        @Volatile
        var activeDeviceIdentifier: String? = null
    }

    /**
     * Build a unique device identifier string from a [UsbDevice].
     */
    private fun getDeviceIdentifier(device: UsbDevice): String {
        return "%04X:%04X:%s".format(device.vendorId, device.productId, device.deviceName ?: "")
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) {
                    val localIntent = Intent(ACTION_DEVICE_ATTACHED).apply {
                        putExtra(EXTRA_DEVICE, device)
                    }
                    context.sendBroadcast(localIntent)
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) {
                    val localIntent = Intent(ACTION_DEVICE_DETACHED).apply {
                        putExtra(EXTRA_DEVICE, device)
                    }
                    context.sendBroadcast(localIntent)

                    // Only stop recording if the detached device matches the active recording device
                    val detachedId = getDeviceIdentifier(device)
                    val activeId = activeDeviceIdentifier
                    if (activeId != null && detachedId == activeId) {
                        android.util.Log.d("UsbConnectionReceiver",
                            "Active recording device detached: $detachedId — stopping recording")
                        val stopIntent = Intent(context, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } else {
                        android.util.Log.d("UsbConnectionReceiver",
                            "Non-active device detached: $detachedId (active=$activeId) — ignoring")
                    }
                }
            }
        }
    }
}
