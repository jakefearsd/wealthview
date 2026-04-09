import client from './client';
import type { Account, AccountRequest } from '../types/account';
import type { PageResponse } from '../types/common';
import type { PortfolioHistory } from '../types/portfolio';

export async function listAccounts(page = 0, size = 25): Promise<PageResponse<Account>> {
    const { data } = await client.get<PageResponse<Account>>('/accounts', {
        params: { page, size },
    });
    return data;
}

export async function getAccount(id: string): Promise<Account> {
    const { data } = await client.get<Account>(`/accounts/${id}`);
    return data;
}

export async function createAccount(request: AccountRequest): Promise<Account> {
    const { data } = await client.post<Account>('/accounts', request);
    return data;
}

export async function updateAccount(id: string, request: AccountRequest): Promise<Account> {
    const { data } = await client.put<Account>(`/accounts/${id}`, request);
    return data;
}

export async function deleteAccount(id: string): Promise<void> {
    await client.delete(`/accounts/${id}`);
}

export async function getTheoreticalHistory(accountId: string, months = 24): Promise<PortfolioHistory> {
    const { data } = await client.get<PortfolioHistory>(`/accounts/${accountId}/theoretical-history`, {
        params: { months },
    });
    return data;
}
