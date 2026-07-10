-- V067: global symbol -> asset class seed map used by the security classifier.
CREATE TABLE IF NOT EXISTS security_asset_class (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol      text NOT NULL,
    asset_class text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_security_asset_class_symbol UNIQUE (symbol),
    CONSTRAINT chk_security_asset_class_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
