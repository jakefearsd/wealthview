-- TOTP MFA columns on the users table. mfa_secret_encrypted holds the
-- AES-GCM encrypted Base32 TOTP shared secret. mfa_enabled flips true only
-- after the user verifies a setup code; until then the secret is provisional.
ALTER TABLE users
    ADD COLUMN mfa_enabled boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret_encrypted text NULL,
    ADD COLUMN mfa_setup_at timestamptz NULL;
