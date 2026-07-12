-- V079: income-source ownership + survivor continuation for household modeling.
ALTER TABLE income_sources
    ADD COLUMN IF NOT EXISTS owner text NOT NULL DEFAULT 'primary';
ALTER TABLE income_sources
    ADD CONSTRAINT chk_income_sources_owner CHECK (owner IN ('primary','spouse'));
ALTER TABLE income_sources
    ADD COLUMN IF NOT EXISTS survivor_percent numeric(5,4) NOT NULL DEFAULT 1.0;
ALTER TABLE income_sources
    ADD CONSTRAINT chk_income_sources_survivor_percent CHECK (survivor_percent >= 0 AND survivor_percent <= 1);
