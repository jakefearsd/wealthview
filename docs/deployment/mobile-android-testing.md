# Mobile — Android device testing

This guide covers getting `@wealthview/mobile` onto a physical Android phone for end-to-end testing against your local backend. The Linux dev environment can typecheck and run Jest, but it cannot build APKs — that part runs on your machine.

iOS deployment is deferred until first release; that section is a placeholder at the bottom.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Panda Feature Drop (2025.3.4+) | Installed at `~/android-studio/`. Launchable from the apps menu (entry created at `~/.local/share/applications/android-studio.desktop`) or via `studio.sh` once `~/android-studio/bin` is on PATH. |
| JDK | 17.0.x via SDKMAN | RN 0.85 requires JDK 17. Backend uses JDK 25. SDKMAN auto-switches based on `mobile/.sdkmanrc`. |
| Node | 22.11+ | Same engine pin as the rest of the monorepo. |
| Android phone | Android 9+ | Older versions also work but cleartext-HTTP behaviour differs. |
| `adb` on `PATH` | from platform-tools | `adb devices` should list your phone after USB debugging is enabled. Installed alongside the SDK by Studio's first-run wizard. |

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

The `mobile/.sdkmanrc` file in this repo declares `java=17.0.19-tem`. With `sdkman_auto_env=true`, every `cd` into `mobile/` switches the shell's JDK automatically; `cd ..` reverts to JDK 25 for backend work.

### Android Studio first-run wizard (one-time, GUI)

1. Launch Android Studio (apps menu → Android Studio, or `studio.sh` from terminal).
2. "Do not import settings" → Next.
3. Welcome → Next.
4. Install Type: **Standard** (downloads recommended SDK + platform tools + emulator).
5. UI Theme: pick.
6. Verify Settings → Next → accept all licenses → Finish.
7. SDK download starts (~2-3GB; 5-10 minutes on a fast connection). Sit through it once.

### Install the Android API levels RN needs (also one-time, GUI)

The Standard wizard installs the latest SDK platform Studio's release ships with — that's not always the version this repo's `mobile/android/build.gradle` pins (today: `compileSdk = 36`, `buildToolsVersion = 36.0.0` — i.e. Android 16). If they don't match, `npm run android` fails with an unhelpful Gradle error pointing at a missing platform.

From the **Welcome to Android Studio** screen:

1. Click **More Actions** (top-right of the project list) → **SDK Manager**. (The SDK Manager is hidden behind this dropdown when no project is open; once a project is open it lives under Tools → SDK Manager.)
2. **SDK Platforms** tab — tick at least:
   - **Android 16 (API 36)** — required, matches the repo's `compileSdk`.
   - **Android 15 (API 35)** — recommended for compatibility testing one version back.
3. **SDK Tools** tab — verify these are present (install if missing):
   - **Android SDK Command-line Tools (latest)** — gives you `sdkmanager` on the CLI.
   - **Android SDK Build-Tools** at version 36.0.0.
   - **Android Emulator** (only if you want to use an emulator instead of a physical phone).
4. Apply → accept licenses → wait for the download (~1-2 GB depending on what was missing).

After this, **open a new terminal** so `ANDROID_HOME` and the `platform-tools` PATH entries take effect, then verify:

```bash
adb --version             # should print Android Debug Bridge version 1.0.x
sdkmanager --version      # should print 20.0 (or similar)
echo "$ANDROID_HOME"      # should print /home/jakefear/Android/Sdk
sdkmanager --list_installed | grep -E "platforms|build-tools"
# should show platforms;android-36 and build-tools;36.0.0 at minimum
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

2. Make sure the backend is running and bound to all interfaces. `./wv up` from the repo root binds to `0.0.0.0:80` so any host on the LAN can reach it.

3. On the phone, enter the URL into the Server URL field. Examples:
   - `http://192.168.1.50` — typical home LAN
   - `http://10.0.2.2` — Android emulator's host gateway (not for physical devices)
   - `https://wealthview.example.com` — Cloudflare-tunnelled or otherwise TLS-terminated deployment

4. Sign in with the seeded demo credentials:
   - **Email:** `demo@wealthview.local`
   - **Password:** `demo123`

### What you should see

After login you'll land on the **Portfolio** tab showing your net worth and accounts grouped by category (Investment Accounts / Cash / Other). Pull down to refresh. Tap any account card to see its details. The **Settings** tab lets you change the server URL or log out.

If the Portfolio screen lands on the empty state ("No accounts yet"), the demo data probably hasn't seeded yet — confirm `SampleDataInitializer` ran in the backend logs (`docker compose logs app | grep -i sample`).

## 4. HTTP-vs-HTTPS gotcha

Android 9+ blocks plaintext HTTP traffic by default. The RN scaffold sets `usesCleartextTraffic="true"` only for `localhost` and `10.0.2.2` (the emulator gateway). For an arbitrary LAN IP, you need to allowlist your subnet explicitly **for debug builds only**.

Drop this file at `mobile/android/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Cleartext is allowed for these subnets in debug builds only.
         Release builds rely on HTTPS (Cloudflare Tunnel, real TLS, etc.). -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
        <!-- LAN ranges. Trim to whichever subnet your home network uses. -->
        <domain includeSubdomains="true">192.168.0.0</domain>
        <domain includeSubdomains="true">192.168.1.0</domain>
        <domain includeSubdomains="true">192.168.1.50</domain>
        <domain includeSubdomains="true">10.0.0.0</domain>
    </domain-config>
</network-security-config>
```

Reference it from `AndroidManifest.xml` in the `<application>` tag (debug variant only — Android Studio's flavour system can scope this to `src/debug/AndroidManifest.xml`):

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

**Signing.** The scaffold's `release` build type currently reuses the debug keystore so `assembleRelease` succeeds out of the box. For a real sideload you should generate your own keystore:

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
| `Network request failed` on login | Backend URL wrong, cleartext blocked by Android (see §4), or backend bound only to `localhost`. Confirm with `curl http://<lan-ip>:80/actuator/health` from another LAN device. |
| App crashes on launch | Check `adb logcat | grep -i react`. Often a missing native module: `cd mobile/android && ./gradlew clean` and rebuild. |
| `SDK location not found` | Set `ANDROID_HOME` to your SDK path (e.g. `~/Android/Sdk` on Linux, `~/Library/Android/sdk` on macOS) and add `$ANDROID_HOME/platform-tools` to `PATH`. |
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

Sideload-to-physical-iPhone via Xcode requires opening `mobile/ios/MobileApp.xcworkspace` and signing the build with a personal team. App Store distribution requires an App ID, provisioning profile, and an archive build through Xcode's Organizer.

This document will be expanded with the concrete iOS instructions when the user is ready to ship to TestFlight.
