package com.usbdeckrec.midi

/**
 * Tracks fader state transitions for Pioneer DJM mixers and generates
 * track marker events on meaningful fader movements.
 *
 * Monitors channel faders (CC 7, 8) and crossfader (CC 9).
 * When a fader transitions from 0 to >10 or from >10 to 0,
 * the [onTrackMarker] callback is invoked with the triggering MidiEvent.
 */
class FaderAutomationHandler(
    private val onTrackMarker: (MidiEvent) -> Unit
) {
    private val faderStates = mutableMapOf<Int, Int>()

    fun handleMidiEvent(event: MidiEvent) {
        when (event) {
            is MidiEvent.ControlChange -> {
                val lastValue = faderStates[event.controller] ?: -1
                val transition = (lastValue == 0 && event.value > 10) ||
                        (lastValue > 10 && event.value == 0)

                if (transition && event.controller in listOf(7, 8, 9)) {
                    onTrackMarker(event)
                }
                faderStates[event.controller] = event.value
            }
            else -> { /* ignore non-control-change events */ }
        }
    }

    fun getFaderState(controller: Int): Int = faderStates[controller] ?: -1

    fun reset() {
        faderStates.clear()
    }
}
