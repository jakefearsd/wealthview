import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

import { useAuth } from '../context/AuthContext';
import Layout from './Layout';

const mockUseAuth = vi.mocked(useAuth);
type AuthValue = ReturnType<typeof useAuth>;

const logout = vi.fn();

function authAs(role: string | null): AuthValue {
    return {
        isAuthenticated: true,
        userId: 'u1',
        tenantId: 't1',
        email: 'demo@wealthview.local',
        role,
        loading: false,
        loginSuccess: vi.fn(),
        logout,
    } as unknown as AuthValue;
}

function renderLayout(entry = '/') {
    return render(
        <MemoryRouter initialEntries={[entry]}>
            <Routes>
                <Route element={<Layout />}>
                    <Route path="/" element={<div>Dashboard body</div>} />
                    <Route path="/accounts" element={<div>Accounts body</div>} />
                </Route>
            </Routes>
        </MemoryRouter>,
    );
}

/**
 * The application shell had zero coverage. Its one piece of real logic is the nav filter, which
 * decides which links a role sees — an over-permissive filter would surface the Admin area to an
 * ordinary member, and an over-strict one would hide it from a super_admin.
 */
describe('Layout', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockUseAuth.mockReturnValue(authAs('member'));
    });

    it('renders the routed page inside the shell', () => {
        renderLayout();

        expect(screen.getByText('Dashboard body')).toBeInTheDocument();
        expect(screen.getByText('WealthView')).toBeInTheDocument();
    });

    it('shows the signed-in identity and role', () => {
        renderLayout();

        expect(screen.getByText('demo@wealthview.local')).toBeInTheDocument();
        expect(screen.getByText('member')).toBeInTheDocument();
    });

    it('shows every unrestricted nav item to a member', () => {
        renderLayout();

        for (const label of ['Dashboard', 'Accounts', 'Projections', 'Spending Profiles',
            'Income Sources', 'Properties', 'Prices', 'Export']) {
            expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
        }
    });

    // === role gating ===

    it('hides the Admin link from a member', () => {
        renderLayout();

        expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    });

    it('shows the Admin link to an admin', () => {
        mockUseAuth.mockReturnValue(authAs('admin'));
        renderLayout();

        expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
    });

    it('shows the Admin link to a super_admin', () => {
        mockUseAuth.mockReturnValue(authAs('super_admin'));
        renderLayout();

        expect(screen.getByRole('link', { name: 'Admin' })).toBeInTheDocument();
    });

    it('hides the Admin link when the role is not yet known', () => {
        mockUseAuth.mockReturnValue(authAs(null));
        renderLayout();

        expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    });

    // === active-link styling and logout ===

    it('marks the current route as the active nav link', () => {
        renderLayout('/accounts');

        const active = screen.getByRole('link', { name: 'Accounts' });
        expect(active).toHaveStyle({ borderLeft: '3px solid #4a9eff' });
    });

    it('does not mark a non-current route as active', () => {
        renderLayout('/accounts');

        // Asserted via colour rather than the border shorthand: jsdom normalises `transparent`
        // to rgba(0,0,0,0), so the shorthand never matches as written in the component.
        expect(screen.getByRole('link', { name: 'Projections' })).toHaveStyle({ color: '#a0a0b0' });
        expect(screen.getByRole('link', { name: 'Accounts' })).toHaveStyle({ color: '#fff' });
    });

    it('logs out when the logout button is pressed', () => {
        renderLayout();

        fireEvent.click(screen.getByRole('button', { name: 'Logout' }));

        expect(logout).toHaveBeenCalledTimes(1);
    });
});
