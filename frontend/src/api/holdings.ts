import client from './client';
import type { Holding, HoldingRequest } from '../types/holding';

export async function listHoldings(accountId: string): Promise<Holding[]> {
    const { data } = await client.get<Holding[]>(`/accounts/${accountId}/holdings`);
    return data;
}

export async function createHolding(request: HoldingRequest): Promise<Holding> {
    const { data } = await client.post<Holding>('/holdings', request);
    return data;
}

export async function updateHolding(id: string, request: HoldingRequest): Promise<Holding> {
    const { data } = await client.put<Holding>(`/holdings/${id}`, request);
    return data;
}
