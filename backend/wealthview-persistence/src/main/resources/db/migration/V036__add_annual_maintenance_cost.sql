-- Add expected annual maintenance cost to properties
ALTER TABLE properties ADD COLUMN annual_maintenance_cost numeric(19,4);
