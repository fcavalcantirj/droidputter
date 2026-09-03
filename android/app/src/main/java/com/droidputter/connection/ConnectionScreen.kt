package com.droidputter.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidputter.core.link.LinkRates
import com.droidputter.core.link.LinkState
import com.droidputter.gps.GpsFeedStatus
import com.droidputter.gps.GpsSentenceSource
import com.droidputter.usb.LinkStatus
import java.util.Locale

/**
 * Everything the golden rule keeps out of :core (device names, permission dialogs) surfaces
 * here as plain state from [LinkStatus]/[LinkRates] -- this composable holds no protocol logic
 * of its own, just formatting and the two user actions the task calls for.
 */
@Composable
fun ConnectionScreen(
    status: LinkStatus,
    rates: LinkRates,
    gpsStatus: GpsFeedStatus,
    onReconnect: () -> Unit,
    onResendHelloAck: () -> Unit,
    onToggleGps: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()

        Text("Link state: ${linkStateLabel(status.state)}")
        Text("Device: ${status.deviceName ?: "none"}")
        Text("Permission: ${permissionLabel(status.permissionGranted)}")
        Text("Missed pings: ${status.missedPings}")

        HorizontalDivider()
        Text("STATS", style = MaterialTheme.typography.titleMedium)
        Text("fps: ${"%.1f".format(Locale.US, rates.fps)}")
        Text("throughput: ${"%.1f".format(Locale.US, rates.kbPerSec)} KB/s")
        Text("dropped: ${rates.dropped}")

        HorizontalDivider()
        Text("Devices seen by Android", style = MaterialTheme.typography.titleMedium)
        if (status.availableDevices.isEmpty()) {
            Text("(none)")
        } else {
            status.availableDevices.forEach { name -> Text("- $name") }
        }

        HorizontalDivider()
        Text("GPS feed", style = MaterialTheme.typography.titleMedium)
        Text("streaming: ${if (gpsStatus.active) "on" else "off"}")
        Text("last sentence: ${gpsStatus.lastSentence ?: "(none)"}")
        Text("source: ${gpsSourceLabel(gpsStatus.lastSource)}")
        Text("satellites in use: ${gpsStatus.satellitesInUse}")

        HorizontalDivider()
        Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
            Text("Reconnect")
        }
        OutlinedButton(onClick = onResendHelloAck, modifier = Modifier.fillMaxWidth()) {
            Text("Send HELLO_ACK again")
        }
        OutlinedButton(onClick = onToggleGps, modifier = Modifier.fillMaxWidth()) {
            Text(if (gpsStatus.active) "Stop GPS feed" else "Start GPS feed")
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

private fun linkStateLabel(state: LinkState): String = state.name.lowercase(Locale.US).replace('_', ' ')

private fun permissionLabel(granted: Boolean?): String = when (granted) {
    true -> "granted"
    false -> "denied"
    null -> "not requested"
}

private fun gpsSourceLabel(source: GpsSentenceSource?): String = when (source) {
    GpsSentenceSource.RAW_NMEA -> "raw NMEA"
    GpsSentenceSource.SYNTHESIZED -> "synthesized"
    null -> "(none yet)"
}
