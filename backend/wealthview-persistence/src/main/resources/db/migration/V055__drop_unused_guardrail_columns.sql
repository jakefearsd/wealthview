-- V055: Drop columns on guardrail_spending_profiles that were declared but never
-- populated by the optimizer. Verified 2026-04-19 against production code:
--   * return_stddev      — written from the request, echoed back in the
--                          response, but no computation reads it (the bootstrap
--                          MC optimizer derives volatility from historical
--                          returns, and the deterministic Roth optimizer only
--                          uses the mean).
--   * percentile_90_final — column exists from V037 but no setter is ever
--                           called and no UI surface displays it.
--   * percentile_55_final — column added in V046 for the abandoned
--                           ContingentSpendingTable feature; never populated
--                           after the feature was replaced by the
--                           NearTermSpendingGuide frontend heuristics.

ALTER TABLE guardrail_spending_profiles
    DROP COLUMN IF EXISTS return_stddev,
    DROP COLUMN IF EXISTS percentile_90_final,
    DROP COLUMN IF EXISTS percentile_55_final;
