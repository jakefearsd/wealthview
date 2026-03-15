-- Add smoothing and floor constraint fields to guardrail spending profiles
ALTER TABLE guardrail_spending_profiles
    ADD COLUMN portfolio_floor numeric(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN max_annual_adjustment_rate numeric(5,4) NOT NULL DEFAULT 0.0500,
    ADD COLUMN phase_blend_years integer NOT NULL DEFAULT 1,
    ADD COLUMN risk_tolerance text;
