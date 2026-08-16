# Mobile — Android device testing

This guide covers getting `@wealthview/mobile` onto a physical Android phone for end-to-end testing against your local backend. The Linux dev environment can typecheck and run Jest, but it cannot build APKs — that part runs on your machine.

iOS deployment is deferred until first release; that section is a placeholder at the bottom.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| React Native | 0.87.0 | Pinned in `mobile/package.json` (`react-native`, `@react-native/*` tooling, and `@react-native/new-app-screen` all on 0.87.0). React 19.2.x. |
| Android Studio | Recent enough to install SDK Platform 37 | Installed at `~/android-studio/`. Launchable from the apps menu (entry created at `~/.local/share/applications/android-studio.desktop`) or via `studio.sh` once `~/android-studio/bin` is on PATH. |
| JDK | 17.0.19-tem via SDKMAN | Declared in `mobile/.sdkmanrc`. The backend uses JDK 25 (`backend/.sdkmanrc`); SDKMAN auto-switches per directory. |
| Node | ≥ 22.13.0 | `engines` in `mobile/package.json`. |
| Gradle | 9.4.1 | Via the wrapper — don't install it separately, use `./gradlew`. |
| Android phone | Android 9+ (`minSdkVersion = 24` allows 7.0+) | Older versions also work but cleartext-HTTP behaviour differs. |
| `adb` on `PATH` | from platform-tools | `adb devices` should list your phone after USB debugging is enabled. Installed alongside the SDK by Studio's first-run wizard. |

Android SDK versions come from `mobile/android/build.gradle`:

| Property | Value |
|---|---|
| `compileSdkVersion` | 37 |
| `targetSdkVersion` | 36 |
| `minSdkVersion` | 24 |
| `buildToolsVersion` | 37.0.0 |
| `ndkVersion` | 27.1.12297006 |
| `kotlinVersion` | 2.2.0 |

### One-time machine setup (already done on the dev workstation)

If setting up a new machine from scratch, replicate:

```bash
# JDK 17 alongside JDK 25 via SDKMAN with auto-switch
sdk install java 17.0.19-tem        # may need: sed -i 's/sdkman_auto_env=false/sdkman_auto_env=true/' ~/.sdkman/etc/config

# Android Studio (3.5GB download, 3.3GB on disk after extract)
curl -L -o /tmp/studio.tgz "$(curl -fsSL https://developer.android.com/studio | grep -oE 'https://[^\"]*android-studio[^\"]*linux[^\"]*\.tar\.gz' | head -1)"
tar -xzf /tmp/studio.tgz -C ~

# .bashrc additions (after SDK install completes via Studio):
#   export ANDROID_HOME="$HOME/Android/Sdk"
#   export ANDROID_SDK_ROOT="$ANDROID_HOME"
#   export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
#   export PATH="$HOME/android-studio/bin:$PATH"
```

The `mobile/.sdkmanrc` file in this repo declares `java=17.0.19-tem`. With `sdkman_auto_env=true`, every `cd` into `mobile/` switches the shell's JDK automatically; `cd backend` picks up `backend/.sdkmanrc` (JDK 25) for backend work.

### Android Studio first-run wizard (one-time, GUI)

1. Launch Android Studio (apps menu → Android Studio, or `studio.sh` from terminal).
2. "Do not import settings" → Next.
3. Welcome → Next.
4. Install Type: **Standard** (downloads recommended SDK + platform tools + emulator).
5. UI Theme: pick.
6. Verify Settings → Next → accept all licenses → Finish.
7. SDK download starts (~2-3GB; 5-10 minutes on a fast connection). Sit through it once.

### Install the Android API levels RN needs (also one-time, GUI)

The Standard wizard installs the latest SDK platform Studio's release ships with — that's not always the version this repo's `mobile/android/build.gradle` pins (today: `compileSdkVersion = 37`, `buildToolsVersion = 37.0.0`, `targetSdkVersion = 36`). If they don't match, `npm run android` fails with an unhelpful Gradle error pointing at a missing platform.

From the **Welcome to Android Studio** screen:

1. Click **More Actions** (top-right of the project list) → **SDK Manager**. (The SDK Manager is hidden behind this dropdown when no project is open; once a project is open it lives under Tools → SDK Manager.)
2. **SDK Platforms** tab — tick at least:
   - **API 37** — required, matches the repo's `compileSdkVersion`.
   - **Android 16 (API 36)** — the repo's `targetSdkVersion`; useful for emulator images.
3. **SDK Tools** tab — verify these are present (install if missing):
   - **Android SDK Command-line Tools (latest)** — gives you `sdkmanager` on the CLI.
   - **Android SDK Build-Tools** at version 37.0.0.
   - **NDK (Side by side)** at 27.1.12297006 — the version `build.gradle` pins.
   - **Android Emulator** (only if you want to use an emulator instead of a physical phone).
4. Apply → accept licenses → wait for the download (~1-2 GB depending on what was missing).

After this, **open a new terminal** so `ANDROID_HOME` and the `platform-tools` PATH entries take effect, then verify:

```bash
adb --version             # should print Android Debug Bridge version 1.0.x
sdkmanager --version
echo "$ANDROID_HOME"      # e.g. /home/<you>/Android/Sdk
sdkmanager --list_installed | grep -E "platforms|build-tools|ndk"
# should show platforms;android-37, build-tools;37.0.0 and ndk;27.1.12297006
```

On the phone:
- Settings → About phone → tap "Build number" seven times → Developer options unlocked.
- Developer options → enable "USB debugging".
- Plug into the dev machine. The phone shows a dialog asking to trust the host — accept it.
- `adb devices` on the dev machine prints the device serial and `device` (not `unauthorized`).

## 2. First-time setup

```bash
# repo root
npm install                                    # hoists workspace deps

# mobile/
cd mobile
npm start                                      # leave Metro running in this terminal
```

In a second terminal:

```bash
cd mobile
npm run android                                # builds the APK and installs it
```

The first build is slow — Gradle warms its caches and pulls down Android dependencies. Budget 5–15 minutes. Subsequent incremental builds are quick (Gradle daemon caches everything).

## 3. Pointing the app at your backend

The first launch shows the Server URL screen. To talk to your local backend:

1. Find your dev machine's LAN IP:

   ```bash
   ip -4 addr show | grep inet                # Linux
   ifconfig | grep 'inet '                    # macOS
   ```

   Look for an address on your home subnet — usually `192.168.x.y` or `10.0.x.y`.

2. Make sure the backend is running and bound to all interfaces. `./wv up` from the repo root starts the dev stack, whose `app` service publishes `80:8080` with no address restriction — so it listens on all interfaces and any host on the LAN can reach it.

3. On the phone, enter the URL into the Server URL field. It must parse as an `http:`/`https:` URL with a host — `ServerConfigScreen` rejects anything else. Examples:
   - `http://192.168.1.50` — typical home LAN
   - `http://10.0.2.2` — Android emulator's host gateway (not for physical devices)
   - `https://wealthview.example.com` — Cloudflare-tunnelled or otherwise TLS-terminated deployment

4. Sign in with the seeded demo credentials. The mobile client authenticates against `POST /api/v1/auth/token/login` (the token-based endpoints in `AuthMobileController`), not the cookie-based web ones.
   - **Email:** `demo@wealthview.local`
   - **Password:** `demo123`

   These are seeded by `SampleDataInitializer`, which runs on the `dev` and `docker` profiles only — so they exist against a local `./wv up` stack, not against a `prod` deployment.

### What you should see

After login you'll land on the **Portfolio** tab showing your net worth and accounts grouped by category (Investment Accounts / Cash / Other). Pull down to refresh. Tap any account card to see its details. The **Settings** tab lets you change the server URL or log out.

If the Portfolio screen lands on the empty state ("No accounts yet"), the demo data probably hasn't seeded yet — confirm `SampleDataInitializer` ran in the backend logs (`./wv logs --no-follow app | grep -i sample`).

## 4. HTTP-vs-HTTPS gotcha

Android 9+ blocks plaintext HTTP traffic by default, and the app opts back in via the manifest placeholder `android:usesCleartextTraffic="${usesCleartextTraffic}"` in `mobile/android/app/src/main/AndroidManifest.xml`. React Native's Gradle plugin fills that placeholder for you: **`true` for the `debug` and `debugOptimized` build types, `false` for `release`**. There is no per-domain allowlist involved — a debug build permits cleartext to *any* host, including an arbitrary LAN IP.

So for the normal `npm run android` debug workflow, plain `http://192.168.x.y` just works. If "Network request failed" hits you in debug mode, look at the backend URL, LAN reachability, and firewall before you suspect cleartext.

Where it *does* bite: a **release** build (`assembleRelease`, §5) has `usesCleartextTraffic="false"` and will refuse any `http://` server URL. The right answer there is to terminate TLS at the edge and use `https://`. If you must sideload a release build against a plaintext LAN backend, add a scoped exception rather than flipping cleartext on globally.

Drop this file at `mobile/android/app/src/main/res/xml/network_security_config.xml` (neither the `res/xml/` directory nor the file exists in the repo — you are creating both):

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Narrow cleartext exceptions. `<domain>` entries are hostnames or
         literal IPs — not CIDR ranges — so list the exact addresses you use. -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
        <!-- Your dev machine's LAN IP. Replace with the real one. -->
        <domain includeSubdomains="true">192.168.1.50</domain>
    </domain-config>
</network-security-config>
```

Reference it from `AndroidManifest.xml` in the `<application>` tag. `mobile/android/app/src/` currently contains only `main/`, so to scope this to debug builds you first create a `src/debug/AndroidManifest.xml` that AGP merges over the main one:

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

For release builds, terminate TLS at the edge (Cloudflare Tunnel, nginx with Let's Encrypt, etc.) and use `https://`. Don't ship cleartext to production.

## 5. Sideloading a release APK

Once the auth flow looks good in debug mode, you can build a self-installable APK:

```bash
cd mobile/android
./gradlew assembleRelease
```

The APK lands in `mobile/android/app/build/outputs/apk/release/app-release.apk`. Copy it to the phone (USB, AirDroid, email-to-self, etc.) and tap to install — the phone will prompt for "Install from unknown sources" the first time.

Two things about this build type, both visible in `mobile/android/app/build.gradle`: `enableProguardInReleaseBuilds` is `false`, so nothing is minified; and `usesCleartextTraffic` resolves to `false`, so the server URL must be `https://` unless you added the exception in §4.

**Signing.** The scaffold's `release` build type reuses `signingConfigs.debug` (storeFile `debug.keystore`, alias `androiddebugkey`, password `android`) so `assembleRelease` succeeds out of the box. That is a placeholder, not a release configuration. For a real sideload, generate your own keystore:

```bash
keytool -genkeypair -v \
  -keystore wealthview-release.keystore \
  -alias wealthview-release \
  -keyalg RSA -keysize 2048 -validity 10000
```

Place the keystore **outside** the repo (e.g. `~/.android/wealthview-release.keystore`). Reference it from `mobile/android/gradle.properties` (gitignored) — never commit the keystore or its passwords. The Android docs at <https://reactnative.dev/docs/signed-apk-android> walk through the `signingConfigs` / `buildTypes.release` wiring.

## 6. Common issues

| Symptom | Cause / fix |
|---|---|
| `Unable to load script` | Metro isn't running, or the phone can't reach the dev machine on port 8081. Try `adb reverse tcp:8081 tcp:8081`. |
| `Network request failed` on login (debug build) | Backend URL wrong, host firewall, or the backend isn't up. Cleartext is *not* the cause in a debug build (see §4). Confirm with `curl http://<lan-ip>/actuator/health` from another LAN device. |
| `Network request failed` on login (release build) | `usesCleartextTraffic` is `false` in release. Use an `https://` URL or add the scoped exception in §4. |
| App crashes on launch | Check `adb logcat \| grep -i react`. Often a missing native module: `cd mobile/android && ./gradlew clean` and rebuild. |
| Gradle error about a missing platform / build-tools | The SDK Manager didn't install what `mobile/android/build.gradle` pins. You need platform 37, build-tools 37.0.0, and NDK 27.1.12297006. |
| `SDK location not found` | Set `ANDROID_HOME` to your SDK path (e.g. `~/Android/Sdk` on Linux, `~/Library/Android/sdk` on macOS) and add `$ANDROID_HOME/platform-tools` to `PATH`. |
| Gradle picks the wrong JDK | `mobile/.sdkmanrc` pins `17.0.19-tem`. Confirm with `java -version` inside `mobile/`; the backend's JDK 25 will not build the Android project. |
| Login succeeds but Dashboard shows blank | The `/auth/me` round-trip on focus is failing silently. Check `adb logcat` for axios errors and confirm rate limits aren't tripping (`X-RateLimit-Remaining`). |
| Tokens "stick" after backend restart | The backend bumps `token_generation` on restart only if you've also rotated `JWT_SECRET`. Otherwise pull-to-refresh / re-login should clear it. If it doesn't, `Settings → Log out` wipes the keychain. |

## 7. iOS (placeholder)

iOS deployment requires:
- A Mac (any reasonably recent one).
- Xcode 16+.
- An Apple Developer account ($99/year for App Store distribution; free for sideloading via Xcode for 7 days).
- CocoaPods (`gem install cocoapods` or `brew install cocoapods`).

Setup once:

```bash
cd mobile/ios && pod install
cd ..        && npm run ios
```

`mobile/ios/` currently holds `MobileApp.xcodeproj` and a `Podfile`; `pod install` is what generates `MobileApp.xcworkspace`. Sideload-to-physical-iPhone via Xcode requires opening that workspace and signing the build with a personal team. App Store distribution requires an App ID, provisioning profile, and an archive build through Xcode's Organizer.

This document will be expanded with the concrete iOS instructions when the user is ready to ship to TestFlight.
