-- Seed federal standard deduction amounts for 2022-2025
-- Re-runnable: deletes and re-inserts all data
DELETE FROM standard_deductions;

INSERT INTO standard_deductions (tax_year, filing_status, amount) VALUES
(2022, 'single', 12950.0000),
(2022, 'married_filing_jointly', 25900.0000),
(2023, 'single', 13850.0000),
(2023, 'married_filing_jointly', 27700.0000),
(2024, 'single', 14600.0000),
(2024, 'married_filing_jointly', 29200.0000),
(2025, 'single', 15000.0000),
(2025, 'married_filing_jointly', 30000.0000);
