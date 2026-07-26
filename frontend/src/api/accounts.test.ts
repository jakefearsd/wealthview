import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    post: vi.mocked(client.post),
    put: vi.mocked(client.put),
    del: vi.mocked(client.delete),
};

import {
    listAccounts,
    getAccount,
    createAccount,
    updateAccount,
    deleteAccount,
    getTheoreticalHistory,
} from './accounts';
import type { Account, AccountRequest } from '../types/account';
import type { PageResponse } from '../types/common';
import type { PortfolioHistory } from '../types/portfolio';

const ACCOUNT: Account = {
    id: 'a1',
    name: 'Brokerage',
    type: 'taxable',
    institution: 'Vanguard',
    currency: 'USD',
    balance: 1000,
    created_at: '2024-01-01T00:00:00Z',
};

const REQUEST: AccountRequest = {
    name: 'Brokerage',
    type: 'taxable',
    institution: 'Vanguard',
    currency: 'USD',
};

describe('api/accounts', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('listAccounts forwards page/size and returns the paged body', async () => {
        const page: PageResponse<Account> = {
            data: [ACCOUNT],
            page: 1,
            size: 50,
            total: 1,
        };
        mocks.get.mockResolvedValue({ data: page });

        const result = await listAccounts(1, 50);

        expect(result).toEqual(page);
        expect(mocks.get).toHaveBeenCalledWith('/accounts', { params: { page: 1, size: 50 } });
    });

    it('listAccounts uses the documented defaults when page/size are omitted', async () => {
        mocks.get.mockResolvedValue({
            data: { data: [], page: 0, size: 25, total: 0 },
        });

        await listAccounts();

        expect(mocks.get).toHaveBeenCalledWith('/accounts', { params: { page: 0, size: 25 } });
    });

    it('getAccount embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: ACCOUNT });

        const result = await getAccount('abc-123');

        expect(result).toEqual(ACCOUNT);
        expect(mocks.get).toHaveBeenCalledWith('/accounts/abc-123');
    });

    it('createAccount POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: ACCOUNT });

        const result = await createAccount(REQUEST);

        expect(result).toEqual(ACCOUNT);
        expect(mocks.post).toHaveBeenCalledWith('/accounts', REQUEST);
    });

    it('updateAccount PUTs the request body to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: ACCOUNT });

        const result = await updateAccount('abc-123', REQUEST);

        expect(result).toEqual(ACCOUNT);
        expect(mocks.put).toHaveBeenCalledWith('/accounts/abc-123', REQUEST);
    });

    it('deleteAccount issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteAccount('abc-123');

        expect(mocks.del).toHaveBeenCalledWith('/accounts/abc-123');
    });

    it('getTheoreticalHistory passes the months query param (default 24)', async () => {
        const history: PortfolioHistory = {
            account_id: 'a1',
            data_points: [],
            symbols: [],
            weeks: 0,
            has_money_market_holdings: false,
            money_market_total: null,
        };
        mocks.get.mockResolvedValue({ data: history });

        const result = await getTheoreticalHistory('a1');

        expect(result).toEqual(history);
        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/theoretical-history', {
            params: { months: 24 },
        });
    });

    it('getTheoreticalHistory honors a custom months value', async () => {
        mocks.get.mockResolvedValue({ data: { account_id: 'a1', data_points: [] } });

        await getTheoreticalHistory('a1', 60);

        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/theoretical-history', {
            params: { months: 60 },
        });
    });

    it('propagates server errors instead of swallowing them', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(getAccount('a1')).rejects.toThrow('500');
    });
});
