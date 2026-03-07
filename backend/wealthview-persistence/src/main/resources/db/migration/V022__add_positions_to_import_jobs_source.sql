-- Allow 'positions' as a valid import source
ALTER TABLE import_jobs DROP CONSTRAINT IF EXISTS import_jobs_source_check;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_source_check CHECK (source IN ('csv', 'ofx', 'manual', 'positions'));
