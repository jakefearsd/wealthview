import { Link } from 'react-router';

export default function NotFoundPage() {
    return (
        <div style={{ textAlign: 'center', padding: '4rem 2rem' }}>
            <h1 style={{ fontSize: '4rem', color: '#ccc', marginBottom: '1rem' }}>404</h1>
            <p style={{ color: '#666', marginBottom: '2rem' }}>Page not found</p>
            <Link to="/" style={{ color: '#1976d2', textDecoration: 'none' }}>Go to Dashboard</Link>
        </div>
    );
}
