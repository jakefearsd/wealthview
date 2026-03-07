import { useState, type ChangeEvent } from 'react';
import { useParams, Link } from 'react-router-dom';
import { importCsv, importOfx, importPositions, listImportJobs } from '../api/import';
import { useApiQuery } from '../hooks/useApiQuery';
import toast from 'react-hot-toast';

type TabType = 'transactions' | 'positions';

export default function ImportPage() {
    const { id: accountId } = useParams<{ id: string }>();
    const [activeTab, setActiveTab] = useState<TabType>('transactions');
    const [file, setFile] = useState<File | null>(null);
    const [txnFormat, setTxnFormat] = useState('generic');
    const [posFormat, setPosFormat] = useState('fidelityPositions');
    const [uploading, setUploading] = useState(false);
    const { data: jobs, loading, refetch } = useApiQuery(listImportJobs);

    function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
        setFile(e.target.files?.[0] || null);
    }

    async function handleUploadTransactions() {
        if (!file || !accountId) return;
        setUploading(true);
        try {
            const isOfx = txnFormat === 'ofx';
            const result = isOfx
                ? await importOfx(accountId, file)
                : await importCsv(accountId, file, txnFormat === 'generic' ? undefined : txnFormat);
            toast.success(`Imported: ${result.successful_rows} successful, ${result.failed_rows} failed`);
            setFile(null);
            refetch();
        } catch {
            toast.error('Import failed');
        } finally {
            setUploading(false);
        }
    }

    async function handleUploadPositions() {
        if (!file || !accountId) return;
        if (!window.confirm('This will delete all existing transactions and holdings for this account. This cannot be undone. Continue?')) {
            return;
        }
        setUploading(true);
        try {
            const result = await importPositions(accountId, file, posFormat);
            toast.success(`Imported: ${result.successful_rows} positions`);
            setFile(null);
            refetch();
        } catch {
            toast.error('Position import failed');
        } finally {
            setUploading(false);
        }
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
                <Link to={`/accounts/${accountId}`} style={{ color: '#1976d2', textDecoration: 'none' }}>Back to Account</Link>
            </div>
            <h2 style={{ marginBottom: '1.5rem' }}>Import</h2>

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <div style={{ display: 'flex', gap: '0.5rem', borderBottom: '1px solid #e0e0e0', marginBottom: '1.5rem' }}>
                    <button style={tabStyle('transactions')} onClick={() => { setActiveTab('transactions'); setFile(null); }}>
                        Transaction History
                    </button>
                    <button style={tabStyle('positions')} onClick={() => { setActiveTab('positions'); setFile(null); }}>
                        Current Positions
                    </button>
                </div>

                {activeTab === 'transactions' && (
                    <div>
                        <p style={{ color: '#666', marginBottom: '1rem', fontSize: '0.9rem' }}>
                            Import historical buy, sell, and dividend transactions. New transactions are added to existing data. Duplicates are automatically skipped.
                        </p>
                        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                            <select value={txnFormat} onChange={(e) => setTxnFormat(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                                <option value="generic">Generic CSV</option>
                                <option value="fidelity">Fidelity</option>
                                <option value="vanguard">Vanguard</option>
                                <option value="schwab">Schwab</option>
                                <option value="ofx">OFX / QFX</option>
                            </select>
                            <input type="file" accept={txnFormat === 'ofx' ? '.ofx,.qfx' : '.csv'} onChange={handleFileChange} />
                            <button onClick={handleUploadTransactions} disabled={!file || uploading} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                {uploading ? 'Uploading...' : 'Upload'}
                            </button>
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
                            <select value={posFormat} onChange={(e) => setPosFormat(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                                <option value="fidelityPositions">Fidelity</option>
                            </select>
                            <input type="file" accept=".csv" onChange={handleFileChange} />
                            <button onClick={handleUploadPositions} disabled={!file || uploading} style={{ padding: '0.5rem 1rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                                {uploading ? 'Uploading...' : 'Replace & Import'}
                            </button>
                        </div>
                    </div>
                )}
            </div>

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <h3 style={{ marginBottom: '1rem' }}>Import History</h3>
                {loading ? <div>Loading...</div> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Date</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Source</th>
                                <th style={{ textAlign: 'left', padding: '0.5rem' }}>Status</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Total</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Success</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Failed</th>
                            </tr>
                        </thead>
                        <tbody>
                            {jobs?.map((job) => (
                                <tr key={job.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>{new Date(job.created_at).toLocaleDateString()}</td>
                                    <td style={{ padding: '0.5rem' }}>{job.source}</td>
                                    <td style={{ padding: '0.5rem' }}>{job.status}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{job.total_rows}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{job.successful_rows}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: job.failed_rows > 0 ? '#d32f2f' : 'inherit' }}>{job.failed_rows}</td>
                                </tr>
                            ))}
                            {jobs?.length === 0 && <tr><td colSpan={6} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No import history</td></tr>}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}
