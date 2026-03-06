-- Add optional spending profile reference to projection scenarios.

ALTER TABLE projection_scenarios
    ADD COLUMN spending_profile_id uuid REFERENCES spending_profiles(id);
