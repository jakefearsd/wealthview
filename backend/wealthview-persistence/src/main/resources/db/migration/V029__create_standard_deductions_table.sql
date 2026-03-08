-- Create standard_deductions table for federal standard deduction amounts by year and filing status
CREATE TABLE IF NOT EXISTS standard_deductions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_year int NOT NULL,
    filing_status text NOT NULL,
    amount numeric(19,4) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_standard_deductions_year_status UNIQUE (tax_year, filing_status),
    CONSTRAINT chk_standard_deductions_filing_status CHECK (filing_status IN ('single', 'married_filing_jointly'))
);
