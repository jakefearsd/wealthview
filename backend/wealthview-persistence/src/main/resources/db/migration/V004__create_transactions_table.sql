-- Transactions table for buy/sell/dividend/deposit/withdrawal

CREATE TABLE transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    date date NOT NULL,
    type text NOT NULL CHECK (type IN ('buy', 'sell', 'dividend', 'deposit', 'withdrawal')),
    symbol text,
    quantity numeric(19, 4),
    amount numeric(19, 4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_tenant_id ON transactions(tenant_id);
CREATE INDEX idx_transactions_account_id_symbol ON transactions(account_id, symbol);
