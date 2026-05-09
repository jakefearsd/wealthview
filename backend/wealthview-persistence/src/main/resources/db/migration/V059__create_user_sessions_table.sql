-- Per-device session tracking. Each successful login creates a row here and
-- the issued access token carries the session id (sid claim). Users can
-- revoke individual sessions without disturbing other devices, unlike
-- token_generation which is a single per-user counter.
CREATE TABLE IF NOT EXISTS user_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_label text NULL,
    transport text NOT NULL CHECK (transport IN ('cookie', 'bearer')),
    ip_address text NULL,
    user_agent text NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz NULL
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_revoked
    ON user_sessions (user_id, revoked_at);
