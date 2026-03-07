-- Add is_active flag to tenants for admin panel enable/disable
ALTER TABLE tenants ADD COLUMN is_active boolean NOT NULL DEFAULT true;
