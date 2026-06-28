package com.usbdeckrec.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.usbdeckrec.data.repository.SettingsRepository

/**
 * ViewModel for the Settings screen.
 *
 * Reads and writes user preferences through [SettingsRepository].
 * Exposes each setting as a simple property that the UI reads directly
 * and writes through dedicated save methods.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val appContext: Context
) : ViewModel() {

    // ── Recording Format ────────────────────────────────────────────

    fun getRecordingFormat(): String = settingsRepository.getRecordingFormat()

    fun saveRecordingFormat(format: String) {
        settingsRepository.saveRecordingFormat(format)
    }

    val availableFormats: List<String> = listOf("FLAC", "WAV")

    // ── Sample Rate ─────────────────────────────────────────────────

    fun getSampleRate(): Int = settingsRepository.getSampleRate()

    fun saveSampleRate(rate: Int) {
        settingsRepository.saveSampleRate(rate)
    }

    val availableSampleRates: List<Int> = listOf(44100, 48000, 96000)

    // ── Bit Depth ───────────────────────────────────────────────────

    fun getBitDepth(): Int = settingsRepository.getBitDepth()

    fun saveBitDepth(depth: Int) {
        settingsRepository.saveBitDepth(depth)
    }

    val availableBitDepths: List<Int> = listOf(16, 24, 32)

    // ── Mixer Profile Override ──────────────────────────────────────

    fun getMixerProfileOverride(): String? = settingsRepository.getMixerProfileOverride()

    fun saveMixerProfileOverride(channels: Int, masterLeft: Int, masterRight: Int) {
        settingsRepository.saveMixerProfileOverride(channels, masterLeft, masterRight)
    }

    fun clearMixerProfileOverride() {
        settingsRepository.clearMixerProfileOverride()
    }

    val availableChannelCounts: List<Int> = listOf(2, 4, 6, 8, 10, 16, 32)

    // ── Debug Log Visibility ──────────────────────────────────────────

    fun isDebugLogVisible(): Boolean = settingsRepository.isDebugLogVisible()

    fun saveDebugLogVisible(visible: Boolean) {
        settingsRepository.saveDebugLogVisible(visible)
    }

    // ── Storage Permission ──────────────────────────────────────────

    /**
     * Whether the appropriate storage read permission has been granted.
     * On Android 13+ this is [Manifest.permission.READ_MEDIA_AUDIO];
     * on Android 12 and below this is [Manifest.permission.READ_EXTERNAL_STORAGE].
     */
    val storagePermissionGranted: Boolean
        get() {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return ContextCompat.checkSelfPermission(appContext, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

    companion object {
        /**
         * The permission to request for reading audio files from the public
         * Music folder. On Android 13+ this is [Manifest.permission.READ_MEDIA_AUDIO];
         * on Android 12 and below it is [Manifest.permission.READ_EXTERNAL_STORAGE].
         */
        val REQUIRED_STORAGE_PERMISSION: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
    }
}
