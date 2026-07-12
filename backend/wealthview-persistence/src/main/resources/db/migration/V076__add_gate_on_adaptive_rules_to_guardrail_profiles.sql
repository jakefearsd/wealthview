-- V076 (T24): per-profile toggle letting the guardrail Monte Carlo optimizer's sustainability
-- search GATE on the with-rules (simulated adaptation) success metric instead of the no-adaptation
-- one. Defaults to false so every existing profile keeps gating on the no-adaptation metric --
-- the search's default behavior is unchanged unless a profile explicitly opts in.
ALTER TABLE guardrail_spending_profiles
    ADD COLUMN IF NOT EXISTS gate_on_adaptive_rules boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN guardrail_spending_profiles.gate_on_adaptive_rules IS
  'When true, SustainabilitySearch gates candidate spending on success_probability_with_rules '
  '(the simulated guardrail-adaptation success rate) instead of the no-adaptation success rate.';
