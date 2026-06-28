package com.usbdeckrec.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbdeckrec.audio.MixerProfile

/**
 * Device information badge showing the connected mixer's profile and
 * active audio capture mode (USB Direct vs Oboe/AAudio).
 *
 * Displays the mixer model name, total channel count, master channel
 * labeling, and mode indicator. Uses a [Surface] with
 * [MaterialTheme.colorScheme.primaryContainer] color and rounded corners.
 *
 * For mixers with a dedicated record bus (e.g., DJM-900NXS2), the label
 * reads "Master Rec: Ch X/Y". For all others it reads "Master: Ch X/Y".
 *
 * @param profile The resolved [MixerProfile] for the connected device, or null.
 * @param captureMode Human-readable capture mode string ("USB Direct", "Oboe/AAudio", "Idle")
 * @param resolvedName Optional name override from ViewModel state
 * @param resolvedChannels Optional channel info string from ViewModel state
 */
@Composable
fun DeviceInfoBadge(
    profile: MixerProfile?,
    modifier: Modifier = Modifier,
    captureMode: String? = null,
    resolvedName: String? = null,
    resolvedChannels: String? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // USB text badge
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "USB",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = if (resolvedName != null) resolvedName
                           else profile?.modelName ?: "Unknown Device",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                if (profile != null) {
                    Text(
                        text = buildString {
                            append("${profile.totalChannels} channels")
                            append(" · ")
                            append(
                                if (profile.hasDedicatedRecBus) {
                                    "Master Rec: Ch ${profile.masterLeftChannel + 1}/${profile.masterRightChannel + 1}"
                                } else {
                                    "Master: Ch ${profile.masterLeftChannel + 1}/${profile.masterRightChannel + 1}"
                                }
                            )
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                } else if (resolvedChannels != null) {
                    Text(
                        text = resolvedChannels,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        text = "2 channels · Master: Ch 1/2",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }

                // Capture mode indicator
                if (captureMode != null && captureMode != "Idle") {
                    Spacer(modifier = Modifier.size(2.dp))
                    val modeColor = if (captureMode == "USB Direct")
                        Color(0xFFE65100) // orange for direct USB
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                    Text(
                        text = captureMode,
                        color = modeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
