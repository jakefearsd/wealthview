-- Add is_revoked flag to invite_codes to support admin revocation of outstanding invites
ALTER TABLE invite_codes ADD COLUMN IF NOT EXISTS is_revoked boolean NOT NULL DEFAULT false;
