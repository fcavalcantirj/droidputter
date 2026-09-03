package com.droidputter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.droidputter.core.link.LinkAction
import com.droidputter.core.link.LinkEvent
import com.droidputter.core.link.LinkStateMachine
import com.droidputter.core.link.encodeHelloAck
import com.droidputter.core.link.encodePingIn
import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import com.droidputter.core.transport.FixtureTransport
import com.droidputter.render.DroidputterScreen
import com.droidputter.render.ScreenController
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
    private lateinit var linkManager: UsbLinkManager
    private var transport: UsbDpTransport? = null
    private var demoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val controller = remember { screenController }
                    Box(Modifier.padding(0.dp)) {
                        DroidputterScreen(controller)
                        Button(
                            onClick = { startDemoReplay() },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) {
                            Text("Replay fixture")
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
                val framer = Framer()
                lifecycleScope.launch {
                    opened.incoming.collect { bytes ->
                        framer.feed(bytes).forEach { frame ->
                            val message = decodeDpMessage(frame) ?: return@forEach
                            if (message is DpMessage.Hello) stateMachine.handle(LinkEvent.HelloReceived)
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
                    LinkAction.SEND_HELLO_ACK -> {
                        val metrics = resources.displayMetrics
                        transport?.write(encodeHelloAck(metrics.widthPixels, metrics.heightPixels))
                    }
                    LinkAction.SEND_PING -> transport?.write(encodePingIn())
                    else -> {}
                }
            },
        )
        linkManager.start()
    }

    override fun onDestroy() {
        linkManager.stop()
        super.onDestroy()
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
