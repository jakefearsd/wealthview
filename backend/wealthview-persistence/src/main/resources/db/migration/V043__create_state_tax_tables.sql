-- State tax tables: brackets, standard deductions, and surcharges
-- Mirrors federal tax_brackets and standard_deductions tables with state_code dimension

CREATE TABLE state_tax_brackets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    state_code text NOT NULL,
    tax_year int NOT NULL,
    filing_status text NOT NULL,
    bracket_floor numeric(19,4) NOT NULL,
    bracket_ceiling numeric(19,4),
    rate numeric(5,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_state_tax_brackets_filing_status
        CHECK (filing_status IN ('single', 'married_filing_jointly'))
);
CREATE UNIQUE INDEX idx_state_tax_brackets_state_year_status_floor
    ON state_tax_brackets(state_code, tax_year, filing_status, bracket_floor);

CREATE TABLE state_standard_deductions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    state_code text NOT NULL,
    tax_year int NOT NULL,
    filing_status text NOT NULL,
    amount numeric(19,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_state_standard_deductions_state_year_status
        UNIQUE (state_code, tax_year, filing_status),
    CONSTRAINT chk_state_standard_deductions_filing_status
        CHECK (filing_status IN ('single', 'married_filing_jointly'))
);

CREATE TABLE state_tax_surcharges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    state_code text NOT NULL,
    tax_year int NOT NULL,
    filing_status text NOT NULL,
    surcharge_name text NOT NULL,
    income_threshold numeric(19,4) NOT NULL,
    rate numeric(5,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_state_tax_surcharges_state_year_status_name
        UNIQUE (state_code, tax_year, filing_status, surcharge_name),
    CONSTRAINT chk_state_tax_surcharges_filing_status
        CHECK (filing_status IN ('single', 'married_filing_jointly'))
);
