// gps-demo: shows droidputter_gps() is the only app-side change a UART-based
// GPS app needs to read the phone's location -- TinyGPSPlus reads the
// DROIDPUTTER shim's GPS_NMEA (0x82) ring exactly like a real GPS UART.
#include <M5Unified.h>
#include <TinyGPSPlus.h>
#include "droidputter.h"

static TinyGPSPlus gps;
static uint32_t lastDraw = 0;

void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  M5.Display.setRotation(1);
  M5.Display.setTextSize(2);
  M5.Display.setTextColor(TFT_WHITE, TFT_BLACK);
}

void loop() {
  M5.update();
  // This app has no M5Cardputer Keyboard_Class instance (the usual trigger for
  // dp::poll(), see shim/patches/M5Cardputer-1.1.1-droidputter.patch), so it
  // must pump incoming USB frames -- including GPS_NMEA -- itself.
  dp::poll();
  while (droidputter_gps().available()) gps.encode(droidputter_gps().read());

  if (millis() - lastDraw < 500) return;
  lastDraw = millis();

  M5.Display.fillScreen(TFT_BLACK);
  M5.Display.setCursor(0, 0);
  M5.Display.println("DROIDPUTTER GPS");
  M5.Display.println();
  if (gps.location.isValid()) {
    M5.Display.printf("lat: %.6f\n", gps.location.lat());
    M5.Display.printf("lon: %.6f\n", gps.location.lng());
  } else {
    M5.Display.println("lat: --");
    M5.Display.println("lon: --");
  }
  M5.Display.printf("sats: %d\n", gps.satellites.isValid() ? gps.satellites.value() : 0);
  if (gps.time.isValid()) {
    M5.Display.printf("time: %02d:%02d:%02d\n", gps.time.hour(), gps.time.minute(), gps.time.second());
  } else {
    M5.Display.println("time: --:--:--");
  }
}
