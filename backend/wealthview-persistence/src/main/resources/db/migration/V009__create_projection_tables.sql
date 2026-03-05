-- Projection tables placeholder for Phase 3

CREATE TABLE projection_scenarios (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name text NOT NULL,
    retirement_date date,
    end_age integer,
    inflation_rate numeric(5, 4),
    params_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projection_scenarios_tenant_id ON projection_scenarios(tenant_id);

CREATE TABLE projection_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES projection_scenarios(id) ON DELETE CASCADE,
    linked_account_id uuid REFERENCES accounts(id),
    initial_balance numeric(19, 4) NOT NULL DEFAULT 0,
    annual_contribution numeric(19, 4) NOT NULL DEFAULT 0,
    expected_return numeric(5, 4) NOT NULL DEFAULT 0.07,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projection_accounts_scenario_id ON projection_accounts(scenario_id);
