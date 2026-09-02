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
import com.droidputter.core.link.LinkStateMachine
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

private const val ACTION_USB_PERMISSION = "com.droidputter.USB_PERMISSION"
private const val ESP_VENDOR_ID = 0x303A
private const val ESP_PRODUCT_ID = 0x1001

/**
 * Owns the USB attach/permission/open dance and drives [LinkStateMachine] with the result:
 * device plugged in -> [LinkEvent.DeviceAttached] (permission requested as its side effect),
 * permission dialog answered -> [LinkEvent.PermissionGranted]/[LinkEvent.PermissionDenied],
 * port opened -> [LinkEvent.Opened], unplugged (including an ESP-side reset, which
 * re-enumerates the same VID/PID and re-triggers the attach intent) -> [LinkEvent.Detached].
 * Actions returned by the state machine that this class does not itself cause (SEND_HELLO_ACK,
 * SEND_PING) are forwarded to [onAction] for the caller to execute.
 */
class UsbLinkManager(
    private val context: Context,
    private val stateMachine: LinkStateMachine,
    private val onTransportOpened: (UsbDpTransport) -> Unit,
    private val onTransportClosed: () -> Unit,
    private val onAction: (LinkAction) -> Unit = {},
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber(
        ProbeTable().apply { addProduct(ESP_VENDOR_ID, ESP_PRODUCT_ID, CdcAcmSerialDriver::class.java) },
    )
    private val defaultProber = UsbSerialProber.getDefaultProber()

    private var transport: UsbDpTransport? = null
    private var started = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
            dispatch(if (granted) LinkEvent.PermissionGranted else LinkEvent.PermissionDenied)
            if (granted && device != null) open(device)
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
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(attachReceiver) }
        closeTransport()
    }

    private fun findDevice(): UsbSerialDriver? =
        usbManager.deviceList.values.firstNotNullOfOrNull { device ->
            prober.probeDevice(device) ?: defaultProber.probeDevice(device)
        }

    private fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun open(device: UsbDevice) {
        val driver = prober.probeDevice(device) ?: defaultProber.probeDevice(device) ?: return
        val connection = usbManager.openDevice(driver.device) ?: return
        val opened = UsbDpTransport(driver.ports[0], connection)
        transport = opened
        dispatch(LinkEvent.Opened)
        onTransportOpened(opened)
    }

    private fun closeTransport() {
        transport?.close()
        transport = null
        onTransportClosed()
    }

    private fun dispatch(event: LinkEvent) {
        stateMachine.handle(event).forEach(onAction)
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? =
    @Suppress("DEPRECATION")
    getParcelableExtra(name)
