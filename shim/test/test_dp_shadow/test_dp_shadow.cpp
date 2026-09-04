// Native tests for dp_shadow.{h,cpp} (coalescing shadow framebuffer) and dp_rle_encode_be.
#include <unity.h>
#include <string.h>
#include "dp_shadow.h"
#include "dp_rle.h"

void setUp(void) { dp::dp_shadow_reset(); }
void tearDown(void) {}

static uint16_t px(uint16_t x, uint16_t y) {
  const uint8_t* b = dp::dp_shadow_buffer() + ((size_t)y * dp::DP_SHADOW_W + x) * 2;
  return (uint16_t)(b[0] << 8 | b[1]);
}

static void test_window_write_lands_row_major_and_marks_rows_dirty(void) {
  dp::dp_shadow_set_window(10, 5, 12, 6);            // 3x2 window
  uint8_t data[12] = { 0x11,0x11, 0x22,0x22, 0x33,0x33, 0x44,0x44, 0x55,0x55, 0x66,0x66 };
  dp::dp_shadow_write_bytes(data, sizeof data);
  TEST_ASSERT_EQUAL_HEX16(0x1111, px(10, 5)); TEST_ASSERT_EQUAL_HEX16(0x3333, px(12, 5));
  TEST_ASSERT_EQUAL_HEX16(0x4444, px(10, 6)); TEST_ASSERT_EQUAL_HEX16(0x6666, px(12, 6));
  TEST_ASSERT_EQUAL_HEX16(0x0000, px(13, 5));       // outside the window untouched
  uint16_t y0, y1; TEST_ASSERT_TRUE(dp::dp_shadow_dirty(&y0, &y1));
  TEST_ASSERT_EQUAL_UINT16(5, y0); TEST_ASSERT_EQUAL_UINT16(6, y1);
  TEST_ASSERT_EQUAL_UINT32(0, dp::dp_shadow_cursor());  // wrapped back to the window start
}

static void test_cursor_wraps_inside_window_like_the_panel(void) {
  dp::dp_shadow_set_window(0, 0, 1, 0);              // 2 pixels
  uint8_t a[4] = { 0xAA,0xAA, 0xBB,0xBB }; dp::dp_shadow_write_bytes(a, 4);
  uint8_t c[2] = { 0xCC,0xCC }; dp::dp_shadow_write_bytes(c, 2);   // third pixel overwrites the first
  TEST_ASSERT_EQUAL_HEX16(0xCCCC, px(0, 0)); TEST_ASSERT_EQUAL_HEX16(0xBBBB, px(1, 0));
}

static void test_fill_and_repeat_use_wire_order_bytes(void) {
  dp::dp_shadow_fill(100, 50, 2, 2, 0x12, 0x34);
  TEST_ASSERT_EQUAL_HEX16(0x1234, px(101, 51));
  dp::dp_shadow_set_window(0, 134, 2, 134); dp::dp_shadow_repeat(0xF8, 0x00, 3);
  TEST_ASSERT_EQUAL_HEX16(0xF800, px(2, 134));
  uint16_t y0, y1; dp::dp_shadow_dirty(&y0, &y1);
  TEST_ASSERT_EQUAL_UINT16(50, y0); TEST_ASSERT_EQUAL_UINT16(134, y1);
}

static void test_clear_and_mark_all(void) {
  uint16_t y0, y1;
  TEST_ASSERT_FALSE(dp::dp_shadow_dirty(&y0, &y1));
  dp::dp_shadow_mark_all_dirty(); TEST_ASSERT_TRUE(dp::dp_shadow_dirty(&y0, &y1));
  TEST_ASSERT_EQUAL_UINT16(0, y0); TEST_ASSERT_EQUAL_UINT16(dp::DP_SHADOW_H - 1, y1);
  dp::dp_shadow_clear_dirty(); TEST_ASSERT_FALSE(dp::dp_shadow_dirty(&y0, &y1));
}

static void test_fill_clips_to_the_panel(void) {
  dp::dp_shadow_fill(238, 133, 10, 10, 0xFF, 0xFF);   // overhangs both edges
  TEST_ASSERT_EQUAL_HEX16(0xFFFF, px(239, 134));
  uint16_t y0, y1; dp::dp_shadow_dirty(&y0, &y1); TEST_ASSERT_EQUAL_UINT16(134, y1);
}

static void test_rle_be_matches_rle_on_host_order_pixels(void) {
  const size_t n = 500; uint16_t host[n]; uint8_t be[n * 2];
  for (size_t i = 0; i < n; i++) { host[i] = (i < 300) ? 0x07E0 : (uint16_t)i; be[i * 2] = host[i] >> 8; be[i * 2 + 1] = host[i] & 0xFF; }
  uint8_t a[2048], b[2048];
  size_t la = dp::dp_rle_encode(host, n, a, sizeof a), lb = dp::dp_rle_encode_be(be, n, b, sizeof b);
  TEST_ASSERT_EQUAL_UINT32(la, lb);
  TEST_ASSERT_EQUAL_MEMORY(a, b, la);
}

static void test_rle_be_returns_zero_when_not_shorter(void) {
  uint8_t be[64 * 2]; for (size_t i = 0; i < 64; i++) { be[i * 2] = (uint8_t)i; be[i * 2 + 1] = (uint8_t)(i * 7); }
  uint8_t out[512]; TEST_ASSERT_EQUAL_UINT32(0, dp::dp_rle_encode_be(be, 64, out, sizeof out));
}

static void test_clear_dirty_top_keeps_the_rest_dirty(void) {
  dp::dp_shadow_reset();
  dp::dp_shadow_fill(0, 20, 240, 10, 0x12, 0x34);   // rows 20..29 dirty
  uint16_t y0 = 0, y1 = 0;
  TEST_ASSERT_TRUE(dp::dp_shadow_dirty(&y0, &y1)); TEST_ASSERT_EQUAL_UINT16(20, y0); TEST_ASSERT_EQUAL_UINT16(29, y1);
  dp::dp_shadow_clear_dirty_top(4);                    // rows 20..23 flushed
  TEST_ASSERT_TRUE(dp::dp_shadow_dirty(&y0, &y1)); TEST_ASSERT_EQUAL_UINT16(24, y0); TEST_ASSERT_EQUAL_UINT16(29, y1);
  dp::dp_shadow_clear_dirty_top(6);                    // the rest
  TEST_ASSERT_FALSE(dp::dp_shadow_dirty(&y0, &y1));
  dp::dp_shadow_clear_dirty_top(3);                    // clean stays clean
  TEST_ASSERT_FALSE(dp::dp_shadow_dirty(&y0, &y1));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_window_write_lands_row_major_and_marks_rows_dirty);
  RUN_TEST(test_cursor_wraps_inside_window_like_the_panel);
  RUN_TEST(test_fill_and_repeat_use_wire_order_bytes);
  RUN_TEST(test_clear_and_mark_all);
  RUN_TEST(test_fill_clips_to_the_panel);
  RUN_TEST(test_rle_be_matches_rle_on_host_order_pixels);
  RUN_TEST(test_rle_be_returns_zero_when_not_shorter);
  RUN_TEST(test_clear_dirty_top_keeps_the_rest_dirty);
  return UNITY_END();
}
