package com.usbdeckrec

import com.usbdeckrec.midi.FaderAutomationHandler
import com.usbdeckrec.midi.MidiEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FaderAutomationHandlerTest {

    @Test
    fun `fader transition from 0 to greater than 10 triggers marker`() {
        var triggered = false
        var capturedEvent: MidiEvent? = null
        val handler = FaderAutomationHandler { event ->
            triggered = true
            capturedEvent = event
        }

        // Start with channel fader 1 (CC 7) at position 0
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 0))
        assertFalse(triggered, "Initial setting should not trigger")

        // Move fader to position 64 (past the >10 threshold)
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 64))
        assertTrue(triggered, "Transition 0 -> >10 should trigger marker")
        assertTrue(capturedEvent is MidiEvent.ControlChange)
        val cc = capturedEvent as MidiEvent.ControlChange
        assertEquals(7, cc.controller)
        assertEquals(64, cc.value)
    }

    @Test
    fun `fader transition from greater than 10 to 0 triggers marker`() {
        var triggerCount = 0
        var capturedEvent: MidiEvent? = null
        val handler = FaderAutomationHandler { event ->
            triggerCount++
            capturedEvent = event
        }

        // Start with channel fader 2 (CC 8) at position 64
        handler.handleMidiEvent(MidiEvent.ControlChange(8, 64))
        assertEquals(0, triggerCount, "Initial setting should not trigger")

        // Move fader to 0
        handler.handleMidiEvent(MidiEvent.ControlChange(8, 0))
        assertEquals(1, triggerCount, "Transition >10 -> 0 should trigger marker")
        assertTrue(capturedEvent is MidiEvent.ControlChange)
        val cc = capturedEvent as MidiEvent.ControlChange
        assertEquals(8, cc.controller)
        assertEquals(0, cc.value)
    }

    @Test
    fun `small fader movement does not trigger marker (0 to 5)`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // Start at 0
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 0))
        // Small movement to 5 - below the >10 threshold
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 5))
        assertEquals(0, triggerCount, "Small movement 0->5 should NOT trigger")
    }

    @Test
    fun `non-fader CC does not trigger marker`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // EQ knob (CC 12) is NOT in the fader list (7, 8, 9)
        handler.handleMidiEvent(MidiEvent.ControlChange(12, 0))
        handler.handleMidiEvent(MidiEvent.ControlChange(12, 64))
        assertEquals(0, triggerCount, "Non-fader CC should not trigger marker")
    }

    @Test
    fun `sustained fader position does not create repeated markers`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // Initial state: fader at 0
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 0))
        assertEquals(0, triggerCount)

        // Move to 64 - triggers one marker
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 64))
        assertEquals(1, triggerCount)

        // Same position again
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 64))
        assertEquals(1, triggerCount, "Same position should not retrigger")

        // Slight adjustment (still >10)
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 80))
        assertEquals(1, triggerCount, "Movement within >10 range should not retrigger")
    }

    @Test
    fun `crossfader transition triggers marker`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // Crossfader (CC 9) is in the fader list
        handler.handleMidiEvent(MidiEvent.ControlChange(9, 0))
        assertEquals(0, triggerCount)

        // Move crossfader to full
        handler.handleMidiEvent(MidiEvent.ControlChange(9, 127))
        assertEquals(1, triggerCount, "Crossfader 0->127 should trigger marker")
    }

    @Test
    fun `note on event does not trigger marker`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        handler.handleMidiEvent(MidiEvent.NoteOn(36, 100, true))
        assertEquals(0, triggerCount, "NoteOn should not trigger marker")
    }

    @Test
    fun `unknown event does not trigger marker`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        handler.handleMidiEvent(MidiEvent.Unknown(0x80))
        assertEquals(0, triggerCount, "Unknown event should not trigger marker")
    }

    @Test
    fun `multiple faders tracked independently`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // Move fader 1 (CC 7) from 0 -> 64
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 0))
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 64))
        assertEquals(1, triggerCount)

        // Move fader 2 (CC 8) from 0 -> 64
        handler.handleMidiEvent(MidiEvent.ControlChange(8, 0))
        handler.handleMidiEvent(MidiEvent.ControlChange(8, 64))
        assertEquals(2, triggerCount, "Fader 2 transition should be tracked independently")
    }

    @Test
    fun `transition from 0 to exactly 10 does not trigger`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        // The threshold is >10, so exactly 10 should NOT trigger
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 0))
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 10))
        assertEquals(0, triggerCount, "0->10 is at threshold and should NOT trigger")

        // One more to 11 should trigger
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 11))
        assertEquals(1, triggerCount, "0->11 is past threshold and SHOULD trigger")
    }

    @Test
    fun `reset clears all tracked states`() {
        var triggerCount = 0
        val handler = FaderAutomationHandler { triggerCount++ }

        handler.handleMidiEvent(MidiEvent.ControlChange(7, 64))
        assertEquals(0, triggerCount)

        handler.reset()

        // After reset, fader state is -1 so value 64 should be treated as initial
        handler.handleMidiEvent(MidiEvent.ControlChange(7, 127))
        assertEquals(0, triggerCount, "After reset, next event is initial state - no transition")
    }

    @Test
    fun `getFaderState returns correct initial and updated values`() {
        val handler = FaderAutomationHandler {}

        assertEquals(-1, handler.getFaderState(7), "Unobserved fader returns -1")

        handler.handleMidiEvent(MidiEvent.ControlChange(7, 42))
        assertEquals(42, handler.getFaderState(7))
    }
}
