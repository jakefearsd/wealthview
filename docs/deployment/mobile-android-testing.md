# Mobile — Android device testing

This guide covers getting `@wealthview/mobile` onto a physical Android phone for end-to-end testing against your local backend. The Linux dev environment can typecheck and run Jest, but it cannot build APKs — that part runs on your machine.

iOS deployment is deferred until first release; that section is a placeholder at the bottom.

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | latest stable | Or just the command-line tools + platform-tools, but the bundled SDK manager is by far the easiest way to install the right `compileSdk` / `buildTools` versions. |
| JDK | 17+ | RN 0.85 needs JDK 17 minimum. `java -version` to check. |
| Node | 22.11+ | Same engine pin as the rest of the monorepo. |
| Android phone | Android 9+ | Older versions also work but cleartext-HTTP behaviour differs. |
| `adb` on `PATH` | from platform-tools | `adb devices` should list your phone after USB debugging is enabled. |

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
