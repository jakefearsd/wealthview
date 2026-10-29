import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, Link } from 'react-router';
import { completeMfaChallenge, login } from '../api/auth';
import { useAuth } from '../context/AuthContext';
import Button from '../components/Button';
import { MFA_CODE_FORMAT_MESSAGE, parseMfaCode } from '../utils/mfaCode';

/**
 * The server's 401 message when the MFA challenge itself is no longer usable (expired after
 * five minutes, already used, or tampered with). Retrying a code cannot succeed then, so the
 * page sends the user back to the password step. Pinned by MfaIT on the backend.
 */
const DEAD_CHALLENGE_MESSAGE = 'Invalid MFA challenge';

export default function LoginPage() {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const [mfaToken, setMfaToken] = useState<string | null>(null);
    const [mfaCode, setMfaCode] = useState('');
    const mfaCodeInput = useRef<HTMLInputElement>(null);
    const navigate = useNavigate();
    const { loginSuccess } = useAuth();

    useEffect(() => {
        if (mfaToken) {
            mfaCodeInput.current?.focus();
        }
    }, [mfaToken]);

    function errorMessage(err: unknown, fallback: string) {
        return (err as { response?: { data?: { message?: string } } })
            ?.response?.data?.message || fallback;
    }

    async function handleSubmit(e: FormEvent) {
        e.preventDefault();
        setError('');
        setLoading(true);
        try {
            const result = await login({ email, password });
            if (result.type === 'mfa_required') {
                setMfaToken(result.mfa_token);
                setMfaCode('');
                return;
            }
            loginSuccess(result.auth);
            navigate('/');
        } catch (err: unknown) {
            setError(errorMessage(err, 'Login failed'));
        } finally {
            setLoading(false);
        }
    }

    async function handleMfaSubmit(e: FormEvent) {
        e.preventDefault();
        if (!mfaToken) {
            return;
        }
        if (!parseMfaCode(mfaCode)) {
            setError(MFA_CODE_FORMAT_MESSAGE);
            return;
        }
        setError('');
        setLoading(true);
        try {
            const auth = await completeMfaChallenge(mfaToken, mfaCode);
            loginSuccess(auth);
            navigate('/');
        } catch (err: unknown) {
            const message = errorMessage(err, 'Verification failed');
            if (message === DEAD_CHALLENGE_MESSAGE) {
                backToSignIn();
                setError('Your sign-in step expired. Enter your password again.');
            } else {
                setError(message);
            }
        } finally {
            setLoading(false);
        }
    }

    function backToSignIn() {
        setMfaToken(null);
        setMfaCode('');
        setPassword('');
        setError('');
    }

    const formStyle = { background: '#fff', padding: '2rem', borderRadius: '8px', width: '380px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' };
    const errorBox = error && <div role="alert" style={{ color: '#d32f2f', marginBottom: '1rem', padding: '0.5rem', background: '#fde', borderRadius: '4px' }}>{error}</div>;

    if (mfaToken) {
        return (
            <main style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' }}>
                <form data-testid="mfa-form" onSubmit={handleMfaSubmit} style={formStyle}>
                    <h1 style={{ fontSize: '1.5em', marginTop: '0.83em', marginBottom: '1.5rem' }}>Two-step verification</h1>
                    {errorBox}
                    <div style={{ marginBottom: '1.5rem' }}>
                        <label htmlFor="mfa-code" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Authentication code</label>
                        <input id="mfa-code" ref={mfaCodeInput} type="text" inputMode="text" autoComplete="one-time-code"
                            value={mfaCode} onChange={(e) => setMfaCode(e.target.value)} required
                            style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <p style={{ marginTop: '0.5rem', fontSize: '0.85rem', color: '#666' }}>
                            Enter the 6-digit code from your authenticator app, or one of your 8-character recovery codes.
                        </p>
                    </div>
                    <Button type="submit" disabled={loading} style={{ width: '100%', padding: '0.75rem' }}>
                        {loading ? 'Verifying...' : 'Verify'}
                    </Button>
                    <p style={{ marginTop: '1rem', textAlign: 'center', fontSize: '0.9rem' }}>
                        <button type="button" onClick={backToSignIn}
                            style={{ background: 'none', border: 'none', color: '#1976d2', cursor: 'pointer', textDecoration: 'underline', fontSize: 'inherit' }}>
                            Back to sign in
                        </button>
                    </p>
                </form>
            </main>
        );
    }

    return (
        <main style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' }}>
            <form data-testid="login-form" onSubmit={handleSubmit} style={formStyle}>
                <h1 style={{ fontSize: '1.5em', marginTop: '0.83em', marginBottom: '1.5rem' }}>Login to WealthView</h1>
                {errorBox}
                <div style={{ marginBottom: '1rem' }}>
                    <label htmlFor="email" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Email</label>
                    <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required
                        style={{ width: '100%', padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                </div>
                <div style={{ marginBottom: '1.5rem' }}>
                    <label htmlFor="password" style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Password</label>
                    <div style={{ position: 'relative' }}>
                        <input id="password" type={showPassword ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} required
                            style={{ width: '100%', padding: '0.5rem', paddingRight: '3.5rem', border: '1px solid #ccc', borderRadius: '4px' }} />
                        <button type="button" onClick={() => setShowPassword(!showPassword)}
                            aria-label={showPassword ? 'Hide password' : 'Show password'}
                            style={{ position: 'absolute', right: '0.5rem', top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#666', fontSize: '0.85rem' }}>
                            {showPassword ? 'Hide' : 'Show'}
                        </button>
                    </div>
                </div>
                <Button type="submit" disabled={loading} style={{ width: '100%', padding: '0.75rem' }}>
                    {loading ? 'Signing in...' : 'Sign In'}
                </Button>
                <p style={{ marginTop: '1rem', textAlign: 'center', fontSize: '0.9rem' }}>
                    Don't have an account? <Link to="/register">Register</Link>
                </p>
            </form>
        </main>
    );
}
