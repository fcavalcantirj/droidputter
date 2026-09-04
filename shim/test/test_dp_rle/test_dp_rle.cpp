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

// --- row-wise encoder for band splitting (2026-09-04) ---
static void fill_rows_be(uint8_t* px, uint16_t width, uint16_t rows, uint16_t (*color)(uint16_t r, uint16_t x)) {
  for (uint16_t r = 0; r < rows; r++) for (uint16_t x = 0; x < width; x++) {
    uint16_t v = color(r, x); px[((size_t)r * width + x) * 2] = v >> 8; px[((size_t)r * width + x) * 2 + 1] = v & 0xFF;
  }
}
static uint16_t solid_per_row(uint16_t r, uint16_t) { return (uint16_t)(0x1000 + r); }
static uint16_t noise(uint16_t r, uint16_t x) { return (uint16_t)(r * 977 + x * 131 + (x & 1) * 0x8000); }

static void test_rows_all_fit_is_one_run_per_row(void) {
  static uint8_t px[240 * 10 * 2]; fill_rows_be(px, 240, 10, solid_per_row);
  uint8_t out[64]; uint16_t done = 0;
  size_t len = dp::dp_rle_encode_rows_be(px, 240, 10, out, sizeof out, &done);
  TEST_ASSERT_EQUAL_UINT16(10, done);
  TEST_ASSERT_EQUAL_UINT32(10 * 3, len);   // 240 px <= 255 -> one run per row
  TEST_ASSERT_EQUAL_UINT8(240, out[0]); TEST_ASSERT_EQUAL_UINT8(0x10, out[1]); TEST_ASSERT_EQUAL_UINT8(0x00, out[2]);
  TEST_ASSERT_EQUAL_UINT8(0x09, out[9 * 3 + 2]);   // last row's color 0x1009
}

static void test_rows_stop_before_the_row_that_does_not_fit(void) {
  static uint8_t px[240 * 10 * 2]; fill_rows_be(px, 240, 10, solid_per_row);
  uint8_t out[3 * 4 + 1]; uint16_t done = 0;   // room for 4 rows, not 5
  size_t len = dp::dp_rle_encode_rows_be(px, 240, 10, out, sizeof out, &done);
  TEST_ASSERT_EQUAL_UINT16(4, done);
  TEST_ASSERT_EQUAL_UINT32(12, len);
}

static void test_rows_zero_when_first_row_does_not_fit(void) {
  static uint8_t px[240 * 2 * 2]; fill_rows_be(px, 240, 2, noise);   // noise: 240 runs of 1 = 720 B per row
  uint8_t out[500]; uint16_t done = 7;
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_encode_rows_be(px, 240, 2, out, sizeof out, &done));
  TEST_ASSERT_EQUAL_UINT16(0, done);
}

static void test_rows_concatenation_decodes_to_the_same_pixels(void) {
  static uint8_t px[240 * 6 * 2]; fill_rows_be(px, 240, 6, noise);
  static uint8_t out[240 * 6 * 3]; uint16_t done = 0;
  size_t len = dp::dp_rle_encode_rows_be(px, 240, 6, out, sizeof out, &done);
  TEST_ASSERT_EQUAL_UINT16(6, done);
  static uint16_t decoded[240 * 6];
  TEST_ASSERT_EQUAL_UINT32(240 * 6, dp::dp_rle_decode(out, len, decoded, 240 * 6));
  for (size_t i = 0; i < 240 * 6; i++) {
    uint16_t v = (uint16_t)(px[i * 2] << 8 | px[i * 2 + 1]);
    TEST_ASSERT_EQUAL_UINT16(v, decoded[i]);
  }
  // and for noise the caller must notice RLE is NOT shorter than raw (240*6*2 = 2880 B)
  TEST_ASSERT_TRUE(len >= 240 * 6 * 2);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_solid_rect_compresses_to_n_over_255_runs);
  RUN_TEST(test_alternating_pixels_return_zero);
  RUN_TEST(test_round_trip_equals_input);
  RUN_TEST(test_encode_rejects_output_over_capacity);
  RUN_TEST(test_decode_rejects_malformed_stream);
  RUN_TEST(test_rows_all_fit_is_one_run_per_row);
  RUN_TEST(test_rows_stop_before_the_row_that_does_not_fit);
  RUN_TEST(test_rows_zero_when_first_row_does_not_fit);
  RUN_TEST(test_rows_concatenation_decodes_to_the_same_pixels);
  return UNITY_END();
}
