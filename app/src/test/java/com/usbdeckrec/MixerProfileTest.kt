package com.usbdeckrec

import com.usbdeckrec.audio.MixerProfileDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MixerProfileTest {

    @Test
    fun `DJM-900Nexus profile has 4 channels and master on 1-2`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0155)
        assertNotNull(profile)
        assertEquals("DJM-900Nexus", profile!!.modelName)
        assertEquals(4, profile.totalChannels)
        assertEquals(0, profile.masterLeftChannel)
        assertEquals(1, profile.masterRightChannel)
        assertFalse(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-900NXS2 profile has 10 channels and master on 9-10`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0158)
        assertNotNull(profile)
        assertEquals("DJM-900NXS2", profile!!.modelName)
        assertEquals(10, profile.totalChannels)
        assertEquals(8, profile.masterLeftChannel)
        assertEquals(9, profile.masterRightChannel)
        assertTrue(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-900SRT profile has 2 channels and master on 1-2`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0156)
        assertNotNull(profile)
        assertEquals("DJM-900SRT", profile!!.modelName)
        assertEquals(2, profile.totalChannels)
        assertEquals(0, profile.masterLeftChannel)
        assertEquals(1, profile.masterRightChannel)
        assertFalse(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-750MK2 profile has 4 channels and master on 1-2`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0157)
        assertNotNull(profile)
        assertEquals("DJM-750MK2", profile!!.modelName)
        assertEquals(4, profile.totalChannels)
        assertEquals(0, profile.masterLeftChannel)
        assertEquals(1, profile.masterRightChannel)
        assertFalse(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-A9 profile has 10 channels and master on 9-10`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x016A)
        assertNotNull(profile)
        assertEquals("DJM-A9", profile!!.modelName)
        assertEquals(10, profile.totalChannels)
        assertEquals(8, profile.masterLeftChannel)
        assertEquals(9, profile.masterRightChannel)
        assertTrue(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-V10 profile has 10 channels and master on 9-10`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0163)
        assertNotNull(profile)
        assertEquals("DJM-V10", profile!!.modelName)
        assertEquals(10, profile.totalChannels)
        assertEquals(8, profile.masterLeftChannel)
        assertEquals(9, profile.masterRightChannel)
        assertTrue(profile.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-S11 profile has 10 channels and master on 9-10`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x016C)
        assertNotNull(profile)
        assertEquals("DJM-S11", profile!!.modelName)
        assertEquals(10, profile.totalChannels)
        assertEquals(8, profile.masterLeftChannel)
        assertEquals(9, profile.masterRightChannel)
        assertTrue(profile.hasDedicatedRecBus)
    }

    @Test
    fun `unknown device returns null from database`() {
        val profile = MixerProfileDatabase.getProfile(0x1234, 0x5678)
        assertNull(profile)
    }

    @Test
    fun `generic fallback profile has 2 channels`() {
        val profile = MixerProfileDatabase.getGenericProfile()
        assertEquals(2, profile.totalChannels)
        assertEquals(0, profile.masterLeftChannel)
        assertEquals(1, profile.masterRightChannel)
        assertEquals("Generic USB Audio Device", profile.modelName)
        assertFalse(profile.hasDedicatedRecBus)
        assertEquals(-1, profile.midiInterfaceIndex)
    }

    @Test
    fun `getAllProfiles returns all 7 profiles`() {
        val allProfiles = MixerProfileDatabase.getAllProfiles()
        assertEquals(7, allProfiles.size)
        val modelNames = allProfiles.map { it.modelName }.toSet()
        assertTrue(modelNames.contains("DJM-900Nexus"))
        assertTrue(modelNames.contains("DJM-900NXS2"))
        assertTrue(modelNames.contains("DJM-900SRT"))
        assertTrue(modelNames.contains("DJM-750MK2"))
        assertTrue(modelNames.contains("DJM-A9"))
        assertTrue(modelNames.contains("DJM-V10"))
        assertTrue(modelNames.contains("DJM-S11"))
    }

    @Test
    fun `profile is found by any of its supported vendor IDs`() {
        // All three vendor IDs should find the DJM-900Nexus (0x0155)
        assertNotNull(MixerProfileDatabase.getProfile(0x04B4, 0x0155))
        assertNotNull(MixerProfileDatabase.getProfile(0x2B73, 0x0155))
        assertNotNull(MixerProfileDatabase.getProfile(0x08E4, 0x0155))

        // All three vendor IDs should find the DJM-900NXS2 (0x0158)
        assertNotNull(MixerProfileDatabase.getProfile(0x04B4, 0x0158))
        assertNotNull(MixerProfileDatabase.getProfile(0x2B73, 0x0158))
        assertNotNull(MixerProfileDatabase.getProfile(0x08E4, 0x0158))
    }

    @Test
    fun `wrong vendor ID for a known product returns null`() {
        // 0x04B4 is a valid vendor for DJM-900Nexus, but 0xFFFF is not
        assertNull(MixerProfileDatabase.getProfile(0xFFFF, 0x0155))
    }

    @Test
    fun `DJM-900NXS2 has dedicated rec bus`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0158)
        assertNotNull(profile)
        assertTrue(profile!!.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-900Nexus does not have dedicated rec bus`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0155)
        assertNotNull(profile)
        assertFalse(profile!!.hasDedicatedRecBus)
    }

    @Test
    fun `DJM-S11 has Serato notes`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x016C)
        assertNotNull(profile)
        assertTrue(profile!!.notes.contains("Serato"))
    }

    @Test
    fun `DJM-900SRT has limited MIDI notes`() {
        val profile = MixerProfileDatabase.getProfile(0x04B4, 0x0156)
        assertNotNull(profile)
        assertTrue(profile!!.notes.contains("limited MIDI"))
    }
}
