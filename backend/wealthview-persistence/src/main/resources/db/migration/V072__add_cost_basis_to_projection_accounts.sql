-- V072: optional cost-basis input for hypothetical taxable projection accounts.
-- Linked accounts always derive cost basis live from their holdings (ignoring this column);
-- hypothetical accounts fall back to initial_balance when this is null (no embedded gain).
ALTER TABLE projection_accounts ADD COLUMN IF NOT EXISTS cost_basis numeric(19,4);
COMMENT ON COLUMN projection_accounts.cost_basis IS
  'Hypothetical-account cost basis for capital-gains tax; null => defaults to initial_balance. Ignored for linked accounts.';
