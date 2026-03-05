import client from './client';
import type { DashboardSummary } from '../types/dashboard';

export async function getDashboardSummary(): Promise<DashboardSummary> {
    const { data } = await client.get<DashboardSummary>('/dashboard/summary');
    return data;
}
