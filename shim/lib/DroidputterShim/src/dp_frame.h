// DROIDPUTTER wire framing core (docs/PROTOCOL.md): pure C++17, no Arduino headers,
// so it can be unit-tested on the host (`pio test -e native`) as well as built into
// the ESP-side shim.
// Wire: D7 50 | type u8 | length u16 LE | payload | crc8(poly 0x07 over type+length+payload)
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace dp {

constexpr uint8_t DP_SYNC0 = 0xD7;
constexpr uint8_t DP_SYNC1 = 0x50;
constexpr size_t DP_FRAME_MAX_PAYLOAD = 4096;

// crc8, poly 0x07, seeded by the caller (0 for a fresh frame).
uint8_t dp_frame_crc8(uint8_t seed, const uint8_t* data, size_t n);

// Encodes sync+type+length+payload+crc into out (capacity must be >= len + 6).
// Returns the total bytes written, or 0 if len exceeds DP_FRAME_MAX_PAYLOAD.
size_t dp_frame_encode(uint8_t type, const uint8_t* payload, size_t len, uint8_t* out);

struct DpFrame {
  uint8_t type = 0;
  uint16_t length = 0;
  uint8_t payload[DP_FRAME_MAX_PAYLOAD] = {0};
};

// Byte-at-a-time parser. A receiver that loses sync (bad second sync byte,
// an oversize length, or a crc mismatch) drops the candidate frame and
// resumes scanning for the next 0xD7 0x50, matching docs/PROTOCOL.md and
// the ESP-side dp::poll() / host-side tools/dp_receiver.py Framer.
class DpFrameParser {
 public:
  // Feeds one byte; returns true and fills *frame when a complete, crc-valid
  // frame has just been parsed.
  bool feed(uint8_t byte, DpFrame* frame);

  uint32_t resyncCount() const { return resync_; }

 private:
  enum class State { Sync0, Sync1, Type, LenLo, LenHi, Payload, Crc };
  void reset();

  State state_ = State::Sync0;
  uint8_t type_ = 0;
  uint16_t len_ = 0;
  uint16_t idx_ = 0;
  uint8_t payload_[DP_FRAME_MAX_PAYLOAD];
  uint32_t resync_ = 0;
};

}  // namespace dp
