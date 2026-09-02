// Native (host) tests for shim/lib/DroidputterShim/src/dp_gps.{h,cpp} -- the
// GPS_NMEA (0x82) inbound ring behind droidputter_gps() (task 16).
#include <unity.h>
#include <string.h>
#include "dp_gps.h"

void setUp(void) { dp::dp_gps_reset(); }
void tearDown(void) {}

static bool push(const char* s) {
  return dp::dp_gps_push((const uint8_t*)s, strlen(s));
}

static void test_crlf_appended(void) {
  TEST_ASSERT_TRUE(push("$GPGGA,1"));
  TEST_ASSERT_EQUAL_size_t(8 + 2, dp::dp_gps_available());
  char got[10];
  for (int i = 0; i < 10; i++) got[i] = (char)dp::dp_gps_read();
  TEST_ASSERT_EQUAL_UINT8_ARRAY("$GPGGA,1\r\n", got, 10);
  TEST_ASSERT_EQUAL_size_t(0, dp::dp_gps_available());
}

static void test_available_read_peek_semantics(void) {
  TEST_ASSERT_EQUAL_INT(-1, dp::dp_gps_peek());
  TEST_ASSERT_EQUAL_INT(-1, dp::dp_gps_read());
  push("AB");
  TEST_ASSERT_EQUAL_size_t(4, dp::dp_gps_available());  // "AB\r\n"
  TEST_ASSERT_EQUAL_INT('A', dp::dp_gps_peek());
  TEST_ASSERT_EQUAL_size_t(4, dp::dp_gps_available());  // peek does not consume
  TEST_ASSERT_EQUAL_INT('A', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_size_t(3, dp::dp_gps_available());
  TEST_ASSERT_EQUAL_INT('B', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_INT('\r', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_INT('\n', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_size_t(0, dp::dp_gps_available());
  TEST_ASSERT_EQUAL_INT(-1, dp::dp_gps_read());
}

static void test_two_sentences_queue_in_order(void) {
  push("$GPGGA,1");
  push("$GPRMC,2");
  TEST_ASSERT_EQUAL_size_t(20, dp::dp_gps_available());
  char got[20];
  for (int i = 0; i < 20; i++) got[i] = (char)dp::dp_gps_read();
  TEST_ASSERT_EQUAL_UINT8_ARRAY("$GPGGA,1\r\n$GPRMC,2\r\n", got, 20);
}

static void test_overflow_drops_whole_sentence_never_partial(void) {
  // Fill to just short of capacity with fixed-size sentences, then push one
  // more that cannot fit: the ring must be unaffected (no partial bytes).
  char sentence[62];
  memset(sentence, 'X', sizeof sentence);
  size_t per = sizeof(sentence) + 2;  // 64 B per queued sentence
  size_t n = dp::DP_GPS_RING / per;   // fills to <= capacity, some free space left
  for (size_t i = 0; i < n; i++) {
    TEST_ASSERT_TRUE(dp::dp_gps_push((const uint8_t*)sentence, sizeof sentence));
  }
  size_t before = dp::dp_gps_available();
  size_t free_space = dp::DP_GPS_RING - before;
  TEST_ASSERT_TRUE(free_space < per);  // not enough room for one more full sentence

  char oversize[100];
  memset(oversize, 'Y', sizeof oversize);
  TEST_ASSERT_FALSE(dp::dp_gps_push((const uint8_t*)oversize, sizeof oversize));
  TEST_ASSERT_EQUAL_size_t(before, dp::dp_gps_available());  // unchanged, no partial write

  // What is queued is still exactly the first sentence intact (byte-exact),
  // proving the rejected push left no stray bytes ahead of it.
  for (size_t i = 0; i < sizeof sentence; i++) TEST_ASSERT_EQUAL_INT('X', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_INT('\r', dp::dp_gps_read());
  TEST_ASSERT_EQUAL_INT('\n', dp::dp_gps_read());
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_crlf_appended);
  RUN_TEST(test_available_read_peek_semantics);
  RUN_TEST(test_two_sentences_queue_in_order);
  RUN_TEST(test_overflow_drops_whole_sentence_never_partial);
  return UNITY_END();
}
