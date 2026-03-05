-- Add import_hash column to transactions for deduplication during CSV/OFX import.
-- The hash is computed from date+type+symbol+quantity+amount using SHA-256.
ALTER TABLE transactions ADD COLUMN import_hash text;
CREATE INDEX idx_transactions_import_hash ON transactions(tenant_id, account_id, import_hash);
