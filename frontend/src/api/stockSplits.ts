import client from './client';

export interface StockSplit {
    id: string;
    symbol: string;
    effective_date: string;
    numerator: number;
    denominator: number;
    source: 'finnhub' | 'yahoo' | 'manual' | 'backfill';
    applied_at: string;
    notes: string | null;
}

export interface CreateSplitRequest {
    symbol: string;
    effective_date: string;
    numerator: number;
    denominator: number;
}

export interface SplitSyncResult {
    symbols_scanned: number;
    splits_discovered: number;
    splits_applied: number;
    failed_symbols: string[];
}

export async function listStockSplits(opts?: {
    symbol?: string;
    from?: string;
    to?: string;
}): Promise<StockSplit[]> {
    const params = new URLSearchParams();
    if (opts?.symbol) params.set('symbol', opts.symbol);
    if (opts?.from) params.set('from', opts.from);
    if (opts?.to) params.set('to', opts.to);
    const qs = params.toString();
    const { data } = await client.get<StockSplit[]>(`/stock-splits${qs ? `?${qs}` : ''}`);
    return data;
}

export async function createStockSplit(req: CreateSplitRequest): Promise<StockSplit> {
    const { data } = await client.post<StockSplit>('/admin/stock-splits', req);
    return data;
}

export async function unapplyStockSplit(id: string): Promise<void> {
    await client.delete(`/admin/stock-splits/${id}`);
}

export async function syncStockSplits(): Promise<SplitSyncResult> {
    const { data } = await client.post<SplitSyncResult>('/admin/stock-splits/sync');
    return data;
}
