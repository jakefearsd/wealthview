import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import LoginPage from './LoginPage';
import { AuthProvider } from '../context/AuthContext';

vi.mock('../api/auth', () => ({
    login: vi.fn(),
}));

function renderLoginPage() {
    return render(
        <AuthProvider>
            <MemoryRouter>
                <LoginPage />
            </MemoryRouter>
        </AuthProvider>
    );
}

describe('LoginPage', () => {
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
});
