package com.usbdeckrec

import com.usbdeckrec.audio.MixerProfile
import com.usbdeckrec.audio.MixerProfileDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure logic tests for the channel extraction indexing math.
 * Validates that the master channel pair is correctly selected
 * from an N-channel interleaved float buffer.
 *
 * The extraction logic mirrors the C++ ChannelExtractor::ExtractStereo
 * implementation, testing the index calculations in Kotlin.
 */

object TestAudioGenerator {

    /**
     * Generate an N-channel interleaved float buffer with test tones
     * on specified channels. Supports any channel count for multi-profile testing.
     */
    fun generateConfigurableBuffer(
        numFrames: Int,
        totalChannels: Int,
        toneLeftChannel: Int = 0,
        toneRightChannel: Int = 1,
        frequencyHz: Float = 440f,
        sampleRate: Int = 48000
    ): FloatArray {
        val buffer = FloatArray(numFrames * totalChannels)
        for (i in 0 until numFrames) {
            val sample = sin(2.0f * PI.toFloat() * frequencyHz * i / sampleRate)
            if (toneLeftChannel < totalChannels) {
                buffer[i * totalChannels + toneLeftChannel] = sample
            }
            if (toneRightChannel < totalChannels) {
                buffer[i * totalChannels + toneRightChannel] = sample
            }
        }
        return buffer
    }

    /**
     * Generate a buffer using a MixerProfile for realistic test scenarios.
     */
    fun generateFromProfile(
        numFrames: Int,
        profile: MixerProfile,
        frequencyHz: Float = 440f,
        sampleRate: Int = 48000
    ): FloatArray = generateConfigurableBuffer(
        numFrames = numFrames,
        totalChannels = profile.totalChannels,
        toneLeftChannel = profile.masterLeftChannel,
        toneRightChannel = profile.masterRightChannel,
        frequencyHz = frequencyHz,
        sampleRate = sampleRate
    )
}

/**
 * Mirrors the C++ ChannelExtractor::ExtractStereo logic in Kotlin.
 * Extracts a stereo pair from an N-channel interleaved float buffer
 * using 0-based channel indices.
 */
fun extractStereoPair(
    buffer: FloatArray,
    totalChannels: Int,
    leftChannel: Int,
    rightChannel: Int
): FloatArray {
    val numFrames = buffer.size / totalChannels
    val output = FloatArray(numFrames * 2)
    for (i in 0 until numFrames) {
        val inputOffset = i * totalChannels
        val outputOffset = i * 2
        output[outputOffset] = buffer[inputOffset + leftChannel]
        output[outputOffset + 1] = buffer[inputOffset + rightChannel]
    }
    return output
}

fun extractStereoPair(buffer: FloatArray, profile: MixerProfile): FloatArray =
    extractStereoPair(buffer, profile.totalChannels, profile.masterLeftChannel, profile.masterRightChannel)

class ChannelExtractionTest {

    @Test
    fun `extract master from 4-channel Nexus frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0155)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 100,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(100 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f }, "Master channels should have tone data")
    }

    @Test
    fun `extract master from 10-channel NXS2 frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0158)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 100,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(100 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f }, "Master rec channels (9-10) should have tone data")
    }

    @Test
    fun `extract master from 2-channel SRT frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0156)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 100,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(100 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f }, "Master channels (1-2) should have tone data")
    }

    @Test
    fun `out-of-range channel indices return empty`() {
        val buffer = TestAudioGenerator.generateConfigurableBuffer(
            numFrames = 100,
            totalChannels = 4,
            toneLeftChannel = 0,
            toneRightChannel = 1
        )
        // Extract with out-of-range indices on a 4-channel buffer
        val extracted = extractStereoPair(buffer, 4, 10, 11)
        assertEquals(100 * 2, extracted.size)
        assertTrue(extracted.all { it == 0f }, "Out-of-range indices should extract silence (zeros)")
    }

    @Test
    fun `extract from 10ch frame when tone is on channels 1-2 returns silence`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0158)!!
        // Tone placed on wrong channels (1-2 instead of 9-10)
        val buffer = TestAudioGenerator.generateConfigurableBuffer(
            numFrames = 100,
            totalChannels = profile.totalChannels,
            toneLeftChannel = 0,
            toneRightChannel = 1
        )
        val extracted = extractStereoPair(buffer, profile)
        // Master channels (8, 9) should be silent
        assertTrue(extracted.all { it == 0f }, "Wrong channels should result in silence")
    }

    @Test
    fun `extract master from DJM-750MK2 4-channel frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0157)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 200,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(200 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f })
    }

    @Test
    fun `extract master from DJM-A9 10-channel frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x016A)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 150,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(150 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f })
    }

    @Test
    fun `extract master from DJM-V10 10-channel frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0163)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 150,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(150 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f })
    }

    @Test
    fun `extract master from DJM-S11 10-channel frame`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x016C)!!
        val buffer = TestAudioGenerator.generateFromProfile(
            numFrames = 150,
            profile = profile
        )
        val extracted = extractStereoPair(buffer, profile)
        assertEquals(150 * 2, extracted.size)
        assertTrue(extracted.any { it != 0f })
    }

    @Test
    fun `extraction preserves stereo separation`() {
        // Generate a buffer where left master has a 440 Hz tone
        // and right master has silence
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0155)!!
        val buffer = TestAudioGenerator.generateConfigurableBuffer(
            numFrames = 100,
            totalChannels = profile.totalChannels,
            toneLeftChannel = profile.masterLeftChannel,
            toneRightChannel = -1 // No tone on right
        )
        val extracted = extractStereoPair(buffer, profile)

        // Left channel should have signal
        for (i in 0 until 100) {
            val left = extracted[i * 2]
            val right = extracted[i * 2 + 1]
            assertNotEquals(0f, left, "Left should have signal at frame $i")
            assertEquals(0f, right, "Right should be silent at frame $i")
        }
    }

    @Test
    fun `single frame extraction correctness`() {
        // A single frame of 4-channel data with known values
        val buffer = floatArrayOf(
            0.1f, 0.2f, 0.3f, 0.4f // One frame: ch1, ch2, ch3, ch4
        )
        // Extract master on channels 1-2 (0-based: 0, 1)
        val extracted = extractStereoPair(buffer, 4, 0, 1)
        assertEquals(2, extracted.size)
        assertEquals(0.1f, extracted[0], "Left master should be ch1 (0.1)")
        assertEquals(0.2f, extracted[1], "Right master should be ch2 (0.2)")
    }

    @Test
    fun `extraction with 10-channel NXS2 profile uses correct indices`() {
        // Single frame of 10-channel data with identifiable values per channel
        val buffer = FloatArray(10) { idx -> (idx + 1) * 0.1f }
        // DJM-900NXS2: master on channels 9-10 (0-based: 8, 9)
        val extracted = extractStereoPair(buffer, 10, 8, 9)
        assertEquals(2, extracted.size)
        assertEquals(0.9f, extracted[0], 0.001f, "Left master should be ch9 (0.9)")
        assertEquals(1.0f, extracted[1], 0.001f, "Right master should be ch10 (1.0)")
    }
}
