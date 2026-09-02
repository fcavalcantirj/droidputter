package com.droidputter.core.gps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun fields(sentence: String): List<String> {
    val body = sentence.removePrefix("$").substringBefore("*")
    return body.split(",")
}

class NmeaSynthTest {
    // Classic NMEA reference GGA sentence (widely published, also used for TinyGPSPlus's own
    // canonical test in this repo's gps-demo, task 16): $GPGGA,...*47.
    @Test
    fun `checksum matches a known-good GGA reference sentence`() {
        val body = "GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        assertEquals(0x47, NmeaSynth.checksum(body))
    }

    // Classic NMEA reference RMC sentence: $GPRMC,...*68.
    @Test
    fun `checksum matches a known-good RMC reference sentence`() {
        val body = "GPRMC,225446,A,4916.45,N,12311.12,W,000.5,054.7,191194,020.3,E"
        assertEquals(0x68, NmeaSynth.checksum(body))
    }

    @Test
    fun `gpgga and gprmc carry a valid recomputable checksum`() {
        val fix = GpsFix(48.1173, 11.5166, 545.4, 10.0, 90.0, 1_000_000_000_000L, 5.0f, 8)
        for (sentence in listOf(NmeaSynth.gpgga(fix), NmeaSynth.gprmc(fix))) {
            val body = sentence.removePrefix("$").substringBefore("*")
            val claimed = sentence.substringAfter("*")
            assertEquals(claimed, "%02X".format(NmeaSynth.checksum(body)))
        }
    }

    @Test
    fun `southern and western hemisphere produce S and W`() {
        val fix = GpsFix(-22.9, -43.2, 0.0, 0.0, 0.0, 0L, 5.0f, 4)
        val gga = fields(NmeaSynth.gpgga(fix))
        assertEquals("S", gga[3])
        assertEquals("W", gga[5])

        val rmc = fields(NmeaSynth.gprmc(fix))
        assertEquals("S", rmc[4])
        assertEquals("W", rmc[6])
    }

    @Test
    fun `northern and eastern hemisphere produce N and E`() {
        val fix = GpsFix(48.1173, 11.5166, 0.0, 0.0, 0.0, 0L, 5.0f, 4)
        val gga = fields(NmeaSynth.gpgga(fix))
        assertEquals("N", gga[3])
        assertEquals("E", gga[5])
    }

    @Test
    fun `zero satellites produce GGA fix quality 0`() {
        val fix = GpsFix(48.1173, 11.5166, 0.0, 0.0, 0.0, 0L, 5.0f, 0)
        val gga = fields(NmeaSynth.gpgga(fix))
        assertEquals("0", gga[6])
        assertEquals("00", gga[7])
    }

    @Test
    fun `zero satellites produce RMC status V, otherwise A`() {
        val noFix = fields(NmeaSynth.gprmc(GpsFix(0.0, 0.0, 0.0, 0.0, 0.0, 0L, 5.0f, 0)))
        assertEquals("V", noFix[2])

        val withFix = fields(NmeaSynth.gprmc(GpsFix(0.0, 0.0, 0.0, 0.0, 0.0, 0L, 5.0f, 6)))
        assertEquals("A", withFix[2])
    }

    @Test
    fun `GNGGA talker id is rewritten to GPGGA with a recomputed checksum`() {
        val raw = "\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*59"
        val expected = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        assertEquals(expected, NmeaNormalizer.normalize(raw))
    }

    @Test
    fun `a non-GN sentence passes through unchanged`() {
        val gp = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        assertEquals(gp, NmeaNormalizer.normalize(gp))
    }

    @Test
    fun `GNRMC talker id is also rewritten to GPRMC`() {
        val raw = "\$GNRMC,225446,A,4916.45,N,12311.12,W,000.5,054.7,191194,020.3,E*4B"
        val out = NmeaNormalizer.normalize(raw)
        assertTrue(out.startsWith("\$GPRMC,"))
        val body = out.removePrefix("$").substringBefore("*")
        assertEquals(out.substringAfter("*"), "%02X".format(NmeaSynth.checksum(body)))
    }
}
