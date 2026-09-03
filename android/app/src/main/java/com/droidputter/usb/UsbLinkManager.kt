package com.droidputter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.droidputter.core.link.LinkAction
import com.droidputter.core.link.LinkEvent
import com.droidputter.core.link.LinkState
import com.droidputter.core.link.LinkStateMachine
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

private const val ACTION_USB_PERMISSION = "com.droidputter.USB_PERMISSION"
private const val ESP_VENDOR_ID = 0x303A
private const val ESP_PRODUCT_ID = 0x1001

/** Everything the connection screen needs to render, in one immutable snapshot. */
data class LinkStatus(
    val state: LinkState,
    val deviceName: String?,
    val permissionGranted: Boolean?,
    val missedPings: Int,
    val availableDevices: List<String>,
)

/**
 * Owns the USB attach/permission/open dance and drives [LinkStateMachine] with the result:
 * device plugged in -> [LinkEvent.DeviceAttached] (permission requested as its side effect),
 * permission dialog answered -> [LinkEvent.PermissionGranted]/[LinkEvent.PermissionDenied],
 * port opened -> [LinkEvent.Opened], unplugged (including an ESP-side reset, which
 * re-enumerates the same VID/PID and re-triggers the attach intent) -> [LinkEvent.Detached].
 * Actions returned by the state machine that this class does not itself cause (SEND_HELLO_ACK,
 * SEND_PING) are forwarded to [onAction] for the caller to execute. Every state/device/permission
 * change is mirrored into [onStatus] so a connection screen can render without polling.
 */
class UsbLinkManager(
    private val context: Context,
    private val stateMachine: LinkStateMachine,
    private val onTransportOpened: (UsbDpTransport) -> Unit,
    private val onTransportClosed: () -> Unit,
    private val onAction: (LinkAction) -> Unit = {},
    private val onStatus: (LinkStatus) -> Unit = {},
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber(
        ProbeTable().apply { addProduct(ESP_VENDOR_ID, ESP_PRODUCT_ID, CdcAcmSerialDriver::class.java) },
    )
    private val defaultProber = UsbSerialProber.getDefaultProber()

    private var transport: UsbDpTransport? = null
    private var started = false
    private var deviceName: String? = null
    private var permissionGranted: Boolean? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
            permissionGranted = granted
            dispatch(if (granted) LinkEvent.PermissionGranted else LinkEvent.PermissionDenied)
            if (granted && device != null) open(device)
            emitStatus()
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> findDevice()?.let { requestPermission(it.device) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    dispatch(LinkEvent.Detached)
                    closeTransport()
                }
            }
            emitStatus()
        }
    }

    /** Registers receivers and, if the ESP is already plugged in, kicks off permission/open. */
    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, attachReceiver, attachFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findDevice()?.let { driver ->
            dispatch(LinkEvent.DeviceAttached)
            requestPermission(driver.device)
        }
        emitStatus()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(attachReceiver) }
        closeTransport()
    }

    /** Manual "Reconnect" action: re-probes for the ESP and, if present, re-requests permission
     * even from [LinkState.ERROR] or [LinkState.RECONNECTING] where no OS intent will retry it
     * on its own (e.g. permission was denied once, or the state machine gave up after missed
     * pings but the device never physically detached). No-op if nothing is plugged in. */
    fun reconnect() {
        runCatching {
            findDevice()?.let { driver ->
                dispatch(LinkEvent.DeviceAttached)
                requestPermission(driver.device)
            }
            emitStatus()
        }.onFailure { android.util.Log.w("Droidputter", "reconnect failed: ${it.message}") }
    }

    private fun findDevice(): UsbSerialDriver? =
        usbManager.deviceList.values.firstNotNullOfOrNull { device ->
            prober.probeDevice(device) ?: defaultProber.probeDevice(device)
        }

    private fun requestPermission(device: UsbDevice) {
        // Android 14+ (targetSdk 34+) rejects FLAG_MUTABLE on an implicit intent; making the
        // intent explicit (setPackage) is what allows the system to still fill in
        // EXTRA_PERMISSION_GRANTED/EXTRA_DEVICE when it delivers the permission result.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun open(device: UsbDevice) {
        val driver = prober.probeDevice(device) ?: defaultProber.probeDevice(device) ?: return
        val connection = usbManager.openDevice(driver.device) ?: return
        val opened = UsbDpTransport(driver.ports[0], connection)
        transport = opened
        deviceName = device.productName ?: device.deviceName
        dispatch(LinkEvent.Opened)
        onTransportOpened(opened)
        emitStatus()
    }

    private fun closeTransport() {
        transport?.close()
        transport = null
        deviceName = null
        onTransportClosed()
    }

    /** The activity decodes frames; a HELLO means the ESP is up on this link. Routed through here so
     *  the published [LinkStatus] follows the state machine (OPENING -> LINKED), which is what
     *  starts the foreground service that keeps the USB link alive through screen-off/Doze. */
    fun onHelloReceived() {
        dispatch(LinkEvent.HelloReceived)
        emitStatus()
    }

    /** The reader thread died with an IOException (device gone or re-enumerating). Same handling as
     *  a physical detach: close the port and wait for the OS attach intent to bring it back. */
    fun onReaderFailed() {
        dispatch(LinkEvent.Detached)
        closeTransport()
        emitStatus()
    }

    private fun dispatch(event: LinkEvent) {
        stateMachine.handle(event).forEach(onAction)
    }

    private fun emitStatus() {
        val devices = usbManager.deviceList.values.map { it.productName ?: it.deviceName }
        onStatus(LinkStatus(stateMachine.state, deviceName, permissionGranted, stateMachine.missedPings, devices))
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? =
    @Suppress("DEPRECATION")
    getParcelableExtra(name)
