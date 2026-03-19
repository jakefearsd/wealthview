-- Add composite indexes on property_expenses and property_income for
-- efficient date-range queries filtered by property_id.

CREATE INDEX IF NOT EXISTS idx_property_expenses_property_date
    ON property_expenses(property_id, date);

CREATE INDEX IF NOT EXISTS idx_property_income_property_date
    ON property_income(property_id, date);
