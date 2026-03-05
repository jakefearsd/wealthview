import { useState, type FormEvent } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { register } from '../api/auth';
import { useAuth } from '../context/AuthContext';

export default function RegisterPage() {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [inviteCode, setInviteCode] = useState('');
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();
    const { loginSuccess } = useAuth();

    async function handleSubmit(e: FormEvent) {
        e.preventDefault();
        setError('');
        setLoading(true);
        try {
            const response = await register({ email, password, invite_code: inviteCode });
            loginSuccess(response);
            navigate('/');
        } catch (err: unknown) {
            const msg = (err as { response?: { data?: { message?: string } } })
                ?.response?.data?.message || 'Registration failed';
            setError(msg);
        } finally {
            setLoading(false);
        }
    }

    return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' }}>
            <form onSubmit={handleSubmit} style={{ background: '#fff', padding: '2rem', borderRadius: '8px', width: '380px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
                <h2 style={{ marginBottom: '1.5rem' }}>Register for WealthView</h2>
                {error && <div role="alert" style={{ color: '#d32f2f', marginBottom: '1rem', padding: '0.5rem', background: '#fde', borderRadius: '4px' }}>{error}</div>}
                <div style={{ marginBottom: '1rem' }}>
                    <label htmlFor="email" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Email</label>
                    <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required
                        style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                </div>
                <div style={{ marginBottom: '1rem' }}>
                    <label htmlFor="password" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Password</label>
                    <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={6}
                        style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                </div>
                <div style={{ marginBottom: '1.5rem' }}>
                    <label htmlFor="inviteCode" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Invite Code</label>
                    <input id="inviteCode" type="text" value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} required
                        style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                </div>
                <button type="submit" disabled={loading}
                    style={{ width: '100%', padding: '0.75rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontWeight: 600 }}>
                    {loading ? 'Creating account...' : 'Register'}
                </button>
                <p style={{ marginTop: '1rem', textAlign: 'center', fontSize: '0.9rem' }}>
                    Already have an account? <Link to="/login">Sign In</Link>
                </p>
            </form>
        </div>
    );
}
