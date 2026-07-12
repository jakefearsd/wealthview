-- V075: IRMAA (Income-Related Monthly Adjustment Amount) surcharge tiers. Each row is a
-- (tax_year, filing_status) MAGI bracket carrying the ADDITIONAL monthly Part B amount over the
-- standard premium and the monthly Part D surcharge for that bracket -- pre-netted against the
-- standard premium so the engine never needs a separate "standard Part B premium" lookup. The
-- first (below-threshold) bracket per (tax_year, filing_status) is seeded with zero/zero.
CREATE TABLE IF NOT EXISTS irmaa_tiers (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_year          integer NOT NULL,
    filing_status     text NOT NULL,
    magi_floor        numeric(19,4) NOT NULL,
    magi_ceiling      numeric(19,4),
    part_b_surcharge  numeric(19,4) NOT NULL,
    part_d_surcharge  numeric(19,4) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_irmaa_tiers_year_status_floor UNIQUE (tax_year, filing_status, magi_floor)
);
