-- V069: per-account asset allocation (jsonb) + make expected_return an optional override.
ALTER TABLE projection_accounts ADD COLUMN IF NOT EXISTS allocation jsonb;
ALTER TABLE projection_accounts ALTER COLUMN expected_return DROP NOT NULL;
COMMENT ON COLUMN projection_accounts.allocation IS
  'Asset-class weights {us_stock,intl_stock,bond,cash}; null => derive from holdings (linked) or default.';
COMMENT ON COLUMN projection_accounts.expected_return IS
  'Optional nominal expected-return override; null => derive from allocation.';
