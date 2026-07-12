-- V078: per-owner projection accounts for household modeling (spec 2026-07-12).
ALTER TABLE projection_accounts
    ADD COLUMN IF NOT EXISTS owner text NOT NULL DEFAULT 'primary';
ALTER TABLE projection_accounts
    ADD CONSTRAINT chk_projection_accounts_owner CHECK (owner IN ('primary','spouse','joint'));
