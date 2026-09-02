package com.droidputter

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.droidputter.core.link.LinkEvent
import com.droidputter.core.link.LinkStateMachine
import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import com.droidputter.usb.UsbLinkManager
import kotlinx.coroutines.launch

private const val TAG = "Droidputter"

// Dumb shell scaffold; rendering/link-UI logic lands in later tasks. For now this just
// forwards USB bytes into the core Framer/decoder and logs what arrives, so the transport
// wiring is observable over logcat with real hardware.
class MainActivity : ComponentActivity() {
    private val stateMachine = LinkStateMachine()
    private lateinit var linkManager: UsbLinkManager

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
            onTransportOpened = { transport ->
                val framer = Framer()
                lifecycleScope.launch {
                    transport.incoming.collect { bytes ->
                        framer.feed(bytes).forEach { frame ->
                            val message = decodeDpMessage(frame)
                            if (message is DpMessage.Hello) stateMachine.handle(LinkEvent.HelloReceived)
                            Log.d(TAG, "decoded: $message")
                        }
                    }
                }
            },
            onTransportClosed = { Log.d(TAG, "usb transport closed") },
            onAction = { action -> Log.d(TAG, "link action: $action") },
        )
        linkManager.start()
    }

    override fun onDestroy() {
        linkManager.stop()
        super.onDestroy()
    }
}
