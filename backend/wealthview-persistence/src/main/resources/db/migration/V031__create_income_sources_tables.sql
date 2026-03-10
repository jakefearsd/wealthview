-- Create income_sources and scenario_income_sources tables
-- Income sources are first-class entities that replace the JSON income_streams on spending profiles.
-- They have typed tax treatment and can optionally link to properties for rental income.

CREATE TABLE income_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name text NOT NULL,
    income_type text NOT NULL
        CHECK (income_type IN ('rental_property', 'social_security', 'pension',
                               'part_time_work', 'annuity', 'other')),
    annual_amount numeric(19, 4) NOT NULL,
    start_age integer NOT NULL,
    end_age integer,
    inflation_rate numeric(7, 5) NOT NULL DEFAULT 0,
    one_time boolean NOT NULL DEFAULT false,
    tax_treatment text NOT NULL DEFAULT 'taxable'
        CHECK (tax_treatment IN ('taxable', 'partially_taxable', 'tax_free',
                                  'rental_passive', 'rental_active_reps', 'rental_active_str',
                                  'self_employment')),
    property_id uuid REFERENCES properties(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_income_sources_tenant_id ON income_sources(tenant_id);
CREATE INDEX idx_income_sources_property_id ON income_sources(property_id);

CREATE TABLE scenario_income_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id uuid NOT NULL REFERENCES projection_scenarios(id) ON DELETE CASCADE,
    income_source_id uuid NOT NULL REFERENCES income_sources(id) ON DELETE CASCADE,
    override_annual_amount numeric(19, 4),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_scenario_income_source UNIQUE (scenario_id, income_source_id)
);

CREATE INDEX idx_scenario_income_sources_scenario ON scenario_income_sources(scenario_id);
