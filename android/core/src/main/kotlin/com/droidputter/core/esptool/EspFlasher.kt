package com.droidputter.core.esptool

import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * Byte pipe to a chip sitting in the ROM bootloader. The app supplies it over usb-serial-for-android;
 * tests supply a fake ROM. [readSome] returns whatever arrived within the timeout (possibly empty).
 */
interface RomLink {
    fun write(bytes: ByteArray)
    fun readSome(timeoutMs: Long): ByteArray
}

class EspFlashException(message: String) : Exception(message)

/** One image to write: [data] at flash [offset]. */
data class FlashImage(val name: String, val offset: Long, val data: ByteArray)

/**
 * esptool's `write_flash --no-stub` for an ESP32-S3 over a [RomLink]: sync, chip check, SPI attach +
 * parameters, then FLASH_BEGIN / FLASH_DATA / FLASH_END per image and an MD5 read-back of every
 * region against the bytes we sent. No reset logic here -- entering the ROM and the final hard
 * reset need DTR/RTS on the real port, which belongs to the app layer.
 */
class EspFlasher(
    private val link: RomLink,
    private val flashSizeBytes: Long = 8L * 1024 * 1024,
    private val onProgress: (String, Int) -> Unit = { _, _ -> },
    private val compress: Boolean = true,
) {
    /** Set once the ROM refuses FLASH_DEFL_BEGIN; every later image goes uncompressed. */
    var compressionSupported: Boolean = compress
        private set
    private val decoder = SlipDecoder()
    private val pending = ArrayDeque<ByteArray>()

    fun sync(attempts: Int = 7) {
        repeat(attempts) { attempt ->
            link.write(Slip.encode(RomProtocol.sync()))
            val r = awaitResponse(RomProtocol.OP_SYNC, 100)
            if (r != null) {
                drain(200)   // the ROM answers a SYNC several times; swallow the echoes
                return
            }
            if (attempt == attempts - 1) throw EspFlashException("no SYNC response from the ROM bootloader")
        }
    }

    fun readReg(addr: Long): Long {
        link.write(Slip.encode(RomProtocol.readReg(addr)))
        val r = awaitResponse(RomProtocol.OP_READ_REG, 3_000) ?: throw EspFlashException("READ_REG 0x${addr.toString(16)} timed out")
        if (!r.statusOk) throw EspFlashException("READ_REG 0x${addr.toString(16)} failed (error ${r.errorCode})")
        return r.value
    }

    fun requireEsp32s3() {
        val magic = readReg(RomProtocol.CHIP_DETECT_MAGIC_REG)
        if (magic != RomProtocol.ESP32S3_CHIP_MAGIC) throw EspFlashException("not an ESP32-S3 ROM (chip magic 0x${magic.toString(16)})")
    }

    fun prepareFlash() {
        command(RomProtocol.spiAttach(), RomProtocol.OP_SPI_ATTACH, 3_000, "SPI_ATTACH")
        command(RomProtocol.spiSetParams(flashSizeBytes), RomProtocol.OP_SPI_SET_PARAMS, 3_000, "SPI_SET_PARAMS")
    }

    fun writeImage(image: FlashImage) {
        if (compressionSupported && writeImageCompressed(image)) return
        writeImageRaw(image)
    }

    /** zlib level 9 stream in 1 KB FLASH_DEFL_DATA blocks; false if the ROM rejected DEFL_BEGIN. */
    private fun writeImageCompressed(image: FlashImage): Boolean {
        val size = image.data.size.toLong()
        val comp = deflate(image.data)
        val blocks = ((comp.size + RomProtocol.FLASH_WRITE_SIZE - 1) / RomProtocol.FLASH_WRITE_SIZE)
        onProgress("${image.name}: erasing ${size} B at 0x${image.offset.toString(16)} (compressed ${comp.size} B)", 0)
        val beginTimeout = maxOf(10_000L, 30_000L * (size / 1_048_576 + 1))
        link.write(Slip.encode(RomProtocol.flashDeflBegin(size, comp.size.toLong(), image.offset)))
        val r = awaitResponse(RomProtocol.OP_FLASH_DEFL_BEGIN, beginTimeout) ?: throw EspFlashException("FLASH_DEFL_BEGIN timed out")
        if (!r.statusOk) {
            onProgress("${image.name}: ROM refused compressed mode (error 0x${r.errorCode.toString(16)}), falling back to raw", 0)
            compressionSupported = false
            return false
        }
        for (seq in 0 until blocks) {
            val from = seq * RomProtocol.FLASH_WRITE_SIZE
            val to = minOf(from + RomProtocol.FLASH_WRITE_SIZE, comp.size)
            // the ROM inflates and writes up to a few KB per block: give it esptool's 40 s/MB budget
            command(RomProtocol.flashDeflData(comp.copyOfRange(from, to), seq.toLong()), RomProtocol.OP_FLASH_DEFL_DATA, 5_000, "FLASH_DEFL_DATA #$seq")
            if (seq % 16 == 0 || seq == blocks - 1) onProgress("${image.name}: ${to} / ${comp.size} B compressed", ((seq + 1) * 100 / blocks))
        }
        return true
    }

    private fun writeImageRaw(image: FlashImage) {
        val size = image.data.size.toLong()
        val blocks = ((size + RomProtocol.FLASH_WRITE_SIZE - 1) / RomProtocol.FLASH_WRITE_SIZE).toInt()
        onProgress("${image.name}: erasing ${size} B at 0x${image.offset.toString(16)}", 0)
        // ROM erases the region inside FLASH_BEGIN: budget 30 s per MB like esptool, 10 s minimum.
        // One retry after a re-sync: two early hardware runs saw the second image's FLASH_BEGIN go
        // unanswered (2026-09-03, cause not isolated); SYNC is harmless inside the loader.
        val beginTimeout = maxOf(10_000L, 30_000L * (size / 1_048_576 + 1))
        try {
            command(RomProtocol.flashBegin(size, image.offset), RomProtocol.OP_FLASH_BEGIN, beginTimeout, "FLASH_BEGIN")
        } catch (e: EspFlashException) {
            if (!e.message!!.contains("timed out")) throw e
            onProgress("${image.name}: FLASH_BEGIN unanswered, re-syncing and retrying once", 0)
            sync()
            command(RomProtocol.flashBegin(size, image.offset), RomProtocol.OP_FLASH_BEGIN, beginTimeout, "FLASH_BEGIN (retry)")
        }
        for (seq in 0 until blocks) {
            val from = seq * RomProtocol.FLASH_WRITE_SIZE
            val to = minOf(from + RomProtocol.FLASH_WRITE_SIZE, image.data.size)
            command(RomProtocol.flashData(image.data.copyOfRange(from, to), seq.toLong()), RomProtocol.OP_FLASH_DATA, 3_000, "FLASH_DATA #$seq")
            if (seq % 16 == 0 || seq == blocks - 1) onProgress("${image.name}: ${to} / ${size} B", ((seq + 1) * 100 / blocks))
        }
        // No FLASH_END here: on the ROM loader it exits the loader and runs user code whatever the
        // flag says (esptool skips it for the ROM for that reason; 2026-09-03 [REAL]: the second
        // image's FLASH_BEGIN timed out after it). The caller's hard reset boots the new app.
    }

    /** MD5 of the flashed region as the ROM reads it back (32 ASCII hex chars) vs our bytes. */
    fun verify(image: FlashImage) {
        link.write(Slip.encode(RomProtocol.flashMd5(image.offset, image.data.size.toLong())))
        val r = awaitResponse(RomProtocol.OP_SPI_FLASH_MD5, maxOf(8_000L, 8_000L * (image.data.size / 1_048_576 + 1)))
            ?: throw EspFlashException("${image.name}: MD5 read-back timed out")
        if (!r.statusOk) throw EspFlashException("${image.name}: MD5 read-back failed (error ${r.errorCode})")
        val onChip = String(r.payload.copyOfRange(0, minOf(32, r.payload.size))).lowercase()
        val expected = md5Hex(image.data)
        if (onChip != expected) throw EspFlashException("${image.name}: MD5 mismatch (chip $onChip, image $expected)")
        onProgress("${image.name}: verified (md5 $expected)", 100)
    }

    /** Full sequence for a catalog entry. The caller hard-resets the chip afterwards (no FLASH_END). */
    fun flashAll(images: List<FlashImage>) {
        onProgress("syncing with the ROM bootloader", 0)
        sync()
        requireEsp32s3()
        prepareFlash()
        for (img in images) writeImage(img)
        for (img in images) verify(img)
        onProgress("done: ${images.size} images written and verified", 100)
    }

    private fun command(packet: ByteArray, op: Int, timeoutMs: Long, what: String): RomProtocol.Response {
        link.write(Slip.encode(packet))
        val r = awaitResponse(op, timeoutMs) ?: throw EspFlashException("$what timed out")
        if (!r.statusOk) throw EspFlashException("$what failed (ROM error 0x${r.errorCode.toString(16)})")
        return r
    }

    private fun awaitResponse(op: Int, timeoutMs: Long): RomProtocol.Response? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            while (pending.isNotEmpty()) {
                val r = RomProtocol.parseResponse(pending.removeFirst()) ?: continue
                if (r.op == op) return r
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val chunk = link.readSome(minOf(remaining, 200))
            if (chunk.isNotEmpty()) pending.addAll(decoder.feed(chunk))
        }
    }

    private fun drain(quietMs: Long) {
        val deadline = System.currentTimeMillis() + quietMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = link.readSome(50)
            if (chunk.isNotEmpty()) decoder.feed(chunk) // discard
        }
        pending.clear()
    }

    companion object {
        fun md5Hex(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

        /** zlib stream (header + deflate + adler32), level 9 -- what esptool's zlib.compress(data, 9) sends. */
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(9)
            d.setInput(data); d.finish()
            val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(16384)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            d.end()
            return out.toByteArray()
        }
    }
}
