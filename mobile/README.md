# @wealthview/mobile

WealthView's React Native client. Lives as a workspace package alongside the web frontend and the cross-platform `shared/` package — see the root `README.md` for the monorepo layout.

This pass ships the daily-driver Portfolio screen on top of the auth MVP: net-worth headline, category breakdown chips, and accounts grouped by type with tap-through to a per-account detail screen. The authenticated UI is a two-tab bar (Portfolio / Settings) — see "Navigation" below. Holdings, transactions, charts, and account creation land in subsequent passes (web app for now).

## What ships in this build

| Screen | Purpose |
|---|---|
| BootSplash | Spinner shown while AuthContext reads the keychain and verifies `/auth/me` on cold start. |
| ServerConfigScreen | First screen on a fresh install. Validates and stores the user's WealthView server URL. |
| LoginScreen | Email + password against `POST /api/v1/auth/token/login`. Friendly refusal of MFA-enabled accounts (challenge UI is a later pass). |
| **PortfolioScreen** (default tab) | The screen the user opens every day. Net-worth headline, investment / cash / property breakdown chips, accounts grouped by category with the largest balances on top, pull-to-refresh, tap-through to AccountDetail. Hits `GET /dashboard/summary` and `GET /accounts` in parallel. |
| AccountDetailScreen | Tap-through landing for any account row. Shows balance, type, institution, currency. Holdings + transactions are a separate pass. |
| SettingsScreen | Identity card (email / role / tenant / user IDs), editable server URL (with confirmation when it would invalidate the current session), and a logout button. |

## Navigation

Authenticated UI is a `@react-navigation/bottom-tabs` navigator with two tabs:

- **Portfolio** — stack containing `Portfolio` and `AccountDetail`.
- **Settings** — single-screen stack.

Unauthenticated UI keeps its plain native-stack (`Login` ↔ `Settings` for server-URL editing). The root `RootNavigator` switches between these trees on `auth.status`.

State is held in a single `AuthProvider` (`src/auth/AuthContext.tsx`) using `useReducer`. Tokens persist in `react-native-keychain` (Keystore-backed `EncryptedSharedPreferences` on Android, `kSecClassGenericPassword` on iOS). The HTTP layer comes from `@wealthview/shared` — `createApiClient` and `createAuthApi` — so the same axios factory will eventually serve both the mobile app and the web frontend.

## CI vs local builds

| Step | Where it runs |
|---|---|
| `npm run typecheck --workspace mobile` | CI and locally |
| `npm run test --workspace mobile` | CI and locally |
| Android Gradle build (`npm run android`) | **Local only** — needs Android SDK |
| iOS Xcode build (`npm run ios`) | **Local only** — needs a Mac with Xcode |

Linux CI never builds APKs or runs xcodebuild. Releases are cut from a developer machine.

## Local development

From the repository root:

```bash
npm install                                # one-time, hoists workspace deps
```

From `mobile/`:

```bash
npm start                                  # Metro bundler
npm run android                            # build + launch Android emulator/device
npm run ios                                # build + launch iOS simulator (Mac only)
npm test                                   # Jest
npm run typecheck                          # tsc --noEmit
```

Metro is configured to watch `../shared/` and resolve modules from both the local and root-hoisted `node_modules`. See `metro.config.js`. Hierarchical lookup is disabled to prevent duplicate React installations from fighting.

## Running on a physical Android device (primary testing path)

See [`docs/deployment/mobile-android-testing.md`](../docs/deployment/mobile-android-testing.md) for the full step-by-step guide. Quick path once you've completed the one-time setup:

1. Backend running and bound to `0.0.0.0`: `./wv up` from the repo root.
2. Phone connected via USB with USB debugging on; `adb devices` confirms it.
3. Phone on the same Wi-Fi network as the dev machine.
4. From `mobile/`: `npm start` in one terminal, `npm run android` in another.
5. App launches to Server URL screen. Enter your dev machine's LAN IP, e.g. `http://192.168.1.50`.
6. Sign in with `demo@wealthview.local` / `demo123`.

If "Network request failed" trips you on a non-localhost LAN IP, you'll need to add an Android `network_security_config.xml` allowlisting your subnet for cleartext traffic in debug builds. Same doc has the snippet.

## Running on Android emulator

```bash
# in one terminal
cd mobile && npm start

# in another
cd mobile && npm run android
```

The emulator's host loopback is `10.0.2.2` (not `localhost`) — point the server URL at `http://10.0.2.2:80` to talk to a backend running on the host machine.

## iOS

iOS builds require a Mac with Xcode and a CocoaPods install. Deferred until the user is ready to publish to TestFlight / the App Store. Once needed:

```bash
cd mobile/ios && pod install
cd .. && npm run ios
```

The JS / business logic in this build is platform-agnostic — every native dep used (`react-native-keychain`, `react-native-screens`, `react-native-safe-area-context`, `@react-navigation/native-stack`, `@react-navigation/bottom-tabs`) ships an iOS implementation.

## Tests

```bash
npm run test --workspace mobile           # from repo root
```

The suite covers `AuthContext` (cold-start restore, login outcomes including MFA refusal, logout), every screen (input validation, error display, navigation, loading/empty/error states for Portfolio), the bottom-tabs root, and a top-level App smoke that mounts the full provider tree. Mocks for `react-native-keychain`, `react-native-screens`, `react-native-safe-area-context`, `@react-navigation/native-stack`, and `@react-navigation/bottom-tabs` live in `jest.setup.js` so individual tests don't need to repeat them.

## Adding cross-platform code

Pure utilities, types, and finance-domain helpers belong in `shared/`. The shared package has no React or DOM dependencies — both this app and the web frontend consume its TypeScript source directly. See `shared/README.md`.

Anything platform-specific (`react-native-keychain`, native modules, navigation glue) lives here.
