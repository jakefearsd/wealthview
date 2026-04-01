import client from './client';

export interface PriceSyncStatus {
    symbol: string;
    latest_date: string | null;
    source: string | null;
    stale: boolean;
}

export interface SymbolError {
    symbol: string;
    reason: string;
}

export interface FinnhubSyncResult {
    succeeded: number;
    total: number;
    failures: SymbolError[];
}

export interface YahooSyncResult {
    inserted: number;
    updated: number;
    failures: SymbolError[];
}

export interface YahooFetchRequest {
    symbols: string[];
    from_date: string;
    to_date: string;
}

export interface PriceEntry {
    symbol: string;
    date: string;
    close_price: number;
}

export interface CsvImportResult {
    imported: number;
    errors: string[];
}

export async function getPriceStatus(): Promise<PriceSyncStatus[]> {
    const { data } = await client.get<PriceSyncStatus[]>('/admin/prices/status');
    return data;
}

export async function syncFinnhub(): Promise<FinnhubSyncResult> {
    const { data } = await client.post<FinnhubSyncResult>('/admin/prices/sync');
    return data;
}

export async function syncYahoo(): Promise<YahooSyncResult> {
    const { data } = await client.post<YahooSyncResult>('/admin/prices/yahoo/sync');
    return data;
}

export async function fetchYahoo(request: YahooFetchRequest): Promise<PriceEntry[]> {
    const { data } = await client.post<PriceEntry[]>('/admin/prices/yahoo/fetch', request);
    return data;
}

export async function saveYahooPrices(prices: PriceEntry[]): Promise<void> {
    await client.post('/admin/prices/yahoo/save', { prices });
}

export async function uploadPriceCsv(file: File): Promise<CsvImportResult> {
    const formData = new FormData();
    formData.append('file', file);
    const { data } = await client.post<CsvImportResult>('/admin/prices/csv', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
    });
    return data;
}
