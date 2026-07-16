-- V080: sex-specific SSA period-life mortality rates for the stochastic-mortality Monte Carlo
-- (spec 2026-07-15). qx = P(death within the year | alive at exact age). Seeded by R__seed_mortality_rates.
CREATE TABLE IF NOT EXISTS mortality_rates (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sex        text NOT NULL,
    age        integer NOT NULL,
    qx         numeric(9,8) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_mortality_rates_sex_age UNIQUE (sex, age),
    CONSTRAINT chk_mortality_rates_sex CHECK (sex IN ('male','female')),
    CONSTRAINT chk_mortality_rates_qx CHECK (qx >= 0 AND qx <= 1)
);
