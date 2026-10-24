-- V084: the bond tent target the Monte Carlo optimizer certified a guardrail profile with. Null when the
-- scenario has no bond tent, and for profiles optimized before the tent was certified.
ALTER TABLE guardrail_spending_profiles ADD COLUMN IF NOT EXISTS bond_tent_target numeric(19,4);
