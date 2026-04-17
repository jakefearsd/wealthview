import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../api/adminSystem', () => ({
    getConfig: vi.fn(),
    setConfig: vi.fn(),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { getConfig, setConfig } from '../../api/adminSystem';
import SystemConfigSection from './SystemConfigSection';

const configs = [
    { key: 'finnhub.api-key', value: 'secret-value', updated_at: '2026-04-01T00:00:00Z' },
    { key: 'cors.allowed-origins', value: 'https://app.local', updated_at: '2026-04-01T00:00:00Z' },
    { key: 'zillow.scraper.enabled', value: 'true', updated_at: '2026-04-01T00:00:00Z' },
];

describe('SystemConfigSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(getConfig).mockResolvedValue(configs);
    });

    it('renders config keys grouped by section', async () => {
        render(<SystemConfigSection />);
        expect(await screen.findByText('API Keys')).toBeInTheDocument();
        expect(screen.getByText('Application Settings')).toBeInTheDocument();
        expect(screen.getByText('cors.allowed-origins')).toBeInTheDocument();
    });

    it('masks sensitive keys by default', async () => {
        render(<SystemConfigSection />);
        await screen.findByText('API Keys');
        expect(screen.queryByText('secret-value')).not.toBeInTheDocument();
    });

    it('allows editing a non-sensitive value', async () => {
        vi.mocked(setConfig).mockResolvedValue(undefined);
        render(<SystemConfigSection />);
        await screen.findByText('cors.allowed-origins');

        // cors.allowed-origins is the only populated non-sensitive, non-boolean config
        // so opening its edit mode by finding its value, then triggering from a sibling button.
        const corsValue = screen.getByText('https://app.local');
        // walk up to the row container and click the Edit button in that row
        const row = corsValue.closest('div[style*="display: flex"]');
        expect(row).not.toBeNull();
        const editBtn = row!.querySelector('button');
        // The first button in the value-row container is either "Show" (sensitive) or "Edit"
        // For non-sensitive values it is the Edit button.
        if (editBtn) fireEvent.click(editBtn);

        const input = screen.getByDisplayValue('https://app.local');
        fireEvent.change(input, { target: { value: 'https://new.local' } });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => {
            expect(setConfig).toHaveBeenCalledWith('cors.allowed-origins', 'https://new.local');
        });
    });
});
