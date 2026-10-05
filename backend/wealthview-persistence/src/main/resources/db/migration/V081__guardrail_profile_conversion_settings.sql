-- V081 (Phase 1a / D13): persist the two Roth-conversion optimizer settings reoptimize needs.
-- Before this, GuardrailProfileService.reoptimize inferred optimize_conversions from
-- traditional_exhaustion_buffer != null -- always true, because every save writes a buffer --
-- so reoptimizing a profile created WITHOUT conversions silently turned them on, and the
-- dynamic-sequencing bracket rate was dropped entirely. Backfill: a profile had conversions
-- enabled exactly when the optimizer produced a conversion schedule for it.
ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS optimize_conversions boolean NOT NULL DEFAULT false;

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS dynamic_sequencing_bracket_rate numeric(5,4);

UPDATE guardrail_spending_profiles
    SET optimize_conversions = (conversion_schedule IS NOT NULL);

COMMENT ON COLUMN guardrail_spending_profiles.optimize_conversions IS
  'Whether the optimizer ran the joint Roth-conversion search for this profile; echoed by reoptimize.';
COMMENT ON COLUMN guardrail_spending_profiles.dynamic_sequencing_bracket_rate IS
  'Dynamic-sequencing bracket ceiling used for this profile (null = not set); echoed by reoptimize.';
