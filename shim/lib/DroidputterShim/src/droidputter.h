// DROIDPUTTER shim: tee of every panel write + key injection over USB-CDC (HWCDC `Serial`).
// Wire: D7 50 | type u8 | len u16 LE | payload | crc8(poly 0x07 over type+len+payload)
#pragma once
#include <stdint.h>
#include <stddef.h>
namespace lgfx { inline namespace v1 { struct pixelcopy_t; } }
namespace dp {
enum : uint8_t { HELLO = 0x01, FILL = 0x02, RECT = 0x03, RECT_RLE = 0x04, STATS = 0x05, PING = 0x06,
                 KEY = 0x81, GPS_NMEA = 0x82, PING_IN = 0x83, HELLO_ACK = 0x84 };
void begin(const char* app, uint16_t w, uint16_t h, uint8_t rot);   // idempotent
void window(uint16_t xs, uint16_t ys, uint16_t xe, uint16_t ye);
void bytes(const uint8_t* data, uint32_t nbytes);                   // pixel bytes in panel wire order
void repeat(uint32_t rawcolor, uint32_t npixels);
void fill(uint16_t x, uint16_t y, uint16_t w, uint16_t h, uint32_t rawcolor);
void pixel(uint16_t x, uint16_t y, uint32_t rawcolor);
void pixelsConv(lgfx::v1::pixelcopy_t* param, uint32_t npixels);    // convert path
void poll();                                                        // parse phone->ESP frames (keys)
// injected keys (row,col) currently held; returns count, fills out[max]
uint8_t injectedKeys(uint8_t* rows, uint8_t* cols, uint8_t max);
}
