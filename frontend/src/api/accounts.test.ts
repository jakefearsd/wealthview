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
    cash_balance: 1000,
    holdings: [],
} as unknown as Account;

const REQUEST: AccountRequest = {
    name: 'Brokerage',
    type: 'taxable',
    institution: 'Vanguard',
    currency: 'USD',
} as unknown as AccountRequest;

describe('api/accounts', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
        mocks.del.mockReset();
    });

    it('listAccounts forwards page/size and returns the paged body', async () => {
        const page: PageResponse<Account> = {
            data: [ACCOUNT],
            page: 1,
            size: 50,
            total_elements: 1,
            total_pages: 1,
        } as unknown as PageResponse<Account>;
        mocks.get.mockResolvedValue({ data: page });

        const result = await listAccounts(1, 50);

        expect(result).toEqual(page);
        expect(mocks.get).toHaveBeenCalledWith('/accounts', { params: { page: 1, size: 50 } });
    });

    it('listAccounts uses the documented defaults when page/size are omitted', async () => {
        mocks.get.mockResolvedValue({
            data: { data: [], page: 0, size: 25, total_elements: 0, total_pages: 0 },
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
        const history = { points: [] } as unknown as PortfolioHistory;
        mocks.get.mockResolvedValue({ data: history });

        const result = await getTheoreticalHistory('a1');

        expect(result).toEqual(history);
        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/theoretical-history', {
            params: { months: 24 },
        });
    });

    it('getTheoreticalHistory honors a custom months value', async () => {
        mocks.get.mockResolvedValue({ data: { points: [] } });

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
