import client from './client';
import type { ExchangeRate, ExchangeRateRequest } from '../types/exchangeRate';

export async function listExchangeRates(): Promise<ExchangeRate[]> {
    const { data } = await client.get<ExchangeRate[]>('/exchange-rates');
    return data;
}

export async function createExchangeRate(request: ExchangeRateRequest): Promise<ExchangeRate> {
    const { data } = await client.post<ExchangeRate>('/exchange-rates', request);
    return data;
}

export async function updateExchangeRate(
    currencyCode: string,
    request: ExchangeRateRequest
): Promise<ExchangeRate> {
    const { data } = await client.put<ExchangeRate>(`/exchange-rates/${currencyCode}`, request);
    return data;
}

export async function deleteExchangeRate(currencyCode: string): Promise<void> {
    await client.delete(`/exchange-rates/${currencyCode}`);
}
