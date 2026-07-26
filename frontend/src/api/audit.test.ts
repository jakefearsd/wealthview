import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
};

import { getAuditLogs } from './audit';
import type { AuditLogEntry } from '../types/audit';
import type { PageResponse } from '../types/common';

const ENTRY: AuditLogEntry = {
    id: 'al1',
    tenant_id: 't1',
    user_id: 'u1',
    action: 'CREATE',
    entity_type: 'account',
    entity_id: 'a1',
    details: null,
    created_at: '2026-01-01T00:00:00Z',
};

describe('api/audit', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('getAuditLogs uses the default page/size params', async () => {
        const page: PageResponse<AuditLogEntry> = { data: [ENTRY], page: 0, size: 50, total: 1 };
        mocks.get.mockResolvedValue({ data: page });

        const result = await getAuditLogs();

        expect(result).toEqual(page);
        expect(mocks.get).toHaveBeenCalledWith('/audit-log', { params: { page: 0, size: 50 } });
    });

    it('getAuditLogs forwards custom page/size values', async () => {
        mocks.get.mockResolvedValue({ data: { data: [], page: 2, size: 10, total: 0 } });

        await getAuditLogs(2, 10);

        expect(mocks.get).toHaveBeenCalledWith('/audit-log', { params: { page: 2, size: 10 } });
    });

    it('getAuditLogs includes entity_type only when supplied', async () => {
        mocks.get.mockResolvedValue({ data: { data: [ENTRY], page: 0, size: 50, total: 1 } });

        await getAuditLogs(0, 50, 'account');

        expect(mocks.get).toHaveBeenCalledWith('/audit-log', {
            params: { page: 0, size: 50, entity_type: 'account' },
        });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('403'));

        await expect(getAuditLogs()).rejects.toThrow('403');
    });
});
