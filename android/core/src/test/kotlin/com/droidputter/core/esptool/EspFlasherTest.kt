package com.droidputter.core.esptool

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A ROM bootloader that answers like an ESP32-S3 (4 status bytes, echoes SYNC 8 times). */
private class FakeRom(private val flashSize: Int = 8 * 1024 * 1024, private val failMd5: Boolean = false, private val deflSupported: Boolean = true) : RomLink {
    private val inflater = java.util.zip.Inflater()
    private var deflWritten = 0L
    val flash = ByteArray(flashSize) { 0xFF.toByte() }
    val ops = ArrayList<Int>()
    private val decoder = SlipDecoder()
    private val outbox = ArrayDeque<Byte>()
    private var beginOffset = 0L
    private var beginBlocks = 0L
    var synced = false

    override fun write(bytes: ByteArray) {
        for (pkt in decoder.feed(bytes)) handle(pkt)
    }

    override fun readSome(timeoutMs: Long): ByteArray {
        val n = minOf(outbox.size, 61)  // USB-sized chunks, split anywhere
        return ByteArray(n) { outbox.removeFirst() }
    }

    private fun reply(op: Int, value: Long = 0, payload: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
        val body = payload + byteArrayOf(status.toByte(), error.toByte(), 0, 0)
        val pkt = ByteArray(8 + body.size)
        pkt[0] = 0x01; pkt[1] = op.toByte()
        pkt[2] = (body.size and 0xFF).toByte(); pkt[3] = (body.size shr 8).toByte()
        for (i in 0 until 4) pkt[4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        body.copyInto(pkt, 8)
        for (b in Slip.encode(pkt)) outbox.addLast(b)
    }

    private fun handle(pkt: ByteArray) {
        val op = pkt[1].toInt() and 0xFF
        val data = pkt.copyOfRange(8, pkt.size)
        ops += op
        when (op) {
            RomProtocol.OP_SYNC -> { synced = true; repeat(8) { reply(op) } }
            RomProtocol.OP_READ_REG -> reply(op, value = if (RomProtocol.get32(data, 0) == RomProtocol.CHIP_DETECT_MAGIC_REG) RomProtocol.ESP32S3_CHIP_MAGIC else 0)
            RomProtocol.OP_SPI_ATTACH -> reply(op, status = if (data.size == 8) 0 else 1, error = 5)
            RomProtocol.OP_SPI_SET_PARAMS -> reply(op, status = if (RomProtocol.get32(data, 4) == flashSize.toLong()) 0 else 1, error = 6)
            RomProtocol.OP_FLASH_BEGIN -> {
                beginBlocks = RomProtocol.get32(data, 4); beginOffset = RomProtocol.get32(data, 12)
                val eraseSize = RomProtocol.get32(data, 0)
                for (i in 0 until eraseSize) flash[(beginOffset + i).toInt()] = 0xFF.toByte()
                reply(op, status = if (data.size == 20) 0 else 1, error = 7)  // S3 ROM wants the encrypt word
            }
            RomProtocol.OP_FLASH_DATA -> {
                val len = RomProtocol.get32(data, 0).toInt(); val seq = RomProtocol.get32(data, 4)
                val block = data.copyOfRange(16, 16 + len)
                val sum = RomProtocol.checksum(block)
                val hdrSum = RomProtocol.get32(pkt, 4).toInt()
                if (sum != hdrSum || len != RomProtocol.FLASH_WRITE_SIZE || seq >= beginBlocks) { reply(op, status = 1, error = 8); return }
                block.copyInto(flash, (beginOffset + seq * len).toInt())
                reply(op)
            }
            RomProtocol.OP_FLASH_DEFL_BEGIN -> {
                if (!deflSupported) { reply(op, status = 1, error = 0x05); return }
                beginBlocks = RomProtocol.get32(data, 4); beginOffset = RomProtocol.get32(data, 12); deflWritten = 0
                inflater.reset()
                reply(op, status = if (data.size == 20) 0 else 1, error = 7)
            }
            RomProtocol.OP_FLASH_DEFL_DATA -> {
                val len = RomProtocol.get32(data, 0).toInt(); val seq = RomProtocol.get32(data, 4)
                val block = data.copyOfRange(16, 16 + len)
                if (RomProtocol.checksum(block) != RomProtocol.get32(pkt, 4).toInt() || seq >= beginBlocks) { reply(op, status = 1, error = 8); return }
                inflater.setInput(block)
                val tmp = ByteArray(65536)
                while (true) { val n = inflater.inflate(tmp); if (n == 0) break; tmp.copyInto(flash, (beginOffset + deflWritten).toInt(), 0, n); deflWritten += n }
                reply(op)
            }
            RomProtocol.OP_FLASH_END -> reply(op)
            RomProtocol.OP_SPI_FLASH_MD5 -> {
                val off = RomProtocol.get32(data, 0).toInt(); val size = RomProtocol.get32(data, 4).toInt()
                val md5 = if (failMd5) "0".repeat(32) else EspFlasher.md5Hex(flash.copyOfRange(off, off + size))
                reply(op, payload = md5.toByteArray())
            }
            else -> reply(op, status = 1, error = 0x05)
        }
    }
}

class EspFlasherTest {
    @Test
    fun slipRoundTripsEscapesAndSplitChunks() {
        val packet = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0x42, 0xC0.toByte())
        val encoded = Slip.encode(packet)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x00, 0xDB.toByte(), 0xDC.toByte(), 0xDB.toByte(), 0xDD.toByte(), 0x42, 0xDB.toByte(), 0xDC.toByte(), 0xC0.toByte()), encoded)
        val dec = SlipDecoder()
        val first = dec.feed("noise".toByteArray() + encoded.copyOfRange(0, 4))
        assertTrue(first.isEmpty())
        val rest = dec.feed(encoded.copyOfRange(4, encoded.size))
        assertEquals(1, rest.size)
        assertArrayEquals(packet, rest[0])
    }

    @Test
    fun syncPacketIsByteExactWithEsptool() {
        // c0 00 08 24 00 00 00 00 00 07 07 12 20 55*32 c0 -- the same bytes MainActivity's ROM probe writes
        val expected = byteArrayOf(0xC0.toByte(), 0x00, 0x08, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 } + byteArrayOf(0xC0.toByte())
        assertArrayEquals(expected, Slip.encode(RomProtocol.sync()))
    }

    @Test
    fun flashDataChecksumAndPaddingMatchEsptool() {
        val block = byteArrayOf(1, 2, 3)
        val pkt = RomProtocol.flashData(block, 7)
        assertEquals(RomProtocol.OP_FLASH_DATA, pkt[1].toInt())
        assertEquals(16 + RomProtocol.FLASH_WRITE_SIZE, pkt.size - 8)
        assertEquals(0xEF xor 1 xor 2 xor 3 xor (0xFF.takeIf { (RomProtocol.FLASH_WRITE_SIZE - 3) % 2 == 1 } ?: 0), RomProtocol.get32(pkt, 4).toInt())
        assertEquals(7L, RomProtocol.get32(pkt, 12))
        assertEquals(0xFF, pkt[8 + 16 + 3].toInt() and 0xFF)
    }

    @Test
    fun responseParserHonoursStatusBytes() {
        val ok = byteArrayOf(0x01, 0x0A, 0x04, 0x00, 0x09, 0, 0, 0, 0, 0, 0, 0)
        val r = RomProtocol.parseResponse(ok)!!
        assertEquals(0x0A, r.op); assertEquals(9L, r.value); assertTrue(r.statusOk)
        val bad = byteArrayOf(0x01, 0x02, 0x04, 0x00, 0, 0, 0, 0, 1, 7, 0, 0)
        val b = RomProtocol.parseResponse(bad)!!
        assertEquals(false, b.statusOk); assertEquals(7, b.errorCode)
        assertNull(RomProtocol.parseResponse(byteArrayOf(0x00, 0x08)))
        assertNull(RomProtocol.parseResponse(byteArrayOf(0x01, 0x02, 0x09, 0x00, 0, 0, 0, 0, 0, 0, 0, 0)))  // length mismatch
    }

    @Test
    fun flashesAndVerifiesTwoImagesOnAFakeRom() {
        val rom = FakeRom()
        val progress = ArrayList<String>()
        val flasher = EspFlasher(rom, onProgress = { msg, _ -> progress += msg })
        val boot = ByteArray(15104) { (it * 7).toByte() }
        val fw = ByteArray(3000 + 17) { (it xor 0x5A).toByte() }  // not a multiple of the block size
        flasher.flashAll(listOf(FlashImage("bootloader.bin", 0x0, boot), FlashImage("firmware.bin", 0x10000, fw)))
        assertTrue(rom.synced)
        assertArrayEquals(boot, rom.flash.copyOfRange(0, boot.size))
        assertArrayEquals(fw, rom.flash.copyOfRange(0x10000, 0x10000 + fw.size))
        assertEquals(0xFF, rom.flash[0x10000 + fw.size].toInt() and 0xFF)  // padding stays erased
        assertEquals(listOf(RomProtocol.OP_SYNC, RomProtocol.OP_READ_REG, RomProtocol.OP_SPI_ATTACH, RomProtocol.OP_SPI_SET_PARAMS), rom.ops.take(4))
        assertEquals(2, rom.ops.count { it == RomProtocol.OP_FLASH_DEFL_BEGIN })
        assertEquals(0, rom.ops.count { it == RomProtocol.OP_FLASH_BEGIN })
        assertTrue(rom.ops.count { it == RomProtocol.OP_FLASH_DEFL_DATA } < 15 + 3)  // compressible test data: fewer blocks than raw
        assertEquals(2, rom.ops.count { it == RomProtocol.OP_SPI_FLASH_MD5 })
        assertEquals(0, rom.ops.count { it == RomProtocol.OP_FLASH_END })  // ROM loader: FLASH_END would exit the loader
        assertTrue(progress.last().startsWith("done: 2 images"))
    }

    @Test
    fun rawPathFlashesAndVerifies() {
        val rom = FakeRom()
        val fw = ByteArray(3000 + 17) { (it xor 0x5A).toByte() }
        EspFlasher(rom, compress = false).flashAll(listOf(FlashImage("firmware.bin", 0x10000, fw)))
        assertArrayEquals(fw, rom.flash.copyOfRange(0x10000, 0x10000 + fw.size))
        assertEquals(1, rom.ops.count { it == RomProtocol.OP_FLASH_BEGIN })
        assertEquals(3, rom.ops.count { it == RomProtocol.OP_FLASH_DATA })
    }

    @Test
    fun romWithoutDeflateFallsBackToRaw() {
        val rom = FakeRom(deflSupported = false)
        val fw = ByteArray(2048) { (it * 3).toByte() }
        val flasher = EspFlasher(rom)
        flasher.flashAll(listOf(FlashImage("a.bin", 0x0, fw), FlashImage("b.bin", 0x10000, fw)))
        assertEquals(false, flasher.compressionSupported)
        assertEquals(1, rom.ops.count { it == RomProtocol.OP_FLASH_DEFL_BEGIN })  // refused once, never retried
        assertEquals(2, rom.ops.count { it == RomProtocol.OP_FLASH_BEGIN })
        assertArrayEquals(fw, rom.flash.copyOfRange(0x10000, 0x10000 + fw.size))
    }

    @Test
    fun md5MismatchIsAnError() {
        val rom = FakeRom(failMd5 = true)
        val e = assertThrows(EspFlashException::class.java) {
            EspFlasher(rom).flashAll(listOf(FlashImage("firmware.bin", 0x10000, ByteArray(2048) { 1 })))
        }
        assertTrue(e.message!!.contains("MD5 mismatch"))
    }

    @Test
    fun wrongChipIsRejectedBeforeAnyWrite() {
        val rom = object : RomLink {
            val inner = FakeRom()
            override fun write(bytes: ByteArray) = inner.write(bytes)
            override fun readSome(timeoutMs: Long): ByteArray {
                val b = inner.readSome(timeoutMs)
                // forge the READ_REG value: rewrite any 0x09 magic value byte in a READ_REG response
                for (i in 0 until b.size - 1) if (b[i].toInt() == 0x0A && i >= 1 && b[i - 1].toInt() == 0x01) b[i + 3] = 0x02
                return b
            }
        }
        val e = assertThrows(EspFlashException::class.java) { EspFlasher(rom).flashAll(listOf(FlashImage("x", 0, ByteArray(1024)))) }
        assertTrue(e.message!!.contains("not an ESP32-S3"))
        assertEquals(0, rom.inner.ops.count { it == RomProtocol.OP_FLASH_BEGIN })
    }

    @Test
    fun silentRomTimesOutOnSync() {
        val dead = object : RomLink { override fun write(bytes: ByteArray) {}; override fun readSome(timeoutMs: Long) = ByteArray(0) }
        val e = assertThrows(EspFlashException::class.java) { EspFlasher(dead).sync(attempts = 2) }
        assertTrue(e.message!!.contains("SYNC"))
    }
}
