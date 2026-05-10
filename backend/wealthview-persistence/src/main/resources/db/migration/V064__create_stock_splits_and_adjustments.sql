-- Stock split detection + retroactive adjustment.
--
-- stock_splits: global source of truth (no tenant_id). A split is a market
-- event that applies to every tenant holding the symbol.
--
-- stock_split_adjustments: per-tenant audit/undo trail. One split row triggers
-- many adjustment rows (one per affected transaction field and optionally per
-- affected price row). Adjustments to the global prices table are recorded
-- with tenant_id = (any tenant that triggered the apply) — first writer wins;
-- per-tenant price recording would cause duplicate-restore on unapply.

CREATE TABLE stock_splits (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol          text NOT NULL,
    effective_date  date NOT NULL,
    numerator       integer NOT NULL CHECK (numerator > 0),
    denominator     integer NOT NULL CHECK (denominator > 0),
    source          text NOT NULL CHECK (source IN ('finnhub', 'yahoo', 'manual', 'backfill')),
    applied_at      timestamptz NOT NULL DEFAULT now(),
    notes           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_splits_symbol_date UNIQUE (symbol, effective_date)
);

CREATE INDEX idx_stock_splits_symbol_date ON stock_splits (symbol, effective_date);

CREATE TABLE stock_split_adjustments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    split_id        uuid NOT NULL REFERENCES stock_splits(id) ON DELETE CASCADE,
    tenant_id       uuid NOT NULL REFERENCES tenants(id),
    target_table    text NOT NULL CHECK (target_table IN ('transactions', 'prices', 'holdings')),
    target_row_id   uuid NOT NULL,
    field_name      text NOT NULL,
    old_value       numeric(19, 8) NOT NULL,
    new_value       numeric(19, 8) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_split_adjustments_split ON stock_split_adjustments (split_id);
CREATE INDEX idx_split_adjustments_tenant ON stock_split_adjustments (tenant_id);
