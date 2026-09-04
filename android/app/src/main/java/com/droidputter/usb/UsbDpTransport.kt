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
    private var rawChunksLogged = 0
    // ESP console lines (boot banner, Guru Meditation, Backtrace) hidden inside the raw stream; a prebuilt
    // app or a crashing shim build is otherwise just silence on the phone. Lines go to logcat as "esp: ..."
    // and to [onEspLine] for the flash verdicts.
    private val sniffer = com.droidputter.core.link.PanicSniffer()
    var onEspLine: ((String) -> Unit)? = null

    init {
        port.open(connection)
        port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.setDTR(true)
        port.setRTS(true)
    }

    override fun write(bytes: ByteArray) {
        // A dead/re-enumerating port throws IOException; the link manager's detach path handles it.
        runCatching { port.write(bytes, WRITE_TIMEOUT_MS) }.onFailure { android.util.Log.w("Droidputter", "usb write failed: ${it.message}") }
    }

    override val incoming: Flow<ByteArray> = callbackFlow {
        val manager = SerialInputOutputManager(
            port,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    // Raw visibility for link triage: the first chunks after open, hex + ASCII, so
                    // an ESP boot banner ("rst:0x.. boot:0x.."), a panic ("Guru Meditation") or the
                    // ROM's "waiting for download" is readable in logcat even though the Framer
                    // discards anything that is not a DP frame.
                    if (rawChunksLogged < RAW_LOG_CHUNKS) {
                        rawChunksLogged++
                        val head = data.copyOf(minOf(data.size, RAW_LOG_BYTES))
                        val hex = head.joinToString(" ") { "%02x".format(it) }
                        val ascii = String(head.map { b -> if (b in 0x20..0x7e) b.toInt().toChar() else '.' }.toCharArray())
                        android.util.Log.d("Droidputter", "raw#$rawChunksLogged ${data.size}B: $hex | $ascii")
                    }
                    for (line in sniffer.feed(data)) {
                        android.util.Log.w("Droidputter", "esp: $line")
                        onEspLine?.invoke(line)
                    }
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
        const val RAW_LOG_CHUNKS = 40
        const val RAW_LOG_BYTES = 96
    }
}
