import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../api/audit', () => ({
    getAuditLogs: vi.fn(),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

import { getAuditLogs } from '../../api/audit';
import AuditLogSection from './AuditLogSection';

const entry = {
    id: 'a1',
    tenant_id: 't-1',
    user_id: 'u-1',
    action: 'CREATE',
    entity_type: 'account',
    entity_id: 'acc-1',
    details: null,
    created_at: '2026-04-10T00:00:00Z',
};

describe('AuditLogSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(getAuditLogs).mockResolvedValue({
            data: [entry],
            total: 1,
            page: 0,
            page_size: 50,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
    });

    it('loads and displays entries', async () => {
        render(<AuditLogSection />);
        expect(await screen.findByText('CREATE')).toBeInTheDocument();
        // "account" appears both in the filter dropdown and in the row — verify at least one
        expect(screen.getAllByText('account').length).toBeGreaterThan(0);
    });

    it('refetches with filter when entity type changes', async () => {
        render(<AuditLogSection />);
        await screen.findByText('CREATE');

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'account' } });

        await waitFor(() => {
            expect(getAuditLogs).toHaveBeenLastCalledWith(0, 50, 'account');
        });
    });
});
