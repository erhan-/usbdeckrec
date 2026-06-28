package com.usbdeckrec

import com.usbdeckrec.midi.MidiEvent
import com.usbdeckrec.midi.MidiParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MidiParserTest {

    private val parser = MidiParser()

    @Test
    fun `parse control change midi packet`() {
        // USB-MIDI packet: CIN=0xB, Cable=0, Status=0xB1 (CC, ch2), Controller=7, Value=64
        val packet = byteArrayOf(
            (0x0B shl 4).toByte(), // CIN + Cable
            0xB1.toByte(),         // Status: Control Change, channel 2
            0x07,                  // Controller: Channel Fader 1 (CC 7)
            0x40                   // Value: 64 (midpoint)
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.ControlChange)
        val cc = event as MidiEvent.ControlChange
        assertEquals(7, cc.controller)
        assertEquals(64, cc.value)
    }

    @Test
    fun `parse note on midi packet`() {
        // USB-MIDI packet: CIN=0x9, Status=0x91 (Note On, ch2), Note=36, Velocity=100
        val packet = byteArrayOf(
            (0x09 shl 4).toByte(), // CIN + Cable
            0x91.toByte(),         // Status: Note On, channel 2
            0x24,                  // Note: 36 (C2)
            0x64                   // Velocity: 100
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.NoteOn)
        val noteOn = event as MidiEvent.NoteOn
        assertEquals(36, noteOn.note)
        assertEquals(100, noteOn.velocity)
        assertTrue(noteOn.isPressed)
    }

    @Test
    fun `parse note off midi packet (velocity 0)`() {
        // USB-MIDI packet: CIN=0x9, Status=0x90 (Note On, ch1), Note=36, Velocity=0
        // Velocity=0 in MIDI convention means Note Off
        val packet = byteArrayOf(
            (0x09 shl 4).toByte(), // CIN + Cable
            0x90.toByte(),         // Status: Note On, channel 1
            0x24,                  // Note: 36 (C2)
            0x00                   // Velocity: 0 => Note Off
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.NoteOn)
        val noteOff = event as MidiEvent.NoteOn
        assertEquals(36, noteOff.note)
        assertEquals(0, noteOff.velocity)
        assertFalse(noteOff.isPressed)
    }

    @Test
    fun `parse unknown midi packet`() {
        // USB-MIDI packet with status 0x80 (Note Off - not handled by parser directly)
        // Since Note Off status (0x80) does not match 0xB0 or 0x90 masked check,
        // it falls through to Unknown
        val packet = byteArrayOf(
            (0x08 shl 4).toByte(),
            0x80.toByte(), // Note Off status (ch1)
            0x24,
            0x40
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.Unknown)
        val unknown = event as MidiEvent.Unknown
        assertEquals(0x80, unknown.status)
    }

    @Test
    fun `return null for invalid buffer (size less than 4)`() {
        val shortPacket = byteArrayOf(0x00, 0x01, 0x02) // Only 3 bytes
        val event = parser.parseMidiPacket(shortPacket)
        assertNull(event)
    }

    @Test
    fun `return null for empty buffer`() {
        val emptyPacket = ByteArray(0)
        val event = parser.parseMidiPacket(emptyPacket)
        assertNull(event)
    }

    @Test
    fun `parse control change with extreme values`() {
        // Controller 127, Value 127 (max allowed)
        val packet = byteArrayOf(
            (0x0B shl 4).toByte(),
            0xB0.toByte(),
            0x7F, // Controller: 127
            0x7F  // Value: 127
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.ControlChange)
        val cc = event as MidiEvent.ControlChange
        assertEquals(127, cc.controller)
        assertEquals(127, cc.value)
    }

    @Test
    fun `parse control change with zero values`() {
        // Controller 0, Value 0 (min)
        val packet = byteArrayOf(
            (0x0B shl 4).toByte(),
            0xB0.toByte(),
            0x00,
            0x00
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.ControlChange)
        val cc = event as MidiEvent.ControlChange
        assertEquals(0, cc.controller)
        assertEquals(0, cc.value)
    }

    @Test
    fun `crossfader CC 9 is parsed as control change`() {
        // Crossfader on CC 9, value 127 (full left or right)
        val packet = byteArrayOf(
            (0x0B shl 4).toByte(),
            0xB0.toByte(),
            0x09, // Crossfader CC
            0x7F  // Full value
        )
        val event = parser.parseMidiPacket(packet)
        assertNotNull(event)
        assertTrue(event is MidiEvent.ControlChange)
        val cc = event as MidiEvent.ControlChange
        assertEquals(9, cc.controller)
        assertEquals(127, cc.value)
    }
}
