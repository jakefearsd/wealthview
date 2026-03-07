import client from './client';
import type { CombinedPortfolioHistory, DashboardSummary } from '../types/dashboard';

export async function getDashboardSummary(): Promise<DashboardSummary> {
    const { data } = await client.get<DashboardSummary>('/dashboard/summary');
    return data;
}

export async function getCombinedPortfolioHistory(years = 2): Promise<CombinedPortfolioHistory> {
    const { data } = await client.get<CombinedPortfolioHistory>(
        '/dashboard/portfolio-history', { params: { years } });
    return data;
}
