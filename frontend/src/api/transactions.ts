import client from './client';
import type { Transaction, TransactionRequest } from '../types/transaction';
import type { PageResponse } from '../types/common';

export async function listTransactions(
    accountId: string,
    page = 0,
    size = 25
): Promise<PageResponse<Transaction>> {
    const { data } = await client.get<PageResponse<Transaction>>(
        `/accounts/${accountId}/transactions`,
        { params: { page, size } }
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
