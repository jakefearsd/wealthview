-- V066: real historical annual returns per asset class, for the joint block bootstrap.
CREATE TABLE IF NOT EXISTS asset_class_returns (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    year         integer NOT NULL,
    asset_class  text NOT NULL,
    real_return  numeric(9,6) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_asset_class_returns_year_class UNIQUE (year, asset_class),
    CONSTRAINT chk_asset_class_returns_class
        CHECK (asset_class IN ('us_stock', 'intl_stock', 'bond', 'cash'))
);
