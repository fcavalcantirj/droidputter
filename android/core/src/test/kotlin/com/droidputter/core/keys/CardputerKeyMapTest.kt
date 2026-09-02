package com.droidputter.core.keys

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

class CardputerKeyMapTest {
    @Test
    fun `table has exactly 56 keys, one per row-col pair, no duplicates`() {
        assertEquals(56, CardputerKeyMap.KEYS.size)
        val positions = CardputerKeyMap.KEYS.map { it.row to it.col }
        assertEquals(positions.size, positions.toSet().size)
        for (row in 0..3) for (col in 0..13) {
            assertTrue(positions.contains(row to col), "missing ($row,$col)")
        }
    }

    // docs/PROTOCOL.md: "`1` = (0,1) confirmed [REAL] against Pense-Bem (starts Adição)".
    @Test
    fun `1 is at row 0 col 1`() {
        val key = CardputerKeyMap.at(0, 1)
        assertEquals("1", key?.legend)
        assertEquals("!", key?.shiftedLegend)
    }

    // docs/PROTOCOL.md: "`enter` = (2,13) confirmed [REAL] (answers a question)".
    @Test
    fun `enter is at row 2 col 13`() {
        assertEquals("enter", CardputerKeyMap.at(2, 13)?.legend)
    }

    @Test
    fun `space is at row 3 col 13`() {
        assertEquals("space", CardputerKeyMap.at(3, 13)?.legend)
    }

    // Ground truth: apps/pense-bem/lib/M5Cardputer/src/utility/Keyboard/Keyboard.h
    // `_key_value_map[0][0] = {'`', '~'}` -- not "esc" as docs/PROTOCOL.md's pre-hardware sketch
    // named it (see CardputerKeyMap's class doc for the full discrepancy).
    @Test
    fun `row 0 col 0 is backtick tilde, not esc, per the real M5Cardputer key value map`() {
        val key = CardputerKeyMap.at(0, 0)
        assertEquals("`", key?.legend)
        assertEquals("~", key?.shiftedLegend)
    }

    @Test
    fun `unknown position returns null`() {
        assertNull(CardputerKeyMap.at(4, 0))
        assertNull(CardputerKeyMap.at(0, 14))
    }

    // docs/PROTOCOL.md "phone -> ESP: KEY down/up for key '1' at (row=0, col=1)" worked example.
    @Test
    fun `encodeKey down for row 0 col 1 is byte-exact with PROTOCOL_md`() {
        val frame = encodeKey(0, 1, down = true)
        assertEquals("d7 50 81 03 00 00 01 01 71", hex(frame))
    }

    @Test
    fun `encodeKey up for row 0 col 1 is byte-exact with PROTOCOL_md`() {
        val frame = encodeKey(0, 1, down = false)
        assertEquals("d7 50 81 03 00 00 01 00 76", hex(frame))
    }

    @Test
    fun `AndroidKeyMap arrows and escape have no physical key`() {
        val KEYCODE_DPAD_UP = 19
        val KEYCODE_DPAD_DOWN = 20
        val KEYCODE_DPAD_LEFT = 21
        val KEYCODE_DPAD_RIGHT = 22
        val KEYCODE_ESCAPE = 111
        for (code in listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_ESCAPE)) {
            assertNull(AndroidKeyMap.position(code))
        }
    }

    @Test
    fun `AndroidKeyMap maps letters, digits, space and enter to the real key value map`() {
        val KEYCODE_0 = 7
        val KEYCODE_A = 29
        val KEYCODE_SPACE = 62
        val KEYCODE_ENTER = 66
        val KEYCODE_DEL = 67

        val a = AndroidKeyMap.position(KEYCODE_A)
        assertEquals(2 to 2, a)
        assertEquals("a", CardputerKeyMap.at(a!!.first, a.second)?.legend)

        val zero = AndroidKeyMap.position(KEYCODE_0)
        assertEquals(0 to 10, zero)
        assertEquals("0", CardputerKeyMap.at(zero!!.first, zero.second)?.legend)

        val one = AndroidKeyMap.position(KEYCODE_0 + 1)
        assertEquals(0 to 1, one)

        assertEquals(3 to 13, AndroidKeyMap.position(KEYCODE_SPACE))
        assertEquals(2 to 13, AndroidKeyMap.position(KEYCODE_ENTER))
        assertEquals(0 to 13, AndroidKeyMap.position(KEYCODE_DEL))
    }
}
