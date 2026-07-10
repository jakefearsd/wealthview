-- Seed long-term capital gains brackets for 2025 (0/15/20), on TOTAL taxable income.
-- Re-runnable: truncates and re-inserts all bracket data.
TRUNCATE TABLE ltcg_brackets;
INSERT INTO ltcg_brackets (tax_year, filing_status, rate, bracket_floor, bracket_ceiling) VALUES
  (2025, 'single', 0.0000, 0, 48350),
  (2025, 'single', 0.1500, 48350, 533400),
  (2025, 'single', 0.2000, 533400, NULL),
  (2025, 'married_filing_jointly', 0.0000, 0, 96700),
  (2025, 'married_filing_jointly', 0.1500, 96700, 600050),
  (2025, 'married_filing_jointly', 0.2000, 600050, NULL);
