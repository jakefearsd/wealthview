# @wealthview/mobile

WealthView's React Native client. Lives as a workspace package alongside the web frontend and the cross-platform `shared/` package — see the root `README.md` for the monorepo layout.

The current scaffold is intentionally minimal: a single screen that renders `formatCurrency(1234567.89)` from `@wealthview/shared`, proving the cross-platform consumption pattern end-to-end. Real screens, navigation, and the API client land in subsequent passes.

## CI vs local builds

| Step | Where it runs |
|---|---|
| `npm run typecheck --workspace mobile` | CI (`.github/workflows/mobile.yml`) and locally |
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

## Adding cross-platform code

Pure utilities, types, and finance-domain helpers belong in `shared/`. The shared package has no React or DOM dependencies — both this app and the web frontend consume its TypeScript source directly. See `shared/README.md`.

Anything platform-specific (`AsyncStorage`, native modules, navigation glue) lives here.
