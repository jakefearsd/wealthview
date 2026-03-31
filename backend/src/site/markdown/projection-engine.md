# Projection Engine

The projection engine lives in `wealthview-projection` and exposes two Spring-managed components
plus one package-private optimizer. The core interfaces that decouple them from the rest of the
application are defined in `wealthview-core`.

---

## Design Overview

```
ProjectionEngine  (interface, wealthview-core)
  └── DeterministicProjectionEngine  (@Component)

SpendingOptimizer  (interface, wealthview-core)
  └── MonteCarloSpendingOptimizer    (@Component)
        └── RothConversionOptimizer  (package-private, instantiated internally)

SpendingPlan  (sealed interface, wealthview-core)
  ├── TierBasedSpendingPlan     (wraps SpendingProfileEntity tiers)
  └── GuardrailSpendingInput    (wraps MC-optimized yearly spending)

WithdrawalStrategy  (sealed interface, wealthview-core)
  ├── FixedPercentageWithdrawal
  ├── DynamicPercentageWithdrawal
  └── VanguardDynamicSpendingWithdrawal

PoolStrategy  (sealed interface, wealthview-projection)
  ├── SinglePool   (aggregate balance — for simple scenarios)
  └── MultiPool    (taxable / traditional / Roth — for tax-aware scenarios)
```

---

## DeterministicProjectionEngine

**Entry point:** `run(ProjectionInput input)` → `ProjectionResultResponse`

The engine simulates retirement year by year from the user's current age to `end_age`. Each year
applies contributions (pre-retirement), growth, income sources, withdrawals, and tax calculations
in a fixed pipeline.

### Year-loop Pipeline

```
For each year from current to end_age:
  1. Resolve spending target (SpendingPlan or withdrawal-rate fallback)
  2. Process income sources (Social Security, pension, rental, part-time)
     → RentalLossCalculator, SocialSecurityTaxCalculator, SelfEmploymentTaxCalculator
  3. Apply contributions (pre-retirement only)
     → IncomeContributionCalculator
  4. Apply RMD if age >= rmdStartAge
     → RmdCalculator (Uniform Lifetime Table)
  5. Execute Roth conversion (from schedule if provided, else from optimizer)
  6. Execute withdrawals from PoolStrategy
     → satisfies (spending target − income sources) net of tax
  7. Apply portfolio growth (weighted by account mix)
  8. Compute combined tax: federal + state
     → FederalTaxCalculator, StateTaxCalculatorFactory
  9. Build ProjectionYearDto; check for portfolio exhaustion
```

### Key Constants and Defaults

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_WITHDRAWAL_RATE` | 4% | Used when no SpendingPlan is configured |
| `SHORTFALL_TOLERANCE` | −$10 | Negative portfolio values within this tolerance are floored to zero, not treated as exhaustion |
| `IRMAA_BRACKET_RATE` | 22% | Medicare surcharge warning threshold for Roth conversions at age 63+ |

### Tax Calculation

The engine uses a pluggable `TaxCalculationStrategy`:

* **`CombinedTaxCalculator`** — federal + state (default when state code is present)
* **`FederalOnlyTaxStrategy`** — federal only (when no state is configured or state has no income tax)
* **`NullStateTaxCalculator`** — no-op for states with zero income tax

State-specific logic (California SDI, surcharges) is implemented in
`CaliforniaStateTaxCalculator` and `BracketBasedStateTaxCalculator`.
The `StateTaxCalculatorFactory` selects the correct implementation by state code.

Tax brackets are inflation-indexed for future years using the scenario's configured inflation rate,
matching IRS cost-of-living adjustment methodology.

---

## WithdrawalOrder (Pool Sequencing)

The `WithdrawalOrder` enum controls which accounts are drawn down first:

| Value | Strategy |
|---|---|
| `TAXABLE_FIRST` | Draw taxable accounts, then traditional, then Roth (default) |
| `TRADITIONAL_FIRST` | Draw traditional accounts first (maximises Roth growth) |
| `ROTH_FIRST` | Draw Roth first (unusual; useful for specific scenarios) |
| `PRO_RATA` | Withdraw proportionally from all pools each year |
| `DYNAMIC_SEQUENCING` | Withdraw from the pool that fills the configured bracket most efficiently |

**Dynamic Sequencing** — the most sophisticated strategy — selects the withdrawal source each
year based on the `dynamic_sequencing_bracket_rate` threshold. For amounts below the threshold,
it draws from traditional (filling lower brackets efficiently); for amounts above it draws from
Roth or taxable. This minimises lifetime marginal rates by managing taxable income relative to
the IRMAA cliff and future RMD exposure.

---

## MonteCarloSpendingOptimizer

**Entry point:** `optimize(GuardrailOptimizationInput input)` → `GuardrailProfileResponse`

The optimizer finds the highest sustainable inflation-adjusted spending level by running hundreds
of portfolio simulations with randomised return sequences.

### Algorithm (5 Stages)

**Stage 1 — Context preparation (`prepareContext`)**

Loads income arrays, rental passive losses, marginal rate pre-computation, tax context, and
dynamic sequencing bracket ceiling per year.

**Stage 2 — Portfolio path generation (`generatePortfolioPaths`)**

`BlockBootstrapReturnGenerator` samples 5-year blocks from historical return data
(`HistoricalReturns`) and strings them together to form full-length return sequences. This
preserves autocorrelation (return momentum / mean reversion) that a simple i.i.d. draw would
destroy.

**Stage 3 — Joint grid search for spending + conversion (`runJointGridSearch`)**

A 20×20 grid over (spending fraction × conversion fraction) is evaluated. Each cell runs
`JOINT_SEARCH_TRIALS` (500) simulation trials. The cell with the highest sustainable spending
at the target confidence level advances to refinement.

**Stage 4 — Ternary refinement (`runJointRefinement`)**

`JOINT_REFINE_ITERATIONS` (10) iterations of ternary search within a ±10% window around the
grid winner, binary-searching for the exact sustainable spending amount at each conversion level.

**Stage 5 — Output assembly**

Constructs the `GuardrailProfileResponse` including:
* Year-by-year spending array (inflation-adjusted)
* Phase annotations (accumulation / transition / distribution)
* Spending corridor (floor / ceiling from portfolio guardrails)
* Adaptive Spending Guide — portfolio-contingent spending targets
* Roth conversion schedule from the optimal conversion fraction

### Key Parameters

| Parameter | Default | Meaning |
|---|---|---|
| Trial count | Configurable (typically 1000) | Number of MC simulations per evaluation |
| Block length | 5 years | Bootstrap block size for return sampling |
| Confidence level | Configurable | Portfolio survival threshold |
| `MAX_SPENDING_CEILING` | $500,000 / year | Binary search upper bound |
| `CASH_REPLENISHMENT_RATE` | 10% | Equity → cash bucket transfer rate |

---

## RothConversionOptimizer

**Scope:** Package-private. Instantiated by `MonteCarloSpendingOptimizer` and
`DeterministicProjectionEngine` — not a Spring bean.

**Goal:** Find the conversion fraction (fraction of traditional balance to convert each year)
that minimises **lifetime total tax paid** across the projection horizon.

### Algorithm

**Grid scan:** 50 evenly spaced conversion fractions [0, 1] are evaluated. For each fraction,
a full lifetime simulation is run computing total federal tax with conversions vs. without.
The fraction with the lowest lifetime tax advances.

**Ternary refinement:** 20 iterations of ternary search within ±5% of the grid winner narrow
the optimum to high precision.

**MAGI convergence:** Because Roth conversions increase MAGI, which affects passive loss
deductibility (rental properties), the optimizer iterates up to 3 times with updated MAGI to
achieve convergence within a $100 tolerance.

**Affordability constraint:** A binary search (30 iterations) ensures the chosen conversion
amount does not force the portfolio below the essential spending floor in any year.
Pre-59.5 conversion feasibility is checked separately (penalty-free threshold).

**RMD-target balance:** Rather than using portfolio exhaustion as a terminal constraint, the
optimizer targets a specific traditional balance at `rmdStartAge` that keeps projected RMDs
within the configured `rmd_target_bracket_rate`. This prevents the all-or-nothing failure mode
of exhaustion-based constraints.

### Output — `RothConversionSchedule`

```java
record RothConversionSchedule(
    double[] conversionByYear,          // annual conversion amounts
    double[] conversionTaxByYear,       // tax cost of each conversion
    double[] traditionalBalance,        // traditional pool balance per year
    double[] rothBalance,               // Roth pool balance per year
    double[] taxableBalance,            // taxable pool balance per year
    double[] projectedRmd,              // projected RMDs under this schedule
    double lifetimeTaxWith,             // total lifetime tax WITH conversions
    double lifetimeTaxWithout,          // total lifetime tax WITHOUT conversions
    int exhaustionAge,
    boolean exhaustionTargetMet,
    double conversionFraction,          // the optimal fraction found
    double targetTraditionalBalance     // the RMD-anchor target balance
)
```

---

## RmdCalculator

Implements the **IRS Uniform Lifetime Table** for Required Minimum Distribution computation.
Given age and traditional balance, returns the minimum withdrawal amount.

RMDs begin at the configured `rmd_start_age` (typically 73 under SECURE 2.0).
The calculator is also used by the Roth optimizer to set the target traditional balance that
keeps future RMDs below the configured bracket ceiling.

---

## IncomeSourceProcessor

Processes the full set of income sources for a given projection year:

* **Social Security** — applies the statutory 85% inclusion rule via `SocialSecurityTaxCalculator`
* **Pension / annuity** — fully taxable ordinary income
* **Part-time employment** — subject to self-employment tax via `SelfEmploymentTaxCalculator`
* **Rental income** — passive income; passive losses (from depreciation, expenses) computed by
  `RentalLossCalculator`; MAGI phase-out applied for the $25k rental loss allowance

---

## SpendingPlan Type Hierarchy

```java
sealed interface SpendingPlan permits TierBasedSpendingPlan, GuardrailSpendingInput {
    ResolvedYearSpending resolveYear(int year, int age, int yearsInRetirement,
                                     BigDecimal inflationRate, BigDecimal activeIncome);
}
```

**`TierBasedSpendingPlan`** — wraps spending tiers from a `SpendingProfileEntity`. Each tier
covers an age range with a base annual spend, optional per-tier inflation override, and optional
cola (cost-of-living adjustment). Tiers are checked for gaps and overlaps during validation.

**`GuardrailSpendingInput`** — wraps the year-indexed spending array from an MC optimization
result. Simple year-based lookup; supports guardrail adjustments (spending cut when portfolio
drops below the floor corridor).

A `ProjectionScenarioEntity` holds at most one active plan at a time: setting `spending_profile_id`
clears `guardrail_profile_id`, and vice versa. When neither is set, the engine falls back to
the configured withdrawal rate (default 4%).
