-- V068: per-tenant reclassification overrides layered on top of security_asset_class.
CREATE TABLE IF NOT EXISTS security_class_override (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid NOT NULL,
    symbol      text NOT NULL,
    asset_class text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_security_class_override_tenant_symbol UNIQUE (tenant_id, symbol),
    CONSTRAINT chk_security_class_override_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
