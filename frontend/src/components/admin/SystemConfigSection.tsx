import { useState, useEffect } from 'react';
import { getConfig, setConfig } from '../../api/adminSystem';
import type { SystemConfig } from '../../api/adminSystem';
import { cardStyle } from '../../utils/styles';
import toast from 'react-hot-toast';

const SENSITIVE_KEYS = ['finnhub.api-key'];

const CONFIG_SECTIONS: { title: string; keys: string[] }[] = [
    {
        title: 'API Keys',
        keys: ['finnhub.api-key', 'zillow.scraper.enabled'],
    },
    {
        title: 'Application Settings',
        keys: ['cors.allowed-origins', 'jwt.access-token-expiry', 'jwt.refresh-token-expiry'],
    },
    {
        title: 'Price Sync',
        keys: ['finnhub.sync-schedule', 'finnhub.rate-limit-ms', 'yahoo.rate-limit-ms'],
    },
];

export default function SystemConfigSection() {
    const [configs, setConfigs] = useState<SystemConfig[]>([]);
    const [loading, setLoading] = useState(true);
    const [editingKey, setEditingKey] = useState<string | null>(null);
    const [editValue, setEditValue] = useState('');
    const [saving, setSaving] = useState(false);
    const [revealedKeys, setRevealedKeys] = useState<Set<string>>(new Set());

    useEffect(() => {
        loadConfigs();
    }, []);

    async function loadConfigs() {
        setLoading(true);
        try {
            const data = await getConfig();
            setConfigs(data);
        } catch {
            toast.error('Failed to load config');
        } finally {
            setLoading(false);
        }
    }

    function getConfigValue(key: string): string {
        return configs.find((c) => c.key === key)?.value ?? '';
    }

    function isSensitive(key: string): boolean {
        return SENSITIVE_KEYS.includes(key);
    }

    function displayValue(key: string): string {
        const val = getConfigValue(key);
        if (isSensitive(key) && !revealedKeys.has(key)) {
            return val ? '********' : '(not set)';
        }
        return val || '(not set)';
    }

    function toggleReveal(key: string) {
        setRevealedKeys((prev) => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            return next;
        });
    }

    function startEdit(key: string) {
        setEditingKey(key);
        setEditValue(getConfigValue(key));
    }

    async function handleSave() {
        if (!editingKey) return;
        setSaving(true);
        try {
            await setConfig(editingKey, editValue);
            toast.success('Config updated');
            setEditingKey(null);
            loadConfigs();
        } catch {
            toast.error('Failed to save config');
        } finally {
            setSaving(false);
        }
    }

    if (loading) return <div>Loading config...</div>;

    return (
        <div>
            <h2 style={{ marginBottom: '1.5rem' }}>System Config</h2>

            {CONFIG_SECTIONS.map((section) => (
                <div key={section.title} style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>{section.title}</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <tbody>
                            {section.keys.map((key) => (
                                <tr key={key} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', fontWeight: 600, fontSize: '0.9rem', width: '250px' }}>
                                        {key}
                                    </td>
                                    <td style={{ padding: '0.5rem' }}>
                                        {editingKey === key ? (
                                            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                <input
                                                    type={isSensitive(key) ? 'password' : 'text'}
                                                    value={editValue}
                                                    onChange={(e) => setEditValue(e.target.value)}
                                                    style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px', flex: 1 }}
                                                    autoFocus
                                                />
                                                <button
                                                    onClick={handleSave}
                                                    disabled={saving}
                                                    style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                                                >
                                                    {saving ? '...' : 'Save'}
                                                </button>
                                                <button
                                                    onClick={() => setEditingKey(null)}
                                                    style={{ padding: '0.4rem 0.8rem', background: '#fff', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                                                >
                                                    Cancel
                                                </button>
                                            </div>
                                        ) : (
                                            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                <span style={{ color: '#555', fontSize: '0.9rem', fontFamily: isSensitive(key) ? 'inherit' : 'monospace' }}>
                                                    {displayValue(key)}
                                                </span>
                                                {isSensitive(key) && getConfigValue(key) && (
                                                    <button
                                                        onClick={() => toggleReveal(key)}
                                                        style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.8rem' }}
                                                    >
                                                        {revealedKeys.has(key) ? 'Hide' : 'Show'}
                                                    </button>
                                                )}
                                                <button
                                                    onClick={() => startEdit(key)}
                                                    style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem', marginLeft: 'auto' }}
                                                >
                                                    Edit
                                                </button>
                                            </div>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            ))}

            {/* Show any extra config keys not in the predefined sections */}
            {(() => {
                const knownKeys = new Set(CONFIG_SECTIONS.flatMap((s) => s.keys));
                const extraConfigs = configs.filter((c) => !knownKeys.has(c.key));
                if (extraConfigs.length === 0) return null;
                return (
                    <div style={cardStyle}>
                        <h3 style={{ marginBottom: '1rem' }}>Other</h3>
                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                            <tbody>
                                {extraConfigs.map((c) => (
                                    <tr key={c.key} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                        <td style={{ padding: '0.5rem', fontWeight: 600, fontSize: '0.9rem', width: '250px' }}>{c.key}</td>
                                        <td style={{ padding: '0.5rem' }}>
                                            {editingKey === c.key ? (
                                                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                    <input
                                                        type="text"
                                                        value={editValue}
                                                        onChange={(e) => setEditValue(e.target.value)}
                                                        style={{ padding: '0.4rem', border: '1px solid #ccc', borderRadius: '4px', flex: 1 }}
                                                        autoFocus
                                                    />
                                                    <button onClick={handleSave} disabled={saving}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                        {saving ? '...' : 'Save'}
                                                    </button>
                                                    <button onClick={() => setEditingKey(null)}
                                                        style={{ padding: '0.4rem 0.8rem', background: '#fff', border: '1px solid #ccc', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}>
                                                        Cancel
                                                    </button>
                                                </div>
                                            ) : (
                                                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                                                    <span style={{ color: '#555', fontSize: '0.9rem', fontFamily: 'monospace' }}>{c.value}</span>
                                                    <button onClick={() => startEdit(c.key)}
                                                        style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', fontSize: '0.85rem', marginLeft: 'auto' }}>
                                                        Edit
                                                    </button>
                                                </div>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                );
            })()}
        </div>
    );
}
