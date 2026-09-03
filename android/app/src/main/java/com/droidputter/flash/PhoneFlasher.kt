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

    suspend fun flash(entry: CatalogEntry): Result<Unit> = withContext(Dispatchers.IO) {
        val images = catalogRepository.loadImages(entry)
        if (images.isEmpty()) return@withContext Result.failure(EspFlashException("no bin parts bundled for ${entry.name}"))
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
            var flasher = EspFlasher(UsbRomLink(romPort.port), onProgress = { msg, pct -> status(if (pct in 1..99) "$msg ($pct%)" else msg) })
            val syncedOnSamePort = runCatching { flasher.sync(attempts = 5) }.isSuccess
            if (!syncedOnSamePort) {
                status("waiting for the bootloader to re-enumerate")
                runCatching { romPort.port.close() }
                romPort = withTimeoutOrNull(10_000) { reattached.receive() }
                    ?: return@withContext Result.failure(EspFlashException("ROM bootloader did not come back on USB"))
                current = romPort
                flasher = EspFlasher(UsbRomLink(romPort.port), onProgress = { msg, pct -> status(if (pct in 1..99) "$msg ($pct%)" else msg) })
                flasher.sync()
            }
            flasher.requireEsp32s3()
            flasher.prepareFlash()
            for (img in images) flasher.writeImage(img)
            for (img in images) flasher.verify(img)
            status("all parts verified, rebooting the ESP")
            UsbRomLink(romPort.port).hardReset()
            Result.success(Unit)
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

    private companion object {
        const val TAG = "Droidputter"
    }
}
