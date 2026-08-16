# Monte Carlo Optimizer: Confidence Levels and Percentiles

## What the Simulation Actually Does

The optimizer runs **5,000 trials by default** (`trial_count`, request-overridable). Each trial is one
possible market history for your whole retirement, and the optimizer searches for the highest constant
spending plan that still works in enough of them.

**What is random:** the market. Each trial draws one *block bootstrap* sequence of historical years
(expected block length **5 years**) out of the real annual returns for four asset classes — US stock,
international stock, bond, cash. Sampling blocks rather than single years preserves momentum and
mean-reversion, which is what makes sequence-of-returns risk show up at all. The default history
window is **1972–2025**; a scenario can opt into the full **1928–2025** window to pull in the
Depression-era tail.

The *same* year sequence drives every account in a trial, so cross-asset correlation is preserved.
Your accounts' returns come from their asset allocations blended against that sequence (or a fixed
expected-return override, which shows no variability at all), minus your fee rate.

**What is NOT random:** everything else. Income sources, Social Security, tax brackets, the
conversion schedule, spending phases, inflation, and life expectancy are all held fixed across trials.
(Life expectancy is the one exception — see *Stochastic Mortality* below, which is opt-in.)

**Everything is in today's dollars.** Returns are real (inflation-adjusted), spending is constant
real, and tax brackets are used at their seeded values without annual indexing. The only exception is
thresholds Congress froze in nominal terms — the Social Security taxability thresholds and the NIIT
threshold — which are deflated so their real erosion is modeled.

**Random seeds — runs are reproducible.** You never set a seed by hand.
`GuardrailProfileService` derives one deterministically from a **scenario signature**: a SHA-256 over
every input that can move the result — retirement date, end age, inflation, each account's
type/balance/contribution/expected-return/allocation/cost basis/owner, the scenario's dividend, fee
and interest yields, the Depression-window opt-in, every linked income source (id, effective amount,
owner, survivor percent), and the whole household/mortality block. Re-optimizing an unchanged
scenario therefore reproduces the same numbers exactly; change any of those inputs and both the seed
and the cache key move together.

From that one seed, three *separate* random streams are derived so they cannot perturb each other:
the return paths use `seed`, the Roth-conversion arm search uses `seed + 1`, and stochastic mortality
uses `seed + 0x4D4F5254`. (An engine-level call with no seed at all falls back to unseeded
randomness, but nothing in the application takes that path.)

## The Confidence Level (Conservative/Moderate/Aggressive)

This is the **target success probability** — the share of simulated market histories in which the plan
must succeed:

- **Conservative: 95%.** Only the worst 5% of scenarios are allowed to fail.
- **Moderate: 90%.** The worst 10% can fail.
- **Aggressive: 80%.** The worst 20% can fail.

(An explicit `confidence_level` on the request overrides the risk-tolerance mapping. With neither
supplied the default is 0.95.)

**"Success" means your essential floor was funded in every single year of that trial.** It is *not*
"the portfolio never hit zero" and it is *not* "the ending balance beat a target". A trial fails the
moment income + portfolio draws + cash reserve fall short of that year's floor.

This is the **decision input** — it drives how much the optimizer lets you spend. Higher confidence =
less spending (more margin for bad markets). Lower confidence = more spending (accepting more risk).

### Terminal Target and Portfolio Floor are *extra* constraints

If you set a terminal balance target or a portfolio floor, they are checked **on top of** the success
gate, and they are the two settings that are evaluated at a percentile:

- **Terminal balance target** — the final balance at the `1 − confidence` percentile must reach it.
- **Portfolio floor** — the *minimum* balance reached during retirement, again at the
  `1 − confidence` percentile, must reach it.

Leave them at zero and neither constrains anything: the success rate alone decides.

### If the floor itself is unaffordable

Before searching, the optimizer checks your essential floor against what the portfolio can actually
support at your confidence level and **clamps it down** in any year where it can't. When that happens
the result discloses `floor_reduced = true` plus an `original_floor_success_probability` — the success
rate of the same plan measured against the floor you actually asked for. Read that number, not the
headline, when the clamp has fired.

## The Percentiles (P10, P25, P50)

These are **reporting outputs** — they describe what the portfolio balance does across the 5,000
trials, GIVEN the spending plan the optimizer already chose. Every year of the result carries all
three:

- **P10**: In 90% of trials, the portfolio is above this number. Only the worst 10% fall below.
- **P25**: 75% of trials are above this.
- **P50 (Median)**: Half the trials are above, half below. The "typical" outcome.

The headline summary additionally reports the **median final balance** and the **P10 final balance**.

Percentiles are computed by linear interpolation between the two nearest ranks of the sorted trial
values — the standard `index = p x (n − 1)` convention.

### Where you see them in the UI

- **Portfolio fan chart** — the outer band spans P10 → median, the inner band spans P25 → median,
  with the median drawn as a line. The widening of the outer band over time is exactly the
  sequence-of-returns dispersion.
- **Near-term spending guide** — the 5-year tactical view anchors on **P25 as the optimizer's
  recommended draw** and shows **median balance x 4%** beside it as a "typical market" reference
  point. Both are heuristic framings of the same underlying per-year percentile balances; only P25 and
  the median feed it.

## How They Connect

The optimizer's primary gate is a **count**, not a percentile: at moderate (90%), it found the highest
spending level where at least 9 of every 10 trials funded the floor every year.

The percentiles then tell you what that plan *looks like*:

- **P10 is the tenth-worst-percentile trajectory.** At 90% confidence some of those trials are among
  the 10% that were allowed to fail — a P10 balance running low, or hitting zero late, is expected,
  not a bug.
- **P25 is comfortably inside the successful set.**
- **P50 is the "typical" outcome** — much better than what the optimizer planned for.

```
Worst ──── P10 ──── P25 ──── P50 ──── Best
        ↑
   ~the failure region you accepted at your confidence level
```

If you *did* set a terminal target or portfolio floor, those bind at the `1 − confidence` percentile
specifically — 10th percentile at moderate, 5th at conservative, 20th at aggressive.

## Why P10 Can Show Massive Over-Accumulation

If your P10 ending balance is far above the terminal target (e.g. $7.5M vs $2M target), it means the
constraints are binding somewhere in the MIDDLE of retirement, not at the end.

Example: a $1M portfolio floor constraint during the Travel phase (ages 60-74) forces the optimizer to
limit Travel spending. In bad market scenarios, Travel-era withdrawals of $200K+/year almost violate
the $1M floor. The optimizer pulls back spending to protect against that mid-retirement dip.

Once you survive the Travel years, the portfolio recovers and grows — by age 82+, even P10 is well
above the terminal target. But the optimizer already committed to low later-phase discretionary
because it was constrained by the Travel-era floor risk.

## The Spending Corridor

Each year also carries a **corridor low / corridor high** band around the recommended spend. It is
derived from cross-trial dispersion, not from your constraints: the low band is the 10th percentile of
resources available that year (clamped to at least the essential floor and at most the recommended
spend), and the high band is the 90th percentile (clamped to at least the recommended spend and at
most 3x it). The bands are then smoothed with a 3-point moving average and re-clamped so they always
bracket the recommendation.

### The adaptive-rules success rate

If you set a `max_annual_adjustment_rate`, the result also reports
`success_probability_with_rules`: a second pass over the same trials in which spending *adapts* —
cutting discretionary toward the trial's own portfolio position when it breaches the lower band
(bounded by your adjustment rate, never below the essential floor), and recovering toward plan
otherwise (never above plan). It measures "success if you follow this specific ratio-cut rule", not
"spending always stayed inside the shown corridor."

Whether that with-rules rate *certifies* the plan, or is merely reported beside the no-adaptation
rate, depends on the profile's `gate_on_adaptive_rules` toggle. **New optimizations gate on the
with-rules metric by default**; profiles optimized before that change keep their stored setting until
re-optimized. The result's `gated_on` disclosure tells you which metric actually certified the run.

## Withdrawal Taxes in the Simulation

The trials model tax, not just balances. Within each simulated year the ordinary-income stack is built
in a fixed order and each layer is priced incrementally on top of the ones before it:

```
base outside income  →  taxable-pool interest  →  RMDs  →  Roth conversion  →  traditional draw
```

Each layer's tax is `tax(stack so far + layer) − tax(stack so far)`, using that year's exact bracket
table, so a draw that crosses a bracket is priced correctly. On top of that:

- **Long-term capital gains** on taxable-pool sales are realized FIFO against per-account cost basis
  and taxed at the 0/15/20% LTCG brackets, *stacked on ordinary income*.
- **Qualified dividends** (the equity share of the taxable pool's yield) are taxed the same way;
  the bond/cash share is taxed as **ordinary interest** instead.
- **NIIT** — the 3.8% surtax on `min(net investment income, MAGI − threshold)`, with net rental income
  included in the investment-income pot.
- **Early withdrawal penalty** — 10% on pre-59½ traditional distributions.
- **RMDs** run one stream per owner from that owner's own SECURE 2.0 start age (73 if born before
  1960, otherwise 75), on the IRS Uniform Lifetime Table.
- Tax bills that the year's income cannot cover are drawn from the pools, and that funding draw is
  itself grossed up when it touches traditional.

**Not modeled in the Monte Carlo engine:** IRMAA Medicare surcharges (deterministic engine only),
state income tax, itemized deductions and credits, and any state-specific surtax.

> **Note:** Spending recommendations account for income tax on traditional account withdrawals using
> your scenario's filing status and withdrawal ordering. Actual tax liability may vary based on
> deductions, credits, and state taxes not fully modeled in the Monte Carlo simulation. WealthView
> provides planning estimates only, not tax advice.

## Stochastic Mortality (opt-in)

By default both spouses die at fixed ages and every trial runs the same horizon. Turning on
stochastic mortality samples each spouse's death age per trial from SSA period-life tables (`qx` by
sex and age), conditional on being alive at retirement.

It changes **only the reported success statistics** — the spending recommendation is still derived
from the fixed-death run and is not re-optimized. It requires a two-person household. What you get
back:

- **Lifetime success probability** — unconditional across all trials. Dying early is never a failure
  by itself; such a trial succeeds if its floor was met over its own shorter horizon.
- **Longevity-conditional success** — the success rate restricted to trials whose surviving spouse
  reached a given age (default **95**), plus what fraction of trials qualified for that subset. A
  small fraction means a thin, less trustworthy sample.
- **Death-age distributions** — P10 / median / P90 of the sampled first- and second-death ages.

## Settings Reference

| Setting | What It Controls | Effect |
|---------|-----------------|--------|
| Confidence (conservative 95% / moderate 90% / aggressive 80%) | What share of market scenarios must fund the essential floor every year | The primary gate on spending |
| Essential floor | The spending that must be funded for a trial to count as a success | Clamped down (and disclosed) if unaffordable |
| Terminal balance target | Minimum ending balance at the `1 − confidence` percentile | Extra constraint; 0 disables it |
| Portfolio floor | Minimum balance at ANY point, at the `1 − confidence` percentile | Prevents deep mid-retirement dips, but can constrain later spending |
| Max annual adjustment rate | Year-over-year smoothing bound, and the adaptive-rules cut size | Also enables `success_probability_with_rules` |
| Gate on adaptive rules | Which success metric certifies the search | Defaults to ON for new optimizations |
| Trial count | Number of simulated market histories | Default 5,000 |
| P10/P25/P50 balances | What the portfolio actually does under your plan | Reporting only |

## Interpreting the Gap

The gap between P10 final balance and the terminal target tells you how much excess the plan
accumulates:

- **Large gap** (P10 >> terminal target): Your constraints are binding mid-retirement, not at the end.
  The optimizer is over-conservative for later years. Consider relaxing the portfolio floor or
  terminal target.
- **Small gap** (P10 near terminal target): The optimizer is fully utilizing the portfolio.
  Constraints are binding at end-of-life.
- **P10 below terminal target**: Expected for some trials at your confidence level. At 80% confidence,
  up to 20% of trials can end below the target.

## Which Constraint is Binding?

To figure out what's limiting your spending, look at:

1. **The success probability itself** — if it sits right at your confidence level, the success gate is
   binding and nothing else is.
2. **The phase where P10 balance is lowest** — that's where the portfolio floor constraint is tightest.
3. **Whether P10 grows after that phase** — if so, the floor bound there is preventing later spending.
4. **The terminal target** — if P10 at death is close to the target, end-of-life is the binding
   constraint.
5. **`floor_reduced`** — if it is true, your essential floor was never affordable in the first place
   and everything else is downstream of that.

Relaxing the binding constraint will unlock more spending in the under-allocated phases.
