// Virtual panel: no bus, no physical display. Every write feeds the tee
// (dp_display) and a static RGB565 shadow framebuffer, so a bare ESP32-S3
// with no TFT of its own can still run an M5GFX/M5Unified app -- the phone
// is the only screen. Selected by the patched M5GFX::init_impl() (see
// shim/patches/M5GFX-0.2.27-droidputter.patch) when built with
// -DDROIDPUTTER_VIRTUAL=1, bypassing the normal I2C/SPI board autodetect.
#pragma once
#ifdef ARDUINO
#ifdef DROIDPUTTER_VIRTUAL
#include <lgfx/v1/panel/Panel_FrameBufferBase.hpp>

namespace lgfx { inline namespace v1 {

struct Panel_Droidputter : public Panel_FrameBufferBase {
 public:
  Panel_Droidputter(void);
  bool init(bool use_reset) override;

  // The app (e.g. Pense-Bem's main.cpp) calls M5.Display.setRotation(1) at
  // startup, as it would for the real hardware panel. Our panel_width/height
  // are already the final logical size (see constructor), so the inherited
  // Panel_FrameBufferBase::setRotation() swap -- which grows _height past
  // the DROIDPUTTER_H rows s_lines actually has -- must never run; only
  // _rotation (cosmetic, getRotation()) is updated. Geometry is pinned once
  // via an explicit base-qualified setRotation(0) call in init().
  void setRotation(uint_fast8_t r) override;

  void setWindow(uint_fast16_t xs, uint_fast16_t ys, uint_fast16_t xe, uint_fast16_t ye) override;
  void drawPixelPreclipped(uint_fast16_t x, uint_fast16_t y, uint32_t rawcolor) override;
  void writeFillRectPreclipped(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, uint32_t rawcolor) override;
  void writeBlock(uint32_t rawcolor, uint32_t length) override;
  void writePixels(pixelcopy_t* param, uint32_t length, bool use_dma) override;
  void writeImage(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, pixelcopy_t* param, bool use_dma) override;
  void writeImageARGB(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h, pixelcopy_t* param) override;
  void copyRect(uint_fast16_t dst_x, uint_fast16_t dst_y, uint_fast16_t w, uint_fast16_t h, uint_fast16_t src_x, uint_fast16_t src_y) override;

 private:
  // Streams the current shadow-framebuffer contents of one rect to the tee,
  // for calls (image blits, scroll) whose pixel data isn't known until after
  // the base Panel_FrameBufferBase write already placed it in the buffer.
  void teeRect(uint_fast16_t x, uint_fast16_t y, uint_fast16_t w, uint_fast16_t h);
};

}}  // namespace lgfx::v1
#endif  // DROIDPUTTER_VIRTUAL
#endif  // ARDUINO
