package com.droidputter.core.gps

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * A single location sample, as read off Android's FusedLocationProvider / LocationManager
 * (no android.* import here: the app layer converts a Location into this before calling
 * [NmeaSynth], per the golden rule that :core stays pure JVM).
 */
data class GpsFix(
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeMeters: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val timeMillis: Long,
    val accuracyMeters: Float,
    val satellites: Int,
)

/** Builds $GPGGA/$GPRMC sentences from a [GpsFix] -- used when Android's raw NMEA listener
 * (see [NmeaNormalizer]) yields nothing for a fix (SPEC finding D: fused/network fixes are
 * silent on NMEA). One sentence per call; the app decides the 1 Hz cadence. */
object NmeaSynth {
    /** XOR of every byte in [body] (the sentence with no leading '$' and no trailing '*checksum'). */
    fun checksum(body: String): Int {
        var c = 0
        for (ch in body) c = c xor ch.code
        return c
    }

    /** Wraps [body] as a full sentence: "$" + body + "*" + the 2-digit uppercase hex checksum. */
    fun withChecksum(body: String): String = "$" + body + "*" + "%02X".format(checksum(body))

    fun gpgga(fix: GpsFix): String {
        val (lat, latHemi) = encodeLat(fix.latDeg)
        val (lon, lonHemi) = encodeLon(fix.lonDeg)
        val quality = if (fix.satellites <= 0) 0 else 1
        val numSat = "%02d".format(fix.satellites.coerceAtLeast(0))
        val body = "GPGGA,${utcTime(fix.timeMillis)},$lat,$latHemi,$lon,$lonHemi,$quality,$numSat,,${"%.1f".format(fix.altitudeMeters)},M,,M,,"
        return withChecksum(body)
    }

    fun gprmc(fix: GpsFix): String {
        val (lat, latHemi) = encodeLat(fix.latDeg)
        val (lon, lonHemi) = encodeLon(fix.lonDeg)
        val status = if (fix.satellites <= 0) "V" else "A"
        val knots = fix.speedMps * 1.9438444924406
        val body = "GPRMC,${utcTime(fix.timeMillis)},$status,$lat,$latHemi,$lon,$lonHemi," +
            "${"%05.1f".format(knots)},${"%05.1f".format(fix.bearingDeg)},${utcDate(fix.timeMillis)},,"
        return withChecksum(body)
    }

    private fun encodeLat(latDeg: Double): Pair<String, Char> {
        val hemi = if (latDeg >= 0) 'N' else 'S'
        return encodeDegrees(latDeg, degreeDigits = 2) to hemi
    }

    private fun encodeLon(lonDeg: Double): Pair<String, Char> {
        val hemi = if (lonDeg >= 0) 'E' else 'W'
        return encodeDegrees(lonDeg, degreeDigits = 3) to hemi
    }

    private fun encodeDegrees(deg: Double, degreeDigits: Int): String {
        val absDeg = abs(deg)
        val whole = absDeg.toInt()
        val minutes = (absDeg - whole) * 60.0
        return whole.toString().padStart(degreeDigits, '0') + "%07.4f".format(minutes)
    }

    private fun utcTime(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        val centis = t.nano / 10_000_000
        return "%02d%02d%02d.%02d".format(t.hour, t.minute, t.second, centis)
    }

    private fun utcDate(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        return "%02d%02d%02d".format(t.dayOfMonth, t.monthValue, t.year % 100)
    }
}
