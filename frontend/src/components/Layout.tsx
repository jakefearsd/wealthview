import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const navItems = [
    { to: '/', label: 'Dashboard', adminOnly: false },
    { to: '/accounts', label: 'Accounts', adminOnly: false },
    { to: '/projections', label: 'Projections', adminOnly: false },
    { to: '/spending-profiles', label: 'Spending Profiles', adminOnly: false },
    { to: '/properties', label: 'Properties', adminOnly: false },
    { to: '/prices', label: 'Prices', adminOnly: false },
    { to: '/settings', label: 'Settings', adminOnly: true },
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
                    .filter((item) => !item.adminOnly || role === 'admin')
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
                <Outlet />
            </main>
        </div>
    );
}
