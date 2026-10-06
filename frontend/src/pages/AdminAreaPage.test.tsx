import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

vi.mock('../components/admin/DashboardSection', () => ({
    default: () => <div data-testid="section-dashboard" />,
}));
vi.mock('../components/admin/UsersSection', () => ({
    default: () => <div data-testid="section-users" />,
}));
vi.mock('../components/admin/TenantsSection', () => ({
    default: () => <div data-testid="section-tenants" />,
}));
vi.mock('../components/admin/PricesSection', () => ({
    default: () => <div data-testid="section-prices" />,
}));
vi.mock('../components/admin/ExchangeRatesSection', () => ({
    default: () => <div data-testid="section-exchange-rates" />,
}));
vi.mock('../components/admin/InviteCodesSection', () => ({
    default: () => <div data-testid="section-invite-codes" />,
}));
vi.mock('../components/admin/SystemConfigSection', () => ({
    default: () => <div data-testid="section-system-config" />,
}));
vi.mock('../components/admin/AuditLogSection', () => ({
    default: () => <div data-testid="section-audit-log" />,
}));

import { useAuth } from '../context/AuthContext';
import AdminAreaPage from './AdminAreaPage';

const mockUseAuth = vi.mocked(useAuth);

type AuthValue = ReturnType<typeof useAuth>;

function authValue(role: string | null): AuthValue {
    return {
        isAuthenticated: true,
        userId: 'u',
        tenantId: 't',
        email: 'u@test',
        role,
        loading: false,
        loginSuccess: vi.fn(),
        logout: vi.fn().mockResolvedValue(undefined),
    } as unknown as AuthValue;
}

describe('AdminAreaPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('shows the dashboard by default for super_admin and includes super-admin-only nav items', () => {
        mockUseAuth.mockReturnValue(authValue('super_admin'));

        renderWithRouter(<AdminAreaPage />);

        expect(screen.getByTestId('section-dashboard')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Tenants' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'System Config' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Dashboard' })).toBeInTheDocument();
    });

    it('shows the users section by default for non-super-admin and hides super-admin-only nav items', () => {
        mockUseAuth.mockReturnValue(authValue('admin'));

        renderWithRouter(<AdminAreaPage />);

        expect(screen.getByTestId('section-users')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Tenants' })).toBeNull();
        expect(screen.queryByRole('button', { name: 'System Config' })).toBeNull();
        expect(screen.queryByRole('button', { name: 'Dashboard' })).toBeNull();
    });

    it('switches the active section when a sidebar button is clicked', async () => {
        const user = userEvent.setup();
        mockUseAuth.mockReturnValue(authValue('super_admin'));

        renderWithRouter(<AdminAreaPage />);

        await user.click(screen.getByRole('button', { name: 'Audit Log' }));

        expect(screen.getByTestId('section-audit-log')).toBeInTheDocument();
        expect(screen.queryByTestId('section-dashboard')).toBeNull();
    });

    it.each(['member', 'viewer', null])('shows a not-authorised message instead of the admin area for role %s', (role) => {
        mockUseAuth.mockReturnValue(authValue(role));

        renderWithRouter(<AdminAreaPage />);

        expect(screen.getByRole('alert')).toHaveTextContent(/not authori[sz]ed/i);
        expect(screen.queryByTestId('section-users')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Users' })).toBeNull();
    });

    it('marks the selected sidebar item with aria-current', async () => {
        const user = userEvent.setup();
        mockUseAuth.mockReturnValue(authValue('super_admin'));

        renderWithRouter(<AdminAreaPage />);

        expect(screen.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('button', { name: 'Tenants' })).not.toHaveAttribute('aria-current');

        await user.click(screen.getByRole('button', { name: 'Tenants' }));

        expect(screen.getByRole('button', { name: 'Tenants' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('button', { name: 'Dashboard' })).not.toHaveAttribute('aria-current');
    });
});
