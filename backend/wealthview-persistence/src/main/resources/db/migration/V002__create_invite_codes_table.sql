-- Invite codes for tenant registration

CREATE TABLE invite_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    code text NOT NULL UNIQUE,
    created_by uuid NOT NULL REFERENCES users(id),
    consumed_by uuid REFERENCES users(id),
    consumed_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_invite_codes_tenant_id ON invite_codes(tenant_id);
CREATE INDEX idx_invite_codes_code ON invite_codes(code);
