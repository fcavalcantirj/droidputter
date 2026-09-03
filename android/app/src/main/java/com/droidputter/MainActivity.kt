package com.droidputter

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.droidputter.connection.ConnectionScreen
import com.droidputter.core.keys.AndroidKeyMap
import com.droidputter.core.keys.encodeKey
import com.droidputter.core.link.LinkAction
import com.droidputter.core.link.LinkEvent
import com.droidputter.core.link.LinkRates
import com.droidputter.core.link.LinkState
import com.droidputter.core.link.LinkStateMachine
import com.droidputter.core.link.LinkStatsTracker
import com.droidputter.core.link.encodeGpsNmea
import com.droidputter.core.link.encodeHelloAck
import com.droidputter.core.link.encodePingIn
import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import com.droidputter.core.transport.FixtureTransport
import com.droidputter.gps.GpsFeed
import com.droidputter.gps.GpsFeedStatus
import com.droidputter.gps.GpsSentenceSource
import com.droidputter.keyboard.SoftKeyboard
import com.droidputter.link.LinkForegroundService
import com.droidputter.render.DroidputterScreen
import com.droidputter.render.ScreenController
import com.droidputter.usb.LinkStatus
import com.droidputter.usb.UsbDpTransport
import com.droidputter.usb.UsbLinkManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Droidputter"
private val DEMO_FIXTURE_ASSET_FILES = listOf("boot.bin", "boot.jsonl")
private const val DEMO_FIXTURE_ASSET_DIR = "fixtures/pense-bem"

// Dumb shell: rendering lives in render/ (Bitmap + Compose canvas), protocol decoding in
// :core (Framer/DpMessage/ScreenModel) -- this class only forwards bytes between whichever
// transport is active (real USB, or the bundled fixture in demo mode) and those two.
class MainActivity : ComponentActivity() {
    private val stateMachine = LinkStateMachine()
    private val screenController = ScreenController()
    private val statsTracker = LinkStatsTracker()
    private lateinit var linkManager: UsbLinkManager
    private var transport: UsbDpTransport? = null
    private var demoJob: Job? = null

    private var connectionStatus: LinkStatus by mutableStateOf(
        LinkStatus(LinkState.DETACHED, null, null, 0, emptyList()),
    )
    private var linkRates: LinkRates by mutableStateOf(LinkRates(0.0, 0.0, 0))
    private var showConnectionScreen: Boolean by mutableStateOf(false)

    private var gpsStatus: GpsFeedStatus by mutableStateOf(
        GpsFeedStatus(active = false, lastSentence = null, lastSource = null, satellitesInUse = 0),
    )
    private val gpsFeed: GpsFeed by lazy {
        GpsFeed(
            locationManager = getSystemService(LocationManager::class.java),
            onSentence = ::sendGpsSentence,
            onStatus = { gpsStatus = it },
        )
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denial just hides the notification */ }
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) gpsFeed.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ needs this granted at runtime for LinkForegroundService's notification to
        // actually show; the foreground service itself still runs fine either way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface {
                    val controller = remember { screenController }
                    if (showConnectionScreen) {
                        ConnectionScreen(
                            status = connectionStatus,
                            rates = linkRates,
                            gpsStatus = gpsStatus,
                            onReconnect = { linkManager.reconnect() },
                            onResendHelloAck = ::sendHelloAckNow,
                            onToggleGps = ::toggleGpsFeed,
                            onClose = { showConnectionScreen = false },
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).padding(0.dp)) {
                                DroidputterScreen(controller)
                                Button(
                                    onClick = { showConnectionScreen = true },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                ) {
                                    Text(connectionStatus.state.name)
                                }
                                Button(
                                    onClick = { startDemoReplay() },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                                ) {
                                    Text("Replay fixture")
                                }
                            }
                            SoftKeyboard(onKey = ::sendKey, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        linkManager = UsbLinkManager(
            context = this,
            stateMachine = stateMachine,
            onTransportOpened = { opened ->
                transport = opened
                // A phone opening the port after the ESP's boot-time HELLO already drained its
                // TX ring never sees one otherwise (the ESP only resends HELLO on HELLO_ACK/PING_IN,
                // see droidputter.cpp:onFrame) -- so probe for it immediately.
                opened.write(encodePingIn())
                // The ESP's display tee stays OFF until it receives HELLO_ACK (droidputter.cpp:
                // internal::linked). PING_IN only makes it answer HELLO. So ACK the first HELLO of
                // every link, once (the ESP replies to HELLO_ACK with another HELLO -- ignore that).
                var ackedThisLink = false
                val framer = Framer()
                lifecycleScope.launch {
                    opened.incoming.collect { bytes ->
                        framer.feed(bytes).forEach { frame ->
                            val message = decodeDpMessage(frame) ?: return@forEach
                            if (message is DpMessage.Hello) {
                                stateMachine.handle(LinkEvent.HelloReceived)
                                if (!ackedThisLink) {
                                    ackedThisLink = true
                                    val m = resources.displayMetrics
                                    opened.write(encodeHelloAck(m.widthPixels, m.heightPixels))
                                    Log.d(TAG, "sent HELLO_ACK (link up)")
                                }
                            }
                            if (message is DpMessage.Stats) {
                                linkRates = statsTracker.onStats(
                                    message.frames,
                                    message.bytes,
                                    message.dropped,
                                    System.currentTimeMillis(),
                                )
                            }
                            screenController.onMessage(message)
                            Log.d(TAG, "decoded: $message")
                        }
                    }
                }
            },
            onTransportClosed = {
                transport = null
                Log.d(TAG, "usb transport closed")
            },
            onAction = { action ->
                Log.d(TAG, "link action: $action")
                when (action) {
                    LinkAction.SEND_HELLO_ACK -> sendHelloAckNow()
                    LinkAction.SEND_PING -> transport?.write(encodePingIn())
                    else -> {}
                }
            },
            onStatus = { status ->
                connectionStatus = status
                // The foreground service is what keeps the link alive once the screen turns
                // off -- start it only while actually Linked, so it never lingers after a
                // detach/error and the OS doesn't see an idle foreground service.
                if (status.state == LinkState.LINKED) {
                    LinkForegroundService.start(this, "Linked to ${status.deviceName ?: "device"}")
                } else {
                    LinkForegroundService.stop(this)
                }
            },
        )
        linkManager.start()
    }

    override fun onDestroy() {
        linkManager.stop()
        gpsFeed.stop()
        LinkForegroundService.stop(this)
        super.onDestroy()
    }

    /** Connection screen's "Start/Stop GPS feed" button: requests ACCESS_FINE_LOCATION on first
     * use (a denial just leaves the feed off, same pattern as the notification permission), then
     * toggles [GpsFeed] itself. */
    private fun toggleGpsFeed() {
        if (gpsStatus.active) {
            gpsFeed.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            gpsFeed.start()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** [GpsFeed]'s sentence callback: only actually goes out over the wire while linked (the
     * task's "streams ... while linked"), so a fix arriving before/after a link drop is not lost
     * work, just silently not written -- gpsStatus above still reflects it landed on the phone. */
    private fun sendGpsSentence(sentence: String, @Suppress("UNUSED_PARAMETER") source: GpsSentenceSource) {
        if (connectionStatus.state == LinkState.LINKED) {
            transport?.write(encodeGpsNmea(sentence))
        }
    }

    /** Shared by the soft keyboard and hardware-keyboard passthrough below: both just need to
     * turn a Cardputer (row, col, down) into a KEY frame on whatever transport is open. */
    private fun sendKey(row: Int, col: Int, down: Boolean) {
        transport?.write(encodeKey(row, col, down))
    }

    /** The resync action (task's "Send HELLO_ACK again"): the ESP repaints the whole screen on
     * every HELLO_ACK it receives (droidputter.cpp's link-up resync), so replaying this is also
     * the manual "redraw everything" button when the phone's own copy looks stale. */
    private fun sendHelloAckNow() {
        val metrics = resources.displayMetrics
        transport?.write(encodeHelloAck(metrics.widthPixels, metrics.heightPixels))
    }

    // Hardware-keyboard passthrough (Bluetooth/USB keyboard attached to the phone itself, not
    // the Cardputer's own matrix): repeats are swallowed so a held key sends one KEY down, not a
    // flood of them; codes with no Cardputer position (AndroidKeyMap.position == null) fall
    // through to the default handler untouched.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && event.repeatCount == 0) {
            AndroidKeyMap.position(keyCode)?.let { (row, col) ->
                sendKey(row, col, true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        AndroidKeyMap.position(keyCode)?.let { (row, col) ->
            sendKey(row, col, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** No-USB demo mode: replays the bundled fixture through the same Framer/ScreenController
     * pipeline as a real link, so the renderer is verifiable on the emulator. */
    private fun startDemoReplay() {
        demoJob?.cancel()
        demoJob = lifecycleScope.launch {
            val basePath = withContext(Dispatchers.IO) { copyDemoFixtureToCache() }
            val framer = Framer()
            FixtureTransport(basePath).incoming.collect { bytes ->
                framer.feed(bytes).forEach { frame ->
                    decodeDpMessage(frame)?.let { screenController.onMessage(it) }
                }
            }
        }
    }

    private fun copyDemoFixtureToCache(): String {
        val dir = File(cacheDir, DEMO_FIXTURE_ASSET_DIR).apply { mkdirs() }
        for (name in DEMO_FIXTURE_ASSET_FILES) {
            assets.open("$DEMO_FIXTURE_ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        return File(dir, "boot").path
    }
}
