import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import RegisterPage from './RegisterPage';
import { AuthProvider } from '../context/AuthContext';

vi.mock('../api/auth', () => ({
    register: vi.fn(),
}));

function renderRegisterPage() {
    return render(
        <AuthProvider>
            <MemoryRouter>
                <RegisterPage />
            </MemoryRouter>
        </AuthProvider>
    );
}

describe('RegisterPage', () => {
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
});
