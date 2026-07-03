import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { AxiosInstance } from 'axios';
import { createAccountsApi } from './accounts';
import type { AccountResponse, PageResponse } from './types';

const SAMPLE_ACCOUNT: AccountResponse = {
    id: '11111111-1111-1111-1111-111111111111',
    name: 'Fidelity Brokerage',
    type: 'brokerage',
    institution: 'Fidelity',
    currency: 'USD',
    balance: 456789.0,
    created_at: '2026-01-01T00:00:00Z',
};

const SAMPLE_PAGE: PageResponse<AccountResponse> = {
    data: [SAMPLE_ACCOUNT],
    page: 0,
    size: 25,
    total: 1,
};

function makeFakeClient() {
    const get = vi.fn();
    return {
        client: { get } as unknown as AxiosInstance,
        get,
    };
}

describe('createAccountsApi', () => {
    let get: ReturnType<typeof vi.fn>;
    let api: ReturnType<typeof createAccountsApi>;

    beforeEach(() => {
        const f = makeFakeClient();
        get = f.get;
        api = createAccountsApi(f.client);
    });

    describe('list', () => {
        it('GETs /accounts with no params when none are supplied', async () => {
            get.mockResolvedValue({ data: SAMPLE_PAGE });

            const page = await api.list();

            expect(get).toHaveBeenCalledWith('/accounts', { params: undefined });
            expect(page).toEqual(SAMPLE_PAGE);
        });

        it('forwards page and size as query params when supplied', async () => {
            get.mockResolvedValue({ data: SAMPLE_PAGE });

            await api.list({ page: 2, size: 50 });

            expect(get).toHaveBeenCalledWith('/accounts', {
                params: { page: 2, size: 50 },
            });
        });

        it('preserves the response shape verbatim', async () => {
            get.mockResolvedValue({ data: SAMPLE_PAGE });

            const page = await api.list();

            expect(page.data).toHaveLength(1);
            expect(page.data[0].balance).toBe(456789.0);
            expect(page.total).toBe(1);
        });

        it('propagates errors from the client', async () => {
            get.mockRejectedValue(new Error('boom'));

            await expect(api.list()).rejects.toThrow('boom');
        });
    });

    describe('get', () => {
        it('GETs /accounts/:id and returns the single account', async () => {
            get.mockResolvedValue({ data: SAMPLE_ACCOUNT });

            const account = await api.get(SAMPLE_ACCOUNT.id);

            expect(get).toHaveBeenCalledWith(`/accounts/${SAMPLE_ACCOUNT.id}`);
            expect(account).toEqual(SAMPLE_ACCOUNT);
        });
    });
});
