# Droidputter privacy policy

Effective 2026-09-17. Applies to the Droidputter Android app (`com.droidputter`).

## What the app does with data

- **USB link.** Pixels, key presses and GPS sentences travel over the USB cable between the phone and the
  ESP32-S3 you plugged in. They never leave the cable.
- **Location.** When you tap "Start GPS feed", the app reads the phone's GNSS sentences and forwards them
  over USB to the connected ESP32-S3, so the app running there sees a GPS. Location is never stored and
  never sent to any server. The feed runs only while you keep it on; a foreground-service notification
  shows it is running. The app asks for the location permission only when you start the feed.
- **Builds.** When you ask for a build, the app sends the GitHub repository name, the git ref and the build
  target to the Droidputter build proxy (`droidputter-proxy.vercel.app`), which runs the build on GitHub
  Actions in the public repository `fcavalcantirj/droidputter`. The request contains no personal data.
- **Verdicts (optional, public).** After a flash the app can report whether the firmware worked. Sharing is
  off until you agree in the app. When you agree, each report is published as a public GitHub issue and
  then in a public JSON file; it contains the app name, the firmware hash, the board name, works/broken,
  the date, an optional note, and an anonymous identifier generated on your phone (`device-` followed by
  eight hexadecimal characters). That identifier is random, is not derived from your device or account,
  and cannot be linked to you by us. You can keep verdicts on your phone instead; they then never leave it.
- **Catalog and feeds.** The app downloads the public catalog and verdict files from GitHub, the
  LauncherHub firmware list from `api.launcherhub.net`, and firmware binaries from the build proxy or from
  M5Stack's M5Burner CDN when you flash them. These are ordinary HTTPS downloads.

## What the app does not do

- No account, no sign-in, no advertising, no analytics, no crash reporting, no third-party SDKs that
  collect data.
- No access to contacts, files outside the app's own storage, camera, microphone or the network beyond
  the endpoints named above.

## Data retention and deletion

Everything the app stores lives in its private storage on your phone (build list, downloaded firmware
parts, your verdicts, the anonymous identifier, the sharing consent). Uninstalling the app deletes all of
it. Published verdicts are public records in the GitHub repository; to have one removed, open an issue at
https://github.com/fcavalcantirj/droidputter/issues quoting the anonymous identifier.

## Children

The app is not directed at children and has no content or features aimed at them.

## Contact

Felipe Cavalcanti, via https://github.com/fcavalcantirj/droidputter/issues.
