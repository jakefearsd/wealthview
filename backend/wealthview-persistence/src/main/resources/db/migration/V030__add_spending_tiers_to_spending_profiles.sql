-- Add spending_tiers JSONB column to spending_profiles for age-based spending phases
ALTER TABLE spending_profiles ADD COLUMN spending_tiers jsonb NOT NULL DEFAULT '[]';
