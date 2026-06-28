package com.usbdeckrec.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Manages USB device permission requests for host-mode USB audio devices.
 *
 * Implements the standard Android USB permission flow:
 * 1. Check if permission is already granted via [UsbManager.hasPermission]
 * 2. If not granted, create a [PendingIntent] and register a [BroadcastReceiver]
 * 3. Call [UsbManager.requestPermission] to prompt the user
 * 4. On response, invoke the appropriate callback and unregister the receiver
 */
class UsbPermissionHelper(private val context: Context) {

    companion object {
        const val ACTION_USB_DEVICE_PERMISSION = "com.usbdeckrec.action.USB_DEVICE_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Request permission to communicate with the given USB device.
     *
     * @param device The USB device to request permission for
     * @param onGranted Callback invoked when the user grants permission
     * @param onDenied Callback invoked when the user denies permission
     */
    fun requestDevicePermission(
        device: UsbDevice,
        onGranted: (UsbDevice) -> Unit,
        onDenied: ((UsbDevice) -> Unit)? = null
    ) {
        // Short-circuit if permission is already granted
        if (usbManager.hasPermission(device)) {
            onGranted(device)
            return
        }

        val intent = Intent(ACTION_USB_DEVICE_PERMISSION)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val filter = IntentFilter(ACTION_USB_DEVICE_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    onGranted(device)
                } else {
                    onDenied?.invoke(device)
                }
                context.unregisterReceiver(this)
            }
        }

        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Check if the app already has permission for the given device.
     */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)
}
