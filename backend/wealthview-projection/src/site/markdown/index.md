# wealthview-projection

The computation-intensive module implementing retirement projection algorithms.
Depends on `wealthview-core` for interfaces, DTOs, and the tax model.
All classes in this module are `@Component` beans or package-private helpers.

---

## Core Components

| Class | Type | Role |
|---|---|---|
| `DeterministicProjectionEngine` | `@Component` | Year-by-year projection; implements `ProjectionEngine` |
| `MonteCarloSpendingOptimizer` | `@Component` | MC spending optimization; implements `SpendingOptimizer` |
| `RothConversionOptimizer` | Package-private | Lifetime tax-minimizing conversion schedule |
| `PoolStrategy` | Sealed interface | Manages investment pool balances during projection |
| `IncomeSourceProcessor` | Plain class | Processes SS, pension, rental, self-employment per year |
| `IncomeContributionCalculator` | Plain class | Applies pre-retirement contributions |
| `RmdCalculator` | Plain class | IRS Uniform Lifetime Table RMD computation |
| `BlockBootstrapReturnGenerator` | Plain class | Samples return sequences for MC simulation |
| `HistoricalReturns` | Data class | Historical annual return data sets (equities, bonds, mixed) |

---

## DeterministicProjectionEngine

Implements a complete year-by-year retirement simulation. Key behaviours:

**Pre-retirement years:** applies contributions, growth at expected return, no withdrawals.

**Retirement years:** resolves spending from the active `SpendingPlan` (or 4% default),
nets against income sources, computes after-tax withdrawal need, draws from the pool
in the configured `WithdrawalOrder`, applies growth.

**Tax calculation:** composed from `FederalTaxCalculator` + state calculator (if configured).
Federal brackets are inflation-indexed for future years. State taxes are computed separately
with state-specific rules.

**Roth conversion:** applied each retirement year according to the conversion schedule
(from `RothConversionOptimizer`) or left at zero if no schedule is provided.

**RMDs:** enforced starting at `rmd_start_age` using the IRS Uniform Lifetime Table.
RMDs always come from the traditional pool; they reduce the withdrawal need if they exceed it,
depositing surplus to taxable.

**IRMAA warning:** when age ≥ 63 and projected income + Roth conversion exceeds the 22% bracket
threshold, the year is flagged with an IRMAA warning in the `ProjectionYearDto`.

---

## PoolStrategy — Sealed Interface

```java
sealed interface PoolStrategy permits PoolStrategy.SinglePool, PoolStrategy.MultiPool
```

**`SinglePool`** — aggregate balance only. Used when the scenario does not distinguish between
account types. Simple and fast.

**`MultiPool`** — tracks `taxable`, `traditional`, and `Roth` pools separately. Used for all
tax-aware scenarios. Enables:
* Correct RMD computation (traditional pool only)
* Roth conversion (moves from traditional to Roth)
* Dynamic Sequencing withdrawal ordering
* Per-pool growth rates (each pool can have a different expected return)

Pool balances are floored at zero after each year — negative balances represent exhaustion.

---

## MonteCarloSpendingOptimizer

Runs a 5-stage optimization to find the highest sustainable spending level. See the parent
[Projection Engine](../projection-engine.html) page for the full algorithm description.

**Key constants:**

| Constant | Value |
|---|---|
| `JOINT_GRID_SIZE` | 20 (20×20 spending × conversion grid) |
| `JOINT_SEARCH_TRIALS` | 500 MC trials per grid cell |
| `JOINT_REFINE_ITERATIONS` | 10 ternary refinement iterations |
| `SPENDING_BINARY_SEARCH_ITERATIONS` | 30 binary search iterations |
| `DEFAULT_BLOCK_LENGTH` | 5 years per bootstrap block |
| `CASH_REPLENISHMENT_RATE` | 10% equity → cash replenishment |
| `SUSTAINABILITY_REDUCTION_FACTOR` | 0.95 per iteration |

The optimizer returns a `GuardrailProfileResponse` that includes year-by-year spending,
corridor guardrails (floor + ceiling), phase annotations, and the Roth conversion schedule
that jointly maximises spending.

---

## RothConversionOptimizer

Package-private. Not a Spring bean. Instantiated by `MonteCarloSpendingOptimizer` and
optionally by `DeterministicProjectionEngine`.

Uses a **50-point grid scan** followed by **ternary refinement** (20 iterations) to find
the conversion fraction that minimises lifetime tax. MAGI convergence loop (up to 3 iterations)
accounts for the interaction between Roth conversions and rental passive loss deductibility.

The `RothConversionSchedule` record captures all per-year output plus summary statistics:
lifetime tax with vs. without conversions, port balance trajectories, RMD projections.

---

## BlockBootstrapReturnGenerator

Implements **block bootstrap** return sampling for the MC simulator. Rather than sampling
individual years independently (which would destroy return autocorrelation), it:

1. Splits the historical return series into overlapping 5-year blocks
2. Randomly selects blocks end-to-end until the required projection length is filled
3. Returns the complete annual return sequence for one simulation trial

This preserves sequence-of-returns risk patterns (prolonged bear markets, recovery periods)
that are critical for realistic retirement income simulation.

---

## Coverage Target

Both `wealthview-projection` and the related `com.wealthview.core.projection.*` packages
have a **90%+ line coverage** target. Mutation testing (Pitest) is configured for these
packages to catch gaps that line coverage alone misses.

The projection module exports no test-jar; it uses the `wealthview-core` test-jar for
shared fixtures and the `AbstractIntegrationTest` base class.
