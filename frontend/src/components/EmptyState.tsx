import type { ReactNode } from 'react';

interface Props {
    title: string;
    message?: string;
    action?: ReactNode;
}

export default function EmptyState({ title, message, action }: Props) {
    return (
        <div style={{
            background: '#f9fafb',
            border: '1px solid #e5e7eb',
            borderRadius: '8px',
            padding: '3rem 2rem',
            textAlign: 'center',
        }}>
            <div style={{
                color: '#6b7280',
                fontWeight: 600,
                fontSize: '1.1rem',
                marginBottom: message ? '0.5rem' : action ? '1rem' : 0,
            }}>
                {title}
            </div>
            {message && (
                <div style={{
                    color: '#9ca3af',
                    fontSize: '0.9rem',
                    marginBottom: action ? '1rem' : 0,
                }}>
                    {message}
                </div>
            )}
            {action}
        </div>
    );
}
