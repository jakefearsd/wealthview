-- Holdings table for computed and manual holdings

CREATE TABLE holdings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    symbol text NOT NULL,
    quantity numeric(19, 4) NOT NULL DEFAULT 0,
    cost_basis numeric(19, 4) NOT NULL DEFAULT 0,
    is_manual_override boolean NOT NULL DEFAULT false,
    as_of_date date NOT NULL DEFAULT CURRENT_DATE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_holdings_account_symbol UNIQUE (account_id, symbol)
);

CREATE INDEX idx_holdings_account_id ON holdings(account_id);
CREATE INDEX idx_holdings_tenant_id ON holdings(tenant_id);
