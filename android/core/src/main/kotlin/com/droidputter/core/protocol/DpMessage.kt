package com.droidputter.core.protocol

/**
 * Typed ESP<->phone messages decoded from a [Frame]'s payload, per docs/PROTOCOL.md.
 * Pixel values are RGB565 packed into an Int (0..65535). RECT/RECT_RLE pixel bytes are
 * big-endian off the panel bus; FILL's color is little-endian -- a real asymmetry in the
 * shim (docs/PROTOCOL.md "Note on FILL's color field"), handled here so callers don't
 * have to know about it.
 */
sealed class DpMessage {
    data class Hello(
        val proto: Int,
        val w: Int,
        val h: Int,
        val rotation: Int,
        val bpp: Int,
        val board: String,
        val app: String,
    ) : DpMessage()

    data class Fill(val x: Int, val y: Int, val w: Int, val h: Int, val color: Int) : DpMessage()

    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray) : DpMessage() {
        override fun equals(other: Any?): Boolean =
            other is Rect && x == other.x && y == other.y && w == other.w && h == other.h &&
                pixels.contentEquals(other.pixels)

        override fun hashCode(): Int =
            ((((x * 31 + y) * 31) + w) * 31 + h) * 31 + pixels.contentHashCode()
    }

    data class RectRle(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray) : DpMessage() {
        override fun equals(other: Any?): Boolean =
            other is RectRle && x == other.x && y == other.y && w == other.w && h == other.h &&
                pixels.contentEquals(other.pixels)

        override fun hashCode(): Int =
            ((((x * 31 + y) * 31) + w) * 31 + h) * 31 + pixels.contentHashCode()
    }

    data class Stats(val frames: Long, val bytes: Long, val dropped: Long, val heapFree: Long) : DpMessage()
}

/** Dispatches on [Frame.type]; an unknown type or a payload that fails its type's bounds
 * check returns null instead of throwing -- callers (e.g. a live decode loop replaying
 * real hardware bytes) must be able to skip a malformed frame without crashing. */
fun decodeDpMessage(frame: Frame): DpMessage? = when (frame.type) {
    0x01 -> decodeHello(frame.payload)
    0x02 -> decodeFill(frame.payload)
    0x03 -> decodeRect(frame.payload)
    0x04 -> decodeRectRle(frame.payload)
    0x05 -> decodeStats(frame.payload)
    else -> null
}

/** proto u8, w u16, h u16, rotation u8, bpp u8, board char[16], app char[32] = 55 B. */
fun decodeHello(p: ByteArray): DpMessage.Hello? {
    if (p.size != 55) return null
    return DpMessage.Hello(
        proto = p[0].toInt() and 0xFF,
        w = u16le(p, 1),
        h = u16le(p, 3),
        rotation = p[5].toInt() and 0xFF,
        bpp = p[6].toInt() and 0xFF,
        board = cstr(p, 7, 16),
        app = cstr(p, 23, 32),
    )
}

/** x,y,w,h u16, color u16 (low-byte-first, see [DpMessage] doc) = 10 B. */
fun decodeFill(p: ByteArray): DpMessage.Fill? {
    if (p.size != 10) return null
    return DpMessage.Fill(x = u16le(p, 0), y = u16le(p, 2), w = u16le(p, 4), h = u16le(p, 6), color = u16le(p, 8))
}

/** x,y,w,h u16 header (8 B) then w*h*2 B of raw RGB565, big-endian per pixel. A size
 * mismatch (w*h*2 != payload.size - 8) is a malformed frame, not a crash: return null. */
fun decodeRect(p: ByteArray): DpMessage.Rect? {
    if (p.size < 8) return null
    val x = u16le(p, 0); val y = u16le(p, 2); val w = u16le(p, 4); val h = u16le(p, 6)
    val n = w * h
    if (p.size - 8 != n * 2) return null
    val pixels = IntArray(n)
    for (i in 0 until n) {
        val o = 8 + i * 2
        pixels[i] = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)
    }
    return DpMessage.Rect(x, y, w, h, pixels)
}

/** Same 8 B header as RECT, then runs of (count u8, color u16 big-endian) = 3 B/run.
 * Malformed if the run bytes aren't a multiple of 3, a run has a zero count, a run would
 * overrun the declared w*h, or the runs don't exactly sum to w*h -- any of these return
 * null instead of throwing or silently truncating/overflowing the pixel array. */
fun decodeRectRle(p: ByteArray): DpMessage.RectRle? {
    if (p.size < 8) return null
    val x = u16le(p, 0); val y = u16le(p, 2); val w = u16le(p, 4); val h = u16le(p, 6)
    val n = w * h
    val runBytes = p.size - 8
    if (runBytes % 3 != 0) return null
    val pixels = IntArray(n)
    var idx = 0
    var o = 8
    while (o < p.size) {
        val count = p[o].toInt() and 0xFF
        if (count == 0) return null
        if (idx + count > n) return null
        val color = ((p[o + 1].toInt() and 0xFF) shl 8) or (p[o + 2].toInt() and 0xFF)
        repeat(count) { pixels[idx++] = color }
        o += 3
    }
    if (idx != n) return null
    return DpMessage.RectRle(x, y, w, h, pixels)
}

/** frames,bytes,dropped,heap_free u32 LE = 16 B. */
fun decodeStats(p: ByteArray): DpMessage.Stats? {
    if (p.size != 16) return null
    return DpMessage.Stats(u32le(p, 0), u32le(p, 4), u32le(p, 8), u32le(p, 12))
}

private fun u16le(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun u32le(b: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun cstr(b: ByteArray, o: Int, len: Int): String {
    val end = (o until o + len).firstOrNull { b[it] == 0.toByte() } ?: (o + len)
    return String(b, o, end - o, Charsets.US_ASCII)
}
