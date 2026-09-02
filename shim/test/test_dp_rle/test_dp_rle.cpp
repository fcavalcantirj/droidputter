// Native (host) tests for shim/lib/DroidputterShim/src/dp_rle.{h,cpp} — docs/PROTOCOL.md
// RECT_RLE payload (runs of count u8 <=255, color u16 big-endian).
#include <unity.h>
#include "dp_rle.h"

void setUp(void) {}
void tearDown(void) {}

static void test_solid_rect_compresses_to_n_over_255_runs(void) {
  const size_t n = 1000;  // 3 full 255-runs + 1 partial 235-run
  uint16_t px[n];
  for (size_t i = 0; i < n; i++) px[i] = 0x1234;

  uint8_t out[64];
  size_t len = dp::dp_rle_encode(px, n, out, sizeof out);

  TEST_ASSERT_EQUAL_UINT32(4 * 3, len);  // 4 runs * 3 B/run
  // run 0: count 255, color 0x12,0x34 (big-endian)
  TEST_ASSERT_EQUAL_UINT8(255, out[0]);
  TEST_ASSERT_EQUAL_UINT8(0x12, out[1]);
  TEST_ASSERT_EQUAL_UINT8(0x34, out[2]);
  // last run: count 235 (1000 - 3*255)
  TEST_ASSERT_EQUAL_UINT8(1000 - 3 * 255, out[9]);
  TEST_ASSERT_EQUAL_UINT8(0x12, out[10]);
  TEST_ASSERT_EQUAL_UINT8(0x34, out[11]);
}

static void test_alternating_pixels_return_zero(void) {
  const size_t n = 64;
  uint16_t px[n];
  for (size_t i = 0; i < n; i++) px[i] = (i % 2) ? 0xFFFF : 0x0000;

  uint8_t out[256];
  // Every run is length 1 -> RLE = n*3 B, never shorter than raw n*2 B.
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_encode(px, n, out, sizeof out));
}

static void test_round_trip_equals_input(void) {
  const size_t n = 300;
  uint16_t px[n];
  for (size_t i = 0; i < n; i++) px[i] = (i < 100) ? 0xAAAA : (i < 250 ? 0x5555 : 0x1111);

  uint8_t out[64];
  size_t len = dp::dp_rle_encode(px, n, out, sizeof out);
  TEST_ASSERT_TRUE(len > 0);

  uint16_t decoded[n];
  size_t got = dp::dp_rle_decode(out, len, decoded, n);
  TEST_ASSERT_EQUAL_UINT32(n, got);
  TEST_ASSERT_EQUAL_UINT16_ARRAY(px, decoded, n);
}

static void test_encode_rejects_output_over_capacity(void) {
  const size_t n = 300;  // 2 runs of a solid color = 6 B needed
  uint16_t px[n];
  for (size_t i = 0; i < n; i++) px[i] = 0x2222;

  uint8_t out[5];  // too small for the 6 B a solid run needs
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_encode(px, n, out, sizeof out));
}

static void test_decode_rejects_malformed_stream(void) {
  uint16_t px[8];
  const uint8_t truncated[2] = {3, 0x12};  // not a multiple of 3
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_decode(truncated, sizeof truncated, px, 8));

  const uint8_t zeroRun[3] = {0, 0x12, 0x34};  // a 0-length run is invalid
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_decode(zeroRun, sizeof zeroRun, px, 8));

  const uint8_t tooManyPixels[3] = {5, 0x12, 0x34};
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_decode(tooManyPixels, sizeof tooManyPixels, px, 3));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_solid_rect_compresses_to_n_over_255_runs);
  RUN_TEST(test_alternating_pixels_return_zero);
  RUN_TEST(test_round_trip_equals_input);
  RUN_TEST(test_encode_rejects_output_over_capacity);
  RUN_TEST(test_decode_rejects_malformed_stream);
  return UNITY_END();
}
