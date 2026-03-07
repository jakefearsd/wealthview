import client from './client';
import type { Transaction, TransactionRequest } from '../types/transaction';
import type { PageResponse } from '../types/common';

export async function listTransactions(
    accountId: string,
    page = 0,
    size = 25,
    symbol?: string
): Promise<PageResponse<Transaction>> {
    const params: Record<string, string | number> = { page, size };
    if (symbol) params.symbol = symbol;
    const { data } = await client.get<PageResponse<Transaction>>(
        `/accounts/${accountId}/transactions`,
        { params }
    );
    return data;
}

export async function createTransaction(
    accountId: string,
    request: TransactionRequest
): Promise<Transaction> {
    const { data } = await client.post<Transaction>(
        `/accounts/${accountId}/transactions`,
        request
    );
    return data;
}

export async function updateTransaction(
    id: string,
    request: TransactionRequest
): Promise<Transaction> {
    const { data } = await client.put<Transaction>(`/transactions/${id}`, request);
    return data;
}

export async function deleteTransaction(id: string): Promise<void> {
    await client.delete(`/transactions/${id}`);
}
