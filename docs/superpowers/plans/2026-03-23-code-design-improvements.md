# Code Design Improvements — Ranked Plan

**Goal:** 10 low-to-medium risk refactorings to improve readability, testability, and maintainability. No functional changes.

---

## Stack-Ranked Improvements

| # | Improvement | Risk | Effort | Benefit |
|---|-----------|------|--------|---------|
| 1 | Extract `MonteCarloSpendingOptimizer.optimize()` into 5 stage methods | LOW | 4h | 500-line method → 5 focused methods |
| 2 | `WithdrawalOrderStrategy` pattern in PoolStrategy | LOW | 2h | Isolates DS/ProRata/TaxableFirst logic |
| 3 | Extract `DeterministicProjectionEngine` year-loop into focused methods | MEDIUM | 2h | 200+ lines clearer, parameter records |
| 4 | Named constants for remaining magic strings across projection module | LOW | 1h | Grep-able, documented values |
| 5 | Split `SpendingOptimizerPage.tsx` into sub-components | LOW | 3h | 1033 lines → 4 focused components |
| 6 | Split `ProjectionDetailPage.tsx` into tab components | LOW | 3h | 977 lines → 5 focused components |
| 7 | Extract `buildParamsJson()` to use a map instead of 16-param method | LOW | 1h | Eliminates positional args, extensible |
| 8 | Add missing unit tests for `PoolStrategy.executeOrderedWithdrawals` | LOW | 1h | Direct coverage for array-reorder logic |
| 9 | Extract CSV export logic from ProjectionDetailPage to utility | LOW | 30m | Testable, reusable |
| 10 | Consistent tooltip formatting across all Recharts charts | LOW | 30m | Unified currency/percentage formatting |
