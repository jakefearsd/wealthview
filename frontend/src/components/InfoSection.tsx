import { useState } from 'react';

interface InfoSectionProps {
    prompt?: string;
    children: React.ReactNode;
}

export default function InfoSection({ prompt = 'What is this?', children }: InfoSectionProps) {
    const [expanded, setExpanded] = useState(false);

    return (
        <div style={{ marginBottom: '0.75rem' }}>
            <button
                type="button"
                onClick={() => setExpanded(!expanded)}
                style={{
                    background: 'none',
                    border: 'none',
                    color: '#1976d2',
                    cursor: 'pointer',
                    padding: 0,
                    fontSize: '0.8rem',
                    textDecoration: 'underline',
                }}
            >
                {expanded ? 'Hide' : prompt}
            </button>
            {expanded && (
                <div style={{
                    marginTop: '0.5rem',
                    padding: '0.75rem',
                    background: '#f5f5f5',
                    borderRadius: '6px',
                    fontSize: '0.8rem',
                    color: '#555',
                    lineHeight: 1.5,
                }}>
                    {children}
                </div>
            )}
        </div>
    );
}
