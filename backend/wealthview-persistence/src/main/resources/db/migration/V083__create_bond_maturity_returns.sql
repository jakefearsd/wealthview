-- V083: real historical annual returns for the bond tent's short, intermediate and long Treasury
-- sleeves (2-, 5- and 25-year constant maturity). The ultrashort sleeve is not stored: it reuses
-- asset_class_returns.cash, so the check constraint deliberately rejects it.
CREATE TABLE IF NOT EXISTS bond_maturity_returns (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    year         integer NOT NULL,
    maturity     text NOT NULL,
    real_return  numeric(9,6) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_bond_maturity_returns_year_maturity UNIQUE (year, maturity),
    CONSTRAINT chk_bond_maturity_returns_maturity
        CHECK (maturity IN ('short', 'intermediate', 'long'))
);
