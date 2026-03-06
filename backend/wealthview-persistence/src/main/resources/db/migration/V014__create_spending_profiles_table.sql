-- Create spending profiles table for storing expense and income data
-- used in retirement spending viability analysis.

CREATE TABLE spending_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name text NOT NULL,
    essential_expenses numeric(19,4) NOT NULL DEFAULT 0,
    discretionary_expenses numeric(19,4) NOT NULL DEFAULT 0,
    income_streams jsonb NOT NULL DEFAULT '[]',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_spending_profiles_tenant_id ON spending_profiles(tenant_id);
