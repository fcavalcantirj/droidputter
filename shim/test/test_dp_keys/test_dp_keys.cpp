// Native (host) tests for shim/lib/DroidputterShim/src/dp_keys.{h,cpp} -- the
// injected-key ring merged into Keyboard_Class::updateKeyList (task 15).
#include <unity.h>
#include "dp_keys.h"

void setUp(void) { dp::dp_keys_release_all(); }
void tearDown(void) {}

static void test_down_up_ordering(void) {
  dp::dp_keys_push(0, 1, 1);  // '1' down
  dp::dp_keys_push(2, 13, 1); // enter down
  TEST_ASSERT_EQUAL_UINT8(2, dp::dp_keys_held_count());

  uint8_t rows[4], cols[4];
  uint8_t n = dp::dp_keys_snapshot(rows, cols, 4);
  TEST_ASSERT_EQUAL_UINT8(2, n);
  TEST_ASSERT_EQUAL_UINT8(0, rows[0]);  TEST_ASSERT_EQUAL_UINT8(1, cols[0]);
  TEST_ASSERT_EQUAL_UINT8(2, rows[1]);  TEST_ASSERT_EQUAL_UINT8(13, cols[1]);
  dp::dp_keys_snapshot(rows, cols, 4);  // second keyboard update: both keys now seen DP_KEYS_MIN_SEEN times

  dp::dp_keys_push(0, 1, 0);  // '1' up -- immediate, the key was visible long enough
  TEST_ASSERT_EQUAL_UINT8(1, dp::dp_keys_held_count());
  n = dp::dp_keys_snapshot(rows, cols, 4);
  TEST_ASSERT_EQUAL_UINT8(1, n);
  TEST_ASSERT_EQUAL_UINT8(2, rows[0]);  TEST_ASSERT_EQUAL_UINT8(13, cols[0]);

  dp::dp_keys_push(2, 13, 0);  // enter up
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_held_count());
}

static void test_duplicate_down_ignored(void) {
  dp::dp_keys_push(1, 5, 1);
  dp::dp_keys_push(1, 5, 1);  // repeated down for the same key -- no-op
  dp::dp_keys_push(1, 5, 1);
  TEST_ASSERT_EQUAL_UINT8(1, dp::dp_keys_held_count());
  uint8_t r[4], c[4]; dp::dp_keys_snapshot(r, c, 4); dp::dp_keys_snapshot(r, c, 4);  // seen twice

  dp::dp_keys_push(1, 5, 0);  // single up releases it
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_held_count());
}

static void test_up_of_unheld_key_is_noop(void) {
  dp::dp_keys_push(3, 0, 0);  // up with nothing held
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_held_count());
}

static void test_link_down_releases_all(void) {
  dp::dp_keys_push(0, 1, 1);
  dp::dp_keys_push(0, 3, 1);
  dp::dp_keys_push(2, 13, 1);
  TEST_ASSERT_EQUAL_UINT8(3, dp::dp_keys_held_count());

  dp::dp_keys_release_all();  // USB link dropped
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_held_count());

  uint8_t rows[4], cols[4];
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_snapshot(rows, cols, 4));
}

static void test_fast_tap_stays_visible_for_min_seen_snapshots(void) {
  dp::dp_keys_release_all();
  uint8_t r[4], c[4];
  dp::dp_keys_push(2, 13, 1); dp::dp_keys_push(2, 13, 0);          // down+up before any keyboard update
  TEST_ASSERT_EQUAL_UINT8(1, dp::dp_keys_snapshot(r, c, 4));       // update 1: still reported
  TEST_ASSERT_EQUAL_UINT8(2, r[0]); TEST_ASSERT_EQUAL_UINT8(13, c[0]);
  TEST_ASSERT_EQUAL_UINT8(1, dp::dp_keys_snapshot(r, c, 4));       // update 2: still reported
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_snapshot(r, c, 4));       // update 3: released
  dp::dp_keys_push(0, 1, 1);
  dp::dp_keys_snapshot(r, c, 4); dp::dp_keys_snapshot(r, c, 4);    // seen twice while held
  dp::dp_keys_push(0, 1, 0);                                       // a normal release is immediate
  TEST_ASSERT_EQUAL_UINT8(0, dp::dp_keys_held_count());
  dp::dp_keys_push(0, 2, 1); dp::dp_keys_push(0, 2, 0); dp::dp_keys_push(0, 2, 1);  // re-press cancels the deferred release
  dp::dp_keys_snapshot(r, c, 4); dp::dp_keys_snapshot(r, c, 4); dp::dp_keys_snapshot(r, c, 4);
  TEST_ASSERT_EQUAL_UINT8(1, dp::dp_keys_held_count());
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_down_up_ordering);
  RUN_TEST(test_duplicate_down_ignored);
  RUN_TEST(test_up_of_unheld_key_is_noop);
  RUN_TEST(test_link_down_releases_all);
  RUN_TEST(test_fast_tap_stays_visible_for_min_seen_snapshots);
  return UNITY_END();
}
