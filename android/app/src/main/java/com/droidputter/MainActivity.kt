package com.droidputter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.droidputter.core.link.LinkAction
import com.droidputter.core.link.LinkEvent
import com.droidputter.core.link.LinkStateMachine
import com.droidputter.core.link.encodeHelloAck
import com.droidputter.core.link.encodePingIn
import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import com.droidputter.usb.UsbDpTransport
import com.droidputter.usb.UsbLinkManager
import kotlinx.coroutines.launch

private const val TAG = "Droidputter"

// Dumb shell scaffold; rendering/link-UI logic lands in later tasks. For now this just
// forwards USB bytes into the core Framer/decoder and logs what arrives, so the transport
// wiring is observable over logcat with real hardware.
class MainActivity : ComponentActivity() {
    private val stateMachine = LinkStateMachine()
    private lateinit var linkManager: UsbLinkManager
    private var transport: UsbDpTransport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Droidputter")
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
                            val message = decodeDpMessage(frame)
                            if (message is DpMessage.Hello) stateMachine.handle(LinkEvent.HelloReceived)
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
}
