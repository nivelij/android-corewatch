# CoreWatch

A small Android device monitor — a single screen showing live hardware telemetry and system info, built with Kotlin + Jetpack Compose (Material 3). Dark "Ember" theme, responsive portrait/landscape layouts (built for a gaming handheld).

## Features

- **Live tiles (refresh every 1s):**
  - CPU clock (peak across cores + sparkline; falls back to the max/min range when the kernel doesn't expose live frequency)
  - Memory used vs total
  - Battery — temperature, charge state (AC/USB/Wireless), level, and live charge current (`+`/`−` mA/A)
- **Whole-session charts** (one point every 5s, in-memory, cleared on close): CPU and memory history.
- **System info:** device name, SoC (Snapdragon/MediaTek/… marketing name), cores, ABI, clock range, Android version, security patch, kernel.

No runtime permissions required.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 35). Point the SDK via `local.properties` (`sdk.dir=...`).

```bash
./gradlew assembleDebug      # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease    # release APK -> app/build/outputs/apk/release/
./gradlew installDebug       # build + install on a connected device/emulator
```

- **minSdk 31 (Android 12)**, targetSdk 35.
- Live CPU clock depends on the device exposing `/sys/.../cpufreq/scaling_cur_freq` to apps; some Android 12+ devices block it via SELinux, in which case the CPU tile shows the clock *range* instead.

## Release signing

Updates only install over an existing app if signed with the **same key**. Create a release keystore once and keep it safe (losing it means you can no longer update installs):

```bash
keytool -genkeypair -v -keystore corewatch-release.jks \
  -alias corewatch -keyalg RSA -keysize 2048 -validity 10000
```

**Local signed build:** copy `keystore.properties.example` → `keystore.properties` (gitignored) and fill in the paths/passwords.

**CI:** the GitHub Actions workflow (`.github/workflows/build.yml`) signs using repository secrets:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i corewatch-release.jks` (the keystore, base64-encoded) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `corewatch` |
| `KEY_PASSWORD` | key password |

The workflow builds on push to `main` and on `v*` tags (tag builds also attach the signed APK to a GitHub Release). Bump `versionCode` in `app/build.gradle.kts` for each release so it's recognized as newer.
