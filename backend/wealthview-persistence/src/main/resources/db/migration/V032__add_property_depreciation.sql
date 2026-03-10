-- Add depreciation columns to properties and create property_depreciation_schedule table
-- for cost segregation year-by-year depreciation entries.

ALTER TABLE properties
    ADD COLUMN in_service_date date,
    ADD COLUMN land_value numeric(19, 4),
    ADD COLUMN depreciation_method text NOT NULL DEFAULT 'none'
        CHECK (depreciation_method IN ('none', 'straight_line', 'cost_segregation')),
    ADD COLUMN useful_life_years numeric(4, 1) NOT NULL DEFAULT 27.5;

CREATE TABLE property_depreciation_schedule (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    tax_year integer NOT NULL,
    depreciation_amount numeric(19, 4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_depreciation_schedule_property_year UNIQUE (property_id, tax_year)
);
