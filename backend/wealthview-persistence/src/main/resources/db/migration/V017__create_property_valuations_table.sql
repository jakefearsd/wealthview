-- Property valuations history for tracking market value over time from multiple sources
CREATE TABLE property_valuations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    valuation_date date NOT NULL,
    value numeric(19, 4) NOT NULL,
    source text NOT NULL CHECK (source IN ('manual', 'zillow', 'appraisal')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_property_valuations_property_id ON property_valuations(property_id);
CREATE INDEX idx_property_valuations_tenant_id ON property_valuations(tenant_id);
CREATE UNIQUE INDEX uq_property_valuations_property_source_date
    ON property_valuations(property_id, source, valuation_date);
