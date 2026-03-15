-- Add cash buffer (bucket strategy) parameters to guardrail spending profiles
ALTER TABLE guardrail_spending_profiles
    ADD COLUMN cash_reserve_years integer NOT NULL DEFAULT 2,
    ADD COLUMN cash_return_rate numeric(5,4) NOT NULL DEFAULT 0.0400;
