# @wealthview/mobile

WealthView's React Native client. Lives as a workspace package alongside the web frontend and the cross-platform `shared/` package — see the root `README.md` for the monorepo layout.

This pass ships the daily-driver Portfolio screen on top of the auth MVP: net-worth headline, category breakdown chips, and accounts grouped by type with tap-through to a per-account detail screen. The authenticated UI is a two-tab bar (Portfolio / Settings) — see "Navigation" below. Holdings, transactions, charts, and account creation land in subsequent passes (web app for now).

## Stack

| Package | Version |
|---|---|
| `react-native` | 0.87.0 (new architecture + Hermes both enabled) |
| `react` | 19.2.8 (pinned by the root `overrides` block) |
| `@react-navigation/native` / `native-stack` / `bottom-tabs` | 7.x |
| `react-native-keychain` | 10.x |
| `react-native-screens` | 4.x |
| `react-native-safe-area-context` | 5.x |
| `axios` | 1.19.x (also the transport inside `@wealthview/shared`) |
| Jest | 29.x with `@react-native/jest-preset` + `@testing-library/react-native` 14 |
| TypeScript | 5.9.x |
| ESLint | 8.x with `@react-native/eslint-config` |

Android build inputs live in `android/build.gradle`: `compileSdk` 37, `targetSdk` 36, `minSdk` 24, NDK 27.1.12297006, Kotlin 2.2.0. The app id / namespace is `com.mobileapp`.

**JDK: this workspace is pinned to 17, the backend to 25.** `mobile/.sdkmanrc` sets `java=17.0.19-tem` and `backend/.sdkmanrc` sets `java=25.0.3-tem`; SDKMAN auto-switches on `cd` when `sdkman_auto_env=true`. If a Gradle build fails with an unsupported class-file or toolchain error, you almost certainly have the backend's JDK 25 on `PATH`. Node must be ≥ 22.13.0 (`engines` in `package.json`); CI runs Node 22.

## What ships in this build

| Screen | Purpose |
|---|---|
| BootSplash | Spinner shown while AuthContext reads the keychain and verifies `/auth/me` on cold start. |
| ServerConfigScreen | First screen on a fresh install. Validates and stores the user's WealthView server URL. |
| LoginScreen | Email + password against `POST /api/v1/auth/token/login`. Friendly refusal of MFA-enabled accounts (challenge UI is a later pass). |
| **PortfolioScreen** (default tab) | The screen the user opens every day. Net-worth headline, investment / cash / property breakdown chips, accounts grouped by category with the largest balances on top, pull-to-refresh, tap-through to AccountDetail. Hits `GET /dashboard/summary` and `GET /accounts` in parallel. |
| AccountDetailScreen | Tap-through landing for any account row. Shows balance, type, institution, currency. Holdings + transactions are a separate pass. |
| SettingsScreen | Identity card (email / role / tenant / user IDs), editable server URL (with confirmation when it would invalidate the current session), and a logout button. |

Backend endpoints this client speaks to, and the ones it deliberately doesn't yet (registration, the MFA challenge, per-device sessions, and the `/app/version-check` force-update probe), are catalogued in [`docs/MOBILE_API.md`](../docs/MOBILE_API.md).

## Navigation

`RootNavigator` (`src/navigation/RootNavigator.tsx`) switches on `auth.status`, which has four values:

| `status` | Tree rendered |
|---|---|
| `restoring` | Single-screen stack: `BootSplash`. |
| `needs_server` | Single-screen stack: `ServerConfig`. |
| `unauthenticated` | Native-stack `Login` ↔ `Settings` (the latter for server-URL editing). |
| `authenticated` | `MainTabsNavigator`, wrapped in a headerless native-stack. |

`MainTabsNavigator` is a `@react-navigation/bottom-tabs` navigator with two tabs:

- **PortfolioTab** — native-stack containing `Portfolio` and `AccountDetail`.
- **SettingsTab** — single-screen stack.

State is held in a single `AuthProvider` (`src/auth/AuthContext.tsx`) using `useReducer`. Tokens persist in `react-native-keychain` (`src/auth/tokenStorage.ts`) under three `com.wealthview.mobile.*` service keys — access token, refresh token, cached identity — and the server URL gets a fourth (`src/config/serverUrlStorage.ts`), co-located so tests have one storage primitive to mock.

The HTTP layer comes from `@wealthview/shared`: `createApiClient` (axios instance, bearer transport, refresh-on-401 with a single retry and coalesced refreshes) plus `createAuthApi`, `createDashboardApi`, and `createAccountsApi`. `src/auth/apiClient.ts` builds the auth bundle and `src/api/apis.ts` the data bundle. The web frontend already consumes the same `createApiClient` in cookie mode (`frontend/src/api/client.ts`), so this is one factory serving both clients, not a future aspiration.

## CI vs local builds

| Step | Where it runs |
|---|---|
| `npm run typecheck --workspace mobile` | CI and locally |
| `npm run test --workspace mobile` | CI and locally |
| `npm run lint --workspace mobile` | **Local only** — the script exists but `.github/workflows/mobile.yml` does not run it |
| Android Gradle build (`npm run android`) | **Local only** — needs Android SDK |
| iOS Xcode build (`npm run ios`) | **Local only** — needs a Mac with Xcode |

Linux CI never builds APKs or runs xcodebuild. Releases are cut from a developer machine.

`mobile.yml` triggers only on `push` of a `v*` tag (plus manual `workflow_dispatch`) — not on every push or PR. It checks out, `npm ci` at the root, then typecheck and Jest.

## Local development

Install from the **repository root**, never from `mobile/` — this is an npm workspaces monorepo and a nested install would break hoisting:

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
npm run lint                               # ESLint
```

Two pieces of monorepo wiring worth knowing about:

- **Metro** (`metro.config.js`) watches the entire monorepo root — not just `../shared/` — because Metro builds its file map by walking watched directories, and `nodeModulesPaths` alone tells it where to *look up* modules but not which directories to *scan*. `nodeModulesPaths` then lists both `mobile/node_modules` and the hoisted root `node_modules`.
- **Gradle** (`android/settings.gradle`) reaches the React Native Gradle plugin through the hoisted root path, `../../node_modules/@react-native/gradle-plugin`. That path is relative to `mobile/android/`, so it resolves to the repo root — another reason the root install has to happen first.

Jest resolves `@wealthview/shared` straight to `../shared/src/index.ts` via `moduleNameMapper`, so shared code is type-checked and executed as source in mobile tests too.

## Running on a physical Android device (primary testing path)

See [`docs/deployment/mobile-android-testing.md`](../docs/deployment/mobile-android-testing.md) for the full step-by-step guide. Quick path once you've completed the one-time setup:

1. Backend running and bound to `0.0.0.0`: `./wv up` from the repo root.
2. Phone connected via USB with USB debugging on; `adb devices` confirms it.
3. Phone on the same Wi-Fi network as the dev machine.
4. From `mobile/`: `npm start` in one terminal, `npm run android` in another.
5. App launches to Server URL screen. Enter your dev machine's LAN IP, e.g. `http://192.168.1.50`.
6. Sign in with `demo@wealthview.local` / `demo123`.

On cleartext HTTP: `AndroidManifest.xml` declares `android:usesCleartextTraffic="${usesCleartextTraffic}"`, a placeholder the React Native Gradle plugin fills in per build type — permissive for debug, restrictive for release. So a plain `http://` LAN IP should work in a debug build without extra config. If "Network request failed" still trips you, the testing doc above has the troubleshooting steps.

## Running on Android emulator

```bash
# in one terminal
cd mobile && npm start

# in another
cd mobile && npm run android
```

The emulator's host loopback is `10.0.2.2` (not `localhost`) — point the server URL at `http://10.0.2.2:80` to talk to a backend running on the host machine.

## iOS

The `ios/` directory is scaffolded (`Podfile`, `MobileApp.xcodeproj`, and a `Gemfile` pinning CocoaPods), but iOS builds require a Mac with Xcode and have never been run. Deferred until the user is ready to publish to TestFlight / the App Store. Once needed, from the repository root:

```bash
cd mobile/ios && bundle install && bundle exec pod install
cd .. && npm run ios
```

Use `bundle exec` rather than a bare `pod install` — `Gemfile` pins CocoaPods and `xcodeproj` to versions known to work with this RN release, and `.bundle/config` sets `BUNDLE_PATH: vendor/bundle`.

The JS / business logic in this build is platform-agnostic — every native dep used (`react-native-keychain`, `react-native-screens`, `react-native-safe-area-context`, `@react-navigation/native-stack`, `@react-navigation/bottom-tabs`) ships an iOS implementation.

## Tests

```bash
npm run test --workspace mobile           # from repo root
npm test                                  # from mobile/
```

Tests are NOT co-located with sources — they live under `mobile/__tests__/`, mirroring the `src/` layout:

```
__tests__/App.test.tsx                        provider-tree smoke test
__tests__/auth/AuthContext.test.tsx           cold-start restore, login outcomes
                                              (incl. MFA refusal), logout
__tests__/screens/LoginScreen.test.tsx
__tests__/screens/ServerConfigScreen.test.tsx
__tests__/screens/PortfolioScreen.test.tsx    loading / empty / error / loaded states
__tests__/screens/AccountDetailScreen.test.tsx
__tests__/screens/SettingsScreen.test.tsx
__tests__/screens/MainTabsNavigator.test.tsx
```

`BootSplash` has no test of its own — it is exercised through the `App` smoke test and the `restoring` branch in `AuthContext`.

Mocks for `react-native-keychain`, `react-native-screens`, `react-native-safe-area-context`, `@react-navigation/native-stack`, and `@react-navigation/bottom-tabs` live in `jest.setup.js` so individual tests don't need to repeat them. The two navigator mocks render only their first `Screen` child, which is enough to assert which branch the navigator picked.

## Adding cross-platform code

Pure utilities, types, and finance-domain helpers belong in `shared/`. The shared package has no React or DOM dependencies — both this app and the web frontend consume its TypeScript source directly. See `shared/README.md`.

Anything platform-specific (`react-native-keychain`, native modules, navigation glue) lives here.
