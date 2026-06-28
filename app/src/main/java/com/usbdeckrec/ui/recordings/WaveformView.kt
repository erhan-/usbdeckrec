package com.usbdeckrec.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Simple waveform visualization for a recorded audio track.
 *
 * Draws a white line-based waveform showing amplitude over time.
 * If [audioData] is null, a placeholder message is shown instead.
 *
 * @param audioData  Float array of sample amplitudes normalized to [-1.0, 1.0],
 *                   or null if no waveform data is available.
 * @param modifier   Modifier for the composable.
 */
@Composable
fun WaveformView(
    audioData: FloatArray?,
    modifier: Modifier = Modifier
) {
    if (audioData == null || audioData.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No waveform data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(vertical = 8.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val centerY = canvasHeight / 2f

            val step = (audioData.size.toFloat() / canvasWidth).coerceAtLeast(1f)

            val path = Path()
            path.moveTo(0f, centerY)

            for (x in 0 until canvasWidth.toInt()) {
                val sampleIndex = (x * step).toInt().coerceAtMost(audioData.size - 1)
                val amplitude = audioData[sampleIndex].coerceIn(-1f, 1f)
                val y = centerY - (amplitude * (canvasHeight / 2f))
                path.lineTo(x.toFloat(), y)
            }

            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 2f)
            )
        }
    }
}
