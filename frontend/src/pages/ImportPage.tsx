import { useState, type ChangeEvent } from 'react';
import toast from 'react-hot-toast';
import { useParams, Link } from 'react-router';
import { importCsv, importOfx, importPositions, listImportJobs } from '../api/import';
import { useApiQuery } from '../hooks/useApiQuery';
import { useApiMutation } from '../hooks/useApiMutation';
import Button from '../components/Button';
import LoadingState from '../components/LoadingState';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import { formatDate } from '../utils/format';
import type { ImportJob } from '../types/import';
import { tableStyle, thStyle, tdStyle, trHoverStyle } from '../utils/styles';

type TabType = 'transactions' | 'positions';

export default function ImportPage() {
    const { id: accountId } = useParams<{ id: string }>();
    const [activeTab, setActiveTab] = useState<TabType>('transactions');
    const [file, setFile] = useState<File | null>(null);
    const [txnFormat, setTxnFormat] = useState('generic');
    const [posFormat, setPosFormat] = useState('fidelityPositions');
    // Bumping the key remounts the uncontrolled file input, which is the only way to clear its chosen file.
    const [fileInputKey, setFileInputKey] = useState(0);
    const { data: jobs, loading, error, refetch } = useApiQuery(listImportJobs);
    const sortedJobs = [...(jobs ?? [])].sort((a, b) => b.created_at.localeCompare(a.created_at));

    function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
        setFile(e.target.files?.[0] || null);
    }

    function clearFile() {
        setFile(null);
        setFileInputKey((k) => k + 1);
    }

    function reportImportResult(result: ImportJob) {
        const summary = `Imported: ${result.successful_rows} successful, ${result.failed_rows} failed`;
        if (result.failed_rows > 0) {
            toast.error(summary);
        } else {
            toast.success(summary);
        }
    }

    const uploadTxnMutation = useApiMutation(
        () => (
            txnFormat === 'ofx'
                ? importOfx(accountId!, file!)
                : importCsv(accountId!, file!, txnFormat === 'generic' ? undefined : txnFormat)
        ),
        {
            onSuccess: (result) => {
                reportImportResult(result);
                clearFile();
                refetch();
            },
        },
    );

    function handleUploadTransactions() {
        if (!file || !accountId) return;
        void uploadTxnMutation.mutate(undefined);
    }

    const uploadPosMutation = useApiMutation(
        () => importPositions(accountId!, file!, posFormat),
        {
            onSuccess: (result) => {
                reportImportResult(result);
                clearFile();
                refetch();
            },
        },
    );
    const uploading = uploadTxnMutation.loading || uploadPosMutation.loading;

    function handleUploadPositions() {
        if (!file || !accountId) return;
        if (!window.confirm('This will delete all existing transactions and holdings for this account. This cannot be undone. Continue?')) {
            return;
        }
        void uploadPosMutation.mutate(undefined);
    }

    const tabStyle = (tab: TabType) => ({
        padding: '0.6rem 1.2rem',
        border: 'none',
        borderBottom: activeTab === tab ? '3px solid #1976d2' : '3px solid transparent',
        background: 'none',
        cursor: 'pointer',
        fontWeight: activeTab === tab ? 600 : 400,
        color: activeTab === tab ? '#1976d2' : '#666',
        fontSize: '0.95rem',
    });

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to={`/accounts/${accountId}`} style={{ color: '#1565c0', textDecoration: 'none' }}>Back to Account</Link>
            </div>
            <h2 style={{ marginBottom: '1.5rem' }}>Import</h2>

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <div style={{ display: 'flex', gap: '0.5rem', borderBottom: '1px solid #e0e0e0', marginBottom: '1.5rem' }}>
                    <button style={tabStyle('transactions')} aria-pressed={activeTab === 'transactions'} onClick={() => { setActiveTab('transactions'); clearFile(); }}>
                        Transaction History
                    </button>
                    <button style={tabStyle('positions')} aria-pressed={activeTab === 'positions'} onClick={() => { setActiveTab('positions'); clearFile(); }}>
                        Current Positions
                    </button>
                </div>

                {activeTab === 'transactions' && (
                    <div>
                        <p style={{ color: '#666', marginBottom: '1rem', fontSize: '0.9rem' }}>
                            Import historical buy, sell, and dividend transactions. New transactions are added to existing data. Duplicates are automatically skipped.
                        </p>
                        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                            <select aria-label="File format" value={txnFormat} onChange={(e) => { setTxnFormat(e.target.value); clearFile(); }} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                                <option value="generic">Generic CSV</option>
                                <option value="fidelity">Fidelity</option>
                                <option value="vanguard">Vanguard</option>
                                <option value="schwab">Schwab</option>
                                <option value="ofx">OFX / QFX</option>
                            </select>
                            <input key={fileInputKey} type="file" aria-label="File to import" accept={txnFormat === 'ofx' ? '.ofx,.qfx' : '.csv'} onChange={handleFileChange} />
                            <Button onClick={handleUploadTransactions} disabled={!file || uploading}>
                                {uploading ? 'Uploading...' : 'Upload'}
                            </Button>
                        </div>
                    </div>
                )}

                {activeTab === 'positions' && (
                    <div>
                        <p style={{ color: '#666', marginBottom: '1rem', fontSize: '0.9rem' }}>
                            Import a snapshot of your current holdings. This replaces all existing data for this account.
                        </p>
                        <div style={{ background: '#fff8e1', border: '1px solid #ffe082', borderRadius: '6px', padding: '0.75rem 1rem', marginBottom: '1rem', fontSize: '0.9rem', color: '#6d4c00' }}>
                            Importing positions will delete all existing transaction history and holdings for this account. This cannot be undone.
                        </div>
                        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                            <select aria-label="File format" value={posFormat} onChange={(e) => { setPosFormat(e.target.value); clearFile(); }} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                                <option value="fidelityPositions">Fidelity</option>
                            </select>
                            <input key={fileInputKey} type="file" aria-label="File to import" accept=".csv" onChange={handleFileChange} />
                            <Button onClick={handleUploadPositions} disabled={!file || uploading} variant="danger">
                                {uploading ? 'Uploading...' : 'Replace & Import'}
                            </Button>
                        </div>
                    </div>
                )}
            </div>

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <h3 style={{ marginBottom: '1rem' }}>Import History</h3>
                {loading && !jobs ? <LoadingState message="Loading import history..." /> : error ? (
                    <ErrorState message={error} onRetry={refetch} />
                ) : (
                    <table style={tableStyle}>
                        <thead>
                            <tr>
                                <th style={thStyle}>Date</th>
                                <th style={thStyle}>Source</th>
                                <th style={thStyle}>Status</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Total</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Success</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>Failed</th>
                            </tr>
                        </thead>
                        <tbody>
                            {sortedJobs.map((job) => (
                                <tr key={job.id} style={trHoverStyle}>
                                    <td style={tdStyle}>{formatDate(job.created_at)}</td>
                                    <td style={tdStyle}>{job.source}</td>
                                    <td style={tdStyle}>
                                        {job.status}
                                        {job.error_message && (
                                            <div style={{ color: '#d32f2f', fontSize: '0.8rem', marginTop: '0.25rem' }}>{job.error_message}</div>
                                        )}
                                    </td>
                                    <td style={{ ...tdStyle, textAlign: 'right' }}>{job.total_rows}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right', color: '#2e7d32' }}>{job.successful_rows}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right', color: job.failed_rows > 0 ? '#d32f2f' : 'inherit' }}>{job.failed_rows}</td>
                                </tr>
                            ))}
                            {jobs?.length === 0 && <tr><td colSpan={6}><EmptyState title="No import history" /></td></tr>}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
