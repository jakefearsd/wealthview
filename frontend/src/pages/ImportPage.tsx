import { useState, type ChangeEvent } from 'react';
import { useParams, Link } from 'react-router-dom';
import { importCsv, listImportJobs } from '../api/import';
import { useApiQuery } from '../hooks/useApiQuery';
import toast from 'react-hot-toast';

export default function ImportPage() {
    const { id: accountId } = useParams<{ id: string }>();
    const [file, setFile] = useState<File | null>(null);
    const [format, setFormat] = useState('generic');
    const [uploading, setUploading] = useState(false);
    const { data: jobs, loading, refetch } = useApiQuery(listImportJobs);

    function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
        setFile(e.target.files?.[0] || null);
    }

    async function handleUpload() {
        if (!file || !accountId) return;
        setUploading(true);
        try {
            const result = await importCsv(accountId, file, format === 'generic' ? undefined : format);
            toast.success(`Imported: ${result.successful_rows} successful, ${result.failed_rows} failed`);
            setFile(null);
            refetch();
        } catch {
            toast.error('Import failed');
        } finally {
            setUploading(false);
        }
    }

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to={`/accounts/${accountId}`} style={{ color: '#1976d2', textDecoration: 'none' }}>Back to Account</Link>
            </div>
            <h2 style={{ marginBottom: '1.5rem' }}>Import Transactions</h2>

            <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '2rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <h3 style={{ marginBottom: '1rem' }}>Upload CSV</h3>
                <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                    <select value={format} onChange={(e) => setFormat(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }}>
                        <option value="generic">Generic CSV</option>
                        <option value="fidelity">Fidelity</option>
                        <option value="fidelityPositions">Fidelity Positions</option>
                        <option value="vanguard">Vanguard</option>
                        <option value="schwab">Schwab</option>
                    </select>
                    <input type="file" accept=".csv" onChange={handleFileChange} />
                    <button onClick={handleUpload} disabled={!file || uploading} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>
                        {uploading ? 'Uploading...' : 'Upload'}
                    </button>
                </div>
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
