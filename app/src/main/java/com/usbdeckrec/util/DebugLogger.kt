package com.usbdeckrec.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple in-memory ring-buffer logger for on-device debugging.
 *
 * Stores the last [MAX_ENTRIES] log messages and exposes them as a
 * [StateFlow] that the UI can collect for an on-screen log viewer.
 *
 * All messages are also forwarded to Android's [android.util.Log]
 * via [android.util.Log.d]/[android.util.Log.e].
 */
object DebugLogger {

    private const val MAX_ENTRIES = 200

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * Append a debug log entry.
     *
     * @param tag  A short tag identifying the source component
     * @param message  The log message
     */
    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] [$tag] $message"

        // Also send to adb logcat
        android.util.Log.d(tag, message)

        // Append to ring buffer
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_ENTRIES) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    /**
     * Clear all stored log entries.
     */
    fun clear() {
        _logs.value = emptyList()
        android.util.Log.d("DebugLogger", "Logs cleared")
    }
}
