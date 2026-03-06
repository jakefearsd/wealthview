-- Add loan detail columns to properties for amortization-based mortgage balance computation
ALTER TABLE properties ADD COLUMN loan_amount numeric(19, 4);
ALTER TABLE properties ADD COLUMN annual_interest_rate numeric(7, 5);
ALTER TABLE properties ADD COLUMN loan_term_months integer;
ALTER TABLE properties ADD COLUMN loan_start_date date;
ALTER TABLE properties ADD COLUMN use_computed_balance boolean NOT NULL DEFAULT false;
-- TODO: Future depreciation columns
-- ALTER TABLE properties ADD COLUMN depreciation_method text;
-- ALTER TABLE properties ADD COLUMN depreciable_basis numeric(19, 4);
-- ALTER TABLE properties ADD COLUMN placed_in_service_date date;
