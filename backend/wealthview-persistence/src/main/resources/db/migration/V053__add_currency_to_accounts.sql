-- Add currency column to accounts table (ISO 4217 code, defaults to USD)
ALTER TABLE accounts ADD COLUMN currency text NOT NULL DEFAULT 'USD';
