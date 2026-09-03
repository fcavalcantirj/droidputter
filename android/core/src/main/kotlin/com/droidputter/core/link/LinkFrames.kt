package com.droidputter.core.link

import com.droidputter.core.protocol.DP_SYNC0
import com.droidputter.core.protocol.DP_SYNC1
import com.droidputter.core.protocol.dpCrc8

/** phone -> ESP wire types, docs/PROTOCOL.md "phone -> ESP types". */
const val DP_TYPE_GPS_NMEA: Int = 0x82
const val DP_TYPE_PING_IN: Int = 0x83
const val DP_TYPE_HELLO_ACK: Int = 0x84

private fun frame(type: Int, payload: ByteArray): ByteArray {
    val header = byteArrayOf(type.toByte(), payload.size.toByte(), (payload.size shr 8).toByte())
    val crcInput = header + payload
    val crc = dpCrc8(crcInput).toByte()
    return byteArrayOf(DP_SYNC0.toByte(), DP_SYNC1.toByte()) + crcInput + crc
}

/** Encodes a 0-payload PING_IN frame, the caller's clock-driven keepalive/geometry probe. */
fun encodePingIn(): ByteArray = frame(DP_TYPE_PING_IN, ByteArray(0))

/**
 * Encodes GPS_NMEA {one NMEA sentence, no CRLF} per docs/PROTOCOL.md -- [sentence] is the plain
 * "$GPGGA,...*47" ASCII text (no leading/trailing whitespace, no CRLF; the shim's dp_gps.h ring
 * appends CRLF on its own). Payload is US-ASCII bytes of the sentence as-is.
 */
fun encodeGpsNmea(sentence: String): ByteArray = frame(DP_TYPE_GPS_NMEA, sentence.toByteArray(Charsets.US_ASCII))

/**
 * Encodes HELLO_ACK {w u16 LE, h u16 LE} -- byte-exact with docs/PROTOCOL.md's "phone -> ESP:
 * HELLO_ACK (Poco X7 Pro screen size)" worked example. Sent once per [LinkAction.SEND_HELLO_ACK]
 * so a phone that opened the port after the ESP's boot-time HELLO already drained makes the ESP
 * resend HELLO (droidputter.cpp: onFrame's HELLO_ACK branch).
 */
fun encodeHelloAck(screenWidth: Int, screenHeight: Int): ByteArray {
    val payload = byteArrayOf(
        screenWidth.toByte(), (screenWidth shr 8).toByte(),
        screenHeight.toByte(), (screenHeight shr 8).toByte(),
    )
    return frame(DP_TYPE_HELLO_ACK, payload)
}
