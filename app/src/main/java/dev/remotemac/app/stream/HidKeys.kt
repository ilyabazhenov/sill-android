package dev.remotemac.app.stream

import android.view.KeyEvent
import dev.remotemac.protocol.Modifiers

/** Android keys to USB HID usages (page 7), which the Mac host turns into its own key codes. */
object HidKeys {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val LEFT_CONTROL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_OPTION = 0xE2
    const val LEFT_COMMAND = 0xE3

    private val byKeyCode: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, 0x04 + i)
        for (i in 1..9) put(KeyEvent.KEYCODE_0 + i, 0x1E + i - 1)
        put(KeyEvent.KEYCODE_0, 0x27)
        put(KeyEvent.KEYCODE_ENTER, ENTER); put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58)
        put(KeyEvent.KEYCODE_ESCAPE, ESCAPE)
        put(KeyEvent.KEYCODE_DEL, BACKSPACE); put(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C)
        put(KeyEvent.KEYCODE_TAB, TAB); put(KeyEvent.KEYCODE_SPACE, SPACE)
        put(KeyEvent.KEYCODE_MINUS, 0x2D); put(KeyEvent.KEYCODE_EQUALS, 0x2E)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30)
        put(KeyEvent.KEYCODE_BACKSLASH, 0x31); put(KeyEvent.KEYCODE_SEMICOLON, 0x33)
        put(KeyEvent.KEYCODE_APOSTROPHE, 0x34); put(KeyEvent.KEYCODE_GRAVE, 0x35)
        put(KeyEvent.KEYCODE_COMMA, 0x36); put(KeyEvent.KEYCODE_PERIOD, 0x37); put(KeyEvent.KEYCODE_SLASH, 0x38)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 0x39)
        for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, 0x3A + i)
        put(KeyEvent.KEYCODE_INSERT, 0x49); put(KeyEvent.KEYCODE_MOVE_HOME, 0x4A); put(KeyEvent.KEYCODE_PAGE_UP, 0x4B)
        put(KeyEvent.KEYCODE_MOVE_END, 0x4D); put(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT); put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN); put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_CTRL_LEFT, LEFT_CONTROL); put(KeyEvent.KEYCODE_CTRL_RIGHT, 0xE4)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, LEFT_SHIFT); put(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xE5)
        put(KeyEvent.KEYCODE_ALT_LEFT, LEFT_OPTION); put(KeyEvent.KEYCODE_ALT_RIGHT, 0xE6)
        put(KeyEvent.KEYCODE_META_LEFT, LEFT_COMMAND); put(KeyEvent.KEYCODE_META_RIGHT, 0xE7)
        put(KeyEvent.KEYCODE_VOLUME_MUTE, 0x7F)
    }

    fun usage(keyCode: Int): Int? = byKeyCode[keyCode]

    fun isModifier(usage: Int) = usage in 0xE0..0xE7

    /** Android's meta state as the Mac's flags. Meta (⊞/⌘ on a PC keyboard) is Command, Alt is Option. */
    fun modifiers(meta: Int): Long {
        var m = 0L
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or Modifiers.SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or Modifiers.CONTROL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or Modifiers.OPTION
        if (meta and KeyEvent.META_META_ON != 0) m = m or Modifiers.COMMAND
        return m
    }

    /** A character typed while ⌃/⌥/⌘ is latched, as the key that types it (US layout). */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        val lower = c.lowercaseChar()
        if (lower in 'a'..'z') return (0x04 + (lower - 'a')) to c.isUpperCase()
        if (c in '1'..'9') return (0x1E + (c - '1')) to false
        if (c == '0') return 0x27 to false
        val plain = mapOf(
            ' ' to SPACE, '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31, ';' to 0x33,
            '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38, '\n' to ENTER, '\t' to TAB,
        )
        plain[c]?.let { return it to false }
        val shifted = mapOf(
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22, '^' to 0x23, '&' to 0x24, '*' to 0x25,
            '(' to 0x26, ')' to 0x27, '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31, ':' to 0x33,
            '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
        )
        shifted[c]?.let { return it to true }
        return null
    }

    /** The modifier keys a chord presses around its key, in the order a keyboard would. */
    fun modifierKeys(mods: Long): List<Pair<Int, Long>> = buildList {
        var held = 0L
        for ((bit, key) in listOf(
            Modifiers.CONTROL to LEFT_CONTROL, Modifiers.OPTION to LEFT_OPTION,
            Modifiers.SHIFT to LEFT_SHIFT, Modifiers.COMMAND to LEFT_COMMAND,
        )) {
            if (mods and bit != 0L) { held = held or bit; add(key to held) }
        }
    }
}
