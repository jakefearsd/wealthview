-- Create login_activity table to record authentication attempts for admin audit view
CREATE TABLE IF NOT EXISTS login_activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email text NOT NULL,
    tenant_id uuid,
    success boolean NOT NULL,
    ip_address text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_login_activity_created_at ON login_activity (created_at DESC);
