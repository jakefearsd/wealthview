-- V086: V085 converted cash reserves to bond tents only for scenarios with an active spending plan, and marked just
-- those profiles stale. Guardrail profiles it skipped (no active plan, or a tent already present) still hold results
-- computed with a cash reserve that the optimizer no longer models, so mark every remaining non-stale profile with
-- cash_reserve_years > 0 stale; the next optimize recomputes it. Idempotent: already-stale profiles are not touched.
UPDATE guardrail_spending_profiles
SET is_stale = true
WHERE is_stale = false
  AND cash_reserve_years > 0;
