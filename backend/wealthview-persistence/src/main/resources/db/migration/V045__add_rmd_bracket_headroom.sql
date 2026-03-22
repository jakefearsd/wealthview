-- Add RMD bracket headroom column to guardrail_spending_profiles.
-- This replaces the exhaustion-based constraint with a target-balance approach:
-- instead of forcing traditional IRA to $0 by endAge-N, the optimizer computes
-- a target balance at RMD age where RMDs stay within the user's target bracket,
-- with configurable headroom for market variability (default 10%).

ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS rmd_bracket_headroom numeric(5,4) DEFAULT 0.10;
