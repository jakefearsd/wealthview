# Monte Carlo Optimizer: Confidence Levels and Percentiles

## The Confidence Level (Conservative/Moderate/Aggressive)

This controls **how many MC trials must succeed** for a spending level to be considered "sustainable":

- **Conservative (90%)**: 9 out of 10 simulated market histories must keep your portfolio above the terminal target AND floor. Only the worst 10% of scenarios are allowed to fail.
- **Moderate (80%)**: 8 out of 10 must succeed. The worst 20% can fail.
- **Aggressive (70%)**: 7 out of 10 must succeed. 30% can fail.

This is the **decision input** — it drives how much the optimizer lets you spend. Higher confidence = less spending (more margin for bad markets). Lower confidence = more spending (accepting more risk).

## The Percentiles (P10, P25, P50)

These are **reporting outputs** — they show what your portfolio balance looks like across the 5,000 MC trials, GIVEN the spending plan the optimizer already chose:

- **P10**: In 90% of trials, the portfolio is above this number. Only the worst 10% fall below.
- **P25**: 75% of trials are above this.
- **P50 (Median)**: Half the trials are above, half below. The "typical" outcome.

## How They Connect

If you chose **moderate (80%)**, the optimizer found spending where the **20th percentile** trial just barely meets your constraints ($2M terminal, $1M floor). That means:

- **P10 is BELOW the constraint-binding percentile.** P10 represents trials that are worse than what the optimizer targeted. Some P10 trials may actually violate the terminal target or floor — and that's expected, because you accepted 20% failure.
- **P25 is ABOVE the constraint-binding percentile.** P25 represents trials that are comfortably meeting constraints.
- **P50 is the "typical" outcome** — much better than what the optimizer planned for.

Think of it this way:

```
Worst ──────── P10 ──── P20 (constraint binds here at 80%) ──── P25 ──── P50 ──── Best
                         ↑
                   This is what the optimizer targets.
                   "80% of trials must be above my constraints."
```

## Why P10 Can Show Massive Over-Accumulation

If your P10 ending balance is far above the terminal target (e.g., $7.5M vs $2M target), it means the constraints are binding somewhere in the MIDDLE of retirement, not at the end.

Example: a $1M portfolio floor constraint during the Travel phase (ages 60-74) forces the optimizer to limit Travel spending. In bad market scenarios, Travel-era withdrawals of $200K+/year almost violate the $1M floor. The optimizer pulls back spending to protect against that mid-retirement dip.

Once you survive the Travel years, the portfolio recovers and grows — by age 82+, even P10 is well above the terminal target. But the optimizer already committed to low later-phase discretionary because it was constrained by the Travel-era floor risk.

## Settings Reference

| Setting | What It Controls | Effect |
|---------|-----------------|--------|
| Confidence (conservative/moderate/aggressive) | What % of market scenarios must work | Optimizer targets the corresponding percentile trial |
| Terminal balance target | Minimum ending balance | This amount is locked away, can never be spent |
| Portfolio floor | Minimum balance at ANY point in retirement | Prevents deep mid-retirement dips, but can constrain later spending |
| P10/P25/P50 balances | What the portfolio actually does under your plan | Reporting only — shows whether the plan is conservative or aggressive |

## Interpreting the Gap

The gap between P10 final balance and the terminal target tells you how much excess the plan accumulates:

- **Large gap** (P10 >> terminal target): Your constraints are binding mid-retirement, not at the end. The optimizer is over-conservative for later years. Consider relaxing the portfolio floor or terminal target.
- **Small gap** (P10 near terminal target): The optimizer is fully utilizing the portfolio. Constraints are binding at end-of-life.
- **P10 below terminal target**: Expected for some trials at your confidence level. At 80% confidence, up to 20% of trials can end below the target.

## Which Constraint is Binding?

To figure out what's limiting your spending, look at:

1. **The phase where P10 balance is lowest** — that's where the floor constraint is tightest
2. **Whether P10 grows after that phase** — if so, the floor bound there is preventing later spending
3. **The terminal target** — if P10 at death is close to the target, end-of-life is the binding constraint

Relaxing the binding constraint will unlock more spending in the under-allocated phases.
