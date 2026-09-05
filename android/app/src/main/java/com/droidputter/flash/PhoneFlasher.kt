package com.droidputter.flash

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.droidputter.catalog.CatalogRepository
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.esptool.EspFlashException
import com.droidputter.core.esptool.EspFlasher
import com.droidputter.core.esptool.FlashImage
import com.droidputter.usb.UsbLinkManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flash a catalog entry into the attached ESP32-S3 from the phone itself (no PC, no third-party
 * flasher): the link manager stands down, the running app is reset into the ROM bootloader over
 * DTR/RTS, [EspFlasher] writes and verifies every part, then a hard reset boots the new app and the
 * normal USB link comes back on its own attach intent.
 *
 * The USB-Serial/JTAG peripheral usually keeps its enumeration across the chip reset, so the same
 * port is tried first; if the device re-enumerates instead, the raw session's attach callback
 * hands over the new port.
 */
class PhoneFlasher(
    private val context: Context,
    private val linkManager: UsbLinkManager,
    private val catalogRepository: CatalogRepository,
    private val onStatus: (String) -> Unit,
) {
    private class RawPort(val port: UsbSerialPort, val connection: UsbDeviceConnection)

    /** Flashes [entry]; success carries the sha256 of the firmware part actually written (the build's identity). */
    suspend fun flash(entry: CatalogEntry): Result<String> = withContext(Dispatchers.IO) {
        // Download (or read from the verified cache) BEFORE touching the link: a missing network or a
        // stale catalog must never take the running USB session down.
        val loaded = try {
            catalogRepository.loadImages(entry, progress)
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            status("FAILED: ${e.message}")
            return@withContext Result.failure(e)
        }
        val images = loaded.images
        if (images.isEmpty()) {
            status("FAILED: no bin parts for ${entry.name}")
            return@withContext Result.failure(EspFlashException("no bin parts for ${entry.name}"))
        }
        // Every attach while the raw session is open lands here (the ROM after the reset dance, and
        // possibly the app firmware after the hard reset); the flow takes what it needs in order.
        val reattached = Channel<RawPort>(Channel.UNLIMITED)
        val client = object : UsbLinkManager.RawDeviceClient {
            override fun onDeviceReady(driver: UsbSerialDriver, connection: UsbDeviceConnection) {
                val port = driver.ports[0]
                runCatching { port.open(connection); port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
                    .onSuccess { reattached.trySend(RawPort(port, connection)) }
                    .onFailure { Log.w(TAG, "raw open failed: ${it.message}") }
            }
        }
        var current: RawPort? = null
        try {
            status("stopping the link, opening the port")
            linkManager.beginRawSession(client)
            current = openCurrent() ?: withTimeoutOrNull(5_000) { reattached.receive() }
                ?: return@withContext Result.failure(EspFlashException("no ESP on the USB port"))
            val link0 = UsbRomLink(current.port)
            status("resetting into the ROM bootloader")
            link0.resetIntoBootloader()
            Thread.sleep(300)
            // Same port first (enumeration usually survives the reset); else wait for the re-attach.
            var romPort = current
            var flasher = EspFlasher(UsbRomLink(romPort.port), onProgress = progress)
            val syncedOnSamePort = runCatching { flasher.sync(attempts = 5) }.isSuccess
            if (!syncedOnSamePort) {
                // Two cases land here: the chip re-enumerated (a few seconds), or the running firmware's USB is a
                // software CDC (UiFlow2 / MicroPython / TinyUSB) that never forwards DTR/RTS to the reset logic, so
                // nothing happened at all (StickS3 #2, 2026-09-05). Only a human can fix the second: hold BOOT and
                // replug -- the ROM then enumerates as USB-Serial/JTAG and the raw session's attach hands it over.
                status("no ROM bootloader yet -- if the board stays quiet, hold its BOOT button and replug it now (waiting ${REENUMERATE_WAIT_MS / 1000} s)")
                runCatching { romPort.port.close() }
                romPort = withTimeoutOrNull(REENUMERATE_WAIT_MS) { reattached.receive() }
                    ?: return@withContext Result.failure(EspFlashException("ROM bootloader did not come back on USB (hold BOOT while plugging the board in, then Flash again)"))
                current = romPort
                flasher = EspFlasher(UsbRomLink(romPort.port), onProgress = progress)
                flasher.sync()
            }
            flasher.requireEsp32s3()
            flasher.prepareFlash()
            for (img in images) flasher.writeImage(img)
            for (img in images) flasher.verify(img)
            status("all parts verified, rebooting the ESP")
            UsbRomLink(romPort.port).hardReset()
            Result.success(loaded.firmwareSha256)
        } catch (e: Exception) {
            Log.w(TAG, "flash failed: ${e.message}")
            status("FAILED: ${e.message}")
            Result.failure(e)
        } finally {
            runCatching { current?.port?.close() }
            linkManager.endRawSession()
            // A port opened for a stray attach (the app firmware coming back) must not hold the device.
            while (true) { val stray = reattached.tryReceive().getOrNull() ?: break; runCatching { stray.port.close() } }
        }
    }

    private fun openCurrent(): RawPort? {
        val driver = linkManager.findDevice() ?: return null
        val connection = linkManager.openDevice(driver.device) ?: return null
        val port = driver.ports[0]
        return runCatching {
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            RawPort(port, connection)
        }.getOrElse { Log.w(TAG, "open current failed: ${it.message}"); null }
    }

    private fun status(s: String) {
        Log.d(TAG, "flash: $s")
        onStatus(s)
    }

    /** Download + ROM progress in one shape: "msg (pct%)" while in flight, bare message at the ends. */
    private val progress: (String, Int) -> Unit = { msg, pct -> status(if (pct in 1..99) "$msg ($pct%)" else msg) }

    private companion object {
        const val TAG = "Droidputter"
        /** Long enough for a human to hold BOOT and replug; a real re-enumeration takes a few seconds. */
        const val REENUMERATE_WAIT_MS = 90_000L
    }
}
