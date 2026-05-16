import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import RegisterPage from './RegisterPage';
import { AuthProvider } from '../context/AuthContext';
import type { AuthResponse } from '../types/auth';

const navigate = vi.fn();

vi.mock('react-router', async () => {
    const actual = await vi.importActual<typeof import('react-router')>('react-router');
    return { ...actual, useNavigate: () => navigate };
});

vi.mock('../api/auth', () => ({
    register: vi.fn(),
    getCurrentUser: vi.fn().mockRejectedValue(new Error('401')),
    logout: vi.fn().mockResolvedValue(undefined),
}));

import { register } from '../api/auth';
const mockRegister = vi.mocked(register);

const AUTH_RESPONSE: AuthResponse = {
    user_id: 'u1',
    tenant_id: 't1',
    email: 'new@example.com',
    role: 'member',
};

function renderRegisterPage() {
    return renderWithRouter(
        <AuthProvider>
            <RegisterPage />
        </AuthProvider>
    );
}

async function fillForm(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText(/email/i), 'new@example.com');
    await user.type(screen.getByLabelText(/password/i), 'secret123');
    await user.type(screen.getByLabelText(/invite code/i), 'INVITE-1');
}

describe('RegisterPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders registration form with invite code field', () => {
        renderRegisterPage();
        expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
        expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
        expect(screen.getByLabelText(/invite code/i)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /register/i })).toBeInTheDocument();
    });

    it('shows link to login page', () => {
        renderRegisterPage();
        expect(screen.getByText(/sign in/i)).toBeInTheDocument();
    });

    it('submits the typed credentials and navigates home on success', async () => {
        mockRegister.mockResolvedValue(AUTH_RESPONSE);
        renderRegisterPage();
        const user = userEvent.setup();

        await fillForm(user);
        await user.click(screen.getByRole('button', { name: /register/i }));

        await waitFor(() => {
            expect(mockRegister).toHaveBeenCalledWith({
                email: 'new@example.com',
                password: 'secret123',
                invite_code: 'INVITE-1',
            });
        });
        expect(navigate).toHaveBeenCalledWith('/');
    });

    it('shows the server error message when registration fails', async () => {
        mockRegister.mockRejectedValue({
            response: { data: { message: 'Invite code expired' } },
        });
        renderRegisterPage();
        const user = userEvent.setup();

        await fillForm(user);
        await user.click(screen.getByRole('button', { name: /register/i }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Invite code expired');
        expect(navigate).not.toHaveBeenCalled();
    });

    it('falls back to a generic error message when none is provided', async () => {
        mockRegister.mockRejectedValue(new Error('network down'));
        renderRegisterPage();
        const user = userEvent.setup();

        await fillForm(user);
        await user.click(screen.getByRole('button', { name: /register/i }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Registration failed');
    });

    it('disables the submit button while the request is in flight', async () => {
        let resolveRegister: (value: AuthResponse) => void = () => {};
        mockRegister.mockReturnValue(
            new Promise<AuthResponse>((resolve) => {
                resolveRegister = resolve;
            })
        );
        renderRegisterPage();
        const user = userEvent.setup();

        await fillForm(user);
        await user.click(screen.getByRole('button', { name: /register/i }));

        const button = screen.getByRole('button', { name: /creating account/i });
        expect(button).toBeDisabled();

        resolveRegister(AUTH_RESPONSE);
        await waitFor(() => expect(navigate).toHaveBeenCalledWith('/'));
    });
});
