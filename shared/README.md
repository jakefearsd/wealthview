# @wealthview/shared

Cross-platform utilities and types shared between the WealthView web frontend (Vite + React) and mobile app (React Native + Metro). Both consumers import this package directly as TypeScript source — there is no build step. Vite and Metro each transpile the source for their own target.

`package.json` points both `main` and `types` at `src/index.ts`, and `tsconfig.json` sets `noEmit: true`, so nothing is ever compiled to `dist/`. Mobile's Jest config maps `@wealthview/shared` straight to `../shared/src/index.ts` for the same reason.

## What lives here

| Module | Exports |
|---|---|
| `src/format.ts` | `formatCurrency`, `formatWholeCurrency`, `formatCompactCurrency`, `formatPercent`, `toPercent`, `parseCurrencyInput`, `formatCurrencyInput` |
| `src/errorMessage.ts` | `extractErrorMessage` — pulls a human-readable message out of an Axios error, the API's `{error, message, status}` envelope, or a plain exception |
| `src/api/client.ts` | `createApiClient` + `ApiClientConfig`. Builds a configured axios instance: bearer or cookie transport, refresh-on-401 with a single retry and coalesced concurrent refreshes, and `onTokensRefreshed` / `onAuthFailed` callbacks so token storage stays platform-specific |
| `src/api/auth.ts` | `createAuthApi` + `AuthApi` — `login`, `register`, `refresh`, `logout`, `getCurrentUser`, routed to `/auth/token/*` or `/auth/*` depending on transport |
| `src/api/dashboard.ts` | `createDashboardApi` + `DashboardApi` (`getSummary`) |
| `src/api/accounts.ts` | `createAccountsApi` + `AccountsApi` (`list`, `get`) |
| `src/api/types.ts` | Wire-format types mirroring the backend's snake_case JSON: `LoginRequest`, `RegisterRequest`, `RefreshRequest`, `MobileAuthResponse`, `MfaRequiredResponse`, `LoginOutcome`, `MeResponse`, `ErrorResponse`, `PageResponse<T>`, `AccountResponse`, `AccountType`, `AccountsListParams`, `DashboardSummaryResponse`, `DashboardAccountSummary`, `DashboardAllocationEntry`, `AuthTransport` |
| `src/portfolio/groupAccountsByCategory.ts` | `groupAccountsByCategory` + `AccountCategory` / `AccountGroup` — buckets accounts into investment / cash / other, ordered, largest balance first |

`src/index.ts` is the only public entry point; everything above is re-exported from it. Nothing else in `src/` is part of the contract.

## Who consumes what

- **Mobile** uses nearly all of it: `createApiClient` + `createAuthApi` in `src/auth/apiClient.ts`, `createDashboardApi` + `createAccountsApi` in `src/api/apis.ts`, `extractErrorMessage` in `AuthContext`, and `groupAccountsByCategory` on the Portfolio screen.
- **Frontend** consumes `createApiClient` (in cookie mode) from `src/api/client.ts`, and thin re-export modules keep the pre-monorepo import paths stable: `utils/format.ts`, `utils/errorMessage.ts`, `utils/chartFormatters.ts` (aliases `formatCompactCurrency` as `formatDollarAxis`), `types/account.ts`, `types/dashboard.ts`, and `types/common.ts`.

Because the frontend re-exports rather than importing directly everywhere, renaming an export here is a breaking change in two workspaces — grep both before you touch one.

## Conventions

- **Pure functions only.** No side effects at import time.
- **No platform-only imports.** Do not import from the DOM (`window`, `document`, `navigator`), Node-only modules (`fs`, `path`), or React. `axios` is the one runtime dependency, and it is platform-neutral. Anything platform-specific belongs in the consumer.
- **Type-first.** Prefer exported `type` / `interface` declarations alongside the runtime helpers that use them. API wire-format types live in `src/api/types.ts` and mirror the backend records field for field.
- **Tests are co-located** (`format.ts` + `format.test.ts`) and run with Vitest.

## Tests

```bash
npm run test:shared                        # from the repo root
npm run test --workspace shared            # equivalent
```

Or from `shared/`:

```bash
npm test                                   # vitest run (one pass)
npm run test:watch                         # vitest (watch mode)
npm run typecheck                          # tsc --noEmit
```

There is no `vitest.config.ts` — the defaults pick up every `*.test.ts` under `src/`. CI (`.github/workflows/shared.yml`) runs typecheck then tests, on `v*` tags and manual dispatch only.

Adding code here automatically becomes available to both `frontend/` and `mobile/`. A change to a finance-domain helper propagates to both clients with no copy.
