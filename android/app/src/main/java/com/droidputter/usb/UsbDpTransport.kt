package com.droidputter.usb

import android.hardware.usb.UsbDeviceConnection
import com.droidputter.core.transport.DpTransport
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * CDC-ACM link to the ESP over USB-OTG: one background reader thread
 * ([SerialInputOutputManager]) feeds [incoming], [write] calls block on the port directly.
 */
class UsbDpTransport(
    private val port: UsbSerialPort,
    connection: UsbDeviceConnection,
) : DpTransport {
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "dp-usb-reader") }
    private var ioManager: SerialInputOutputManager? = null

    init {
        port.open(connection)
        port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.setDTR(true)
        port.setRTS(true)
    }

    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override val incoming: Flow<ByteArray> = callbackFlow {
        val manager = SerialInputOutputManager(
            port,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    trySend(data)
                }

                override fun onRunError(e: Exception) {
                    close(e)
                }
            },
        )
        ioManager = manager
        ioExecutor.execute(manager)
        awaitClose { manager.stop() }
    }

    /** Stops the reader thread and releases the port; safe to call more than once. */
    fun close() {
        ioManager?.stop()
        runCatching { port.purgeHwBuffers(true, true) }
        runCatching { port.close() }
        ioExecutor.shutdown()
    }

    private companion object {
        const val BAUD_RATE = 115200
        const val WRITE_TIMEOUT_MS = 200
    }
}
