-- V082: Monte Carlo trials now start in the base year (today) instead of at retirement.
-- Every stored guardrail profile was optimized under the old model, so flag it stale and let
-- the user re-optimize from the UI. Flag only: no stored figures change. Idempotent.
UPDATE guardrail_spending_profiles SET is_stale = true WHERE is_stale = false;
