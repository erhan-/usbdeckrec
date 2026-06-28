package com.usbdeckrec.ui.recording

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private val MeterBackground = Color(0xFF121212)
private val MeterBorder = Color(0xFF333333)
private val LabelColor = Color(0xFF999999)
private val GreenColor = Color(0xFF4CAF50)
private val YellowColor = Color(0xFFFFC107)
private val RedColor = Color(0xFFF44336)

/**
 * Stereo VU meter with logarithmic dB scale.
 *
 * Each channel is rendered as a vertical bar where the amplitude (0.0 to 1.0)
 * is converted to dB and mapped to a visual fraction. The bar is color-coded:
 * - Green:  < 70% of the range (i.e. > -18 dB)
 * - Yellow: 70–90% (-18 to -6 dB)
 * - Red:    > 90% (> -6 dB, near clipping)
 *
 * @param leftAmplitude  Amplitude for the left channel (0.0 to 1.0)
 * @param rightAmplitude Amplitude for the right channel (0.0 to 1.0)
 * @param modifier       Modifier for the composable
 */
@Composable
fun StereoLevelMeter(
    leftAmplitude: Float,
    rightAmplitude: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MeterBackground, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Left channel
            LevelBar(
                label = "L",
                amplitude = leftAmplitude,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Right channel
            LevelBar(
                label = "R",
                amplitude = rightAmplitude,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // dB scale labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0 dB", color = LabelColor, fontSize = 10.sp)
            Text("-20", color = LabelColor, fontSize = 10.sp)
            Text("-40", color = LabelColor, fontSize = 10.sp)
            Text("-60", color = LabelColor, fontSize = 10.sp)
        }
    }
}

/**
 * A single vertical meter bar with label.
 *
 * Converts linear amplitude to dB and maps the range [-60, 0] dB to
 * a visual fill fraction [0, 1]. Color coding is applied based on
 * how full the bar is.
 */
@Composable
private fun LevelBar(
    label: String,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label above the bar
        Text(
            text = label,
            color = LabelColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // VU meter bar
        val dbLevel = linearToDb(amplitude)
        val fillFraction = dbToFillFraction(dbLevel)
        val barColor = getBarColor(fillFraction)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            drawVuMeterBar(
                fillFraction = fillFraction,
                barColor = barColor
            )
        }
    }
}

/**
 * Convert a linear amplitude [0.0, 1.0] to dB.
 * Clamps to a minimum of 0.000001 to avoid log10(0).
 */
private fun linearToDb(amplitude: Float): Float {
    val clamped = amplitude.coerceAtLeast(0.000001f)
    return 20f * log10(clamped)
}

/**
 * Map a dB value in the range [-60, 0] to a visual fill fraction [0, 1].
 * Values below -60 dB are mapped to 0.
 */
private fun dbToFillFraction(db: Float): Float {
    val normalized = (db + 60f) / 60f  // -60 dB -> 0, 0 dB -> 1
    return normalized.coerceIn(0f, 1f)
}

/**
 * Returns the bar color based on fill fraction:
 * - < 70%  -> Green (safe)
 * - 70–90% -> Yellow (caution)
 * - > 90%  -> Red (clipping)
 */
private fun getBarColor(fillFraction: Float): Color {
    return when {
        fillFraction < 0.70f -> GreenColor
        fillFraction < 0.90f -> YellowColor
        else -> RedColor
    }
}

/**
 * Draw a single VU meter bar with background, border, and filled region.
 */
private fun DrawScope.drawVuMeterBar(
    fillFraction: Float,
    barColor: Color
) {
    val barWidth = size.width
    val barHeight = size.height
    val cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())

    // Background
    drawRoundRect(
        color = Color(0xFF1E1E1E),
        topLeft = Offset.Zero,
        size = Size(barWidth, barHeight),
        cornerRadius = cornerRadius
    )

    // Filled region (from bottom up)
    val fillHeight = barHeight * fillFraction.coerceIn(0f, 1f)
    if (fillHeight > 0f) {
        drawRoundRect(
            color = barColor,
            topLeft = Offset(0f, barHeight - fillHeight),
            size = Size(barWidth, fillHeight),
            cornerRadius = cornerRadius
        )
    }

    // Border
    drawRoundRect(
        color = MeterBorder,
        topLeft = Offset.Zero,
        size = Size(barWidth, barHeight),
        cornerRadius = cornerRadius,
        style = Stroke(width = 1.dp.toPx())
    )
}
