-- Add composite index to support the tenant + symbol scoped transaction finder
-- (TransactionRepository.findByTenant_IdAndSymbol), used by the stock-split
-- holdings recompute path. The existing idx_transactions_tenant_id covers only
-- tenant_id, forcing a heap filter on symbol; the existing
-- idx_transactions_account_id_symbol is keyed on account_id, not tenant_id.
-- This composite index lets the new finder be served entirely from the index.
CREATE INDEX IF NOT EXISTS idx_transactions_tenant_id_symbol
    ON transactions (tenant_id, symbol);
