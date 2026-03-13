-- Add appreciation rate, property tax, and insurance cost to properties
-- These fields support projection engine auto-population and property financial assumptions display
ALTER TABLE properties ADD COLUMN annual_appreciation_rate numeric(7,5);
ALTER TABLE properties ADD COLUMN annual_property_tax numeric(19,4);
ALTER TABLE properties ADD COLUMN annual_insurance_cost numeric(19,4);
