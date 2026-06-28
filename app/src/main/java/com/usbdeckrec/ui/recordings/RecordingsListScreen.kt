package com.usbdeckrec.ui.recordings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbdeckrec.data.repository.RecordingRepository
import com.usbdeckrec.model.RecordedTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Factory for creating [RecordingsViewModel] with its dependencies.
 */
class RecordingsViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingsViewModel::class.java)) {
            return RecordingsViewModel(
                recordingRepository = RecordingRepository(context),
                appContext = context.applicationContext
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * Recordings list screen showing all saved recordings with swipe-to-delete,
 * share functionality, and an in-app audio player with play/pause/stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: RecordingsViewModel = viewModel(
        factory = RecordingsViewModelFactory(context)
    )

    val recordings by viewModel.recordings.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Permission launcher: requests storage read access so we can scan the
    // Music/USB DeckRec/ folder for orphaned recordings after a reinstall.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onStoragePermissionGranted()
        }
    }

    // Auto-dismiss playback error after 4 seconds
    val errorMessage = playbackState.errorMessage
    if (errorMessage != null) {
        LaunchedEffect(errorMessage) {
            delay(4000)
            viewModel.clearPlaybackError()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Recordings",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            // In-app audio player bar — appears when a track is loaded
            AnimatedVisibility(
                visible = playbackState.currentRecording != null,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                PlaybackBar(
                    playbackState = playbackState,
                    onPlayPause = {
                        playbackState.currentRecording?.let { rec ->
                            viewModel.playPause(rec)
                        }
                    },
                    onStop = { viewModel.stopPlayback() },
                    onSeek = { pos -> viewModel.seekTo(pos) }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Playback error banner
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (recordings.isEmpty()) {
                // Empty state — prompt to grant permission if needed
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Scanning for recordings…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!viewModel.storagePermissionGranted) {
                            // Permission not granted — show request button
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No recordings found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Grant storage access to see your\npreviously saved recordings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(
                                        RecordingsViewModel.REQUIRED_STORAGE_PERMISSION
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant access")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Files are saved in your device's Music folder under:\nMusic/USB DeckRec/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            // Permission granted but no files
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No recordings yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Recordings will appear here\nafter you finish a session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Files are saved in your device's Music folder under:\nMusic/USB DeckRec/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = recordings,
                        key = { it.id }
                    ) { recording ->
                        RecordingListItem(
                            recording = recording,
                            onDelete = { viewModel.deleteRecording(recording) },
                            onShare = { shareRecording(context, recording) },
                            onPlayPause = { viewModel.playPause(recording) },
                            isNowPlaying = playbackState.currentRecording?.id == recording.id
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Files are saved in your device's Music folder under:\nMusic/USB DeckRec/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Bottom Playback Bar ──────────────────────────────────────────────

@Composable
private fun PlaybackBar(
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit
) {
    // Local slider state that only tracks the user's finger position during
    // a drag. While the user is dragging we show [sliderProgress] instead of
    // [playbackState.currentPositionMs] so the slider follows the finger
    // smoothly. When the drag finishes we commit via onSeek().
    var sliderProgress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = if (isDragging) {
        sliderProgress
    } else if (playbackState.durationMs > 0) {
        playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()
    } else {
        0f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Track name
            playbackState.currentRecording?.let { rec ->
                Text(
                    text = rec.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Seek slider — the [onValueChange] only updates local UI state
            // (slider thumb + time text) while dragging, without touching
            // MediaPlayer. The real [mediaPlayer.seekTo] is called only once
            // when the user releases the thumb in [onValueChangeFinished].
            // This avoids flooding MediaPlayer with overlapping asynchronous
            // seeks that race each other and cause the position to snap back.
            Slider(
                value = progress,
                onValueChange = { fraction ->
                    isDragging = true
                    sliderProgress = fraction
                },
                onValueChangeFinished = {
                    isDragging = false
                    // Commit the final drag position as a real seek.
                    onSeek((sliderProgress * playbackState.durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            // Time labels + control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Current position — shows the drag-local preview while the
                // user is sliding, otherwise the real playback position.
                Text(
                    text = if (isDragging) {
                        formatDurationShort(
                            (sliderProgress * playbackState.durationMs).toLong()
                        )
                    } else {
                        formatDurationShort(playbackState.currentPositionMs)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Stop button
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // Play / Pause button
                    Button(
                        onClick = onPlayPause,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying)
                                Icons.Filled.Pause
                            else
                                Icons.Filled.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (playbackState.isPlaying) "Pause" else "Play",
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Total duration
                Text(
                    text = formatDurationShort(playbackState.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Recording List Item ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingListItem(
    recording: RecordedTrack,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onPlayPause: () -> Unit,
    isNowPlaying: Boolean
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isNowPlaying)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            onClick = onPlayPause
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File icon
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (isNowPlaying)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Info column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isNowPlaying)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatRecordingInfo(recording),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isNowPlaying)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Play / Pause button
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isNowPlaying)
                            Icons.Filled.Pause
                        else
                            Icons.Filled.PlayArrow,
                        contentDescription = if (isNowPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Share button
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Helper Functions ─────────────────────────────────────────────────

/**
 * Share a recording file via Android's share sheet.
 */
private fun shareRecording(context: Context, recording: RecordedTrack) {
    try {
        // On Android 10+ use the MediaStore Content URI directly (scoped storage).
        // On older versions use FileProvider with the direct file path.
        val useContentUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val uri: Uri?

        if (useContentUri && recording.contentUri != null) {
            uri = Uri.parse(recording.contentUri)
        } else {
            val file = File(recording.filePath)
            if (!file.exists()) return
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "Share Recording")
        )
    } catch (_: Exception) {
        // File sharing failed silently
    }
}

/**
 * Format recording metadata into a single-line summary string.
 */
private fun formatRecordingInfo(recording: RecordedTrack): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    val date = Date(recording.dateCreated)
    val dateStr = dateFormat.format(date)

    val durationStr = formatDuration(recording.durationMs)
    val sizeStr = formatFileSize(recording.fileSizeBytes)

    return "$dateStr  ·  $durationStr  ·  $sizeStr  ·  ${recording.format}"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}m ${seconds}s"
}

/**
 * Short duration format for playback bar: mm:ss.
 */
private fun formatDurationShort(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
