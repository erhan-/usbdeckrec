package com.usbdeckrec.ui.recording

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbdeckrec.audio.AudioEngineManager
import com.usbdeckrec.data.repository.RecordingRepository
import com.usbdeckrec.data.repository.SettingsRepository
import com.usbdeckrec.model.RecordingStatus
import com.usbdeckrec.usb.UsbDeviceManager
import kotlinx.coroutines.launch

/**
 * Factory for creating [RecordingViewModel] with its dependencies.
 */
class RecordingViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingViewModel::class.java)) {
            return RecordingViewModel(
                context = context,
                audioEngineManager = AudioEngineManager.getInstance(context),
                usbDeviceManager = UsbDeviceManager(context),
                recordingRepository = RecordingRepository(context),
                settingsRepository = SettingsRepository(context)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * Main recording screen composable.
 *
 * State-driven UI based on [RecordingStatus]:
 * - Idle      → Shows "Connect a DJ Mixer" message
 * - Connected → Shows "Ready to Record" with device badge
 * - Recording → Shows VU meters, timer, recording indicator, slide-to-stop
 * - Paused    → Shows paused state with resume/stop options
 * - Error     → Shows error message
 *
 * A scrollable debug log viewer is displayed at the bottom of the screen
 * for on-device troubleshooting without adb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateToRecordings: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: RecordingViewModel = viewModel(
        factory = RecordingViewModelFactory(context)
    )

    val uiState by viewModel.uiState.collectAsState()
    val levelData by viewModel.levelMeter.collectAsState()
    val elapsedMs by viewModel.elapsedTime.collectAsState()
    val debugLogs by viewModel.debugLogs.collectAsState()
    val lastCompletedTrack by viewModel.lastCompletedTrack.collectAsState()
    val overrunCount by viewModel.overrunCount.collectAsState()
    val hasOverrunWarning by viewModel.hasOverrunWarning.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-navigate to recordings once after a track is completed,
    // then immediately consume the event so Back doesn't re-trigger it.
    LaunchedEffect(lastCompletedTrack) {
        if (lastCompletedTrack != null) {
            onNavigateToRecordings()
            viewModel.consumeCompletedTrack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "USB DeckRec",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToRecordings) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "Recordings"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Overrun warning banner ──────────────────────────────
            AnimatedVisibility(
                visible = hasOverrunWarning,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                OverrunWarningBanner(
                    overrunCount = overrunCount,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Main content area — takes up remaining space above logs
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (uiState.status) {
                    RecordingStatus.Idle -> {
                        IdleContent()
                    }

                    RecordingStatus.Connected -> {
                        ConnectedContent(
                            deviceName = uiState.connectedDeviceName,
                            totalChannels = uiState.totalChannels,
                            masterChannels = uiState.masterChannels,
                            sampleRate = uiState.sampleRate,
                            bitDepth = uiState.bitDepth,
                            levelData = levelData,
                            captureMode = uiState.captureMode,
                            isPhonePlaybackEnabled = uiState.isPhonePlaybackEnabled,
                            onTogglePhonePlayback = { viewModel.togglePhonePlayback() },
                            onStartRecording = { viewModel.startRecording() }
                        )
                    }

                    RecordingStatus.Recording -> {
                        RecordingContent(
                            leftAmplitude = levelData.leftPeak,
                            rightAmplitude = levelData.rightPeak,
                            elapsedMs = elapsedMs,
                            sampleRate = uiState.sampleRate,
                            bitDepth = uiState.bitDepth,
                            isPhonePlaybackEnabled = uiState.isPhonePlaybackEnabled,
                            isSaving = uiState.isSaving,
                            onTogglePhonePlayback = { viewModel.togglePhonePlayback() },
                            onStopConfirmed = {
                                coroutineScope.launch {
                                    viewModel.stopRecording()
                                }
                            }
                        )
                    }

                    RecordingStatus.Paused -> {
                        PausedContent(
                            elapsedMs = elapsedMs,
                            isSaving = uiState.isSaving,
                            onResume = { viewModel.pauseRecording() },
                            onStop = {
                                coroutineScope.launch {
                                    viewModel.stopRecording()
                                }
                            }
                        )
                    }

                    RecordingStatus.Error -> {
                        ErrorContent(
                            errorMessage = uiState.errorMessage
                        )
                    }
                }
            }

            // ── Debug log viewer (toggleable in Settings) ─────────────
            val settingsRepository = remember { SettingsRepository(context) }
            val showDebugLog by remember { mutableStateOf(settingsRepository.isDebugLogVisible()) }
            if (showDebugLog) {
                DebugLogPanel(
                    logs = debugLogs,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Scrollable debug log panel displayed at the bottom of the recording screen.
 * Shows the last N log entries from [DebugLogger] for on-device troubleshooting.
 *
 * Auto-scrolls to the latest entry ONLY when the user is at or near the bottom
 * of the list. If the user has scrolled up to read earlier entries, new messages
 * do NOT force the list to scroll down.
 */
@Composable
private fun DebugLogPanel(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Smart auto-scroll: only follow when user is at the bottom of the list
    LaunchedEffect(logs.size) {
        val canScrollForward = listState.canScrollForward
        if (!canScrollForward && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier.heightIn(max = 150.dp),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E) // dark background like a terminal
        )
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug Log",
                    color = Color(0xFF00FF00),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${logs.size} entries",
                    color = Color(0xFF888888),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Log entries
            if (logs.isEmpty()) {
                Text(
                    text = "Waiting for log entries...",
                    color = Color(0xFF666666),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(logs) { entry ->
                        Text(
                            text = entry,
                            color = Color(0xFFCCCCCC),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 10.sp,
                            modifier = Modifier.padding(vertical = 0.5.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Status-specific content composables ───────────────────────────────

@Composable
private fun IdleContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connect a DJ Mixer",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Plug in a supported USB audio mixer\nto start recording",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ConnectedContent(
    deviceName: String?,
    totalChannels: Int,
    masterChannels: String?,
    sampleRate: Int,
    bitDepth: Int,
    levelData: LevelData = LevelData(),
    captureMode: String? = null,
    isPhonePlaybackEnabled: Boolean = false,
    onTogglePhonePlayback: () -> Unit,
    onStartRecording: () -> Unit
) {

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Device info badge showing connected mixer details
        val channelsDetail = buildString {
            append("${totalChannels} channels · ")
            append(masterChannels ?: "Master: Ch 1/2")
        }
        DeviceInfoBadge(
            profile = null,
            modifier = Modifier.fillMaxWidth(),
            resolvedName = deviceName,
            resolvedChannels = channelsDetail,
            captureMode = captureMode
        )

        // Live VU meter (incoming level before recording)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Incoming Signal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                StereoLevelMeter(
                    leftAmplitude = levelData.leftPeak,
                    rightAmplitude = levelData.rightPeak,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Phone playback (Monitor) button
        Button(
            onClick = onTogglePhonePlayback,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPhonePlaybackEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (isPhonePlaybackEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Headset,
                    contentDescription = "Monitor on Phone",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPhonePlaybackEnabled) "Stop Listening on Phone" else "Listen on Phone (Monitor)",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Ready indicator
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Ready to Record",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatRecordingSpec(sampleRate, bitDepth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Record button — tap to start
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF44336))
                        .clickable(onClick = onStartRecording),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingContent(
    leftAmplitude: Float,
    rightAmplitude: Float,
    elapsedMs: Long,
    sampleRate: Int,
    bitDepth: Int,
    isPhonePlaybackEnabled: Boolean = false,
    isSaving: Boolean = false,
    onTogglePhonePlayback: () -> Unit,
    onStopConfirmed: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Elapsed time counter
        TimeDisplay(elapsedMs = elapsedMs)

        // Recording indicator (red pulsing dot)
        RecordingIndicator()

        Spacer(modifier = Modifier.height(8.dp))

        // VU Meter
        StereoLevelMeter(
            leftAmplitude = leftAmplitude,
            rightAmplitude = rightAmplitude,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Format badge
        FormatBadge(sampleRate = sampleRate, bitDepth = bitDepth)

        // Phone playback (Monitor) button during recording
        Button(
            onClick = onTogglePhonePlayback,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPhonePlaybackEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isPhonePlaybackEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Headset,
                    contentDescription = "Monitor on Phone",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPhonePlaybackEnabled) "Stop Listening on Phone" else "Listen on Phone (Monitor)",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Slide to stop button — shows saving indicator when isSaving is true
        SlideToStopButton(
            isSaving = isSaving,
            onStopConfirmed = onStopConfirmed
        )
    }
}

@Composable
private fun PausedContent(
    elapsedMs: Long,
    isSaving: Boolean = false,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Recording Paused",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        TimeDisplay(elapsedMs = elapsedMs)
        Spacer(modifier = Modifier.height(8.dp))
        if (isSaving) {
            SlideToStopButton(
                isSaving = true,
                onStopConfirmed = {} // no-op when already saving
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Resume button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onResume),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                // Stop button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF44336))
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    errorMessage: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = errorMessage ?: "An unknown error occurred",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Shared sub-composables ────────────────────────────────────────────

@Composable
private fun TimeDisplay(elapsedMs: Long) {
    val totalSeconds = elapsedMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    Text(
        text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun RecordingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(0xFFF44336).copy(alpha = alpha))
        )
        Text(
            text = "REC",
            color = Color(0xFFF44336),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun FormatBadge(sampleRate: Int, bitDepth: Int) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = formatRecordingSpec(sampleRate, bitDepth),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatRecordingSpec(sampleRate: Int, bitDepth: Int): String {
    val srKHz = sampleRate / 1000f
    return "FLAC ${"%.1f".format(srKHz)}kHz/$bitDepth"
}

// ── Overrun warning banner ────────────────────────────────────────────

/**
 * Amber/orange warning banner displayed when the native audio engine
 * detects buffer overruns (dropped audio frames).
 *
 * Persists until the recording is stopped and a new session begins.
 */
@Composable
private fun OverrunWarningBanner(
    overrunCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0) // amber/light orange
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Overrun warning",
                tint = Color(0xFFE65100), // dark orange
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Audio buffer overrun detected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBF360C) // deep orange/red
                )
                Text(
                    text = "$overrunCount audio buffer overflow(s) detected — some audio may have been lost",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5D4037), // dark brown
                    lineHeight = 16.sp
                )
            }
        }
    }
}
