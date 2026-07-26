import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    put: vi.mocked(client.put),
};

import {
    getNotificationPreferences,
    updateNotificationPreferences,
    type NotificationPreference,
} from './notifications';

const PREFS: NotificationPreference[] = [
    { notification_type: 'price_alert', enabled: true },
    { notification_type: 'import_complete', enabled: false },
];

describe('api/notifications', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('getNotificationPreferences returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: PREFS });

        const result = await getNotificationPreferences();

        expect(result).toEqual(PREFS);
        expect(mocks.get).toHaveBeenCalledWith('/notifications/preferences');
    });

    it('updateNotificationPreferences PUTs the wrapped preferences', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await updateNotificationPreferences(PREFS);

        expect(mocks.put).toHaveBeenCalledWith('/notifications/preferences', {
            preferences: PREFS,
        });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(getNotificationPreferences()).rejects.toThrow('500');
    });
});
