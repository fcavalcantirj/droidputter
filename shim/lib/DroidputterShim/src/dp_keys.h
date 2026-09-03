// Injected-key ring: (row,col) positions held down via USB KEY frames, merged
// into Keyboard_Class::updateKeyList exactly like physical keys (see
// shim/patches/M5Cardputer-1.1.1-droidputter.patch). Pure C++17, no Arduino
// headers, host-testable under `pio test -e native`.
#pragma once
#include <stdint.h>

namespace dp {

constexpr uint8_t DP_KEYS_MAX = 16;
// Minimum number of snapshots (keyboard updates) a press stays visible even if its release
// already arrived -- see dp_keys.cpp.
constexpr uint8_t DP_KEYS_MIN_SEEN = 2;

// KEY down (down=1) adds (row,col) if not already held (duplicate downs are
// ignored, the key just stays held); KEY up (down=0) removes it if present -- deferred until
// the key has been reported by DP_KEYS_MIN_SEEN snapshots, so a tap shorter than one app loop
// is still seen by Keyboard_Class::isChange().
void dp_keys_push(uint8_t row, uint8_t col, uint8_t down);

// Copies up to `max` currently-held (row,col) pairs into rows/cols, returns
// the count copied.
uint8_t dp_keys_snapshot(uint8_t* rows, uint8_t* cols, uint8_t max);

// All held keys released. Called when the USB link drops so a phone that
// disconnects mid-keypress never leaves a key stuck down on the ESP.
void dp_keys_release_all();

uint8_t dp_keys_held_count();

}  // namespace dp
