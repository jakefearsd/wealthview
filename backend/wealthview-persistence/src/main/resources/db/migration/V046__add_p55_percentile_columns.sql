-- V046: Add P55 percentile columns alongside existing P90/P75.
-- P55 represents "slightly better than median" — more useful for planning
-- than the unrealistically optimistic P90/P75.

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS percentile_55_final numeric(19,4) DEFAULT NULL;
