import client from './client';
import type { IncomeSource, CreateIncomeSourceRequest, UpdateIncomeSourceRequest } from '../types/projection';

export async function listIncomeSources(): Promise<IncomeSource[]> {
    const { data } = await client.get<IncomeSource[]>('/income-sources');
    return data;
}

export async function getIncomeSource(id: string): Promise<IncomeSource> {
    const { data } = await client.get<IncomeSource>(`/income-sources/${id}`);
    return data;
}

export async function createIncomeSource(request: CreateIncomeSourceRequest): Promise<IncomeSource> {
    const { data } = await client.post<IncomeSource>('/income-sources', request);
    return data;
}

export async function updateIncomeSource(id: string, request: UpdateIncomeSourceRequest): Promise<IncomeSource> {
    const { data } = await client.put<IncomeSource>(`/income-sources/${id}`, request);
    return data;
}

export async function deleteIncomeSource(id: string): Promise<void> {
    await client.delete(`/income-sources/${id}`);
}
