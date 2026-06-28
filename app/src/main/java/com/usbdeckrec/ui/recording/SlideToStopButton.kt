package com.usbdeckrec.ui.recording

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A slide-to-stop recording button.
 *
 * Displays a track with a red draggable handle. When the user slides the
 * handle to the end of the track, [onStopConfirmed] is triggered and the
 * handle snaps back to the start position.
 *
 * When [isSaving] is true, the slider is replaced with a saving indicator
 * showing "Saving..." text and a circular progress animation.
 *
 * Visual design (spec 7.5):
 * - 280dp wide track
 * - 56dp red circle handle with a stop icon
 * - "SLIDE TO STOP RECORDING" text centered on the track
 */
@Composable
fun SlideToStopButton(
    isSaving: Boolean = false,
    onStopConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSaving) {
        SavingIndicator(modifier = modifier)
        return
    }

    val density = LocalDensity.current
    val trackWidth = 280.dp
    val handleSize = 56.dp
    val trackHeight = 64.dp

    // Slidable offset in pixels
    var offsetX by remember { mutableStateOf(0f) }

    // Convert dimensions to px for gesture calculations
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val handleSizePx = with(density) { handleSize.toPx() }

    // Maximum drag distance (track width minus handle size)
    val maxDrag = trackWidthPx - handleSizePx

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF333333)),
        contentAlignment = Alignment.CenterStart
    ) {
        // "SLIDE TO STOP RECORDING" text centered on the track
        Text(
            text = "SLIDE TO STOP RECORDING",
            color = Color(0xFF999999),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        )

        // Red draggable handle
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .size(handleSize)
                .clip(CircleShape)
                .background(Color(0xFFF44336))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap back regardless of position
                            offsetX = 0f
                        },
                        onDragCancel = {
                            offsetX = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxDrag)

                            // If dragged far enough, trigger stop
                            if (offsetX >= maxDrag) {
                                onStopConfirmed()
                                offsetX = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Stop recording",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Saving indicator shown while the recording file is being finalized.
 *
 * Displays a "Saving..." label with a spinning [CircularProgressIndicator]
 * on the same track dimensions as the slide-to-stop button for a seamless
 * visual transition.
 */
@Composable
private fun SavingIndicator(
    modifier: Modifier = Modifier
) {
    val trackWidth = 280.dp
    val trackHeight = 64.dp

    val infiniteTransition = rememberInfiniteTransition(label = "savingPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "savingAlpha"
    )

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF2E7D32)), // dark green background
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 2.dp,
                strokeCap = StrokeCap.Round
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Saving...",
                color = Color.White.copy(alpha = alpha),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}
