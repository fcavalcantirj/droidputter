#include "dp_gps.h"

namespace dp {

static uint8_t ring[DP_GPS_RING];
static size_t head = 0;  // next byte to read
static size_t used = 0;  // bytes currently buffered

bool dp_gps_push(const uint8_t* sentence, size_t len) {
  size_t total = len + 2;  // + CRLF
  if (total > DP_GPS_RING || used + total > DP_GPS_RING) return false;
  size_t tail = (head + used) % DP_GPS_RING;
  for (size_t i = 0; i < len; i++) { ring[tail] = sentence[i]; tail = (tail + 1) % DP_GPS_RING; }
  ring[tail] = '\r'; tail = (tail + 1) % DP_GPS_RING;
  ring[tail] = '\n'; tail = (tail + 1) % DP_GPS_RING;
  used += total;
  return true;
}

size_t dp_gps_available() { return used; }

int dp_gps_read() {
  if (!used) return -1;
  int b = ring[head];
  head = (head + 1) % DP_GPS_RING;
  used--;
  return b;
}

int dp_gps_peek() {
  if (!used) return -1;
  return ring[head];
}

void dp_gps_reset() { head = 0; used = 0; }

}  // namespace dp
