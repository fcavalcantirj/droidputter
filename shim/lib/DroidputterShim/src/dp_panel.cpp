#include "dp_panel.h"
#ifdef ARDUINO
#ifdef DROIDPUTTER_VIRTUAL
#include "droidputter.h"

#ifndef DROIDPUTTER_W
#define DROIDPUTTER_W 240
#endif
#ifndef DROIDPUTTER_H
#define DROIDPUTTER_H 135
#endif

namespace lgfx {
inline namespace v1 {

namespace {
// Shadow framebuffer: 240x135 RGB565 = 64,800 B, static (no per-write alloc).
// _lines_buffer (Panel_FrameBufferBase) indexes it one row pointer per line.
uint16_t s_framebuffer[DROIDPUTTER_W * DROIDPUTTER_H];
uint8_t* s_lines[DROIDPUTTER_H];
}  // namespace

Panel_Droidputter::Panel_Droidputter(void) {
  _cfg.panel_width = _cfg.memory_width = DROIDPUTTER_W;
  _cfg.panel_height = _cfg.memory_height = DROIDPUTTER_H;
  _cfg.readable = true;
  _cfg.bus_shared = false;
}

bool Panel_Droidputter::init(bool use_reset) {
  auto base = reinterpret_cast<uint8_t*>(s_framebuffer);
  for (uint16_t y = 0; y < DROIDPUTTER_H; y++) s_lines[y] = base + (size_t)y * DROIDPUTTER_W * 2;
  _lines_buffer = s_lines;
  bool ok = Panel_FrameBufferBase::init(use_reset);
  // Panel_FrameBufferBase::init() calls setRotation(_rotation) through the
  // vtable, i.e. our own no-op override below -- _width/_height/_xe/_ye are
  // still unset at this point. Establish them for real exactly once, bypassing
  // the override via the base-qualified call.
  Panel_FrameBufferBase::setRotation(0);
  return ok;
}

// The app (e.g. Pense-Bem's main.cpp) calls M5.Display.setRotation(1) at
// startup, as it would for the real hardware panel. panel_width/height are
// already configured as the final logical size (constructor above), so the
// inherited Panel_FrameBufferBase::setRotation() swap -- which would grow
// _height past the DROIDPUTTER_H rows s_lines actually has -- must never
// run; only _rotation (cosmetic, getRotation()) is updated. Geometry is
// pinned once via the explicit base-qualified setRotation(0) call in init().
void Panel_Droidputter::setRotation(uint_fast8_t r) { _rotation = r & 7; }

void Panel_Droidputter::teeRect(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h) {
  if (!w || !h) return;
  dp::window(x, y, x + w - 1, y + h - 1);
  for (uint_fast16_t row = 0; row < h; row++) dp::bytes(&_lines_buffer[y + row][x * 2], (uint32_t)w * 2);
}

void Panel_Droidputter::setWindow(uint_fast16_t xs, uint_fast16_t ys, uint_fast16_t xe, uint_fast16_t ye) {
  dp::window(xs, ys, xe, ye);
  Panel_FrameBufferBase::setWindow(xs, ys, xe, ye);
}

void Panel_Droidputter::drawPixelPreclipped(uint_fast16_t x, uint_fast16_t y, uint32_t rawcolor) {
  // dp::begin() is only triggered lazily from dp::window() (see dp_display.cpp);
  // a pixel draw that never goes through setWindow() must still bootstrap the
  // link, exactly like the real Panel_LCD patch's drawPixelPreclipped does via
  // its own setWindow(x,y,x,y) call before dp::pixel().
  dp::window(x, y, x, y);
  dp::pixel(x, y, rawcolor);
  Panel_FrameBufferBase::drawPixelPreclipped(x, y, rawcolor);
}

void Panel_Droidputter::writeFillRectPreclipped(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, uint32_t rawcolor) {
  dp::window(x, y, x + w - 1, y + h - 1);
  dp::fill(x, y, w, h, rawcolor);
  Panel_FrameBufferBase::writeFillRectPreclipped(x, y, w, h, rawcolor);
}

void Panel_Droidputter::writeBlock(uint32_t rawcolor, uint32_t length) {
  dp::repeat(rawcolor, length);
  Panel_FrameBufferBase::writeBlock(rawcolor, length);
}

void Panel_Droidputter::writePixels(pixelcopy_t* param, uint32_t length, bool use_dma) {
  if (param->no_convert) dp::bytes(reinterpret_cast<const uint8_t*>(param->src_data), length * _write_bits >> 3);
  else dp::pixelsConv(param, length);
  Panel_FrameBufferBase::writePixels(param, length, use_dma);
}

void Panel_Droidputter::writeImage(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, pixelcopy_t* param, bool use_dma) {
  Panel_FrameBufferBase::writeImage(x, y, w, h, param, use_dma);
  teeRect(x, y, w, h);
}

void Panel_Droidputter::writeImageARGB(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, pixelcopy_t* param) {
  Panel_FrameBufferBase::writeImageARGB(x, y, w, h, param);
  teeRect(x, y, w, h);
}

void Panel_Droidputter::copyRect(uint_fast16_t dst_x, uint_fast16_t dst_y, uint_fast16_t w, uint_fast16_t h, uint_fast16_t src_x, uint_fast16_t src_y) {
  Panel_FrameBufferBase::copyRect(dst_x, dst_y, w, h, src_x, src_y);
  teeRect(dst_x, dst_y, w, h);
}

}  // namespace v1
}  // namespace lgfx
#endif  // DROIDPUTTER_VIRTUAL
#endif  // ARDUINO
