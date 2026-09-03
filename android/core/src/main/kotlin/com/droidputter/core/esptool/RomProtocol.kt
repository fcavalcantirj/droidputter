package com.droidputter.core.esptool

/**
 * Request/response encoding of the Espressif serial bootloader protocol (the ROM loader, no stub),
 * as documented in esptool's `ESPLoader` and the "Serial Protocol" page. Byte-exact with esptool
 * for the ESP32-S3 ROM: 4 status bytes trail every response, FLASH_BEGIN carries the extra
 * `encrypt` word, SPI_ATTACH the extra zero word.
 *
 * Request:  0x00, op, size u16 LE, checksum u32 LE (only FLASH_DATA uses it), data
 * Response: 0x01, op, size u16 LE, value u32 LE, data (payload + status bytes)
 */
object RomProtocol {
    const val OP_FLASH_BEGIN = 0x02
    const val OP_FLASH_DATA = 0x03
    const val OP_FLASH_END = 0x04
    const val OP_SYNC = 0x08
    const val OP_READ_REG = 0x0A
    const val OP_SPI_SET_PARAMS = 0x0B
    const val OP_SPI_ATTACH = 0x0D
    const val OP_SPI_FLASH_MD5 = 0x13

    /** ROM loader flash write block (esptool ESP32ROM.FLASH_WRITE_SIZE). */
    const val FLASH_WRITE_SIZE = 0x400

    /** Trailing status bytes on ESP32-S3 ROM responses (esptool ESP32S3ROM.STATUS_BYTES_LENGTH). */
    const val STATUS_BYTES = 4

    const val CHIP_DETECT_MAGIC_REG = 0x40001000L
    const val ESP32S3_CHIP_MAGIC = 0x9L

    const val CHECKSUM_SEED = 0xEF

    fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = CHECKSUM_SEED
        for (i in from until to) c = c xor (data[i].toInt() and 0xFF)
        return c and 0xFF
    }

    fun request(op: Int, data: ByteArray, checksum: Int = 0): ByteArray {
        val out = ByteArray(8 + data.size)
        out[0] = 0x00
        out[1] = op.toByte()
        put16(out, 2, data.size)
        put32(out, 4, checksum.toLong())
        data.copyInto(out, 8)
        return out
    }

    fun sync(): ByteArray = request(OP_SYNC, byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 })

    fun readReg(addr: Long): ByteArray = request(OP_READ_REG, u32(addr))

    /** ROM needs the extra zero word after the hspi argument. */
    fun spiAttach(): ByteArray = request(OP_SPI_ATTACH, u32(0) + u32(0))

    fun spiSetParams(totalSize: Long): ByteArray = request(
        OP_SPI_SET_PARAMS,
        u32(0) + u32(totalSize) + u32(0x10000) + u32(0x1000) + u32(0x100) + u32(0xFFFF),
    )

    /** ROM erases the whole region up front: size rounded up to whole write blocks. */
    fun flashBegin(size: Long, offset: Long): ByteArray {
        val blocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        return request(
            OP_FLASH_BEGIN,
            u32(blocks * FLASH_WRITE_SIZE) + u32(blocks) + u32(FLASH_WRITE_SIZE.toLong()) + u32(offset) + u32(0),
        )
    }

    /** One write block: padded with 0xFF to [FLASH_WRITE_SIZE]; the header checksum covers the block. */
    fun flashData(block: ByteArray, seq: Long): ByteArray {
        val padded = if (block.size == FLASH_WRITE_SIZE) block else ByteArray(FLASH_WRITE_SIZE) { i -> if (i < block.size) block[i] else 0xFF.toByte() }
        return request(
            OP_FLASH_DATA,
            u32(padded.size.toLong()) + u32(seq) + u32(0) + u32(0) + padded,
            checksum(padded),
        )
    }

    /** reboot=false keeps the ROM in the loader so the next part (or the MD5 check) can follow. */
    fun flashEnd(reboot: Boolean): ByteArray = request(OP_FLASH_END, u32(if (reboot) 0 else 1))

    fun flashMd5(offset: Long, size: Long): ByteArray = request(OP_SPI_FLASH_MD5, u32(offset) + u32(size) + u32(0) + u32(0))

    data class Response(val op: Int, val value: Long, val payload: ByteArray, val statusOk: Boolean, val errorCode: Int)

    /** Parses one SLIP-decoded packet; null if it is not a well-formed response. */
    fun parseResponse(packet: ByteArray): Response? {
        if (packet.size < 8 + STATUS_BYTES || packet[0].toInt() != 0x01) return null
        val op = packet[1].toInt() and 0xFF
        val size = get16(packet, 2)
        val value = get32(packet, 4)
        val body = packet.copyOfRange(8, packet.size)
        if (body.size < STATUS_BYTES) return null
        val payload = body.copyOfRange(0, body.size - STATUS_BYTES)
        val status = body[body.size - STATUS_BYTES].toInt() and 0xFF
        val error = body[body.size - STATUS_BYTES + 1].toInt() and 0xFF
        if (size != body.size) return null   // length field disagrees with the packet: not a response
        return Response(op, value, payload, status == 0, error)
    }

    fun u32(v: Long): ByteArray = ByteArray(4).also { put32(it, 0, v) }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun get16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    fun get32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
