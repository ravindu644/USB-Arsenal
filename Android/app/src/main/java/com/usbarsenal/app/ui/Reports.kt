package com.usbarsenal.app.ui

/*
 * The HID reports, as hid.c's report descriptors lay them out: a keyboard report is 8 bytes
 * (modifiers, a reserved 0, six keycodes from usage page 7), a mouse report 4 (buttons, then X,
 * Y and the wheel, each a signed byte).
 */

const val MOUSE_LEFT = 0x01
const val MOUSE_RIGHT = 0x02
const val MOUSE_MIDDLE = 0x04

const val MOD_CTRL = 0x01
const val MOD_SHIFT = 0x02
const val MOD_ALT = 0x04
const val MOD_SUPER = 0x08

private fun signed(v: Int) = v.coerceIn(-127, 127).toByte()

fun mouseReport(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0) =
    byteArrayOf(buttons.toByte(), signed(dx), signed(dy), signed(wheel))

fun keyReport(mods: Int = 0, code: Int = 0) = byteArrayOf(mods.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0)

// The keys the Keys tab names, by their usage codes
val NAMED_KEYS: Map<String, Int> = mapOf(
    "ESC" to 0x29, "TAB" to 0x2B, "BKSP" to 0x2A, "DEL" to 0x4C, "ENTER" to 0x28,
    "INS" to 0x49, "HOME" to 0x4A, "END" to 0x4D, "PGUP" to 0x4B, "PGDN" to 0x4E, "PRTSC" to 0x46,
    "RIGHT" to 0x4F, "LEFT" to 0x50, "DOWN" to 0x51, "UP" to 0x52,
) + (1..12).associate { "F$it" to 0x39 + it }

val MODIFIERS: Map<String, Int> = mapOf("SHIFT" to MOD_SHIFT, "CTRL" to MOD_CTRL, "ALT" to MOD_ALT, "SUPER" to MOD_SUPER)

// US layout: the unshifted and shifted character on each key past the letters and digits
private const val SYMBOLS = "-_=+[{]}\\|;:'\"`~,<.>/?"
private val SYMBOL_CODES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
private const val SHIFTED_DIGITS = ")!@#$%^&*("

// The modifiers and usage code that type [c] on a US layout; null for a character it cannot
fun keyFor(c: Char): Pair<Int, Int>? = when (c) {
    in 'a'..'z' -> 0 to 0x04 + (c - 'a')
    in 'A'..'Z' -> MOD_SHIFT to 0x04 + (c - 'A')
    '0' -> 0 to 0x27
    in '1'..'9' -> 0 to 0x1E + (c - '1')
    ' ' -> 0 to 0x2C
    '\n' -> 0 to 0x28
    '\t' -> 0 to 0x2B
    else -> {
        val shifted = SHIFTED_DIGITS.indexOf(c)
        val symbol = SYMBOLS.indexOf(c)
        when {
            shifted >= 0 -> MOD_SHIFT to if (shifted == 0) 0x27 else 0x1E + shifted - 1
            symbol >= 0 -> (if (symbol % 2 == 1) MOD_SHIFT else 0) to SYMBOL_CODES[symbol / 2]
            else -> null
        }
    }
}

// The bInterval period for [n]: at high and super speed an exponent, 2^(n-1) x 125us, as hid.c
// gives it; at full speed (a USB 1.1 host) n whole 1 ms frames
fun periodText(n: Int, fullSpeed: Boolean = false): String {
    if (fullSpeed) return "$n ms"
    val us = (1L shl (n - 1)) * 125
    // From 1 ms up every period is whole milliseconds; past a second, 1.024 s to 4.096 s
    return when {
        us < 1000 -> "$us us"
        us < 1_000_000 -> "${us / 1000} ms"
        else -> "%.3f s".format(java.util.Locale.ROOT, us / 1e6)
    }
}
