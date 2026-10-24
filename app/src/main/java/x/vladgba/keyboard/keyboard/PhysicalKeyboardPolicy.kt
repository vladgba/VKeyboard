package x.vladgba.keyboard.keyboard

import android.view.InputDevice

object PhysicalKeyboardPolicy {
    fun qualifies(virtual: Boolean, sources: Int, type: Int): Boolean = !virtual &&
        sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD && type == InputDevice.KEYBOARD_TYPE_ALPHABETIC

    fun connected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { qualifies(it.isVirtual, it.sources, it.keyboardType) } == true
    }
}
