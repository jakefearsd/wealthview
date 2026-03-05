import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listProperties, createProperty, deleteProperty } from '../api/properties';
import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import toast from 'react-hot-toast';

function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

export default function PropertiesListPage() {
    const { role } = useAuth();
    const canWrite = role === 'admin' || role === 'member';
    const { data: properties, loading, error, refetch } = useApiQuery(listProperties);
    const [showCreate, setShowCreate] = useState(false);
    const [address, setAddress] = useState('');
    const [purchasePrice, setPurchasePrice] = useState('');
    const [purchaseDate, setPurchaseDate] = useState('');
    const [currentValue, setCurrentValue] = useState('');
    const [mortgageBalance, setMortgageBalance] = useState('');

    async function handleCreate() {
        try {
            await createProperty({
                address,
                purchase_price: parseFloat(purchasePrice),
                purchase_date: purchaseDate,
                current_value: parseFloat(currentValue),
                mortgage_balance: mortgageBalance ? parseFloat(mortgageBalance) : undefined,
            });
            toast.success('Property created');
            setShowCreate(false);
            setAddress('');
            setPurchasePrice('');
            setPurchaseDate('');
            setCurrentValue('');
            setMortgageBalance('');
            refetch();
        } catch {
            toast.error('Failed to create property');
        }
    }

    async function handleDelete(id: string) {
        if (!confirm('Delete this property?')) return;
        try {
            await deleteProperty(id);
            toast.success('Property deleted');
            refetch();
        } catch {
            toast.error('Failed to delete property');
        }
    }

    if (loading) return <div>Loading properties...</div>;
    if (error) return <div style={{ color: '#d32f2f' }}>Error: {error}</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Properties</h2>
                {canWrite && <button onClick={() => setShowCreate(true)} style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>New Property</button>}
            </div>

            {showCreate && (
                <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '1.5rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Create Property</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <input placeholder="Address" value={address} onChange={(e) => setAddress(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <input placeholder="Purchase Price" type="number" value={purchasePrice} onChange={(e) => setPurchasePrice(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <input type="date" value={purchaseDate} onChange={(e) => setPurchaseDate(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <input placeholder="Current Value" type="number" value={currentValue} onChange={(e) => setCurrentValue(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <input placeholder="Mortgage Balance" type="number" value={mortgageBalance} onChange={(e) => setMortgageBalance(e.target.value)} style={{ padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                    </div>
                    <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                        <button onClick={handleCreate} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Create</button>
                        <button onClick={() => setShowCreate(false)} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
                    </div>
                </div>
            )}

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '1rem' }}>
                {properties?.map((p) => (
                    <div key={p.id} style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                        <Link to={`/properties/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                            <h3 style={{ marginBottom: '0.5rem' }}>{p.address}</h3>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem', fontSize: '0.9rem' }}>
                                <div><span style={{ color: '#666' }}>Value:</span> {formatCurrency(p.current_value)}</div>
                                <div><span style={{ color: '#666' }}>Equity:</span> {formatCurrency(p.equity)}</div>
                            </div>
                        </Link>
                        {canWrite && (
                            <button onClick={() => handleDelete(p.id)} style={{ marginTop: '1rem', padding: '0.3rem 0.6rem', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem' }}>Delete</button>
                        )}
                    </div>
                ))}
                {properties?.length === 0 && <div style={{ color: '#999' }}>No properties yet.</div>}
            </div>
        </div>
    );
}
