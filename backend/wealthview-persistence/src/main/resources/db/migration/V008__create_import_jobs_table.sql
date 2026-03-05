-- Import jobs table for tracking CSV/OFX imports

CREATE TABLE import_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    account_id uuid NOT NULL REFERENCES accounts(id),
    source text NOT NULL CHECK (source IN ('csv', 'ofx', 'manual')),
    status text NOT NULL CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    total_rows integer NOT NULL DEFAULT 0,
    successful_rows integer NOT NULL DEFAULT 0,
    failed_rows integer NOT NULL DEFAULT 0,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_jobs_tenant_id ON import_jobs(tenant_id);
