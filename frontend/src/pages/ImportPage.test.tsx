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

vi.mock('../utils/format', () => ({
    formatDate: (v: string) => v.slice(0, 10),
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
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
    error_message: null,
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

    // === history ===

    it('shows an error with retry instead of an empty table when the history fails to load', () => {
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: 'history down', refetch } as any);
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

        expect(screen.getByText('history down')).toBeInTheDocument();
        expect(screen.queryByText('No import history')).not.toBeInTheDocument();
        expect(refetch).toHaveBeenCalled();
    });

    it('lists jobs newest first with the date as YYYY-MM-DD', () => {
        mockUseApiQuery.mockReturnValue({ data: [
            { ...job, id: 'old', source: 'older-src', created_at: '2026-01-01T00:00:00Z' },
            { ...job, id: 'new', source: 'newer-src', created_at: '2026-05-01T00:00:00Z' },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ], loading: false, error: null, refetch: vi.fn() } as any);
        renderPage();

        const rows = screen.getAllByRole('row').map((r) => r.textContent ?? '');
        expect(rows.findIndex((r) => r.includes('newer-src'))).toBeLessThan(rows.findIndex((r) => r.includes('older-src')));
        expect(screen.getByText('2026-05-01')).toBeInTheDocument();
    });

    it('shows the error message of a failed job', () => {
        mockUseApiQuery.mockReturnValue({ data: [
            { ...job, status: 'failed', error_message: 'Unrecognised header row' },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ], loading: false, error: null, refetch: vi.fn() } as any);
        renderPage();

        expect(screen.getByText('Unrecognised header row')).toBeInTheDocument();
    });

    // === upload feedback ===

    const chooseCsv = () => {
        const file = new File(['a,b,c\n1,2,3'], 'txns.csv', { type: 'text/csv' });
        const input = screen.getByLabelText('File to import') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [file] } });
        return file;
    };

    it('reports a clean import with a success toast', async () => {
        vi.mocked(importCsv).mockResolvedValue({ ...job, total_rows: 3, successful_rows: 3, failed_rows: 0 });
        renderPage();

        chooseCsv();
        fireEvent.click(screen.getByText('Upload'));

        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Imported: 3 successful, 0 failed'));
        expect(toastError).not.toHaveBeenCalled();
    });

    it('reports an import with failed rows as an error rather than a green success', async () => {
        vi.mocked(importCsv).mockResolvedValue({ ...job, total_rows: 120, successful_rows: 0, failed_rows: 120 });
        renderPage();

        chooseCsv();
        fireEvent.click(screen.getByText('Upload'));

        await waitFor(() => expect(toastError).toHaveBeenCalledWith('Imported: 0 successful, 120 failed'));
        expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('remounts the file input after a successful upload so the same file can be chosen again', async () => {
        vi.mocked(importCsv).mockResolvedValue({ ...job });
        renderPage();
        const before = screen.getByLabelText('File to import');
        chooseCsv();

        fireEvent.click(screen.getByText('Upload'));

        await waitFor(() => expect(screen.getByLabelText('File to import')).not.toBe(before));
        expect(screen.getByText('Upload').closest('button')).toBeDisabled();
    });

    it('drops the chosen file when the format changes so it is not posted to the other endpoint', () => {
        renderPage();
        chooseCsv();
        expect(screen.getByText('Upload').closest('button')).toBeEnabled();

        fireEvent.change(screen.getByLabelText('File format'), { target: { value: 'ofx' } });

        expect(screen.getByText('Upload').closest('button')).toBeDisabled();
    });

    it('marks the active import mode with aria-pressed', () => {
        renderPage();

        expect(screen.getByRole('button', { name: 'Transaction History' })).toHaveAttribute('aria-pressed', 'true');
        fireEvent.click(screen.getByRole('button', { name: 'Current Positions' }));
        expect(screen.getByRole('button', { name: 'Current Positions' })).toHaveAttribute('aria-pressed', 'true');
        expect(screen.getByRole('button', { name: 'Transaction History' })).toHaveAttribute('aria-pressed', 'false');
    });
});
