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

// Row-wise encoder for band splitting (2026-09-04): encodes whole rows of `width` wire-order pixels,
// appending one run stream per row, and stops before the row that would not fit in `cap`. Returns the
// bytes written and sets *rows_done (0 when even the first row does not fit). A concatenation of per-row
// run streams is a valid RECT_RLE payload for exactly those rows (the decoder consumes runs in order), so
// the caller can send the top of a dirty band now and the rest on the next tick instead of dropping a band
// whose RLE exceeds the 32 KB ring forever. Unlike dp_rle_encode_be it does NOT apply the "shorter than
// raw" rule -- the caller compares against rows_done * width * 2 itself.
size_t dp_rle_encode_rows_be(const uint8_t* px_be, uint16_t width, uint16_t rows, uint8_t* out, size_t cap, uint16_t* rows_done);

// Decodes a run stream produced by dp_rle_encode back into up to `cap` pixels.
// Returns the number of pixels decoded, or 0 on a malformed stream (a run's byte
// triplet is truncated, a zero-length input, or more pixels than `cap` would decode).
size_t dp_rle_decode(const uint8_t* in, size_t inLen, uint16_t* px, size_t cap);

}  // namespace dp
