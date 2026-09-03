// DROIDPUTTER shadow framebuffer: a 240x135 RGB565 copy of the panel in WIRE byte order
// (big-endian per pixel, exactly what RECT/RECT_RLE carry) with dirty-row tracking, so the tee
// can coalesce thousands of tiny panel writes (a text redraw is ~1,500 fills) into one
// RECT_RLE row band per flush tick instead of one frame per write. Pure C++17, no Arduino
// headers, host-testable (`pio test -e native`). Window semantics follow LovyanGFX/ST7789:
// setWindow(xs,ys,xe,ye) then pixels stream row-major inside the window and wrap back to
// the window's first pixel after the last one.
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace dp {

constexpr uint16_t DP_SHADOW_W = 240;
constexpr uint16_t DP_SHADOW_H = 135;

void dp_shadow_reset();                                            // black, clean, no window
void dp_shadow_set_window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye);  // inclusive, clipped
void dp_shadow_write_bytes(const uint8_t* be, size_t nbytes);      // pixel bytes in wire order, at the window cursor
void dp_shadow_repeat(uint8_t c0, uint8_t c1, size_t npixels);     // one wire-order color at the window cursor
void dp_shadow_fill(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint8_t c0, uint8_t c1);  // clipped, cursor untouched
void dp_shadow_load_row(uint16_t y, const uint8_t* be);            // one full row (2*W bytes), marks it dirty
bool dp_shadow_dirty(uint16_t* y0, uint16_t* y1);                  // inclusive dirty row band; false if clean
void dp_shadow_mark_all_dirty();
void dp_shadow_clear_dirty();
const uint8_t* dp_shadow_buffer();                                 // W*H*2 bytes, row-major, wire order
uint32_t dp_shadow_cursor();                                       // test hook: pixel index inside the window

}  // namespace dp
