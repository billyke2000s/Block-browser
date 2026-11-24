# Block Browser

A full Android web browser with ad blocking built in. It is a fork of [Iceraven](https://github.com/fork-maintainers/iceraven-browser), which is itself a fork of Firefox for Android (Fenix). So it runs on Mozilla's Gecko engine and keeps Firefox's features: tabs and tab groups, add-ons, sync, password manager, reader view, private tabs, `about:config`, and so on.

This replaces the earlier WebView-based [Blockbrowser](https://github.com/billyke2000s/Blockbrowser), which couldn't get close to a full browser on top of Android System WebView.

Block Browser is not made, endorsed or supported by Mozilla or the Iceraven maintainers.

## What's different from Iceraven

| Change | Where |
|---|---|
| Name, icon, logo and package name (`io.github.billyke2000s.blockbrowser`), so it installs alongside Firefox and Iceraven | `app/build.gradle`, `app/src/forkRelease/res/`, CI string replacement |
| **Its own ad blocker**: a built-in extension blocks requests to about 215,000 advertising, tracking and malware domains (StevenBlack hosts + HaGeZi Multi PRO, refreshed on every CI build). A page on a blocked domain shows an explanation with "Allow this site". **⋮ → Ad blocking** turns it off or on for the current site. No uBlock Origin or other add-on is installed | `app/src/main/assets/extensions/blockbrowser-blocker/`, `app/src/main/java/io/github/billyke2000s/blockbrowser/blocking/`, `automation/blockbrowser/update-blocklist.sh` |
| **Home page like the old Block Browser**: logo, search box in the middle, shortcuts, ads and trackers blocked today / this session / all time, a 7-day chart and the most blocked domains (private tabs are counted but their domains aren't recorded). Address bar at the top, no Firefox stories | `blocking/BlockStatsSection.kt`, `home/ui/Homepage.kt`, `Settings.kt` |
| Enhanced Tracking Protection is **Strict** by default (Iceraven/Firefox: Standard) | `Settings.kt`, `tracking_protection_preferences.xml` |
| **Download video** in the menu (under Find in page): saves a video the page played. Plain files go to the normal download manager; HLS (`.m3u8`) and DASH (`.mpd`) streams are downloaded in the background with a progress notification and saved to Downloads as MP4 (or WebM for VP9 video), without re-encoding | `app/src/main/java/io/github/billyke2000s/blockbrowser/media/`, `app/src/main/assets/extensions/blockbrowser-media/` |
| CI builds on every push, signs with the project key and publishes a release | `.github/workflows/build.yml`, `automation/blockbrowser/sign-apks.sh` |

### Video downloads

A small built-in extension watches the responses each tab receives and notes video files, HLS playlists and DASH manifests (`assets/extensions/blockbrowser-media/`). **Download video** then checks each one, lets you pick a video and a quality, and downloads it through the browser's own network stack, so signed-in pages and private tabs keep working.

What it won't do, on purpose or because it can't:

- **Copy-protected (DRM) or encrypted video is refused.** That includes Netflix, Disney+, Prime Video and most paid streaming. Block Browser does not get around copy protection.
- **Live streams** are refused; only finished videos can be saved.
- **DASH manifests with several periods** (often adverts spliced in) aren't supported yet.
- **YouTube** doesn't serve its web player as plain HLS/DASH, so it generally isn't detected.
- If nothing is found, start playing the video first: players often only load the stream once playback starts.

The playlist/manifest parsers and the downloader have JVM tests in `app/src/test/java/io/github/billyke2000s/blockbrowser/media/`. They need no Android SDK:

```sh
# from the repo root, with junit-4.13.2.jar and hamcrest-core-1.3.jar in ./lib
M=app/src/main/java/io/github/billyke2000s/blockbrowser/media
T=app/src/test/java/io/github/billyke2000s/blockbrowser/media
javac -d out -cp lib/junit-4.13.2.jar $M/HlsPlaylist.java $M/DashManifest.java $M/StreamDownload.java $M/StreamTrack.java $M/StreamUrls.java $T/*.java
java -cp out:lib/junit-4.13.2.jar:lib/hamcrest-core-1.3.jar org.junit.runner.JUnitCore \
  io.github.billyke2000s.blockbrowser.media.HlsPlaylistTest io.github.billyke2000s.blockbrowser.media.DashManifestTest io.github.billyke2000s.blockbrowser.media.StreamDownloadTest
```

## Install

1. Open the [latest release](https://github.com/billyke2000s/Block-browser/releases/latest).
2. Download **block-browser-arm64-v8a.apk** (almost every phone from the last several years). Older 32-bit phones need `armeabi-v7a`.
3. Open it and allow installs from your browser or file manager if Android asks.

Android 8.0 or newer is required.

## Signing

Releases are signed in CI by `automation/blockbrowser/sign-apks.sh`. It uses these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEY` | The keystore file, base64-encoded (`base64 -w0 key.jks`) |
| `KEY_ALIAS` | Alias of the key in the keystore |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password (can be the same as the keystore password) |

Without them, each build is signed with a throwaway key and the release notes say so; such a build can't update an install signed with any other key. Keep a backup of the keystore outside GitHub: if it's lost, phones can't update and the app must be reinstalled.

## Build

Building takes a lot of memory (CI gives Gradle 7 GB). The workflow in `.github/workflows/build.yml` is the reference. Locally, with the Android SDK at `$ANDROID_SDK_ROOT` and Java 17:

```sh
git clone --recursive https://github.com/billyke2000s/Block-browser
cd Block-browser
./automation/iceraven/patch_android_components.sh
./automation/iceraven/setup_venv.sh
./gradlew app:assembleForkRelease -PversionName=dev
```

The unsigned APKs appear in `gradle/build/app/outputs/apk/forkRelease/`; sign them with `automation/blockbrowser/sign-apks.sh <that folder> dist`.

## Updating from upstream

The `iceraven` branch tracks Iceraven. To pull in a new Iceraven/Firefox release, merge `fork-maintainers/iceraven-browser`'s `iceraven` branch. Block Browser's changes are deliberately small and listed in the table above, so conflicts should stay limited to those files. Iceraven's own `ci.yml` and `release.yml` workflows were removed here; keep them removed when merging.

## Blocklist licences

The bundled blocklist combines [StevenBlack's hosts](https://github.com/StevenBlack/hosts) (MIT) and [HaGeZi Multi PRO](https://github.com/hagezi/dns-blocklists) (GPL-3.0); its header records the versions used.

## License

    This Source Code Form is subject to the terms of the Mozilla Public
    License, v. 2.0. If a copy of the MPL was not distributed with this
    file, You can obtain one at http://mozilla.org/MPL/2.0/
