package com.droidputter.core.keys

/**
 * Cardputer 4x14 physical key layout. Row 0 = top, col 0 = leftmost, matching
 * docs/PROTOCOL.md's "Cardputer 4x14 physical key layout" table and the KEY {row, col, state}
 * wire coordinates.
 *
 * Legend/shiftedLegend are sourced from the vendored M5Cardputer library's own
 * `_key_value_map[4][14]` (apps/pense-bem/lib/M5Cardputer/src/utility/Keyboard/Keyboard.h) --
 * what the firmware actually transmits for each key -- not the pre-hardware sketch in
 * docs/PROTOCOL.md's row0 table, which turns out to mislabel three keys once checked against
 * that ground truth: col0 is labelled "esc" there but the real primary/shifted values are
 * backtick/tilde; col11 is labelled "_" there but the real primary is hyphen (shifted
 * underscore); col13 is labelled "del" there but the real value (both shifted and not) is HID
 * backspace. Every other cell (letters, digits 1-9, punctuation, tab/fn/shift/ctrl/opt/alt/
 * enter/space) matches docs/PROTOCOL.md exactly.
 */
object CardputerKeyMap {
    data class Key(val row: Int, val col: Int, val legend: String, val shiftedLegend: String)

    val KEYS: List<Key> = listOf(
        Key(0, 0, "`", "~"),
        Key(0, 1, "1", "!"),
        Key(0, 2, "2", "@"),
        Key(0, 3, "3", "#"),
        Key(0, 4, "4", "$"),
        Key(0, 5, "5", "%"),
        Key(0, 6, "6", "^"),
        Key(0, 7, "7", "&"),
        Key(0, 8, "8", "*"),
        Key(0, 9, "9", "("),
        Key(0, 10, "0", ")"),
        Key(0, 11, "-", "_"),
        Key(0, 12, "=", "+"),
        Key(0, 13, "backspace", "backspace"),
        Key(1, 0, "tab", "tab"),
        Key(1, 1, "q", "Q"),
        Key(1, 2, "w", "W"),
        Key(1, 3, "e", "E"),
        Key(1, 4, "r", "R"),
        Key(1, 5, "t", "T"),
        Key(1, 6, "y", "Y"),
        Key(1, 7, "u", "U"),
        Key(1, 8, "i", "I"),
        Key(1, 9, "o", "O"),
        Key(1, 10, "p", "P"),
        Key(1, 11, "[", "{"),
        Key(1, 12, "]", "}"),
        Key(1, 13, "\\", "|"),
        Key(2, 0, "fn", "fn"),
        Key(2, 1, "shift", "shift"),
        Key(2, 2, "a", "A"),
        Key(2, 3, "s", "S"),
        Key(2, 4, "d", "D"),
        Key(2, 5, "f", "F"),
        Key(2, 6, "g", "G"),
        Key(2, 7, "h", "H"),
        Key(2, 8, "j", "J"),
        Key(2, 9, "k", "K"),
        Key(2, 10, "l", "L"),
        Key(2, 11, ";", ":"),
        Key(2, 12, "'", "\""),
        Key(2, 13, "enter", "enter"),
        Key(3, 0, "ctrl", "ctrl"),
        Key(3, 1, "opt", "opt"),
        Key(3, 2, "alt", "alt"),
        Key(3, 3, "z", "Z"),
        Key(3, 4, "x", "X"),
        Key(3, 5, "c", "C"),
        Key(3, 6, "v", "V"),
        Key(3, 7, "b", "B"),
        Key(3, 8, "n", "N"),
        Key(3, 9, "m", "M"),
        Key(3, 10, ",", "<"),
        Key(3, 11, ".", ">"),
        Key(3, 12, "/", "?"),
        Key(3, 13, "space", "space"),
    )

    private val byRowCol: Map<Pair<Int, Int>, Key> = KEYS.associateBy { it.row to it.col }
    private val byLegend: Map<String, Key> = KEYS.associateBy { it.legend }

    fun at(row: Int, col: Int): Key? = byRowCol[row to col]

    fun legend(legend: String): Key? = byLegend[legend]
}
