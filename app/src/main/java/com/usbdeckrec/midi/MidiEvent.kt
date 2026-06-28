package com.usbdeckrec.midi

sealed class MidiEvent {
    data class ControlChange(
        val controller: Int,
        val value: Int
    ) : MidiEvent()

    data class NoteOn(
        val note: Int,
        val velocity: Int,
        val isPressed: Boolean
    ) : MidiEvent()

    data class Unknown(val status: Int) : MidiEvent()
}
