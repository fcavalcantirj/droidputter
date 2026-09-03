#include "dp_keys.h"

namespace dp {

// A press must be reported by at least DP_KEYS_MIN_SEEN snapshots before its release takes effect:
// Keyboard_Class::isChange() only compares key-list sizes per update, so a down+up that lands
// between two app loops (an adb tap is ~4 ms; a fast finger can be < 40 ms) was invisible
// (2026-09-03, stellar-map on the Poco: 8 of 10 injected taps lost). Snapshot-counted, not timed,
// so it stays host-testable and follows the app's own update rate.
static uint8_t held_r[DP_KEYS_MAX];
static uint8_t held_c[DP_KEYS_MAX];
static uint8_t held_seen[DP_KEYS_MAX];     // snapshots that reported this key
static uint8_t held_pending[DP_KEYS_MAX];  // release received before MIN_SEEN snapshots
static uint8_t nheld = 0;

static void removeAt(uint8_t i) {
  for (uint8_t j = i; (uint8_t)(j + 1) < nheld; j++) {
    held_r[j] = held_r[j + 1]; held_c[j] = held_c[j + 1]; held_seen[j] = held_seen[j + 1]; held_pending[j] = held_pending[j + 1];
  }
  nheld--;
}

void dp_keys_push(uint8_t row, uint8_t col, uint8_t down) {
  uint8_t i = 0;
  for (; i < nheld; i++) if (held_r[i] == row && held_c[i] == col) break;
  if (down) {
    if (i == nheld) {
      if (nheld < DP_KEYS_MAX) { held_r[nheld] = row; held_c[nheld] = col; held_seen[nheld] = 0; held_pending[nheld] = 0; nheld++; }
    } else {
      held_pending[i] = 0;   // pressed again before the deferred release fired: stays held
    }
  } else if (i < nheld) {
    if (held_seen[i] >= DP_KEYS_MIN_SEEN) removeAt(i); else held_pending[i] = 1;
  }
}

uint8_t dp_keys_snapshot(uint8_t* rows, uint8_t* cols, uint8_t max) {
  uint8_t n = nheld < max ? nheld : max;
  for (uint8_t i = 0; i < n; i++) { rows[i] = held_r[i]; cols[i] = held_c[i]; }
  for (uint8_t i = 0; i < nheld; i++) if (held_seen[i] < 255) held_seen[i]++;
  for (uint8_t i = 0; i < nheld;) {
    if (held_pending[i] && held_seen[i] >= DP_KEYS_MIN_SEEN) removeAt(i); else i++;
  }
  return n;
}

void dp_keys_release_all() { nheld = 0; }

uint8_t dp_keys_held_count() { return nheld; }

}  // namespace dp
