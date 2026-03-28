-- Exchange rates table: one rate per currency per tenant
CREATE TABLE exchange_rates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    currency_code text NOT NULL,
    rate_to_usd numeric(19,8) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_exchange_rates_tenant_currency UNIQUE (tenant_id, currency_code)
);
CREATE INDEX idx_exchange_rates_tenant_id ON exchange_rates(tenant_id);
