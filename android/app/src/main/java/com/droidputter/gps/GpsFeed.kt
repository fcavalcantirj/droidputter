package com.droidputter.gps

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import com.droidputter.core.gps.GpsFix
import com.droidputter.core.gps.NmeaNormalizer
import com.droidputter.core.gps.NmeaSynth

/** SPEC finding D: Android goes silent on `OnNmeaMessageListener` for fused/network fixes, so a
 * synthesized sentence only kicks in once raw NMEA has actually stopped arriving for this long. */
private const val NMEA_SILENCE_FALLBACK_MS = 3_000L
private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L

enum class GpsSentenceSource { RAW_NMEA, SYNTHESIZED }

/** What the connection screen shows -- last sentence sent and where it came from, no protocol
 * logic (that lives in :core's NmeaSynth/NmeaNormalizer, this class only wires Android's
 * LocationManager into them). */
data class GpsFeedStatus(
    val active: Boolean,
    val lastSentence: String?,
    val lastSource: GpsSentenceSource?,
    val satellitesInUse: Int,
)

/**
 * Streams the phone's location as NMEA sentences: prefers Android's raw `OnNmeaMessageListener`
 * output (normalised `$GN` -> `$GP`, see [NmeaNormalizer]), and falls back to synthesizing
 * GGA/RMC from plain [GPS_PROVIDER][LocationManager.GPS_PROVIDER] fixes via [NmeaSynth] when no
 * raw sentence has arrived recently. Caller must hold ACCESS_FINE_LOCATION before calling
 * [start] -- checking that permission is an Activity concern, not this class's.
 */
@SuppressLint("MissingPermission")
class GpsFeed(
    private val locationManager: LocationManager,
    private val onSentence: (String, GpsSentenceSource) -> Unit,
    private val onStatus: (GpsFeedStatus) -> Unit = {},
) {
    private var active = false
    private var lastRawNmeaAtMillis = 0L
    private var satellitesInUse = 0

    private val nmeaListener = OnNmeaMessageListener { message, timestamp ->
        lastRawNmeaAtMillis = timestamp
        emit(NmeaNormalizer.normalize(message), GpsSentenceSource.RAW_NMEA)
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesInUse = used
        }
    }

    private val locationListener = LocationListener { location ->
        val silentForMs = System.currentTimeMillis() - lastRawNmeaAtMillis
        if (silentForMs < NMEA_SILENCE_FALLBACK_MS) return@LocationListener
        val fix = location.toGpsFix(satellitesInUse)
        emit(NmeaSynth.gpgga(fix), GpsSentenceSource.SYNTHESIZED)
        emit(NmeaSynth.gprmc(fix), GpsSentenceSource.SYNTHESIZED)
    }

    fun start() {
        if (active) return
        active = true
        lastRawNmeaAtMillis = 0L
        satellitesInUse = 0
        locationManager.addNmeaListener(nmeaListener, null)
        locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            LOCATION_UPDATE_INTERVAL_MS,
            0f,
            locationListener,
        )
        // Indoors the phone often has only a network/fused fix and raw GPS never arrives; feed
        // those fixes through the same synthesized-NMEA path so the ESP still gets a position.
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                locationListener,
            )
        }
        onStatus(GpsFeedStatus(active = true, lastSentence = null, lastSource = null, satellitesInUse = 0))
    }

    fun stop() {
        if (!active) return
        active = false
        locationManager.removeNmeaListener(nmeaListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        locationManager.removeUpdates(locationListener)
        onStatus(GpsFeedStatus(active = false, lastSentence = null, lastSource = null, satellitesInUse = 0))
    }

    private fun emit(sentence: String, source: GpsSentenceSource) {
        onSentence(sentence, source)
        onStatus(GpsFeedStatus(active = true, lastSentence = sentence, lastSource = source, satellitesInUse))
    }
}

private fun Location.toGpsFix(satellites: Int) = GpsFix(
    latDeg = latitude,
    lonDeg = longitude,
    altitudeMeters = if (hasAltitude()) altitude else 0.0,
    speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
    bearingDeg = if (hasBearing()) bearing.toDouble() else 0.0,
    timeMillis = time,
    accuracyMeters = if (hasAccuracy()) accuracy else 0f,
    satellites = satellites,
)
