-- Properties, property_income, and property_expenses tables

CREATE TABLE properties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    address text NOT NULL,
    purchase_price numeric(19, 4) NOT NULL,
    purchase_date date NOT NULL,
    current_value numeric(19, 4) NOT NULL,
    mortgage_balance numeric(19, 4) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_properties_tenant_id ON properties(tenant_id);

CREATE TABLE property_income (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    date date NOT NULL,
    amount numeric(19, 4) NOT NULL,
    category text NOT NULL CHECK (category IN ('rent', 'other')),
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_property_income_property_id ON property_income(property_id);
CREATE INDEX idx_property_income_tenant_id ON property_income(tenant_id);

CREATE TABLE property_expenses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    date date NOT NULL,
    amount numeric(19, 4) NOT NULL,
    category text NOT NULL CHECK (category IN ('mortgage', 'tax', 'insurance', 'maintenance', 'capex', 'hoa', 'mgmt_fee')),
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_property_expenses_property_id ON property_expenses(property_id);
CREATE INDEX idx_property_expenses_tenant_id ON property_expenses(tenant_id);
