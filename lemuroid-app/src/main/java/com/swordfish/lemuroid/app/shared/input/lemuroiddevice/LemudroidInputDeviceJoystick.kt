package com.swordfish.lemuroid.app.shared.input.lemuroiddevice

import android.content.Context
import android.view.InputDevice
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.RetroKey
import com.swordfish.lemuroid.app.shared.settings.GameMenuShortcut

class LemudroidInputDeviceJoystick(private val device: InputDevice) : LemuroidInputDevice {

    override fun getCustomizableKeys(): List<RetroKey> = InputDeviceManager.OUTPUT_KEYS

    override fun getDefaultBindings(): Map<InputKey, RetroKey> = emptyMap()

    override fun isSupported(): Boolean {
        return sequenceOf(
            (device.sources and SOURCE_JOYSTICK_KEYBOARD) == SOURCE_JOYSTICK_KEYBOARD,
            device.isVirtual.not(),
        ).all { it }
    }

    override fun isEnabledByDefault(appContext: Context): Boolean = false

    override fun getSupportedShortcuts(): List<GameMenuShortcut> = emptyList()

    companion object
    {
        const val SOURCE_JOYSTICK_KEYBOARD: Int =  InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD
    }
}

