import {
    createAccountsApi,
    createDashboardApi,
    type AccountsApi,
    type DashboardApi,
} from '@wealthview/shared';
import type { AxiosInstance } from 'axios';

export interface DataApis {
    dashboardApi: DashboardApi;
    accountsApi: AccountsApi;
}

/**
 * Builds the data-API wrappers (dashboard, accounts) on top of the
 * already-authenticated axios client emitted by buildMobileApi(). Kept
 * as a separate factory so the auth bundle stays focused on auth and
 * the data APIs can grow without touching the auth wiring.
 */
export function buildDataApis(client: AxiosInstance): DataApis {
    return {
        dashboardApi: createDashboardApi(client),
        accountsApi: createAccountsApi(client),
    };
}
