import { NavLink, Outlet } from 'react-router';
import { useAuth } from '../context/AuthContext';
import ErrorBoundary from './ErrorBoundary';

const navItems: { to: string; label: string; requiredRole?: string }[] = [
    { to: '/', label: 'Dashboard' },
    { to: '/accounts', label: 'Accounts' },
    { to: '/projections', label: 'Projections' },
    { to: '/spending-profiles', label: 'Spending Profiles' },
    { to: '/income-sources', label: 'Income Sources' },
    { to: '/properties', label: 'Properties' },
    { to: '/prices', label: 'Prices' },
    { to: '/export', label: 'Export' },
    { to: '/settings', label: 'Settings', requiredRole: 'admin' },
    { to: '/audit-log', label: 'Audit Log', requiredRole: 'admin' },
    { to: '/admin', label: 'Admin', requiredRole: 'super_admin' },
];

const navLinkStyle = ({ isActive }: { isActive: boolean }) => ({
    display: 'block' as const,
    padding: '0.75rem 1.5rem',
    color: isActive ? '#fff' : '#a0a0b0',
    background: isActive ? '#16213e' : 'transparent',
    textDecoration: 'none' as const,
    borderLeft: isActive ? '3px solid #4a9eff' : '3px solid transparent',
});

export default function Layout() {
    const { email, role, logout } = useAuth();

    return (
        <div style={{ display: 'flex', minHeight: '100vh' }}>
            <nav style={{
                width: '220px',
                background: '#1a1a2e',
                color: '#e0e0e0',
                padding: '1.5rem 0',
                display: 'flex',
                flexDirection: 'column',
            }}>
                <div style={{ padding: '0 1.5rem', marginBottom: '2rem' }}>
                    <h1 style={{ fontSize: '1.25rem', color: '#fff' }}>WealthView</h1>
                </div>
                {navItems
                    .filter((item) => {
                        if (!item.requiredRole) return true;
                        if (item.requiredRole === 'super_admin') return role === 'super_admin';
                        return role === 'admin' || role === 'super_admin';
                    })
                    .map((item) => (
                        <NavLink key={item.to} to={item.to} style={navLinkStyle}>
                            {item.label}
                        </NavLink>
                    ))}
                <div style={{ marginTop: 'auto', padding: '1rem 1.5rem', borderTop: '1px solid #333' }}>
                    <div style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>{email}</div>
                    <div style={{ fontSize: '0.75rem', color: '#888', marginBottom: '0.75rem' }}>{role}</div>
                    <button
                        onClick={logout}
                        style={{
                            background: 'none',
                            border: '1px solid #555',
                            color: '#ccc',
                            padding: '0.4rem 0.8rem',
                            cursor: 'pointer',
                            borderRadius: '4px',
                            width: '100%',
                        }}
                    >
                        Logout
                    </button>
                </div>
            </nav>
            <main style={{ flex: 1, padding: '2rem', background: '#f5f5f5' }}>
                <ErrorBoundary>
                    <Outlet />
                </ErrorBoundary>
            </main>
        </div>
    );
}
