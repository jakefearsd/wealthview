import type { AxiosInstance } from 'axios';
import type { DashboardSummaryResponse } from './types';

export interface DashboardApi {
    /** Fetch the top-level dashboard summary (net worth, totals, accounts, allocation). */
    getSummary(): Promise<DashboardSummaryResponse>;
}

/**
 * Builds typed wrappers around `/api/v1/dashboard/*`. Keep this thin —
 * the API client supplied by the caller carries auth, retries, and base
 * URL; this layer only knows about routes and response shapes.
 */
export function createDashboardApi(client: AxiosInstance): DashboardApi {
    return {
        async getSummary() {
            const { data } = await client.get<DashboardSummaryResponse>('/dashboard/summary');
            return data;
        },
    };
}
