// Native (host) tests for shim/lib/DroidputterShim/src/dp_frame.{h,cpp} — docs/PROTOCOL.md framing.
#include <unity.h>
#include <string.h>
#include "dp_frame.h"

using dp::DpFrame;
using dp::DpFrameParser;

void setUp(void) {}
void tearDown(void) {}

static void feedAll(DpFrameParser& p, const uint8_t* data, size_t n, DpFrame* out, bool* got) {
  *got = false;
  for (size_t i = 0; i < n; i++) {
    if (p.feed(data[i], out)) *got = true;
  }
}

static void test_encode_parse_round_trip(void) {
  const uint8_t payload[] = {0xAA, 0x00, 0x01, 0xFF, 0x10, 0x20};
  uint8_t wire[32];
  size_t n = dp::dp_frame_encode(0x03, payload, sizeof payload, wire);
  TEST_ASSERT_EQUAL_UINT32(6 + sizeof payload, n);
  TEST_ASSERT_EQUAL_UINT8(dp::DP_SYNC0, wire[0]);
  TEST_ASSERT_EQUAL_UINT8(dp::DP_SYNC1, wire[1]);

  DpFrameParser parser;
  DpFrame frame; bool got = false;
  feedAll(parser, wire, n, &frame, &got);

  TEST_ASSERT_TRUE(got);
  TEST_ASSERT_EQUAL_UINT8(0x03, frame.type);
  TEST_ASSERT_EQUAL_UINT16(sizeof payload, frame.length);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(payload, frame.payload, sizeof payload);
  TEST_ASSERT_EQUAL_UINT32(0, parser.resyncCount());
}

static void test_split_feeds(void) {
  const uint8_t payload[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
  uint8_t wire[32];
  size_t n = dp::dp_frame_encode(0x05, payload, sizeof payload, wire);

  DpFrameParser parser;
  DpFrame frame; bool got = false;
  // First call only delivers part of the header; no frame yet.
  size_t split = 3;
  for (size_t i = 0; i < split; i++) TEST_ASSERT_FALSE(parser.feed(wire[i], &frame));
  // Second call finishes the header; third finishes the payload+crc mid-byte.
  size_t split2 = split + 4;
  for (size_t i = split; i < split2; i++) TEST_ASSERT_FALSE(parser.feed(wire[i], &frame));
  for (size_t i = split2; i < n; i++) {
    if (parser.feed(wire[i], &frame)) got = true;
  }

  TEST_ASSERT_TRUE(got);
  TEST_ASSERT_EQUAL_UINT8(0x05, frame.type);
  TEST_ASSERT_EQUAL_UINT16(sizeof payload, frame.length);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(payload, frame.payload, sizeof payload);
}

static void test_corrupted_sync(void) {
  const uint8_t payload[] = {0x11, 0x22};
  uint8_t wire[16];
  size_t n = dp::dp_frame_encode(0x01, payload, sizeof payload, wire);

  // Garbage (including a lone 0xD7 not followed by 0x50, and text noise) before the real frame.
  uint8_t stream[64];
  size_t p = 0;
  const uint8_t garbage[] = {0x00, 0xD7, 0x41, 0x42, 0xD7, 0xD7};  // last 0xD7 is a fresh sync0 candidate
  memcpy(stream + p, garbage, sizeof garbage); p += sizeof garbage;
  memcpy(stream + p, wire, n); p += n;

  DpFrameParser parser;
  DpFrame frame; bool got = false;
  feedAll(parser, stream, p, &frame, &got);

  TEST_ASSERT_TRUE(got);
  TEST_ASSERT_EQUAL_UINT8(0x01, frame.type);
  TEST_ASSERT_EQUAL_UINT16(sizeof payload, frame.length);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(payload, frame.payload, sizeof payload);
}

static void test_oversize_length_rejected(void) {
  DpFrameParser parser;
  DpFrame frame; bool got = false;

  // Hand-built header claiming a 4097-byte payload (one over DP_FRAME_MAX_PAYLOAD).
  uint8_t bogus[5] = { dp::DP_SYNC0, dp::DP_SYNC1, 0x03, 0x01, 0x10 };  // len = 0x1001 = 4097
  for (int i = 0; i < 5; i++) TEST_ASSERT_FALSE(parser.feed(bogus[i], &frame));
  TEST_ASSERT_EQUAL_UINT32(1, parser.resyncCount());

  // Parser must recover: a valid frame right after the bogus header still parses.
  const uint8_t payload[] = {0x9, 0x8, 0x7};
  uint8_t wire[16];
  size_t n = dp::dp_frame_encode(0x02, payload, sizeof payload, wire);
  feedAll(parser, wire, n, &frame, &got);

  TEST_ASSERT_TRUE(got);
  TEST_ASSERT_EQUAL_UINT8(0x02, frame.type);
  TEST_ASSERT_EQUAL_UINT16(sizeof payload, frame.length);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(payload, frame.payload, sizeof payload);

  // encode itself also refuses an oversize payload.
  uint8_t big[8]; static uint8_t huge_payload[dp::DP_FRAME_MAX_PAYLOAD + 1] = {0};
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_frame_encode(0x02, huge_payload, sizeof huge_payload, big));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_encode_parse_round_trip);
  RUN_TEST(test_split_feeds);
  RUN_TEST(test_corrupted_sync);
  RUN_TEST(test_oversize_length_rejected);
  return UNITY_END();
}
