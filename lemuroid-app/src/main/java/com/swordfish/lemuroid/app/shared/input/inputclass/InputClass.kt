package com.swordfish.lemuroid.app.shared.input.inputclass

import android.view.InputDevice
import com.swordfish.lemuroid.app.shared.input.InputKey

interface InputClass {
    fun getInputKeys(): Set<InputKey>

    fun getAxesMap(): Map<Int, Int>
}

fun InputDevice?.getInputClass(): InputClass {
    return when {
        this == null -> InputClassUnknown
        (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD -> InputClassGamePad
        (sources and InputClassJoystick.SOURCE_JOYSTICK_KEYBOARD) == InputClassJoystick.SOURCE_JOYSTICK_KEYBOARD -> InputClassJoystick
        (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD -> InputClassKeyboard
        else -> InputClassUnknown
    }
}
