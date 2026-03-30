interface Props {
    message?: string;
}

export default function LoadingState({ message = 'Loading...' }: Props) {
    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '3rem 1rem',
        }}>
            <div style={{
                width: '2.5rem',
                height: '2.5rem',
                border: '3px solid #e0e0e0',
                borderTopColor: '#1976d2',
                borderRadius: '50%',
                animation: 'spin 1s linear infinite',
                marginBottom: '1rem',
            }} />
            <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
            <div style={{ color: '#666', fontSize: '0.95rem' }}>{message}</div>
        </div>
    );
}
