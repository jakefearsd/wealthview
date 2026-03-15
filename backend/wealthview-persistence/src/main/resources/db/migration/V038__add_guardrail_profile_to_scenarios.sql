-- Add optional FK from projection_scenarios to guardrail_spending_profiles.
-- A scenario can have at most one guardrail profile (mutual exclusivity with spending_profile
-- enforced at application level).

ALTER TABLE projection_scenarios
ADD COLUMN guardrail_profile_id uuid REFERENCES guardrail_spending_profiles(id) ON DELETE SET NULL;
