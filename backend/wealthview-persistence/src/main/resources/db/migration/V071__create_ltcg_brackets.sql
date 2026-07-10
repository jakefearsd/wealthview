-- V071: long-term capital gains brackets (0/15/20), stacked on ordinary income.
CREATE TABLE IF NOT EXISTS ltcg_brackets (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tax_year        integer NOT NULL,
    filing_status   text NOT NULL,
    rate            numeric(6,4) NOT NULL,
    bracket_floor   numeric(19,4) NOT NULL,
    bracket_ceiling numeric(19,4),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ltcg_brackets_year_status_floor UNIQUE (tax_year, filing_status, bracket_floor)
);
