#include "dp_keys.h"

namespace dp {

static uint8_t held_r[DP_KEYS_MAX];
static uint8_t held_c[DP_KEYS_MAX];
static uint8_t nheld = 0;

void dp_keys_push(uint8_t row, uint8_t col, uint8_t down) {
  uint8_t i = 0;
  for (; i < nheld; i++) if (held_r[i] == row && held_c[i] == col) break;
  if (down) {
    if (i == nheld && nheld < DP_KEYS_MAX) { held_r[nheld] = row; held_c[nheld] = col; nheld++; }
    // else: already held (duplicate down) -- ignored, key stays held once.
  } else if (i < nheld) {
    for (uint8_t j = i; (uint8_t)(j + 1) < nheld; j++) { held_r[j] = held_r[j + 1]; held_c[j] = held_c[j + 1]; }
    nheld--;
  }
}

uint8_t dp_keys_snapshot(uint8_t* rows, uint8_t* cols, uint8_t max) {
  uint8_t n = nheld < max ? nheld : max;
  for (uint8_t i = 0; i < n; i++) { rows[i] = held_r[i]; cols[i] = held_c[i]; }
  return n;
}

void dp_keys_release_all() { nheld = 0; }

uint8_t dp_keys_held_count() { return nheld; }

}  // namespace dp
