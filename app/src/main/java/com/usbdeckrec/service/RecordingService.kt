package com.usbdeckrec.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.usbdeckrec.audio.AudioEngineBridge
import com.usbdeckrec.audio.AudioEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service responsible for USB audio capture, FLAC encoding, and phone playback.
 *
 * ## PCM Audio Path (standard DJM mixers with isochronous audio)
 * - Reads PCM float data from the native engine
 * - Feeds it into a [MediaCodec] FLAC encoder (if recording)
 * - Writes encoded FLAC packets to a `.flac` file via [StorageHelper]
 * - Plays it back on the phone's speaker/headphones via [AudioTrack] (if phone playback is enabled)
 *
 * ## DJM-900NXS Note
 * The DJM-900NXS (PID 0x0158) uses **bulk MIDI** transfers only — it does NOT
 * stream PCM audio over USB. Recording is not supported for this device.
 * The VU meter monitoring (derived from MIDI CC messages) still works.
 */
class RecordingService : Service() {

    private val audioEngineManager: AudioEngineManager by lazy { AudioEngineManager.getInstance(this) }
    private val notificationHelper: NotificationHelper by lazy { NotificationHelper(this) }
    private val bridge = AudioEngineBridge()

    private var audioLoopJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRecording = false
    @Volatile
    private var isPhonePlaybackEnabled = false
    @Volatile
    private var isMonitoring = false
    private var audioTrack: AudioTrack? = null
    private var activeUsbDevice: UsbDevice? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleActionStart(intent)
            ACTION_STOP -> handleActionStop()
            ACTION_SET_PHONE_PLAYBACK -> handleActionSetPhonePlayback(intent)
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun handleActionStart(intent: Intent) {
        val usbDevice: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getParcelableExtra<UsbDevice>(EXTRA_USB_DEVICE)
        } ?: return

        activeUsbDevice = usbDevice
        val recordExtra = intent.getBooleanExtra(EXTRA_RECORD, true)
        val playbackExtra = intent.getBooleanExtra(EXTRA_PHONE_PLAYBACK, false)

        // Reject recording for bulk-transfer MIDI-only devices like DJM-900NXS.
        // These devices do not stream PCM audio over USB — they only send MIDI
        // event packets (timing clock, CC, SysEx) over bulk endpoints.
        val profile = audioEngineManager.resolveProfileForDevice(usbDevice)
        if (profile.usesBulkTransfer) {
            android.util.Log.w("RecordingService",
                "Cannot record audio: ${profile.modelName} uses bulk MIDI only, " +
                "no PCM audio over USB. VU meter monitoring is available.")
            if (!isRecording && !isPhonePlaybackEnabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        // Request POST_NOTIFICATIONS permission on Android 13+ so the
        // foreground notification is visible. If denied, the service still
        // runs — the user just won't see the persistent notification.
        requestNotificationPermissionIfNeeded()

        // Build and show the foreground notification — use monitoring
        // text when not actively recording to a file.
        val notification = if (recordExtra) {
            notificationHelper.buildRecordingNotification()
        } else {
            notificationHelper.buildMonitoringNotification()
        }
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )

        // Update states
        isRecording = recordExtra
        isPhonePlaybackEnabled = playbackExtra
        isMonitoring = !recordExtra

        // Start the native audio capture/monitoring
        val success = if (isRecording) {
            audioEngineManager.startCapture(usbDevice)
        } else {
            audioEngineManager.startMonitoring(usbDevice)
        }

        if (success) {
            startAudioLoop()
        } else {
            if (!isRecording && !isMonitoring) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleActionStop() {
        isRecording = false
        isPhonePlaybackEnabled = false
        isMonitoring = false
        // The loop will exit naturally at the top of the while condition,
        // which will trigger the finally block to send the broadcast
        // and clean up resources.
    }

    private fun handleActionSetPhonePlayback(intent: Intent) {
        val enabled = intent.getBooleanExtra(EXTRA_PHONE_PLAYBACK_ENABLED, false)
        isPhonePlaybackEnabled = enabled

        if (enabled && activeUsbDevice != null) {
            // Make sure capture/monitoring is started
            if (!audioEngineManager.isRecording() && !audioEngineManager.isMonitoring()) {
                audioEngineManager.startMonitoring(activeUsbDevice!!)
            }
            // Mark as monitoring so the loop stays alive even when
            // only phone playback was keeping it running
            isMonitoring = true
            startAudioLoop()
        }
        // When disabling phone playback while monitoring (not recording),
        // the loop stays alive because isMonitoring is true.
        // The AudioTrack is released in the loop body (else branch).
    }

    private fun getActiveSampleRate(): Int {
        return if (audioEngineManager.getCaptureMode() == "USB Direct") 44100 else 48000
    }

    /**
     * Launch the unified audio capture, encoding, and playback loop.
     *
     * Allocates a direct ByteBuffer (1024 frames * 2 channels * 4 bytes float = 8192 bytes),
     * and loops polling captured data from the native engine.
     * If recording is active, feeds data into MediaCodec FLAC encoder.
     * If phone playback is active, feeds data into AudioTrack.
     */
    private fun startAudioLoop() {
        if (audioLoopJob != null) return // already running

        audioLoopJob = serviceScope.launch {
            val bufferSizeFrames = 1024
            val bytesPerSample = 4 // Float32
            val channels = 2 // Stereo

            // Direct ByteBuffer for zero-copy JNI transfer
            val byteBuffer = ByteBuffer.allocateDirect(
                bufferSizeFrames * channels * bytesPerSample
            ).apply {
                order(ByteOrder.nativeOrder())
            }

            var encoder: MediaCodec? = null
            var outputStream: OutputStream? = null
            val bufferInfo = MediaCodec.BufferInfo()

            var currentSampleRate = getActiveSampleRate()
            var framesWritten = 0
            var diskFullEncountered = false

            try {
                while (isActive && (isRecording || isMonitoring)) {
                    val sampleRate = getActiveSampleRate()
                    if (sampleRate != currentSampleRate) {
                        currentSampleRate = sampleRate
                        releaseAudioTrack()
                    }

                    // 1. Manage Encoder Lifecycle based on isRecording
                    if (isRecording) {
                        if (encoder == null) {
                            android.util.Log.d("RecordingService", "Initializing FLAC encoder at $sampleRate Hz")
                            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                            val format = MediaFormat.createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_FLAC,
                                sampleRate,
                                channels
                            ).apply {
                                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    setInteger(
                                        MediaFormat.KEY_PCM_ENCODING,
                                        android.media.AudioFormat.ENCODING_PCM_FLOAT
                                    )
                                } else {
                                    setInteger(
                                        MediaFormat.KEY_PCM_ENCODING,
                                        android.media.AudioFormat.ENCODING_PCM_16BIT
                                    )
                                }
                            }
                            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                            encoder.start()

                            val (stream, _) = StorageHelper.createAudioFile(
                                this@RecordingService,
                                "flac"
                            )
                            outputStream = stream
                        }
                    } else {
                        if (encoder != null) {
                            android.util.Log.d("RecordingService", "Stopping FLAC encoder")
                            stopAndReleaseEncoder(encoder, outputStream, bufferInfo)
                            encoder = null
                            outputStream = null
                        }
                    }

                    // 2. Manage AudioTrack Lifecycle based on isPhonePlaybackEnabled
                    if (isPhonePlaybackEnabled) {
                        if (audioTrack == null) {
                            android.util.Log.d("RecordingService", "Initializing AudioTrack at $sampleRate Hz")
                            val minBufferSize = AudioTrack.getMinBufferSize(
                                sampleRate,
                                AudioFormat.CHANNEL_OUT_STEREO,
                                AudioFormat.ENCODING_PCM_FLOAT
                            )
                            audioTrack = AudioTrack.Builder()
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                                )
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                        .setSampleRate(sampleRate)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                        .build()
                                )
                                .setBufferSizeInBytes(minBufferSize * 2)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                            audioTrack?.play()
                        }
                    } else {
                        if (audioTrack != null) {
                            android.util.Log.d("RecordingService", "Stopping AudioTrack")
                            releaseAudioTrack()
                        }
                    }

                    // 3. Read and Process Audio Data
                    byteBuffer.clear()
                    val framesRead = bridge.readCapturedData(byteBuffer)

                    if (framesRead > 0) {
                        val bytesToWrite = framesRead * channels * bytesPerSample
                        byteBuffer.limit(bytesToWrite)

                        // Feed into MediaCodec FLAC encoder if recording
                        if (isRecording && encoder != null) {
                            if (outputStream == null) {
                                android.util.Log.e("RecordingService",
                                    "Cannot write encoded audio: outputStream is null")
                            } else {
                                byteBuffer.rewind()
                                val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                                if (inputBufferIndex >= 0) {
                                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                                    inputBuffer.clear()
                                    inputBuffer.put(byteBuffer)
                                    encoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        byteBuffer.limit(),
                                        System.nanoTime() / 1000,
                                        0
                                    )
                                }

                                // Drain encoded output
                                var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                                while (outputBufferIndex >= 0) {
                                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                                    val outData = ByteArray(bufferInfo.size)
                                    outputBuffer.get(outData)
                                    outputStream!!.write(outData)
                                    framesWritten++
                                    // Periodic flush every 100 frames to minimize data loss on crash
                                    if (framesWritten % 100 == 0) {
                                        outputStream!!.flush()
                                    }
                                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                                }
                            }
                        }

                        // Write to AudioTrack if phone playback is enabled
                        if (isPhonePlaybackEnabled && audioTrack != null) {
                            byteBuffer.rewind()
                            audioTrack?.write(byteBuffer, bytesToWrite, AudioTrack.WRITE_NON_BLOCKING)
                        }
                    } else {
                        // Yield CPU if no data is available yet
                        delay(5)
                    }
                }
            } catch (e: IOException) {
                diskFullEncountered = true
                android.util.Log.e("RecordingService",
                    "Disk full or I/O error during recording: ${e.message}", e)
                notificationHelper.showRecordingErrorNotification(
                    "Recording failed: Insufficient storage space")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Clean up encoder
                if (encoder != null) {
                    stopAndReleaseEncoder(encoder, outputStream, bufferInfo)
                }

                // Sync the file descriptor to ensure all data is written to disk
                syncFileIfNeeded()

                // Broadcast that the recording file is fully written and closed.
                // A small delay gives the ViewModel time to register its receiver
                // after sending ACTION_STOP, preventing a race where the broadcast
                // arrives before the receiver is ready.
                val completionIntent = android.content.Intent(
                    com.usbdeckrec.ui.recording.RecordingViewModel.ACTION_RECORDING_COMPLETED
                )
                delay(200) // 200ms grace window for receiver registration
                this@RecordingService.sendBroadcast(completionIntent)

                if (diskFullEncountered) {
                    // Disk was full — delete the incomplete MediaStore entry so the
                    // user never sees a truncated/corrupted recording in their library.
                    android.util.Log.w("RecordingService",
                        "Deleting incomplete recording due to disk full error")
                    StorageHelper.deleteMediaStoreFileIfPending(this@RecordingService)
                } else {
                    // On API 30+ mark the MediaStore file as no longer pending so the
                    // media scanner can index the completed recording.
                    StorageHelper.finalizeMediaStoreFileIfPending(this@RecordingService)
                }

                // Clean up AudioTrack
                releaseAudioTrack()

                audioLoopJob = null

                // If we stopped because both recording and monitoring are disabled, stop the service
                if (!isRecording && !isMonitoring) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * Properly shut down a MediaCodec encoder by signaling end-of-stream,
     * draining all remaining encoded output, then stopping and releasing.
     *
     * Without this EOS handshake the FLAC encoder never writes the final
     * total-sample count into its STREAMINFO header, causing MediaPlayer
     * to report a duration of 0.
     */
    private fun stopAndReleaseEncoder(
        encoder: MediaCodec,
        outputStream: OutputStream?,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        try {
            // 1. Signal end-of-stream so the encoder flushes its internal
            //    state and writes final metadata (especially total sample
            //    count for FLAC STREAMINFO).
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                encoder.queueInputBuffer(
                    inputBufferIndex,
                    0,               // offset
                    0,               // size (empty – just the flag)
                    0,               // presentationTimeUs
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            // 2. Drain all remaining output buffers until we receive the
            //    EOS flag back from the encoder.
            //    A max iteration guard prevents infinite loops if the
            //    encoder malfunctions.
            //    A short sleep on TRY_AGAIN prevents busy-spin blocking
            //    the finally block from executing in a timely manner.
            var drainAttempts = 0
            val maxDrainAttempts = 200
            while (drainAttempts < maxDrainAttempts) {
                drainAttempts++
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // The encoder is still processing – yield before polling again.
                    // A small sleep prevents tight-loop blocking the finally block.
                    Thread.sleep(5)
                    continue
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The output format may change one last time; harmless.
                    continue
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!
                    if (bufferInfo.size > 0) {
                        val outData = ByteArray(bufferInfo.size)
                        outputBuffer.get(outData)
                        try {
                            outputStream?.write(outData)
                            outputStream?.flush()
                        } catch (_: Exception) {
                            // Best-effort flush of remaining encoder output
                        }
                    }
                    val isEos =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    if (isEos) break
                } else {
                    // Other INFO_* values – continue.
                    continue
                }
            }
            if (drainAttempts >= maxDrainAttempts) {
                android.util.Log.w("RecordingService",
                    "stopAndReleaseEncoder: max drain attempts ($maxDrainAttempts) reached, " +
                    "forcing encoder stop")
            }
        } catch (_: Exception) {
            // Best-effort drain – if this fails we still stop/release below.
        }

        try {
            encoder.stop()
        } catch (_: Exception) { }
        try {
            encoder.release()
        } catch (_: Exception) { }
        try {
            outputStream?.close()
        } catch (_: Exception) { }
    }

    /**
     * Sync the recorded file descriptor to persist all buffered data to disk.
     * Uses ParcelFileDescriptor on API 29+ for MediaStore-backed streams.
     */
    private fun syncFileIfNeeded() {
        val uri = StorageHelper.lastCreatedFileUri ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            this@RecordingService.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                pfd.fileDescriptor.sync()
                android.util.Log.d("RecordingService", "File descriptor synced successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingService",
                "Failed to sync file descriptor: ${e.message}", e)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) { }
        audioTrack = null
    }

    private fun stopAudioLoop() {
        audioLoopJob?.cancel()
        audioLoopJob = null
    }

    /**
     * Request [Manifest.permission.POST_NOTIFICATIONS] on Android 13+
     * so the foreground service notification is visible to the user.
     *
     * If permission is denied the service still runs — the user simply
     * won't see the persistent notification. This is a best-effort request.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return

        // We cannot display a system dialog from a Service directly.
        // Instead, start the main Activity which handles the permission request
        // in its onCreate/onResume. The notification still appears on older
        // API levels where permission is not required.
        android.util.Log.i("RecordingService",
            "POST_NOTIFICATIONS permission not granted — " +
            "foreground notification may be hidden on Android 13+")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioLoop()
        audioEngineManager.stopCapture()
        releaseAudioTrack()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.usbdeckrec.action.START_RECORDING"
        const val ACTION_STOP = "com.usbdeckrec.action.STOP_RECORDING"
        const val ACTION_SET_PHONE_PLAYBACK = "com.usbdeckrec.action.SET_PHONE_PLAYBACK"
        const val EXTRA_USB_DEVICE = "extra_usb_device"
        const val EXTRA_RECORD = "extra_record"
        const val EXTRA_PHONE_PLAYBACK = "extra_phone_playback"
        const val EXTRA_PHONE_PLAYBACK_ENABLED = "extra_phone_playback_enabled"
        const val NOTIFICATION_ID = 1001
    }
}
