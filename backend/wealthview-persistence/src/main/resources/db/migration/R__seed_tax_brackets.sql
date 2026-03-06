-- Seed federal tax brackets for 2022-2025
-- Re-runnable: deletes and re-inserts all bracket data
DELETE FROM tax_brackets;

-- 2022 Single
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2022, 'single', 0, 10275, 0.1000),
(2022, 'single', 10275, 41775, 0.1200),
(2022, 'single', 41775, 89075, 0.2200),
(2022, 'single', 89075, 170050, 0.2400),
(2022, 'single', 170050, 215950, 0.3200),
(2022, 'single', 215950, 539900, 0.3500),
(2022, 'single', 539900, NULL, 0.3700);

-- 2022 Married Filing Jointly
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2022, 'married_filing_jointly', 0, 20550, 0.1000),
(2022, 'married_filing_jointly', 20550, 83550, 0.1200),
(2022, 'married_filing_jointly', 83550, 178150, 0.2200),
(2022, 'married_filing_jointly', 178150, 340100, 0.2400),
(2022, 'married_filing_jointly', 340100, 431900, 0.3200),
(2022, 'married_filing_jointly', 431900, 647850, 0.3500),
(2022, 'married_filing_jointly', 647850, NULL, 0.3700);

-- 2023 Single
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2023, 'single', 0, 11000, 0.1000),
(2023, 'single', 11000, 44725, 0.1200),
(2023, 'single', 44725, 95375, 0.2200),
(2023, 'single', 95375, 182100, 0.2400),
(2023, 'single', 182100, 231250, 0.3200),
(2023, 'single', 231250, 578125, 0.3500),
(2023, 'single', 578125, NULL, 0.3700);

-- 2023 Married Filing Jointly
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2023, 'married_filing_jointly', 0, 22000, 0.1000),
(2023, 'married_filing_jointly', 22000, 89450, 0.1200),
(2023, 'married_filing_jointly', 89450, 190750, 0.2200),
(2023, 'married_filing_jointly', 190750, 364200, 0.2400),
(2023, 'married_filing_jointly', 364200, 462500, 0.3200),
(2023, 'married_filing_jointly', 462500, 693750, 0.3500),
(2023, 'married_filing_jointly', 693750, NULL, 0.3700);

-- 2024 Single
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2024, 'single', 0, 11600, 0.1000),
(2024, 'single', 11600, 47150, 0.1200),
(2024, 'single', 47150, 100525, 0.2200),
(2024, 'single', 100525, 191950, 0.2400),
(2024, 'single', 191950, 243725, 0.3200),
(2024, 'single', 243725, 609350, 0.3500),
(2024, 'single', 609350, NULL, 0.3700);

-- 2024 Married Filing Jointly
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2024, 'married_filing_jointly', 0, 23200, 0.1000),
(2024, 'married_filing_jointly', 23200, 94300, 0.1200),
(2024, 'married_filing_jointly', 94300, 201050, 0.2200),
(2024, 'married_filing_jointly', 201050, 383900, 0.2400),
(2024, 'married_filing_jointly', 383900, 487450, 0.3200),
(2024, 'married_filing_jointly', 487450, 731200, 0.3500),
(2024, 'married_filing_jointly', 731200, NULL, 0.3700);

-- 2025 Single
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2025, 'single', 0, 11925, 0.1000),
(2025, 'single', 11925, 48475, 0.1200),
(2025, 'single', 48475, 103350, 0.2200),
(2025, 'single', 103350, 197300, 0.2400),
(2025, 'single', 197300, 250525, 0.3200),
(2025, 'single', 250525, 626350, 0.3500),
(2025, 'single', 626350, NULL, 0.3700);

-- 2025 Married Filing Jointly
INSERT INTO tax_brackets (tax_year, filing_status, bracket_floor, bracket_ceiling, rate) VALUES
(2025, 'married_filing_jointly', 0, 23850, 0.1000),
(2025, 'married_filing_jointly', 23850, 96950, 0.1200),
(2025, 'married_filing_jointly', 96950, 206700, 0.2200),
(2025, 'married_filing_jointly', 206700, 394600, 0.2400),
(2025, 'married_filing_jointly', 394600, 501050, 0.3200),
(2025, 'married_filing_jointly', 501050, 751600, 0.3500),
(2025, 'married_filing_jointly', 751600, NULL, 0.3700);
