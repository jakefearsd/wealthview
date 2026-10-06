import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/export', () => ({
    downloadJson: vi.fn(),
    downloadCsv: vi.fn(),
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { downloadJson, downloadCsv } from '../api/export';
import DataExportPage from './DataExportPage';

describe('DataExportPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders download buttons for JSON and all CSV entities', () => {
        render(<DataExportPage />);
        expect(screen.getByText('Download JSON')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'accounts' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'transactions' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'holdings' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'properties' })).toBeInTheDocument();
    });

    it('gives the Download JSON button a background that contrasts with its white text', () => {
        render(<DataExportPage />);

        expect(screen.getByText('Download JSON')).toHaveStyle({ background: '#1976d2', color: '#fff' });
    });

    it('triggers JSON download', async () => {
        vi.mocked(downloadJson).mockResolvedValue(undefined);
        render(<DataExportPage />);

        fireEvent.click(screen.getByText('Download JSON'));
        await waitFor(() => {
            expect(downloadJson).toHaveBeenCalled();
        });
    });

    it('triggers CSV download for accounts', async () => {
        vi.mocked(downloadCsv).mockResolvedValue(undefined);
        render(<DataExportPage />);

        fireEvent.click(screen.getByRole('button', { name: 'accounts' }));
        await waitFor(() => {
            expect(downloadCsv).toHaveBeenCalledWith('accounts');
        });
    });

    it('lists what the JSON export contains instead of claiming it has all your data', () => {
        render(<DataExportPage />);

        expect(screen.queryByText(/all your data/i)).not.toBeInTheDocument();
        expect(screen.getByText(/accounts, transactions, holdings and properties/i)).toBeInTheDocument();
        const omitted = screen.getByText(/Only those four tables are included/i);
        for (const item of ['prices', 'exchange rates', 'income sources', 'property expenses', 'valuations',
            'projection scenarios', 'spending profiles']) {
            expect(omitted).toHaveTextContent(item);
        }
    });
});
