-- Add account_type column to projection_accounts for per-pool tracking (traditional/roth/taxable)
ALTER TABLE projection_accounts
    ADD COLUMN account_type text NOT NULL DEFAULT 'taxable';

ALTER TABLE projection_accounts
    ADD CONSTRAINT chk_projection_accounts_account_type
    CHECK (account_type IN ('traditional', 'roth', 'taxable'));
