package com.usbdeckrec.ui.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdeckrec.audio.AudioEngineManager
import com.usbdeckrec.audio.MixerProfile
import com.usbdeckrec.data.repository.RecordingRepository
import com.usbdeckrec.data.repository.SettingsRepository
import com.usbdeckrec.model.RecordedTrack
import com.usbdeckrec.model.RecordingStatus
import com.usbdeckrec.usb.UsbDeviceManager
import com.usbdeckrec.usb.UsbPermissionHelper
import com.usbdeckrec.util.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// ── State classes ─────────────────────────────────────────────────────

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val connectedDeviceName: String? = null,
    val totalChannels: Int = 2,
    val masterChannels: String? = "Ch 1/2",
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val errorMessage: String? = null,
    val captureMode: String = "Idle",
    val isPhonePlaybackEnabled: Boolean = false,
    val isSaving: Boolean = false
)

data class LevelData(
    val leftPeak: Float = 0f,
    val rightPeak: Float = 0f,
    val leftRms: Float = 0f,
    val rightRms: Float = 0f
)

// ── ViewModel ─────────────────────────────────────────────────────────

class RecordingViewModel(
    private val context: android.content.Context,
    private val audioEngineManager: AudioEngineManager,
    private val usbDeviceManager: UsbDeviceManager,
    private val recordingRepository: RecordingRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _levelMeter = MutableStateFlow(LevelData())
    val levelMeter: StateFlow<LevelData> = _levelMeter.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    // Expose debug logs so the UI can collect them alongside other state
    val debugLogs: StateFlow<List<String>> = DebugLogger.logs

    // Tracks the most recently completed recording for UI navigation
    private val _lastCompletedTrack = MutableStateFlow<RecordedTrack?>(null)
    val lastCompletedTrack: StateFlow<RecordedTrack?> = _lastCompletedTrack.asStateFlow()

    // ── Overrun tracking ─────────────────────────────────────────────
    private val _overrunCount = MutableStateFlow(0)
    val overrunCount: StateFlow<Int> = _overrunCount.asStateFlow()

    private val _hasOverrunWarning = MutableStateFlow(false)
    val hasOverrunWarning: StateFlow<Boolean> = _hasOverrunWarning.asStateFlow()

    private var levelPollingJob: Job? = null
    private var elapsedTimeJob: Job? = null
    private var overrunPollingJob: Job? = null
    private var devicePollingJob: Job? = null
    private var recordingStartTime: Long = 0L

    // Thread-safe: accessed from multiple coroutines (polling loop, permission callback, start/stop)
    @Volatile
    private var activeUsbDevice: UsbDevice? = null
    private val isRequestingPermission = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    init {
        DebugLogger.log("RecordingViewModel", "ViewModel initialized")
        // Poll USB device connections to detect mixer attachment
        devicePollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val devices = usbDeviceManager.getConnectedDevices()
                    if (devices.isNotEmpty()) {
                        val device = devices.first()
                        val permissionHelper = UsbPermissionHelper(context)

                        if (!permissionHelper.hasPermission(device)) {
                            // We don't have permission yet. Request it if we aren't already doing so.
                            if (!isRequestingPermission.get()) {
                                isRequestingPermission.set(true)
                                DebugLogger.log("RecordingViewModel", "Requesting USB permission for device: ${device.productName}")
                                permissionHelper.requestDevicePermission(
                                    device = device,
                                    onGranted = { grantedDevice ->
                                        isRequestingPermission.set(false)
                                        DebugLogger.log("RecordingViewModel", "USB permission GRANTED for device: ${grantedDevice.productName}")
                                        // Trigger immediate update and monitoring now that we have permission
                                        viewModelScope.launch {
                                            handleDeviceConnected(grantedDevice)
                                        }
                                    },
                                    onDenied = { deniedDevice ->
                                        isRequestingPermission.set(false)
                                        DebugLogger.log("RecordingViewModel", "USB permission DENIED for device: ${deniedDevice.productName}")
                                        _uiState.update { state ->
                                            state.copy(
                                                status = RecordingStatus.Error,
                                                errorMessage = "USB permission denied. Please grant permission to use the mixer."
                                            )
                                        }
                                    }
                                )
                            }
                        } else {
                            // Permission is already granted, proceed normally
                            handleDeviceConnected(device)
                        }

                    } else {
                        if (activeUsbDevice != null) {
                            DebugLogger.log("RecordingViewModel", "Device disconnected")
                            if (audioEngineManager.isMonitoring()) {
                                audioEngineManager.stopCapture()
                            }
                            // Stop service
                            val serviceIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
                                action = com.usbdeckrec.service.RecordingService.ACTION_STOP
                            }
                            context.startService(serviceIntent)
                        }
                        activeUsbDevice = null
                        stopLevelPollingOnly()
                        if (_uiState.value.status == RecordingStatus.Idle ||
                            _uiState.value.status == RecordingStatus.Connected ||
                            _uiState.value.status == RecordingStatus.Error) {
                            _uiState.update { it.copy(connectedDeviceName = null, status = RecordingStatus.Idle, isPhonePlaybackEnabled = false) }
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log("RecordingViewModel", "Device polling error: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    /**
     * Handle a USB device that has been granted permission.
     * Extracted to avoid duplicating the device-connected logic between
     * the immediate-grant and after-requesting-permission paths.
     */
    private fun handleDeviceConnected(device: UsbDevice) {
        val wasPreviouslyConnected = activeUsbDevice != null
        val sameDevice = activeUsbDevice?.deviceId == device.deviceId
        activeUsbDevice = device
        usbDeviceManager.setSelectedDevice(device)

        // Use the new proactive profile resolution method
        val resolvedProfile = audioEngineManager.resolveProfileForDevice(device)
        DebugLogger.log("RecordingViewModel",
            "Device polling: model=%s, ch=%d, L=%d, R=%d"
                .format(resolvedProfile.modelName, resolvedProfile.totalChannels,
                        resolvedProfile.masterLeftChannel, resolvedProfile.masterRightChannel))

        // CRITICAL: Only update device info fields when Idle/Connected/Error.
        // Do NOT overwrite status during Recording/Paused to prevent
        // the "recording screen goes on then off" glitch.
        val currentStatus = _uiState.value.status
        if (currentStatus == RecordingStatus.Idle ||
            currentStatus == RecordingStatus.Connected ||
            currentStatus == RecordingStatus.Error) {
            _uiState.update { state ->
                state.copy(
                    status = RecordingStatus.Connected,
                    connectedDeviceName = resolvedProfile.modelName,
                    totalChannels = resolvedProfile.totalChannels,
                    masterChannels = formatMasterChannels(
                        resolvedProfile.masterLeftChannel,
                        resolvedProfile.masterRightChannel
                    ),
                    sampleRate = settingsRepository.getSampleRate(),
                    bitDepth = settingsRepository.getBitDepth(),
                    errorMessage = null
                )
            }
        }

        // Start monitoring on first detection or device change
        if (!wasPreviouslyConnected || !sameDevice) {
            if (!audioEngineManager.isMonitoring()) {
                audioEngineManager.startMonitoring(device)
            }
        }

        // Start level polling if monitoring but not yet recording
        if (!audioEngineManager.isRecording() && audioEngineManager.isMonitoring()) {
            startLevelPollingOnly()
        }
    }

    /**
     * Start audio capture on the connected USB device.
     * Updates UI state with the resolved mixer profile on success.
     * On failure, the native Oboe error message is included in the UI error.
     */
    fun startRecording() {
        val device = activeUsbDevice ?: usbDeviceManager.getSelectedDevice()
        if (device == null) {
            val msg = "No USB device connected"
            DebugLogger.log("RecordingViewModel", "startRecording FAILED: $msg")
            _uiState.update { it.copy(errorMessage = msg) }
            return
        }

        DebugLogger.log("RecordingViewModel",
            "startRecording: VID=0x%04X, PID=0x%04X, name=%s"
                .format(device.vendorId, device.productId, device.productName ?: ""))

        val serviceIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
            action = com.usbdeckrec.service.RecordingService.ACTION_START
            putExtra(com.usbdeckrec.service.RecordingService.EXTRA_USB_DEVICE, device)
            putExtra(com.usbdeckrec.service.RecordingService.EXTRA_RECORD, true)
            putExtra(com.usbdeckrec.service.RecordingService.EXTRA_PHONE_PLAYBACK, _uiState.value.isPhonePlaybackEnabled)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to start recording service: ${e.message}"
            DebugLogger.log("RecordingViewModel", errorMsg)
            _uiState.update {
                it.copy(
                    status = RecordingStatus.Error,
                    errorMessage = errorMsg
                )
            }
            return
        }

        val profile = audioEngineManager.resolveProfileForDevice(device)
        recordingStartTime = System.currentTimeMillis()
        activeUsbDevice = device

        // Set this device as the active recording device for UsbConnectionReceiver
        com.usbdeckrec.service.UsbConnectionReceiver.activeDeviceIdentifier =
            "%04X:%04X:%s".format(device.vendorId, device.productId, device.deviceName ?: "")

        DebugLogger.log("RecordingViewModel",
            "Recording started: model=%s, ch=%d, L=%d, R=%d"
                .format(profile?.modelName ?: "?", profile?.totalChannels ?: 2,
                        profile?.masterLeftChannel ?: 0, profile?.masterRightChannel ?: 1))

        // Reset overrun state for a fresh recording session
        _overrunCount.value = 0
        _hasOverrunWarning.value = false

        _uiState.update {
            it.copy(
                status = RecordingStatus.Recording,
                connectedDeviceName = profile?.modelName ?: device.productName ?: "USB Audio Device",
                totalChannels = profile?.totalChannels ?: 2,
                masterChannels = formatMasterChannels(
                    profile?.masterLeftChannel,
                    profile?.masterRightChannel
                ),
                errorMessage = null
            )
        }

        startPollingLoops()
    }

    /**
     * Stop audio capture and persist the recording metadata.
     *
     * @return The [RecordedTrack] that was saved, or null if capture wasn't active.
     */
    suspend fun stopRecording(): RecordedTrack? {
        if (_uiState.value.status != RecordingStatus.Recording &&
            _uiState.value.status != RecordingStatus.Paused
        ) {
            return null
        }

        // Guard against double-call race
        if (!isStopping.compareAndSet(false, true)) {
            DebugLogger.log("RecordingViewModel", "stopRecording already in progress — skipping")
            return null
        }

        DebugLogger.log("RecordingViewModel", "Stopping recording")
        _uiState.update { it.copy(isSaving = true) }
        stopPollingLoops()

        // Final poll: capture any remaining overrun count before stopping
        val finalOverrun = audioEngineManager.getOverrunCount()
        if (finalOverrun > 0) {
            _overrunCount.value = finalOverrun
            _hasOverrunWarning.value = true
        }

        // Register a broadcast receiver to be notified when the service has
        // finished writing and closed the file. Falls back after 5s timeout.
        // NOTE: Use the 2-parameter registerReceiver for API < 33 compatibility.
        // The 3-parameter overload with RECEIVER_NOT_EXPORTED was added in API 33
        // and throws on older platforms, which would prevent the finally block
        // from executing and leave isStopping=true forever.
        val completionDeferred = CompletableDeferred<Unit>()
        val completionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                completionDeferred.complete(Unit)
            }
        }
        val filter = IntentFilter(RecordingViewModel.ACTION_RECORDING_COMPLETED)

        var receiverRegistered = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(completionReceiver, filter)
            }
            receiverRegistered = true
            DebugLogger.log("RecordingViewModel", "Completion receiver registered")
        } catch (e: Exception) {
            DebugLogger.log("RecordingViewModel",
                "Failed to register completion receiver: ${e.message} — proceeding without broadcast")
        }

        try {
            // Always send ACTION_STOP to properly stop recording and trigger loop exit.
            // Previously this sent ACTION_START when phone playback was enabled, which
            // caused the audio loop to never exit (since isPhonePlaybackEnabled stayed true),
            // meaning the finally block in the service was never reached, the broadcast
            // was never sent, and the ViewModel timed out after 5s with fileSize=0.
            val stopIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
                action = com.usbdeckrec.service.RecordingService.ACTION_STOP
            }
            context.startService(stopIntent)

            // Wait for the service to confirm file completion, with 5s timeout
            val completed = withTimeoutOrNull(5000) {
                completionDeferred.await()
            }
            if (completed == null) {
                DebugLogger.log("RecordingViewModel",
                    "Recording completion broadcast not received within 5s — proceeding anyway")
            } else {
                DebugLogger.log("RecordingViewModel",
                    "Recording completion confirmed by service")
            }
        } finally {
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(completionReceiver)
                } catch (_: IllegalArgumentException) {
                    // Receiver was already unregistered
                }
            }

            // If phone playback is enabled, restart the service in monitoring+playback mode
            // so the user continues to hear audio through the phone speaker.
            if (_uiState.value.isPhonePlaybackEnabled) {
                val device = activeUsbDevice
                if (device != null) {
                    val playbackIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
                        action = com.usbdeckrec.service.RecordingService.ACTION_START
                        putExtra(com.usbdeckrec.service.RecordingService.EXTRA_USB_DEVICE, device)
                        putExtra(com.usbdeckrec.service.RecordingService.EXTRA_RECORD, false)
                        putExtra(com.usbdeckrec.service.RecordingService.EXTRA_PHONE_PLAYBACK, true)
                    }
                    context.startService(playbackIntent)
                    DebugLogger.log("RecordingViewModel",
                        "Restarted service in monitoring+playback mode after recording stop")
                }
            }

            // CRITICAL: Always reset isStopping so subsequent calls can proceed
            isStopping.set(false)
        }

        val elapsedMs = _elapsedTime.value
        val state = _uiState.value

        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val format = if (state.sampleRate > 0) "FLAC" else "FLAC"
        val fileName = "${timestamp}_recording.${format.lowercase()}"

        // Retrieve the path from StorageHelper, which was set during file creation.
        // This is more reliable than scanning directories, especially under scoped storage.
        val filePath = com.usbdeckrec.service.StorageHelper.lastCreatedFilePath.ifEmpty {
            DebugLogger.log("RecordingViewModel",
                "StorageHelper.lastCreatedFilePath is empty — falling back to directory scan")
            // Fallback: attempt to find the file in the public Music directory
            val recordingsDir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC
                ),
                "USB DeckRec"
            )
            if (recordingsDir.exists()) {
                val files = recordingsDir.listFiles()
                if (files != null) {
                    files.filter { it.name.startsWith(timestamp) }
                        .maxByOrNull { it.lastModified() }
                        ?.absolutePath ?: ""
                } else ""
            } else ""
        }
        var fileSize = 0L
        if (filePath.isNotEmpty()) {
            val file = java.io.File(filePath)
            if (file.exists()) {
                fileSize = file.length()
                DebugLogger.log("RecordingViewModel",
                    "Found recording file: ${file.name}, size=${fileSize} bytes")
            }
        } else {
            DebugLogger.log("RecordingViewModel",
                "No recording file found on disk — will use placeholder path")
        }

        val contentUri = com.usbdeckrec.service.StorageHelper.lastCreatedFileUri?.toString()

        val track = RecordedTrack(
            id = 0,
            fileName = fileName,
            filePath = filePath,
            contentUri = contentUri,
            durationMs = elapsedMs,
            fileSizeBytes = fileSize,
            sampleRate = state.sampleRate,
            bitDepth = state.bitDepth,
            format = format,
            dateCreated = System.currentTimeMillis()
        )

        val insertedId = recordingRepository.insertRecording(track)
        val savedTrack = track.copy(id = insertedId)

        _uiState.update {
            it.copy(
                status = RecordingStatus.Idle,
                errorMessage = null,
                isSaving = false
            )
        }
        _elapsedTime.value = 0L
        _levelMeter.value = LevelData()
        _lastCompletedTrack.value = savedTrack

        // Clear the active device identifier since recording has stopped
        com.usbdeckrec.service.UsbConnectionReceiver.activeDeviceIdentifier = null

        DebugLogger.log("RecordingViewModel",
            "Recording stopped: ${track.fileName}, duration=${elapsedMs}ms, " +
            "size=${formatFileSize(fileSize)}")
        return savedTrack
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    /**
     * Toggle pause/resume recording state.
     */
    fun pauseRecording() {
        val currentStatus = _uiState.value.status
        if (currentStatus == RecordingStatus.Recording) {
            DebugLogger.log("RecordingViewModel", "Recording paused")
            _uiState.update { it.copy(status = RecordingStatus.Paused) }
            stopPollingLoops()
        } else if (currentStatus == RecordingStatus.Paused) {
            DebugLogger.log("RecordingViewModel", "Recording resumed")
            _uiState.update { it.copy(status = RecordingStatus.Recording) }
            startPollingLoops()
        }
    }

    /**
     * Consume (clear) the last completed track to prevent re-navigation on Back press.
     */
    fun consumeCompletedTrack() {
        _lastCompletedTrack.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPollingLoops()
        devicePollingJob?.cancel()
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun startPollingLoops() {
        stopPollingLoops()

        // Poll level data from the native engine
        levelPollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.status == RecordingStatus.Recording) {
                val levels = audioEngineManager.getLevelData()
                if (levels.size >= 4) {
                    _levelMeter.value = LevelData(
                        leftPeak = levels[0],
                        rightPeak = levels[1],
                        leftRms = levels[2],
                        rightRms = levels[3]
                    )
                }
                _uiState.update { it.copy(captureMode = audioEngineManager.getCaptureMode()) }
                delay(50) // ~20 Hz refresh
            }
        }

        // Poll elapsed time
        elapsedTimeJob = viewModelScope.launch {
            while (isActive) {
                _elapsedTime.value = System.currentTimeMillis() - recordingStartTime
                delay(100)
            }
        }

        // Poll overrun count from the native engine (~2 Hz)
        overrunPollingJob = viewModelScope.launch {
            while (isActive) {
                val count = audioEngineManager.getOverrunCount()
                if (count > 0) {
                    _overrunCount.value = count
                    _hasOverrunWarning.value = true
                }
                delay(500)
            }
        }
    }

    private fun stopPollingLoops() {
        levelPollingJob?.cancel()
        levelPollingJob = null
        elapsedTimeJob?.cancel()
        elapsedTimeJob = null
        overrunPollingJob?.cancel()
        overrunPollingJob = null
    }

    /**
     * Start polling level data only (no elapsed time) — used during Connected
     * state where the audio stream is open in monitoring mode for live VU.
     *
     * Also polls the capture mode string to update UI state.
     */
    private fun startLevelPollingOnly() {
        stopLevelPollingOnly()
        levelPollingJob = viewModelScope.launch {
            while (isActive && audioEngineManager.isMonitoring()) {
                val levels = audioEngineManager.getLevelData()
                if (levels.size >= 4) {
                    _levelMeter.value = LevelData(
                        leftPeak = levels[0],
                        rightPeak = levels[1],
                        leftRms = levels[2],
                        rightRms = levels[3]
                    )
                }
                // Update capture mode on each poll
                _uiState.update { it.copy(captureMode = audioEngineManager.getCaptureMode()) }
                delay(50) // ~20 Hz refresh
            }
        }
    }

    private fun stopLevelPollingOnly() {
        levelPollingJob?.cancel()
        levelPollingJob = null
    }

    private fun formatMasterChannels(leftCh: Int?, rightCh: Int?): String {
        if (leftCh == null || rightCh == null) return "Ch 1/2"
        val left = leftCh + 1
        val right = rightCh + 1
        return "Ch $left/$right"
    }

    fun togglePhonePlayback() {
        val device = activeUsbDevice ?: usbDeviceManager.getSelectedDevice()
        if (device == null) {
            DebugLogger.log("RecordingViewModel", "togglePhonePlayback FAILED: No USB device connected")
            return
        }

        val newEnabled = !_uiState.value.isPhonePlaybackEnabled
        DebugLogger.log("RecordingViewModel", "Toggling phone playback to: $newEnabled")

        _uiState.update { it.copy(isPhonePlaybackEnabled = newEnabled) }

        val isServiceRunning = _uiState.value.status == RecordingStatus.Recording ||
            _uiState.value.status == RecordingStatus.Paused

        if (newEnabled && !isServiceRunning) {
            // Start the service in monitoring mode — this will start monitoring
            // if not already running AND enable the AudioTrack for phone playback.
            val startIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
                action = com.usbdeckrec.service.RecordingService.ACTION_START
                putExtra(com.usbdeckrec.service.RecordingService.EXTRA_USB_DEVICE, device)
                putExtra(com.usbdeckrec.service.RecordingService.EXTRA_RECORD, false)
                putExtra(com.usbdeckrec.service.RecordingService.EXTRA_PHONE_PLAYBACK, true)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            } catch (e: Exception) {
                DebugLogger.log("RecordingViewModel", "Failed to start service for phone playback: ${e.message}")
            }
        } else {
            // Service is already running (either recording or monitoring).
            // When turning OFF: just tell the service to disable AudioTrack playback.
            // The monitoring engine stays alive through the isMonitoring flag.
            // When turning ON: just tell the service to create AudioTrack and start playback.
            val toggleIntent = android.content.Intent(context, com.usbdeckrec.service.RecordingService::class.java).apply {
                action = com.usbdeckrec.service.RecordingService.ACTION_SET_PHONE_PLAYBACK
                putExtra(com.usbdeckrec.service.RecordingService.EXTRA_PHONE_PLAYBACK_ENABLED, newEnabled)
            }
            context.startService(toggleIntent)
        }
    }

    companion object {
        /**
         * Broadcast action sent by [RecordingService] when the encoder has
         * flushed all data and the audio file is fully written and closed.
         * The [RecordingViewModel] observes this to coordinate [stopRecording]
         * instead of using a fixed delay.
         */
        const val ACTION_RECORDING_COMPLETED = "com.usbdeckrec.action.RECORDING_COMPLETED"
    }
}
