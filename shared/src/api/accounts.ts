import type { AxiosInstance } from 'axios';
import type { AccountResponse, AccountsListParams, PageResponse } from './types';

export interface AccountsApi {
    /** List accounts for the current tenant. Pagination defaults match the backend (page=0, size=25). */
    list(params?: AccountsListParams): Promise<PageResponse<AccountResponse>>;
    /** Fetch a single account by id. Throws on 404 (caller's job to handle). */
    get(id: string): Promise<AccountResponse>;
}

/**
 * Builds typed wrappers around `/api/v1/accounts`. The mobile portfolio
 * screen calls `list()` with the default page size (25) — most users have
 * fewer than that. Pagination is exposed for completeness but the mobile
 * UI does not currently expose a pager.
 */
export function createAccountsApi(client: AxiosInstance): AccountsApi {
    return {
        async list(params?: AccountsListParams) {
            const { data } = await client.get<PageResponse<AccountResponse>>('/accounts', {
                params,
            });
            return data;
        },
        async get(id: string) {
            const { data } = await client.get<AccountResponse>(`/accounts/${id}`);
            return data;
        },
    };
}
