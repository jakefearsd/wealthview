-- Convert annual_interest_rate from percentage (6.5 = 6.5%) to decimal (0.065 = 6.5%)
-- to align with all other rate columns in the database
UPDATE properties SET annual_interest_rate = annual_interest_rate / 100
WHERE annual_interest_rate IS NOT NULL AND annual_interest_rate != 0;
