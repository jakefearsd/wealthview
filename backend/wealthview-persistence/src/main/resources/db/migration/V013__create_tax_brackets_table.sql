-- Federal tax brackets by year and filing status, stored as data for reproducibility
CREATE TABLE IF NOT EXISTS tax_brackets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_year int NOT NULL,
    filing_status text NOT NULL,
    bracket_floor numeric(19,4) NOT NULL,
    bracket_ceiling numeric(19,4),
    rate numeric(5,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tax_brackets_filing_status
        CHECK (filing_status IN ('single', 'married_filing_jointly'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_tax_brackets_year_status_floor
    ON tax_brackets(tax_year, filing_status, bracket_floor);
