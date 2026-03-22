-- V044: Add Roth conversion optimizer fields to guardrail_spending_profiles
-- Supports the two-phase Roth conversion optimizer: Phase 1 minimizes lifetime
-- tax via conversion scheduling, Phase 2 feeds schedule into MC spending optimizer.

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS conversion_schedule jsonb DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS conversion_bracket_rate numeric(5,4) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS rmd_target_bracket_rate numeric(5,4) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS traditional_exhaustion_buffer int DEFAULT 5;
