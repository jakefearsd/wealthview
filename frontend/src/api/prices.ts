import client from './client';
import type { Price, PriceRequest } from '../types/price';

export async function createPrice(request: PriceRequest): Promise<Price> {
    const { data } = await client.post<Price>('/prices', request);
    return data;
}

export async function getLatestPrice(symbol: string): Promise<Price> {
    const { data } = await client.get<Price>(`/prices/${symbol}/latest`);
    return data;
}
