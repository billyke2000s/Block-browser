# Block Browser

A full Android web browser with ad blocking built in. It is a fork of [Iceraven](https://github.com/fork-maintainers/iceraven-browser), which is itself a fork of Firefox for Android (Fenix). So it runs on Mozilla's Gecko engine and keeps Firefox's features: tabs and tab groups, add-ons, sync, password manager, reader view, private tabs, `about:config`, and so on.

This replaces the earlier WebView-based [Blockbrowser](https://github.com/billyke2000s/Blockbrowser), which couldn't get close to a full browser on top of Android System WebView.

Block Browser is not made, endorsed or supported by Mozilla or the Iceraven maintainers.

## What's different from Iceraven

| Change | Where |
|---|---|
| Name, icon, logo and package name (`io.github.billyke2000s.blockbrowser`), so it installs alongside Firefox and Iceraven | `app/build.gradle`, `app/src/forkRelease/res/`, CI string replacement |
| Offers to install **uBlock Origin** on first start (Firefox's normal add-on prompt; it's from addons.mozilla.org and updates itself) | `app/src/main/java/io/github/billyke2000s/blockbrowser/UBlockOriginOffer.kt` |
| Enhanced Tracking Protection is **Strict** by default (Iceraven/Firefox: Standard) | `Settings.kt`, `tracking_protection_preferences.xml` |
| CI builds on every push, signs with the project key and publishes a release | `.github/workflows/build.yml`, `automation/blockbrowser/sign-apks.sh` |

Planned: a built-in "Download video" menu item for plain video files and HLS/DASH streams. DRM-protected video is out of scope and won't be supported.

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

## License

    This Source Code Form is subject to the terms of the Mozilla Public
    License, v. 2.0. If a copy of the MPL was not distributed with this
    file, You can obtain one at http://mozilla.org/MPL/2.0/
