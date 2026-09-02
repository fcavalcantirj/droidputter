// GPS_NMEA (0x82) inbound ring: whole NMEA sentences from the phone, queued as
// raw bytes (sentence + CRLF) for an app to read like a GPS UART. Pure C++17,
// no Arduino headers, host-testable under `pio test -e native`. The Arduino
// Stream wrapper (droidputter_gps()) lives in droidputter.cpp, guarded by
// #ifdef ARDUINO, and calls straight through to these functions.
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace dp {

constexpr size_t DP_GPS_RING = 1024;

// Appends one sentence (no CRLF, per docs/PROTOCOL.md 0x82) plus a trailing
// CRLF, atomically. If `len` + 2 does not fit in the ring's current free
// space, nothing is written -- the whole incoming sentence is dropped, never
// a partial one, so a reader never sees a sentence missing its terminator or
// a terminator missing its sentence. Returns true if the sentence was queued.
bool dp_gps_push(const uint8_t* sentence, size_t len);

size_t dp_gps_available();
int dp_gps_read();   // -1 if empty
int dp_gps_peek();   // -1 if empty, does not consume

void dp_gps_reset();  // test hook

}  // namespace dp
