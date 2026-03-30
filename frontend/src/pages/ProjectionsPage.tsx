import { useState } from 'react';
import { Link } from 'react-router';
import { listScenarios, createScenario, deleteScenario } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { cardStyle } from '../utils/styles';
import LoadingState from '../components/LoadingState';
import EmptyState from '../components/EmptyState';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';
import type { CreateScenarioRequest } from '../types/projection';
import ScenarioForm from '../components/ScenarioForm';
import Button from '../components/Button';

export default function ProjectionsPage() {
    const { data: scenarios, loading, refetch } = useApiQuery(listScenarios);
    const [showForm, setShowForm] = useState(false);

    async function handleCreate(data: CreateScenarioRequest) {
        try {
            await createScenario(data);
            toast.success('Scenario created');
            setShowForm(false);
            refetch();
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        }
    }

    async function handleDelete(id: string) {
        try {
            await deleteScenario(id);
            toast.success('Scenario deleted');
            refetch();
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        }
    }

    if (loading) return <LoadingState message="Loading scenarios..." />;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Retirement Projections</h2>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <Link
                        to="/projections/compare"
                        style={{ padding: '0.5rem 1rem', background: '#9c27b0', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', textDecoration: 'none', fontSize: '0.9rem', display: 'flex', alignItems: 'center' }}
                    >
                        Compare Scenarios
                    </Link>
                    <Button onClick={() => setShowForm(!showForm)}>
                        {showForm ? 'Cancel' : 'New Scenario'}
                    </Button>
                </div>
            </div>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Create Scenario</h3>
                    <ScenarioForm onSubmit={handleCreate} submitLabel="Create Scenario" />
                </div>
            )}

            {scenarios?.length === 0 ? (
                <EmptyState
                    title="No scenarios"
                    message="Create one to get started."
                />
            ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '1rem' }}>
                    {scenarios?.map(s => (
                        <div key={s.id} style={cardStyle}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                                <Link
                                    to={`/projections/${s.id}`}
                                    style={{ color: '#1976d2', textDecoration: 'none', fontWeight: 600, fontSize: '1.1rem' }}
                                >
                                    {s.name}
                                </Link>
                                <button
                                    onClick={() => handleDelete(s.id)}
                                    style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem', padding: '0' }}
                                >
                                    Delete
                                </button>
                            </div>
                            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>
                                <div><span style={{ color: '#999' }}>Retire:</span> {s.retirement_date}</div>
                                <div><span style={{ color: '#999' }}>End Age:</span> {s.end_age}</div>
                                <div><span style={{ color: '#999' }}>Inflation:</span> {(s.inflation_rate * 100).toFixed(1)}%</div>
                            </div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.8rem', color: '#999' }}>
                                <span>Created {new Date(s.created_at).toLocaleDateString()}</span>
                                <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
                                    <Link
                                        to={`/projections/${s.id}?run=true`}
                                        style={{ color: '#fff', background: '#1976d2', textDecoration: 'none', fontWeight: 500, padding: '0.25rem 0.75rem', borderRadius: '4px', fontSize: '0.8rem' }}
                                    >
                                        Run
                                    </Link>
                                    <Link
                                        to={`/projections/${s.id}`}
                                        style={{ color: '#1976d2', textDecoration: 'none', fontWeight: 500 }}
                                    >
                                        View Details &rarr;
                                    </Link>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
