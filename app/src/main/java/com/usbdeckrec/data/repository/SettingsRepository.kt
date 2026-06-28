package com.usbdeckrec.data.repository

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Repository that wraps [SharedPreferences] for persistent settings storage.
 *
 * All settings are stored in the default shared preferences file and cached
 * in-memory for fast reads. Write operations commit synchronously to ensure
 * settings survive process death.
 */
class SettingsRepository(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val KEY_RECORDING_FORMAT = "recording_format"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_BIT_DEPTH = "bit_depth"
        private const val KEY_MIXER_PROFILE_OVERRIDE = "manual_mixer_profile"
        private const val KEY_SHOW_DEBUG_LOG = "show_debug_log"

        private const val DEFAULT_FORMAT = "FLAC"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_BIT_DEPTH = 24
        private const val DEFAULT_SHOW_DEBUG_LOG = false
    }

    // ── Recording Format ────────────────────────────────────────────

    fun getRecordingFormat(): String {
        return prefs.getString(KEY_RECORDING_FORMAT, DEFAULT_FORMAT) ?: DEFAULT_FORMAT
    }

    fun saveRecordingFormat(format: String) {
        prefs.edit().putString(KEY_RECORDING_FORMAT, format).apply()
    }

    // ── Sample Rate ─────────────────────────────────────────────────

    fun getSampleRate(): Int {
        return prefs.getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
    }

    fun saveSampleRate(rate: Int) {
        prefs.edit().putInt(KEY_SAMPLE_RATE, rate).apply()
    }

    // ── Bit Depth ───────────────────────────────────────────────────

    fun getBitDepth(): Int {
        return prefs.getInt(KEY_BIT_DEPTH, DEFAULT_BIT_DEPTH)
    }

    fun saveBitDepth(depth: Int) {
        prefs.edit().putInt(KEY_BIT_DEPTH, depth).apply()
    }

    // ── Mixer Profile Override ──────────────────────────────────────

    /**
     * Returns the manual mixer profile override string, or null if none set.
     *
     * Format: "channelCount:leftCh:rightCh"
     * Example: "4:0:1" = 4 channels, master left = ch 1 (index 0),
     *          master right = ch 2 (index 1)
     */
    fun getMixerProfileOverride(): String? {
        return prefs.getString(KEY_MIXER_PROFILE_OVERRIDE, null)
    }

    /**
     * Save a manual mixer profile override.
     *
     * @param channels Total channel count
     * @param masterLeftChannel Zero-based index for master left
     * @param masterRightChannel Zero-based index for master right
     */
    fun saveMixerProfileOverride(channels: Int, masterLeftChannel: Int, masterRightChannel: Int) {
        val value = "$channels:$masterLeftChannel:$masterRightChannel"
        prefs.edit().putString(KEY_MIXER_PROFILE_OVERRIDE, value).apply()
    }

    /**
     * Clear the mixer profile override (revert to auto-detect).
     */
    fun clearMixerProfileOverride() {
        prefs.edit().remove(KEY_MIXER_PROFILE_OVERRIDE).apply()
    }

    // ── Debug Log Visibility ──────────────────────────────────────────

    fun isDebugLogVisible(): Boolean {
        return prefs.getBoolean(KEY_SHOW_DEBUG_LOG, DEFAULT_SHOW_DEBUG_LOG)
    }

    fun saveDebugLogVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DEBUG_LOG, visible).apply()
    }
}
