import { screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../api/import', () => ({
    importCsv: vi.fn(),
    importOfx: vi.fn(),
    importPositions: vi.fn(),
    listImportJobs: vi.fn(),
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { importCsv, importOfx, importPositions } from '../api/import';
import ImportPage from './ImportPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

const job = {
    id: 'j-1',
    created_at: '2026-04-10T00:00:00Z',
    source: 'fidelity',
    status: 'complete',
    total_rows: 10,
    successful_rows: 10,
    failed_rows: 0,
};

function setup() {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    mockUseApiQuery.mockReturnValue({ data: [job], loading: false, error: null, refetch: vi.fn() } as any);
}

function renderPage() {
    return renderWithRoute(<ImportPage />, {
        path: '/accounts/:id/import',
        entry: '/accounts/acc-1/import',
    });
}

describe('ImportPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        setup();
    });

    it('renders the transactions tab by default', () => {
        renderPage();
        expect(screen.getByRole('combobox')).toHaveValue('generic');
        expect(screen.getByText('Upload')).toBeInTheDocument();
    });

    it('shows import history', () => {
        renderPage();
        expect(screen.getByText('fidelity')).toBeInTheDocument();
        expect(screen.getByText('complete')).toBeInTheDocument();
    });

    it('switches to positions tab with a warning', () => {
        renderPage();
        fireEvent.click(screen.getByText('Current Positions'));
        expect(screen.getByText(/This cannot be undone/i)).toBeInTheDocument();
        expect(screen.getByText('Replace & Import')).toBeInTheDocument();
    });

    it('calls importCsv for fidelity format', async () => {
        vi.mocked(importCsv).mockResolvedValue({
            id: 'j2', created_at: '', source: 'fidelity', status: 'complete',
            total_rows: 1, successful_rows: 1, failed_rows: 0,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        renderPage();

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'fidelity' } });
        const file = new File(['a,b,c\n1,2,3'], 'txns.csv', { type: 'text/csv' });
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [file] } });
        fireEvent.click(screen.getByText('Upload'));

        await waitFor(() => {
            expect(importCsv).toHaveBeenCalledWith('acc-1', file, 'fidelity');
        });
    });

    it('calls importOfx when OFX format is selected', async () => {
        vi.mocked(importOfx).mockResolvedValue({
            id: 'j3', created_at: '', source: 'ofx', status: 'complete',
            total_rows: 1, successful_rows: 1, failed_rows: 0,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        renderPage();

        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'ofx' } });
        const file = new File(['OFXHEADER:100'], 'stmt.ofx', { type: 'application/x-ofx' });
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [file] } });
        fireEvent.click(screen.getByText('Upload'));

        await waitFor(() => {
            expect(importOfx).toHaveBeenCalledWith('acc-1', file);
        });
    });

    it('requires confirmation for position import', async () => {
        vi.spyOn(window, 'confirm').mockReturnValue(false);
        vi.mocked(importPositions).mockResolvedValue({} as never);
        renderPage();

        fireEvent.click(screen.getByText('Current Positions'));
        const file = new File(['a,b'], 'positions.csv', { type: 'text/csv' });
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [file] } });
        fireEvent.click(screen.getByText('Replace & Import'));

        expect(importPositions).not.toHaveBeenCalled();
    });
});
