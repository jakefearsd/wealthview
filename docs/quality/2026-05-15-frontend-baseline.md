# Frontend Quality Baseline — 2026-05-15

This document is the "before" snapshot for the frontend half of the pre-release quality
refactor (Phase 0, Task 21). It is the counterpart to `2026-05-15-baseline-metrics.md`
(backend). All numbers were generated on 2026-05-16 from the repo as committed on `main`.

This is a **measurement-only** snapshot. Nothing was fixed: lint/type findings are Task 22,
test gaps are Task 23.

Toolchain versions: ESLint 8.57.1, TypeScript 5.9.3, Vitest 3.2.4, `@vitest/coverage-v8` 3.2.4.

---

## 1. Commands Run

From the repo root unless noted. The plan's assumed script names were checked against
`frontend/package.json` and `package.json`; the actual scripts used are recorded here.

```
npm install                                         # repo root — workspaces hoist
npm run lint --workspace frontend                    # -> eslint src --ext .ts,.tsx
npm run typecheck --workspace frontend               # -> tsc --noEmit
(cd frontend && npx vitest run --coverage)           # frontend tests + coverage
npm run test:shared                                  # -> vitest run (shared)
(cd shared && npx vitest run --coverage)             # shared tests + coverage
```

`npm install` reported "up to date" (deps already hoisted) with 3 moderate-severity npm
audit advisories — not actioned, out of scope for this task.

---

## 2. ESLint — frontend

**Result: TOOL DID NOT RUN — no configuration file.**

`npm run lint --workspace frontend` runs `eslint src --ext .ts,.tsx` and fails immediately:

```
ESLint couldn't find a configuration file.
ESLint looked for configuration files in /home/jakefear/source/wealthview/frontend/src
and its ancestors.
```

There is **no `.eslintrc*`, no `eslint.config.js`, and no `eslint` entry in any
`package.json`** anywhere in `frontend/` or the repo root. ESLint 8.57.1 is resolvable
on the path (it is a transitive dependency), but the `lint` script is effectively dead —
it has never had a config to run against.

- ESLint errors: **N/A (cannot run)**
- ESLint warnings: **N/A (cannot run)**
- Top rule categories: **N/A (cannot run)**

**This is the single biggest frontend tooling gap.** Establishing an ESLint flat config
(`eslint.config.js`) with the TypeScript + React + React Hooks plugins is a prerequisite
for Task 22 — there is currently no lint signal at all.

---

## 3. TypeScript — `tsc --noEmit` (frontend)

**Result: PASS — 0 errors.**

`npm run typecheck --workspace frontend` (`tsc --noEmit`) completes with exit code 0 and
no diagnostics. The frontend is type-clean against `tsconfig.json` as committed.

- `tsc` errors: **0**
- Error list: none.

---

## 4. Vitest — frontend

**Result: PASS — all tests green.**

`npx vitest run --coverage` in `frontend/`, exit code 0.

| Metric       | Value          |
|--------------|----------------|
| Test files   | 74 passed (74) |
| Tests        | 398 passed (398) |
| Failures     | 0              |
| Duration     | ~7 s           |

### 4.1 Coverage (v8 provider, `All files` row)

| Metric       | Coverage |
|--------------|----------|
| Statements   | **67.65%** |
| Branches     | **68.98%** |
| Functions    | **44.91%** |
| Lines        | **67.65%** |

Functions coverage (44.91%) is the weakest axis — many modules are imported for their
type exports or have exported helpers that no test calls.

### 4.2 Coverage by directory (`% Stmts`)

| Directory                  | Stmts  | Branch | Funcs  | Lines  |
|----------------------------|--------|--------|--------|--------|
| `src/` (App.tsx, main.tsx) | 28.86% | 50%    | 50%    | 28.86% |
| `src/api/`                 | 23.49% | 70.31% | 33.33% | 23.49% |
| `src/components/`          | 67.94% | 69.34% | 39.69% | 67.94% |
| `src/components/admin/`    | 72.71% | 67.32% | 62.36% | 72.71% |
| `src/context/`             | 99.13% | 96.42% | 100%   | 99.13% |
| `src/hooks/`               | 100%   | 93.75% | 100%   | 100%   |
| `src/pages/`               | 70.33% | 60.03% | 33.84% | 70.33% |
| `src/types/`               | 0%     | 0%     | 0%     | 0%     |
| `src/utils/`               | 98.44% | 97.19% | 100%   | 98.44% |

`src/types/` is 0% across the board — these are pure TypeScript interface files with no
runtime code, so 0% is expected and not a real gap (they should arguably be excluded from
the coverage scope).

### 4.3 Lowest-covered runtime areas

- **`src/api/` — 23.49%.** Most API client modules are entirely untested: `admin.ts`,
  `adminPrices.ts`, `adminSystem.ts`, `adminUsers.ts`, `audit.ts`, `exchangeRates.ts`,
  `holdings.ts`, `import.ts` are at 0%; `export.ts` 9%, `incomeSources.ts` 5%,
  `dashboard.ts` 27%. These are thin Axios wrappers — easy, high-value test targets.
- **`App.tsx` / `main.tsx` — 0%.** Routing/bootstrap shell, untested.
- **Pages with <50% statement coverage:** `ScenarioComparePage` (30.81%),
  `ProjectionDetailPage` (55.99%, but 6.66% functions), `IncomeSourceDetailPage`-style
  detail pages, and several list/detail pages in the 44–58% range.
- **`PortfolioPerformanceChart.tsx` — 5%** and **`AdminEventsSection.tsx` — 7.87%** are
  near-zero-covered components.

---

## 5. Vitest — shared workspace

**Result: PASS — all tests green.**

`npm run test:shared` (`vitest run` in `shared/`), exit code 0. Coverage captured with a
follow-up `npx vitest run --coverage` in `shared/`.

| Metric       | Value          |
|--------------|----------------|
| Test files   | 6 passed (6)   |
| Tests        | 54 passed (54) |
| Failures     | 0              |
| Duration     | ~0.3 s         |

### 5.1 Coverage (v8 provider, `All files` row)

| Metric       | Coverage |
|--------------|----------|
| Statements   | **97.11%** |
| Branches     | **85.21%** |
| Functions    | **96.66%** |
| Lines        | **97.11%** |

By file: `src/api/` 98.63% stmts (`accounts.ts`, `client.ts`, `dashboard.ts` all 100%;
`auth.ts` 96.72%); `src/portfolio/groupAccountsByCategory.ts` 100% stmts but 73.46%
branches; `format.ts` 100% stmts / 90.47% branches. `index.ts` and `types.ts` are 0%
(pure re-export / type files).

The shared workspace is in good shape — well above the de-facto 90% target and not a
priority for Task 23.

---

## 6. Oversized Frontend Source Files (>400 lines)

Gathered with `find ... | xargs wc -l` over `frontend/src`, excluding `*.test.*` files.

| File                                       | Lines |
|--------------------------------------------|-------|
| `pages/ProjectionDetailPage.tsx`           | 628   |
| `pages/SpendingOptimizerPage.tsx`          | 610   |
| `pages/PropertyDetailPage.tsx`             | 485   |
| `components/PropertyIncomeChart.tsx`       | 469   |
| `components/ScenarioForm.tsx`              | 442   |
| `pages/IncomeSourcesPage.tsx`              | 412   |

These overlap with the lowest-covered pages (Section 4.3) — large page components doing
data fetching, form state, and rendering in one file. They are candidate decomposition
targets if a frontend Phase 3 is scoped, and their size partly explains the low function
coverage.

---

## 7. Summary

| Tool / Area              | Result                                  |
|--------------------------|-----------------------------------------|
| ESLint (frontend)        | **Cannot run — no config file**         |
| `tsc --noEmit` (frontend)| **PASS — 0 errors**                     |
| Vitest (frontend)        | **PASS — 74 files / 398 tests**         |
| Frontend coverage        | 67.65% stmts / 68.98% br / 44.91% fn / 67.65% ln |
| Vitest (shared)          | **PASS — 6 files / 54 tests**           |
| Shared coverage          | 97.11% stmts / 85.21% br / 96.66% fn / 97.11% ln |

### Biggest gaps (feeds Tasks 22–23)

1. **No ESLint configuration exists.** The `lint` script is dead. Task 22 must first
   create an `eslint.config.js` (flat config) with TypeScript + React + React-Hooks
   plugins before any lint findings can be measured or fixed. There is currently zero
   lint signal on the frontend.
2. **`src/api/` coverage is 23%.** Eight API client modules are at 0%. These are thin,
   easily-mockable Axios wrappers — the highest-value, lowest-effort target for Task 23.
3. **Function coverage is 44.91%** frontend-wide — the weakest coverage axis. Driven by
   untested page/component handlers and the untested API layer.
4. **Several large page components (<50% covered):** `ScenarioComparePage` (31%),
   `ProjectionDetailPage` (56%), plus detail/list pages in the 44–58% band. The six
   files >400 lines (Section 6) overlap heavily with this list.
5. **`App.tsx` / `main.tsx` untested** — the routing and bootstrap shell has no coverage.

The shared workspace needs no remediation. TypeScript is clean. The frontend's two real
problems are (a) the missing lint setup and (b) the under-tested API layer and large page
components.
