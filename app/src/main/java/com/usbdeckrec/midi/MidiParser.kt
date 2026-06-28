package com.usbdeckrec.midi

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

class MidiParser {

    fun findMidiInterface(device: UsbDevice, preferredIndex: Int = -1): UsbInterface? {
        if (preferredIndex >= 0 && preferredIndex < device.interfaceCount) {
            val iface = device.getInterface(preferredIndex)
            if (isMidiInterface(iface)) return iface
        }

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (isMidiInterface(iface)) return iface
        }
        return null
    }

    private fun isMidiInterface(iface: UsbInterface): Boolean {
        if (iface.interfaceClass == 0x01 && iface.interfaceSubclass == 0x03) return true
        if (iface.interfaceClass == 0xFF) return true
        return false
    }

    fun parseMidiPacket(buffer: ByteArray): MidiEvent? {
        if (buffer.size < 4) return null

        val status = buffer[1].toInt() and 0xFF

        return when (status and 0xF0) {
            0xB0 -> {
                val controller = buffer[2].toInt() and 0xFF
                val value = buffer[3].toInt() and 0xFF
                MidiEvent.ControlChange(controller, value)
            }
            0x90 -> {
                val note = buffer[2].toInt() and 0xFF
                val velocity = buffer[3].toInt() and 0xFF
                MidiEvent.NoteOn(note, velocity, velocity > 0)
            }
            else -> MidiEvent.Unknown(status)
        }
    }
}
