package com.droidputter.core.keys

/**
 * Maps Android hardware `KeyEvent.keyCode` values to a Cardputer (row, col), for a physical
 * (Bluetooth/USB) keyboard attached to the phone driving the shim's KEY frames. Golden rule:
 * android/core has zero android.* imports, so the codes below are copied as plain Int literals
 * from android.view.KeyEvent's public, stable (frozen since API 1) KEYCODE_* constants rather
 * than importing the class.
 *
 * Only codes with an unambiguous physical key on the Cardputer's 4x14 grid are mapped.
 * KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT and KEYCODE_ESCAPE return null: the vendored M5Cardputer
 * `_key_value_map` (see [CardputerKeyMap]) has no arrow or escape key anywhere on the grid, and
 * inventing an fn-combo convention not present in any source file in this repo would be a guess,
 * not a fact. KEYCODE_ALT_RIGHT is also unmapped: Cardputer's second modifier key is "opt", and
 * there is no standard Android keycode that unambiguously means "opt".
 */
object AndroidKeyMap {
    private const val KEYCODE_0 = 7
    private const val KEYCODE_A = 29
    private const val KEYCODE_Z = 54
    private const val KEYCODE_COMMA = 55
    private const val KEYCODE_PERIOD = 56
    private const val KEYCODE_ALT_LEFT = 57
    private const val KEYCODE_SHIFT_LEFT = 59
    private const val KEYCODE_SHIFT_RIGHT = 60
    private const val KEYCODE_TAB = 61
    private const val KEYCODE_SPACE = 62
    private const val KEYCODE_ENTER = 66
    private const val KEYCODE_DEL = 67
    private const val KEYCODE_GRAVE = 68
    private const val KEYCODE_MINUS = 69
    private const val KEYCODE_EQUALS = 70
    private const val KEYCODE_LEFT_BRACKET = 71
    private const val KEYCODE_RIGHT_BRACKET = 72
    private const val KEYCODE_BACKSLASH = 73
    private const val KEYCODE_SEMICOLON = 74
    private const val KEYCODE_APOSTROPHE = 75
    private const val KEYCODE_SLASH = 76
    private const val KEYCODE_CTRL_LEFT = 113
    private const val KEYCODE_CTRL_RIGHT = 114

    private val DIGIT_COLS = intArrayOf(10, 1, 2, 3, 4, 5, 6, 7, 8, 9) // '0'..'9' -> row0 col

    private val LETTER_POSITIONS: Map<Int, Pair<Int, Int>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(letterCode(c), 1 to (i + 1)) }
        "asdfghjkl".forEachIndexed { i, c -> put(letterCode(c), 2 to (i + 2)) }
        "zxcvbnm".forEachIndexed { i, c -> put(letterCode(c), 3 to (i + 3)) }
    }

    private val PUNCTUATION_POSITIONS: Map<Int, Pair<Int, Int>> = mapOf(
        KEYCODE_GRAVE to (0 to 0),
        KEYCODE_MINUS to (0 to 11),
        KEYCODE_EQUALS to (0 to 12),
        KEYCODE_LEFT_BRACKET to (1 to 11),
        KEYCODE_RIGHT_BRACKET to (1 to 12),
        KEYCODE_BACKSLASH to (1 to 13),
        KEYCODE_SEMICOLON to (2 to 11),
        KEYCODE_APOSTROPHE to (2 to 12),
        KEYCODE_COMMA to (3 to 10),
        KEYCODE_PERIOD to (3 to 11),
        KEYCODE_SLASH to (3 to 12),
    )

    private fun letterCode(c: Char): Int = KEYCODE_A + (c - 'a')

    /** Returns the (row, col) a given Android hardware keycode drives, or null if the Cardputer
     * grid has no physical key for it. */
    fun position(keyCode: Int): Pair<Int, Int>? = when {
        keyCode in KEYCODE_0..(KEYCODE_0 + 9) -> 0 to DIGIT_COLS[keyCode - KEYCODE_0]
        keyCode in LETTER_POSITIONS -> LETTER_POSITIONS[keyCode]
        keyCode in PUNCTUATION_POSITIONS -> PUNCTUATION_POSITIONS[keyCode]
        keyCode == KEYCODE_SPACE -> 3 to 13
        keyCode == KEYCODE_ENTER -> 2 to 13
        keyCode == KEYCODE_DEL -> 0 to 13
        keyCode == KEYCODE_TAB -> 1 to 0
        keyCode == KEYCODE_SHIFT_LEFT || keyCode == KEYCODE_SHIFT_RIGHT -> 2 to 1
        keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT -> 3 to 0
        keyCode == KEYCODE_ALT_LEFT -> 3 to 2
        else -> null
    }
}
