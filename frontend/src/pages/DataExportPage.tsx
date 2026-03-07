import { useState } from 'react';
import { downloadJson, downloadCsv } from '../api/export';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';

const csvEntities = ['accounts', 'transactions', 'holdings', 'properties'] as const;

export default function DataExportPage() {
    const [loading, setLoading] = useState<string | null>(null);

    async function handleDownload(type: string, fn: () => Promise<void>) {
        setLoading(type);
        try {
            await fn();
            toast.success(`${type} export downloaded`);
        } catch {
            toast.error(`Failed to export ${type}`);
        } finally {
            setLoading(null);
        }
    }

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>Data Export</h2>

            <div style={cardStyle}>
                <h3 style={{ marginBottom: '1rem' }}>Full Export (JSON)</h3>
                <p style={{ color: '#666', marginBottom: '1rem' }}>
                    Download all your data (accounts, transactions, holdings, properties) as a single JSON file.
                </p>
                <button
                    onClick={() => handleDownload('JSON', downloadJson)}
                    disabled={loading !== null}
                    style={{
                        padding: '0.5rem 1rem',
                        background: '#4a9eff',
                        color: '#fff',
                        border: 'none',
                        borderRadius: '4px',
                        cursor: loading ? 'default' : 'pointer',
                    }}
                >
                    {loading === 'JSON' ? 'Downloading...' : 'Download JSON'}
                </button>
            </div>

            <div style={{ ...cardStyle, marginTop: '1.5rem' }}>
                <h3 style={{ marginBottom: '1rem' }}>CSV Export</h3>
                <p style={{ color: '#666', marginBottom: '1rem' }}>
                    Download individual data tables as CSV files.
                </p>
                <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
                    {csvEntities.map((entity) => (
                        <button
                            key={entity}
                            onClick={() => handleDownload(entity, () => downloadCsv(entity))}
                            disabled={loading !== null}
                            style={{
                                padding: '0.5rem 1rem',
                                background: '#fff',
                                color: '#333',
                                border: '1px solid #ccc',
                                borderRadius: '4px',
                                cursor: loading ? 'default' : 'pointer',
                                textTransform: 'capitalize',
                            }}
                        >
                            {loading === entity ? 'Downloading...' : entity}
                        </button>
                    ))}
                </div>
            </div>
        </div>
    );
}
