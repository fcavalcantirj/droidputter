#include "dp_frame.h"
#include <string.h>

namespace dp {

uint8_t dp_frame_crc8(uint8_t seed, const uint8_t* data, size_t n) {
  uint8_t c = seed;
  while (n--) {
    c ^= *data++;
    for (int i = 0; i < 8; i++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1);
  }
  return c;
}

size_t dp_frame_encode(uint8_t type, const uint8_t* payload, size_t len, uint8_t* out) {
  if (len > DP_FRAME_MAX_PAYLOAD) return 0;
  out[0] = DP_SYNC0; out[1] = DP_SYNC1; out[2] = type;
  out[3] = (uint8_t)(len & 0xFF); out[4] = (uint8_t)(len >> 8);
  if (len) memcpy(out + 5, payload, len);
  out[5 + len] = dp_frame_crc8(0, out + 2, 3 + len);
  return 6 + len;
}

void DpFrameParser::reset() {
  state_ = State::Sync0;
  idx_ = 0;
  len_ = 0;
}

bool DpFrameParser::feed(uint8_t byte, DpFrame* frame) {
  switch (state_) {
    case State::Sync0:
      if (byte == DP_SYNC0) state_ = State::Sync1;
      return false;
    case State::Sync1:
      if (byte == DP_SYNC1) state_ = State::Type;
      else if (byte != DP_SYNC0) state_ = State::Sync0;
      // else: byte is another DP_SYNC0 -- stay put, it becomes the new candidate sync0.
      return false;
    case State::Type:
      type_ = byte;
      state_ = State::LenLo;
      return false;
    case State::LenLo:
      len_ = byte;
      state_ = State::LenHi;
      return false;
    case State::LenHi:
      len_ = (uint16_t)(len_ | ((uint16_t)byte << 8));
      if (len_ > DP_FRAME_MAX_PAYLOAD) { resync_++; reset(); return false; }
      idx_ = 0;
      state_ = len_ ? State::Payload : State::Crc;
      return false;
    case State::Payload:
      payload_[idx_++] = byte;
      if (idx_ >= len_) state_ = State::Crc;
      return false;
    case State::Crc: {
      uint8_t hdr[3] = { type_, (uint8_t)(len_ & 0xFF), (uint8_t)(len_ >> 8) };
      uint8_t c = dp_frame_crc8(0, hdr, 3);
      c = dp_frame_crc8(c, payload_, len_);
      bool ok = (c == byte);
      if (ok) {
        frame->type = type_;
        frame->length = len_;
        if (len_) memcpy(frame->payload, payload_, len_);
      } else {
        resync_++;
      }
      reset();
      return ok;
    }
  }
  return false;
}

}  // namespace dp
