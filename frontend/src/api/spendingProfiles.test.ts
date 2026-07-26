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
    listSpendingProfiles,
    getSpendingProfile,
    createSpendingProfile,
    updateSpendingProfile,
    deleteSpendingProfile,
} from './spendingProfiles';
import type { SpendingProfile, CreateSpendingProfileRequest } from '../types/projection';

const PROFILE: SpendingProfile = {
    id: 'sp1',
    name: 'Comfortable',
    essential_expenses: 40000,
    discretionary_expenses: 20000,
    spending_tiers: [],
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
};

const REQUEST: CreateSpendingProfileRequest = {
    name: 'Comfortable',
    essential_expenses: 40000,
    discretionary_expenses: 20000,
    spending_tiers: [],
};

describe('api/spendingProfiles', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('listSpendingProfiles returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [PROFILE] });

        const result = await listSpendingProfiles();

        expect(result).toEqual([PROFILE]);
        expect(mocks.get).toHaveBeenCalledWith('/spending-profiles');
    });

    it('getSpendingProfile embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: PROFILE });

        const result = await getSpendingProfile('sp1');

        expect(result).toEqual(PROFILE);
        expect(mocks.get).toHaveBeenCalledWith('/spending-profiles/sp1');
    });

    it('createSpendingProfile POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: PROFILE });

        const result = await createSpendingProfile(REQUEST);

        expect(result).toEqual(PROFILE);
        expect(mocks.post).toHaveBeenCalledWith('/spending-profiles', REQUEST);
    });

    it('updateSpendingProfile PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: PROFILE });

        const result = await updateSpendingProfile('sp1', REQUEST);

        expect(result).toEqual(PROFILE);
        expect(mocks.put).toHaveBeenCalledWith('/spending-profiles/sp1', REQUEST);
    });

    it('deleteSpendingProfile issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteSpendingProfile('sp1');

        expect(mocks.del).toHaveBeenCalledWith('/spending-profiles/sp1');
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(getSpendingProfile('sp1')).rejects.toThrow('500');
    });
});
