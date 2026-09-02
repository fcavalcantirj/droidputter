// Shared plumbing between droidputter.cpp (link/protocol: HELLO/STATS/PING/keys)
// and dp_display.cpp (panel tee: window/pixel writes -> FILL/RECT/RECT_RLE).
// Arduino-only, not part of the public API (droidputter.h).
#pragma once
#ifdef ARDUINO
#include <stdint.h>
#include <stddef.h>

namespace dp { namespace internal {

extern bool started;
extern uint16_t scr_w, scr_h;
extern uint8_t scr_rot;
extern uint32_t st_frames, st_bytes, st_dropped;
extern uint32_t write_budget_ms;  // usb_write()'s blocking budget; raised only by the bench path

uint8_t crc8(uint8_t c, const uint8_t* p, size_t n);
void put16(uint8_t* p, uint16_t v);

// Governor: true if the HWCDC TX ring currently has at least `n` bytes free.
// Callers use this to drop a whole outgoing message instead of blocking the
// app while usb_write() waits for room.
bool hasSpace(size_t n);

// Frame send: header+payload(s)+crc8, per docs/PROTOCOL.md. No-op before begin().
// Blocks up to a small write budget if the ring is already draining (rare once
// callers pre-check hasSpace()); counts st_dropped on timeout instead of hanging.
void send(uint8_t type, const uint8_t* a, size_t na, const uint8_t* b = nullptr, size_t nb = 0);

}}  // namespace dp::internal
#endif  // ARDUINO
