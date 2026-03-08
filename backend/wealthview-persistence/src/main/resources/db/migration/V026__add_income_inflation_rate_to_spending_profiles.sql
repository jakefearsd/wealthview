-- Add income inflation rate to spending profiles (0 = strictly nominal, current behavior)
ALTER TABLE spending_profiles ADD COLUMN income_inflation_rate numeric(19,4) NOT NULL DEFAULT 0;
