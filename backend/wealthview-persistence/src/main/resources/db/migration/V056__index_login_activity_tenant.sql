-- Add composite index to support tenant-scoped login activity queries ordered by recency.
-- The existing idx_login_activity_created_at supports global queries but forces a sort
-- when filtering by tenant_id, which the admin audit view does on every request.
CREATE INDEX IF NOT EXISTS idx_login_activity_tenant_created_at
    ON login_activity (tenant_id, created_at DESC);
