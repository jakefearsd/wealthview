-- Single-use MFA challenge tokens. After a credential check succeeds for an
-- MFA-enabled user, the server issues a short-lived (5 min) challenge JWT and
-- records its JTI here. The /mfa/challenge endpoint marks the row used; reuse
-- of the same JTI returns 401.
CREATE TABLE IF NOT EXISTS mfa_challenges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jti uuid NOT NULL UNIQUE,
    transport text NOT NULL CHECK (transport IN ('cookie', 'bearer')),
    expires_at timestamptz NOT NULL,
    used_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mfa_challenges_user ON mfa_challenges (user_id);
