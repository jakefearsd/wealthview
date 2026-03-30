interface Props {
    message: string;
    onRetry?: () => void;
}

export default function ErrorState({ message, onRetry }: Props) {
    return (
        <div style={{
            background: '#fef2f2',
            border: '1px solid #fecaca',
            borderRadius: '8px',
            padding: '1.5rem',
            maxWidth: '600px',
        }}>
            <div style={{
                color: '#b91c1c',
                fontWeight: 600,
                marginBottom: '0.5rem',
                fontSize: '0.95rem',
            }}>
                Something went wrong
            </div>
            <div style={{
                color: '#dc2626',
                fontSize: '0.9rem',
                marginBottom: onRetry ? '1rem' : 0,
            }}>
                {message}
            </div>
            {onRetry && (
                <button
                    onClick={onRetry}
                    style={{
                        padding: '0.4rem 1rem',
                        background: '#dc2626',
                        color: '#fff',
                        border: 'none',
                        borderRadius: '4px',
                        cursor: 'pointer',
                        fontSize: '0.85rem',
                    }}
                >
                    Retry
                </button>
            )}
        </div>
    );
}
