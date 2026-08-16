# wealthview-projection

The computation-intensive module implementing retirement projection algorithms.
Depends on `wealthview-core` for interfaces, DTOs, and the tax model.

The module holds 71 classes in a single flat package, `com.wealthview.projection`, of which
exactly **two are Spring beans**: `DeterministicProjectionEngine` and
`MonteCarloSpendingOptimizer`. Everything else is a package-private collaborator, deliberately
not exposed — the rest of the application talks to this module only through the
`ProjectionEngine` and `SpendingOptimizer` interfaces declared in `wealthview-core`.

---

## Core Components

| Class | Type | Role |
|---|---|---|
| `DeterministicProjectionEngine` | `@Component` | Year-by-year projection; implements `ProjectionEngine` |
| `MonteCarloSpendingOptimizer` | `@Component` | MC spending optimization; implements `SpendingOptimizer` |
| `RothConversionOptimizer` | Package-private | Lifetime tax-minimizing conversion schedule; thin orchestrator over `ConversionSimulator` and `FractionSearch` |
| `PoolStrategy` | Sealed interface | Manages investment pool balances during the year loop |
| `WithdrawalOrderStrategy` | Sealed interface | Allocates a withdrawal across taxable / traditional / Roth |
| `IncomeSourceProcessor`, `IncomeProjector` | Plain classes | Process SS, pension, rental, self-employment per year |
| `IncomeContributionCalculator` | Plain class | Applies pre-retirement contributions |
| `RmdCalculator`, `RmdStreamCalculator` | Plain classes | IRS Uniform Lifetime Table RMDs, one stream per owner |
| `TaxableLots` / `TaxableLotsBd` | Plain classes | Per-lot FIFO cost-basis tracking for capital gains |
| `PoolTaxCascade`, `RetirementTaxAnnotator` | Plain classes | Tax layering and per-year tax annotation |
| `HouseholdTransition`, `OwnerPool`, `SurvivorIncomeAdjuster`, `HouseholdMcResolver`, `HouseholdIndexMath` | Plain classes | Household / survivor modeling |
| `MortalitySampler`, `MortalityDrawGenerator`, `StochasticMortalityEvaluator` | Plain classes | Opt-in stochastic mortality |
| `BlockBootstrapReturnGenerator` | Plain class | Block-bootstraps index sequences into the multi-asset return matrix for MC simulation |
| `PortfolioPathGenerator`, `PortfolioReturnResolver`, `PoolReturnModel` | Plain classes | Draw per-trial return trajectories, blended per account by allocation |
| `SustainabilitySearch`, `FractionSearch`, `JointConversionSearch` | Plain classes | The three search routines the MC optimizer delegates to |
| `SpendingCorridorCalculator`, `SpendingSmoother`, `SpendingFeasibilityAnalyzer` | Plain classes | Guardrail corridor, smoothing, feasibility reporting |

See the [Projection Engine](../projection-engine.html) page for full algorithmic detail.

---

## DeterministicProjectionEngine

Implements a complete year-by-year retirement simulation. It is a thin orchestrator over the
collaborators above — parameter parsing (`ScenarioParamsParser`), pool strategy, income-source
and contribution processing, retirement withdrawals (`RetirementWithdrawalProcessor`), tax
annotation, and feasibility analysis — delegating self-contained concerns to
`WithdrawalStrategyFactory`, `PropertyEquityCalculator`, and `IncomeSourceFieldMapper`.

**Everything is in real (today's-dollars) terms.** Accounts grow at the allocation blend of
real geometric means served by `CapitalMarketAssumptionsProvider`. A user-supplied *nominal*
`expectedReturnOverride` is converted to a real return using the scenario's own inflation rate,
so all cash flows share one real-dollar frame (falling back to 2.5% when the scenario has no
rate).

**Pre-retirement years:** applies contributions, grows each account, no withdrawals.

**Retirement years:** resolves spending from the active `SpendingPlan`, nets it against income
sources, computes the after-tax withdrawal need, draws from the pools in the configured
`WithdrawalOrder`, and applies growth. With no spending plan at all, the engine falls back to a
`WithdrawalStrategy` — the default withdrawal rate is 4%.

**Tax calculation:** composed from `FederalTaxCalculator` plus a state calculator when
configured, with `SocialSecurityTaxCalculator`, `SelfEmploymentTaxCalculator`, and
`RentalLossCalculator` feeding the income picture. Federal brackets are inflation-indexed for
future years.

**Capital gains on taxable accounts:** `TaxableLots` maintains per-lot FIFO cost basis seeded
from each account's `costBasis`, and `CapitalGainsTaxCalculator` applies LTCG brackets and NIIT
to realized gains. A retired year also books the taxable pool's yield distribution as a
qualified-dividend / ordinary-interest split.

**RMDs:** computed inside the main projection from the prior-year-end traditional balance,
one stream per owner via `getTraditionalByOwner()`. RMDs always come from the traditional pool;
surplus beyond the withdrawal need is deposited to taxable.

**Roth conversion:** applied each retirement year from the schedule carried on the
`SpendingPlan` (`conversionSchedule()`), or left at zero when no schedule is present.

**IRMAA:** `IrmaaSurchargeCalculator` is optional (`@Nullable`) — when absent the surcharge
stays zero every year. When present it applies from `MEDICARE_AGE = 65`.

**Household / survivor:** owner-aware pools, per-owner RMD streams, owner-age income windows,
and an atomic first-death transition (keep-larger Social Security, rollover, basis step-up,
MFJ→single filing flip). The survivor spending factor defaults to **0.75** and is clamped to
`[0.5, 1.0]` defensively in the engine, mirroring the same guard applied at write time in
`ScenarioCrudService` and on the MC seam in `HouseholdMcResolver`.

---

## PoolStrategy — Sealed Interface

```java
sealed interface PoolStrategy permits PoolStrategy.MultiPool
```

`MultiPool` is the **sole** implementation. It tracks `taxable`, `traditional`, and `Roth`
sub-pools separately, using empty pools for any account type absent from the scenario — an
all-taxable scenario is simply a `MultiPool` with zero traditional and zero Roth, not a
distinct untaxed code path. (A `SinglePool` variant existed historically and was removed for
exactly that reason.)

This structure enables:

* Correct RMD computation (traditional pool only), broken out per owner via
  `getTraditionalByOwner()`
* Roth conversion (moves from traditional to Roth)
* Per-lot FIFO capital-gains tracking on the taxable pool
* Dynamic Sequencing withdrawal ordering
* Per-account returns, with each pool growing at the balance-weighted average of its accounts

Pool balances are floored at zero after each year — exhaustion, not negative balances.
`PoolStrategy.Memento` (also sealed, permitting `MultiPool.MultiPoolMemento`) snapshots and
restores pool state for search routines that need to replay a year.

## WithdrawalOrderStrategy — Sealed Interface

```java
sealed interface WithdrawalOrderStrategy
        permits WithdrawalOrderStrategy.DynamicSequencingOrder,
                WithdrawalOrderStrategy.ProRataOrder,
                WithdrawalOrderStrategy.OrderedWithdrawalOrder
```

Each `WithdrawalOrder` enum value in `wealthview-core` maps to one implementation. Extracted
from `MultiPool` during the Phase 3 decomposition; the allocation arithmetic is unchanged.

---

## MonteCarloSpendingOptimizer

Runs a multi-stage optimization to find the highest sustainable spending level, delegating each
search to a focused collaborator. See the parent [Projection Engine](../projection-engine.html)
page for the full algorithm description.

**Key constants**, by the class that owns them:

| Constant | Value | Owner |
|---|---|---|
| `SUSTAINABILITY_REDUCTION_FACTOR` | 0.95 per iteration | `MonteCarloSpendingOptimizer` |
| `DEFAULT_LONGEVITY_CONDITIONAL_AGE` | 95 | `MonteCarloSpendingOptimizer` |
| `SPENDING_BINARY_SEARCH_ITERATIONS` | 30 | `SustainabilitySearch` |
| `PHASE_BINARY_SEARCH_ITERATIONS` | 40 | `SustainabilitySearch` |
| `MAX_SPENDING_CEILING` | 500 000 | `SustainabilitySearch` |
| `GRID_SIZE` / `REFINE_ITERATIONS` | 50 / 20 | `FractionSearch` |
| `JOINT_GRID_SIZE` | 20 (20×20 spending × conversion grid) | `JointConversionSearch` |
| `JOINT_SEARCH_TRIALS` | 500 MC trials per grid cell | `JointConversionSearch` |
| `JOINT_REFINE_ITERATIONS` | 10 ternary refinement iterations | `JointConversionSearch` |

The optimizer returns a `GuardrailProfileResponse` (assembled by `GuardrailResponseBuilder`)
that includes year-by-year spending, corridor guardrails (floor + ceiling), phase annotations,
and the Roth conversion schedule that jointly maximises spending.

Risk tolerance maps directly to a **target success probability** in `GuardrailProfileService`
(`wealthview-core`): conservative 0.95, moderate 0.90, aggressive 0.80.

---

## Stochastic Mortality (opt-in)

An opt-in Monte Carlo mode that reports **success probability only**. `MortalityTableProvider`
in `wealthview-core` loads SSA `qx` rows from `mortality_rates` (seeded by
`R__seed_mortality_rates`, migration V080) into a `MortalityTable`. `MortalitySampler` and
`MortalityDrawGenerator` draw per-trial deaths on a **separate RNG stream** so enabling the
mode does not perturb the return paths, and `StochasticMortalityEvaluator` splices the
three regimes (both alive / survivor / neither) and reports longevity-conditional metrics
through `StochasticMortalitySummary` and the core `StochasticMortalityResponse` DTO.

---

## RothConversionOptimizer

Package-private. Not a Spring bean. Instantiated by the service layer.

It derives a `RothConversionConfig`, computes the target traditional balance at RMD age, and
delegates the year-by-year simulation to `ConversionSimulator` and the conversion-fraction
search to `FractionSearch` — a **50-point grid scan** followed by **20 ternary refinement
iterations** within a ±0.05 half-width. A MAGI convergence loop accounts for the interaction
between Roth conversions and rental passive-loss deductibility.

The optimizer holds no random state: identical inputs always yield an identical schedule.

---

## BlockBootstrapReturnGenerator

Implements **block bootstrap** sampling for the MC simulator. Rather than sampling individual
years independently — which would destroy return autocorrelation — at each year a geometric
random variable decides whether to start a new block at a random position in the historical
series or continue sequentially within the current block. The block termination probability is
`1 / expectedBlockLength`, so the expected number of years drawn from each block equals
`expectedBlockLength` (supplied by the caller, not a fixed constant on this class).

The real-return matrix itself — annual real (inflation-adjusted) returns per asset class — is
loaded from the `asset_class_returns` table and cached by `CapitalMarketAssumptionsProvider`
(`wealthview-core`). `PortfolioPathGenerator` draws **one bootstrap index sequence per trial**
and reuses it across every pool and account, so cross-asset correlation is preserved; each
account's return is then its allocation blended against the sampled matrix rows (or a fixed
expected-return override), and each pool grows at the balance-weighted average of its
accounts' returns. This preserves the sequence-of-returns risk patterns — prolonged bear
markets, recovery periods — that are critical for realistic retirement income simulation.

---

## Coverage Target

`wealthview-projection` is gated at **90%** line and **0.84** branch coverage by
`jacoco:check` on `mvn verify`; the related `com.wealthview.core.projection.*` packages are
covered by `wealthview-core`'s 90% / 0.83 gate. Mutation testing (Pitest) is configured for
both modules to catch tests that pass through coverage without pinning behavior — advisory,
not a build gate.

The projection module exports no test-jar; it consumes the `wealthview-core` test-jar for
shared fixtures.
