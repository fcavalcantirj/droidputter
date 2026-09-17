# Google Play: console texts and checklist

Everything the Play Console asks for, ready to paste. Assets are in `docs/play/assets/`.

## Store listing

- **App name (30):** Droidputter
- **Short description (80):** Run Cardputer apps on your phone through an ESP32-S3 plugged in over USB.
- **Full description:**

Plug an ESP32-S3 into your phone with a USB-OTG cable and the phone becomes the Cardputer: the ESP32
runs the app, your phone is its screen, keyboard, GPS and flasher.

Droidputter rebuilds open-source Cardputer apps on demand. Pick an app from the catalog or paste any
GitHub repository, choose the target (a bare ESP32-S3 with no display, or a Cardputer ADV), and the build
runs in the cloud in a couple of minutes. Then flash it from the phone, no computer involved, and the app
appears on your screen the moment the board reboots.

Features:
- Live mirror of the ESP32's screen on the phone
- The Cardputer's full keyboard as a soft keyboard, plus any Bluetooth keyboard
- Your phone's GPS forwarded to the app as if a GPS module were attached
- In-app flasher with verification, no PC needed
- Prebuilt M5Burner firmware from LauncherHub, flash-only
- Optional community verdicts: which apps work on which boards

Requires: a phone with USB host (OTG) support and an ESP32-S3 board (M5Stack Cardputer ADV, StickS3, or
any ESP32-S3 devkit on its native USB port). Open source, MIT: https://github.com/fcavalcantirj/droidputter

- **App category:** Tools. **Tags:** developer tools, hardware.
- **Contact email:** (yours). **Website:** https://github.com/fcavalcantirj/droidputter
- **Privacy policy URL:** https://fcavalcantirj.github.io/droidputter/PRIVACY.html (GitHub Pages from `docs/`)
- **Graphics:** `assets/icon-512.png`, `assets/feature-1024x500.jpg`, the six `assets/screenshot-*.png`
  (2712x1356, 2:1). Promo video: your YouTube upload of the devkit clip.

## App content forms

- **Privacy policy:** the URL above.
- **Ads:** no. **App access:** all features available without credentials (no login).
- **Content rating (IARC):** Utility/Productivity; no violence, no user-generated content shown in the app
  (verdicts are shown as counts only), no purchases, no location sharing with other users.
- **Target audience:** 18 and over (not designed for children).
- **News app:** no. **COVID-19:** no. **Government app:** no. **Financial features:** none.
- **Data safety:**
  - Data collected: *Device or other IDs* -> "Other IDs": an app-generated random identifier. Collected:
    yes (only after in-app consent). Shared: yes, published publicly with the user's verdict. Purpose:
    app functionality (community compatibility reports). Optional: yes. Ephemeral: no.
    Encrypted in transit: yes. Users can request deletion: yes (issue with the identifier).
  - Location: *not collected, not shared* (used on-device, forwarded over the USB cable only).
  - Everything else: not collected.
  - Security practices: data encrypted in transit (HTTPS); deletion on request.
- **Foreground service permissions (Android 14+ declaration):**
  - `connectedDevice`: keeps the USB link to the ESP32-S3 alive while the screen is off or the app is in
    the background; without it Android suspends the USB reader and the board goes silent. Video:
    plug the board, link comes up, press power, screen off 30 s, screen on, the mirror is still live.
  - `location`: while the user has turned the GPS feed on, the service keeps forwarding the phone's GNSS
    sentences to the board with the screen off (the board's app expects a continuous GPS stream). Video:
    Connection screen, tap "Start GPS feed", grant location, the ESP app shows the fix, press power for
    30 s, screen on, the fix is still updating; tap "Stop GPS feed".
- **Location permission declaration:** foreground only (no background location permission requested);
  core feature: "forwards the phone's position to the connected device"; prominent disclosure = the
  permission dialog is shown only when the user taps "Start GPS feed", and the Connection screen states
  what the feed does.

## Release checklist (you, in the console)

1. Create the app: Droidputter, English (US), app, free.
2. Setup > App access, Ads, Content rating, Target audience, News, COVID, Data safety, Government,
   Financial features, Health: answers above.
3. Setup > App signing: use Play App Signing; upload key = our release keystore (the AAB is already
   signed with it; Play generates the app signing key).
4. Store listing: texts + graphics above.
5. Testing > Internal testing: upload `droidputter-vX.Y.Z.aab` from the GitHub Release, add testers
   (emails), roll out. Install the internal-test link on your phone (uninstall the sideloaded APK first:
   Play's signing key differs from the sideload one).
6. Testing > Closed testing: same AAB, 12+ testers, 14 days; then Publishing overview > Apply for
   production access.
7. Optional CI upload: Google Cloud > service account > invite it in Play Console (Users and permissions,
   release manager on this app); put the JSON key in the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`. From
   then on every `v*` tag lands on the internal track by itself (release.yml).
