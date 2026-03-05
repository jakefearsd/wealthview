-- Accounts table for financial accounts

CREATE TABLE accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name text NOT NULL,
    type text NOT NULL CHECK (type IN ('brokerage', 'ira', '401k', 'roth', 'bank')),
    institution text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_tenant_id ON accounts(tenant_id);
