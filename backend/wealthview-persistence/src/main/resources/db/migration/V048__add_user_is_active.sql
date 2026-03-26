-- Add is_active flag to users table to support disabling accounts without deletion
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active boolean NOT NULL DEFAULT true;
