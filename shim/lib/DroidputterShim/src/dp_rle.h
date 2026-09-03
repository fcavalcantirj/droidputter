// DROIDPUTTER RECT_RLE encoder (docs/PROTOCOL.md 0x04): pure C++17, no Arduino headers,
// host-testable (`pio test -e native`). Matches the run-length scheme already used by
// droidputter.cpp's flushRect (runs of identical RGB565 pixels, big-endian on the wire,
// count u8 <= 255, color u16) so this can later replace that inline copy.
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace dp {

// Encodes n RGB565 pixels (big-endian on the wire, per docs/PROTOCOL.md RECT_RLE) as
// runs of (count u8 <=255, color u16) = 3 B/run into out (capacity cap).
// Returns the bytes written, or 0 if the RLE output would not be shorter than the raw
// n*2-byte pixel data (caller should send RECT instead) or would not fit in cap.
size_t dp_rle_encode(const uint16_t* px, size_t n, uint8_t* out, size_t cap);

// Same encoder over pixels already in wire order (2 big-endian bytes each), e.g. the shadow
// framebuffer (dp_shadow.h): no conversion buffer needed. Same return contract.
size_t dp_rle_encode_be(const uint8_t* px_be, size_t n, uint8_t* out, size_t cap);

// Decodes a run stream produced by dp_rle_encode back into up to `cap` pixels.
// Returns the number of pixels decoded, or 0 on a malformed stream (a run's byte
// triplet is truncated, a zero-length input, or more pixels than `cap` would decode).
size_t dp_rle_decode(const uint8_t* in, size_t inLen, uint16_t* px, size_t cap);

}  // namespace dp
