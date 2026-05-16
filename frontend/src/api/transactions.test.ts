import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    del: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        post: mocks.post,
        put: mocks.put,
        delete: mocks.del,
    },
}));

import {
    listTransactions,
    createTransaction,
    updateTransaction,
    deleteTransaction,
} from './transactions';
import type { Transaction, TransactionRequest } from '../types/transaction';
import type { PageResponse } from '../types/common';

const TXN: Transaction = {
    id: 't1',
    account_id: 'a1',
    date: '2026-01-02',
    type: 'buy',
    symbol: 'AAPL',
    quantity: 10,
    amount: 1500,
    created_at: '2026-01-02T00:00:00Z',
};

const REQUEST: TransactionRequest = {
    date: '2026-01-02',
    type: 'buy',
    symbol: 'AAPL',
    quantity: 10,
    amount: 1500,
};

describe('api/transactions', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
        mocks.del.mockReset();
    });

    it('listTransactions forwards page/size and account-scoped path', async () => {
        const page: PageResponse<Transaction> = { data: [TXN], page: 0, size: 25, total: 1 };
        mocks.get.mockResolvedValue({ data: page });

        const result = await listTransactions('a1');

        expect(result).toEqual(page);
        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/transactions', {
            params: { page: 0, size: 25 },
        });
    });

    it('listTransactions includes the symbol param only when provided', async () => {
        mocks.get.mockResolvedValue({ data: { data: [], page: 1, size: 10, total: 0 } });

        await listTransactions('a1', 1, 10, 'MSFT');

        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/transactions', {
            params: { page: 1, size: 10, symbol: 'MSFT' },
        });
    });

    it('createTransaction POSTs to the account-scoped path', async () => {
        mocks.post.mockResolvedValue({ data: TXN });

        const result = await createTransaction('a1', REQUEST);

        expect(result).toEqual(TXN);
        expect(mocks.post).toHaveBeenCalledWith('/accounts/a1/transactions', REQUEST);
    });

    it('updateTransaction PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: TXN });

        const result = await updateTransaction('t1', REQUEST);

        expect(result).toEqual(TXN);
        expect(mocks.put).toHaveBeenCalledWith('/transactions/t1', REQUEST);
    });

    it('deleteTransaction issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteTransaction('t1');

        expect(mocks.del).toHaveBeenCalledWith('/transactions/t1');
    });

    it('propagates server errors', async () => {
        mocks.post.mockRejectedValue(new Error('400'));

        await expect(createTransaction('a1', REQUEST)).rejects.toThrow('400');
    });
});
