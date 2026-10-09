import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import LoginPage from './LoginPage';
import { AuthProvider } from '../context/AuthContext';
import { login, completeMfaChallenge } from '../api/auth';

const navigate = vi.fn();
vi.mock('react-router', async () => {
    const actual = await vi.importActual<typeof import('react-router')>('react-router');
    return { ...actual, useNavigate: () => navigate };
});

vi.mock('../api/auth', () => ({
    login: vi.fn(),
    completeMfaChallenge: vi.fn(),
    getCurrentUser: vi.fn().mockRejectedValue(new Error('401')),
    logout: vi.fn().mockResolvedValue(undefined),
}));

function renderLoginPage() {
    return renderWithRouter(
        <AuthProvider>
            <LoginPage />
        </AuthProvider>
    );
}

const SAMPLE_AUTH = { user_id: 'u1', tenant_id: 't1', email: 'test@example.com', role: 'admin' };

async function submitCredentials(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText(/email/i), 'test@example.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('LoginPage', () => {
    beforeEach(() => {
        vi.mocked(login).mockReset();
        vi.mocked(completeMfaChallenge).mockReset();
        navigate.mockReset();
    });

    it('wraps the form in a main landmark under a level-one heading', () => {
        renderLoginPage();

        expect(screen.getByRole('main')).toBeInTheDocument();
        expect(screen.getByRole('heading', { level: 1, name: 'Login to WealthView' })).toBeInTheDocument();
    });

    it('renders login form', () => {
        renderLoginPage();
        expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
        expect(screen.getByLabelText('Password')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
    });

    it('shows link to register page', () => {
        renderLoginPage();
        expect(screen.getByText(/register/i)).toBeInTheDocument();
    });

    it('allows typing email and password', async () => {
        renderLoginPage();
        const user = userEvent.setup();

        const emailInput = screen.getByLabelText(/email/i);
        const passwordInput = screen.getByLabelText('Password');

        await user.type(emailInput, 'test@example.com');
        await user.type(passwordInput, 'password123');

        expect(emailInput).toHaveValue('test@example.com');
        expect(passwordInput).toHaveValue('password123');
    });

    it('toggles password visibility', async () => {
        renderLoginPage();
        const user = userEvent.setup();

        const passwordInput = screen.getByLabelText('Password');
        expect(passwordInput).toHaveAttribute('type', 'password');

        await user.click(screen.getByRole('button', { name: /show password/i }));
        expect(passwordInput).toHaveAttribute('type', 'text');

        await user.click(screen.getByRole('button', { name: /hide password/i }));
        expect(passwordInput).toHaveAttribute('type', 'password');
    });

    it('shows error on failed login', async () => {
        const { login } = await import('../api/auth');
        (login as ReturnType<typeof vi.fn>).mockRejectedValue({
            response: { data: { message: 'Invalid credentials' } },
        });

        renderLoginPage();
        const user = userEvent.setup();

        await user.type(screen.getByLabelText(/email/i), 'test@example.com');
        await user.type(screen.getByLabelText('Password'), 'wrong');
        await user.click(screen.getByRole('button', { name: /sign in/i }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Invalid credentials');
    });

    it('navigates home after a login that needs no second factor', async () => {
        vi.mocked(login).mockResolvedValue({ type: 'authenticated', auth: SAMPLE_AUTH });
        renderLoginPage();
        const user = userEvent.setup();

        await submitCredentials(user);

        expect(navigate).toHaveBeenCalledWith('/');
    });

    it('asks for an authentication code instead of signing in when MFA is required', async () => {
        vi.mocked(login).mockResolvedValue({ type: 'mfa_required', mfa_token: 'mfa-jwt' });
        renderLoginPage();
        const user = userEvent.setup();

        await submitCredentials(user);

        expect(await screen.findByLabelText(/authentication code/i)).toBeInTheDocument();
        expect(navigate).not.toHaveBeenCalled();
    });

    it('completes the MFA challenge with the entered code and navigates home', async () => {
        vi.mocked(login).mockResolvedValue({ type: 'mfa_required', mfa_token: 'mfa-jwt' });
        vi.mocked(completeMfaChallenge).mockResolvedValue(SAMPLE_AUTH);
        renderLoginPage();
        const user = userEvent.setup();
        await submitCredentials(user);

        await user.type(await screen.findByLabelText(/authentication code/i), '123456');
        await user.click(screen.getByRole('button', { name: /verify/i }));

        expect(completeMfaChallenge).toHaveBeenCalledWith('mfa-jwt', '123456');
        expect(navigate).toHaveBeenCalledWith('/');
    });

    it('shows the server message when the code is rejected', async () => {
        vi.mocked(login).mockResolvedValue({ type: 'mfa_required', mfa_token: 'mfa-jwt' });
        vi.mocked(completeMfaChallenge).mockRejectedValue({
            response: { data: { message: 'Invalid MFA code' } },
        });
        renderLoginPage();
        const user = userEvent.setup();
        await submitCredentials(user);

        await user.type(await screen.findByLabelText(/authentication code/i), '000000');
        await user.click(screen.getByRole('button', { name: /verify/i }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Invalid MFA code');
        expect(navigate).not.toHaveBeenCalled();
    });

    it('returns to the password form from the code step', async () => {
        vi.mocked(login).mockResolvedValue({ type: 'mfa_required', mfa_token: 'mfa-jwt' });
        renderLoginPage();
        const user = userEvent.setup();
        await submitCredentials(user);

        await user.click(await screen.findByRole('button', { name: /back to sign in/i }));

        expect(screen.getByLabelText('Password')).toBeInTheDocument();
    });
});
