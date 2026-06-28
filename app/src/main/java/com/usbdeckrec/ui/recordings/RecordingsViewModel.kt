package com.usbdeckrec.ui.recordings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.usbdeckrec.data.repository.RecordingRepository
import com.usbdeckrec.model.RecordedTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "DeckRec_Playback"

/**
 * Represents the current state of in-app audio playback.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isSeeking: Boolean = false,
    val currentRecording: RecordedTrack? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * ViewModel for the Recordings list screen.
 *
 * Manages the list of saved recordings from [RecordingRepository],
 * and provides in-app audio playback via [ExoPlayer] with
 * play/pause/stop controls.
 *
 * ExoPlayer is used instead of the legacy [android.media.MediaPlayer]
 * because it builds its own seek index at the application layer,
 * enabling reliable seeking in FLAC files recorded via MediaCodec
 * (which lack a native SEEKTABLE metadata block).
 */
class RecordingsViewModel(
    private val recordingRepository: RecordingRepository,
    private val appContext: Context
) : ViewModel() {

    private val _recordings = MutableStateFlow<List<RecordedTrack>>(emptyList())
    val recordings: StateFlow<List<RecordedTrack>> = _recordings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * Whether the appropriate storage permission has been granted.
     * On Android 13+ we need [Manifest.permission.READ_MEDIA_AUDIO];
     * on Android 12 and below we need [Manifest.permission.READ_EXTERNAL_STORAGE].
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
         * The permission to request for reading audio files from the public Music folder.
         * On Android 13+ this is [Manifest.permission.READ_MEDIA_AUDIO];
         * on Android 12 and below it is [Manifest.permission.READ_EXTERNAL_STORAGE].
         */
        val REQUIRED_STORAGE_PERMISSION: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
    }

    private var exoPlayer: ExoPlayer? = null
    private var positionUpdateJob: Job? = null

    /**
     * Listener that bridges ExoPlayer callbacks into our [PlaybackState] flow.
     */
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = _playbackState.value
            when (playbackState) {
                Player.STATE_ENDED -> {
                    Log.d(TAG, "ExoPlayer ended")
                    stopPlaybackInternal()
                    _playbackState.value = PlaybackState()
                }
                Player.STATE_READY -> {
                    val player = exoPlayer ?: return
                    var duration = player.duration
                    if (duration <= 0L) {
                        duration = current.currentRecording?.durationMs ?: 0L
                        Log.w(TAG, "ExoPlayer duration <= 0, falling back to DB duration=${duration}ms")
                    }
                    Log.d(TAG, "ExoPlayer ready: duration=${player.duration}ms, using=$duration")
                    // Only update duration on first ready (not on repeat after seek)
                    if (current.durationMs <= 0L && duration > 0L) {
                        _playbackState.value = current.copy(durationMs = duration)
                    }
                }
                Player.STATE_BUFFERING -> {
                    // No action needed
                }
                Player.STATE_IDLE -> {
                    // No action needed
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val current = _playbackState.value
            _playbackState.value = current.copy(
                isPlaying = isPlaying,
                isPaused = !isPlaying && current.currentRecording != null
            )
            if (isPlaying) {
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                Log.d(TAG, "Seek finished at position: ${newPosition.positionMs}ms")
                val current = _playbackState.value
                _playbackState.value = current.copy(
                    isSeeking = false,
                    currentPositionMs = newPosition.positionMs
                )
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.localizedMessage}", error)
            stopPlaybackInternal()
            _playbackState.value = PlaybackState(
                errorMessage = error.localizedMessage ?: "Playback error"
            )
        }
    }

    init {
        // On API 29+ (Android 10+), MediaStore queries do NOT require
        // READ_EXTERNAL_STORAGE permission. The recordings saved via
        // StorageHelper's MediaStore path will be visible automatically.
        // Only the legacy File API scan (API < 29) needs the permission,
        // and for that case the UI shows a request button.
        if (storagePermissionGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viewModelScope.launch {
                recordingRepository.syncRecordingsFromFilesystem()
            }
        }
        viewModelScope.launch {
            recordingRepository.getAllRecordings().collect { list ->
                _recordings.value = list
            }
        }
    }

    /**
     * Called after the user grants the storage permission via the UI.
     * Scans the public Music/USB DeckRec/ folder for orphaned audio files
     * and imports them into the database so they show up in the list.
     */
    fun onStoragePermissionGranted() {
        viewModelScope.launch {
            _isLoading.value = true
            recordingRepository.syncRecordingsFromFilesystem()
            _isLoading.value = false
        }
    }

    // -- Playback Controls ----------------------------------------------

    /**
     * Toggle play/pause for the given recording.
     *
     * - If the same recording is paused resume playback.
     * - If the same recording is playing pause.
     * - If a different recording stop current, start new.
     */
    fun playPause(recording: RecordedTrack) {
        val current = _playbackState.value

        // Resume paused recording
        if (current.currentRecording?.id == recording.id && current.isPaused) {
            exoPlayer?.play()
            _playbackState.value = current.copy(isPlaying = true, isPaused = false)
            startPositionUpdates()
            return
        }

        // Pause playing recording
        if (current.currentRecording?.id == recording.id && current.isPlaying) {
            exoPlayer?.pause()
            _playbackState.value = current.copy(isPlaying = false, isPaused = true)
            stopPositionUpdates()
            return
        }

        // Different recording stop current, start new
        stopPlaybackInternal()
        startNewPlayback(recording)
    }

    /**
     * Stop playback entirely and reset the playback state.
     */
    fun stopPlayback() {
        stopPlaybackInternal()
        _playbackState.value = PlaybackState()
    }

    /**
     * Seek to a specific position in the current recording.
     *
     * ExoPlayer's [ExoPlayer.seekTo] is reliable on FLAC files recorded
     * via MediaCodec because it builds its own seek index at the
     * application layer, unlike the legacy [android.media.MediaPlayer]
     * which depends on the file's native SEEKTABLE metadata block.
     */
    fun seekTo(positionMs: Long) {
        val state = _playbackState.value
        exoPlayer?.let { player ->
            val safeDuration = if (state.durationMs > 0L) state.durationMs else player.duration
            if (safeDuration > 0L && positionMs in 0..safeDuration) {
                // Set isSeeking = true so the position-update loop skips its
                // writes while ExoPlayer processes the seek asynchronously.
                _playbackState.value = state.copy(
                    isSeeking = true,
                    currentPositionMs = positionMs
                )
                player.seekTo(positionMs)
            }
        }
    }

    // -- Internal helpers -----------------------------------------------

    private fun startNewPlayback(recording: RecordedTrack) {
        try {
            // On Android 10+ use the MediaStore Content URI (scoped storage).
            // On Android 9 and below use the direct file path.
            val useContentUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            if (useContentUri) {
                val uriStr = recording.contentUri
                if (uriStr != null) {
                    val uri = Uri.parse(uriStr)
                    Log.d(TAG, "startNewPlayback: contentUri=$uriStr")
                    startPlaybackWithUri(uri, recording)
                    return
                }
                Log.w(TAG, "startNewPlayback: no contentUri, falling back to file path")
            }

            // Fallback / legacy: direct file path
            val file = File(recording.filePath)
            Log.d(TAG, "startNewPlayback: path=${recording.filePath}, exists=${file.exists()}, " +
                    "length=${file.length()}, readable=${file.canRead()}")

            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "startNewPlayback: file missing or empty")
                _playbackState.value = PlaybackState(
                    errorMessage = "File not found or empty"
                )
                return
            }

            startPlaybackWithPath(recording.filePath, recording)
        } catch (e: Exception) {
            Log.e(TAG, "startNewPlayback exception: ${e.message}", e)
            _playbackState.value = PlaybackState(
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Start ExoPlayer playback from a Content URI (scoped storage).
     *
     * Sets an explicit MIME type of [C.AUDIO_FLAC_MIME_TYPE] so that ExoPlayer's
     * built-in [FlacExtractor] handles seeking at the application layer instead of
     * delegating to the platform MediaCodec decoder, which cannot seek in files
     * without a native SEEKTABLE metadata block.
     */
    private fun startPlaybackWithUri(uri: Uri, recording: RecordedTrack) {
        val player = ExoPlayer.Builder(appContext).build()
        player.addListener(playerListener)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType("audio/flac")
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        exoPlayer = player

        // Update playback state immediately with the recording info
        _playbackState.value = PlaybackState(
            isPlaying = true,
            currentRecording = recording,
            durationMs = recording.durationMs // will be refined by onPlaybackStateChanged
        )
        startPositionUpdates()
    }

    /**
     * Start ExoPlayer playback from a direct file path (legacy).
     *
     * Sets an explicit MIME type of [C.AUDIO_FLAC_MIME_TYPE] so that ExoPlayer's
     * built-in [FlacExtractor] handles seeking at the application layer instead of
     * delegating to the platform MediaCodec decoder.
     */
    private fun startPlaybackWithPath(path: String, recording: RecordedTrack) {
        val player = ExoPlayer.Builder(appContext).build()
        player.addListener(playerListener)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(path)))
            .setMimeType("audio/flac")
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        exoPlayer = player

        // Update playback state immediately with the recording info
        _playbackState.value = PlaybackState(
            isPlaying = true,
            currentRecording = recording,
            durationMs = recording.durationMs // will be refined by onPlaybackStateChanged
        )
        startPositionUpdates()
    }

    private fun stopPlaybackInternal() {
        stopPositionUpdates()
        exoPlayer?.apply {
            removeListener(playerListener)
            stop()
            release()
        }
        exoPlayer = null
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val player = exoPlayer ?: break
                val current = _playbackState.value
                if (current.isPlaying && !current.isSeeking) {
                    try {
                        _playbackState.value = current.copy(
                            currentPositionMs = player.currentPosition
                        )
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // -- Recording management -------------------------------------------

    /**
     * Clear any displayed playback error.
     */
    fun clearPlaybackError() {
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
    }

    /**
     * Delete a recording from both the database and file system.
     * Stops playback first if the deleted recording is currently playing.
     */
    fun deleteRecording(recording: RecordedTrack) {
        Log.d(TAG, "deleteRecording: id=${recording.id}, path=${recording.filePath}")
        if (_playbackState.value.currentRecording?.id == recording.id) {
            stopPlayback()
        }
        viewModelScope.launch {
            recordingRepository.deleteRecording(recording)
        }
    }

    /**
     * Manually refresh the recordings list from the repository.
     */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _isLoading.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlaybackInternal()
    }
}
