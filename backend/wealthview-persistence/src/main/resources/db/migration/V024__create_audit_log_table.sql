-- Create audit_log table for tracking user actions
CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid REFERENCES tenants(id),
    user_id uuid REFERENCES users(id),
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    details jsonb,
    ip_address text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_tenant_id ON audit_log(tenant_id);
CREATE INDEX idx_audit_log_entity_type_entity_id ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
