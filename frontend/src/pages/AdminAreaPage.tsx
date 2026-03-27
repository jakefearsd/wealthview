import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import DashboardSection from '../components/admin/DashboardSection';
import UsersSection from '../components/admin/UsersSection';
import TenantsSection from '../components/admin/TenantsSection';
import PricesSection from '../components/admin/PricesSection';
import InviteCodesSection from '../components/admin/InviteCodesSection';
import SystemConfigSection from '../components/admin/SystemConfigSection';
import AuditLogSection from '../components/admin/AuditLogSection';

type AdminSection = 'dashboard' | 'users' | 'tenants' | 'prices' | 'invite-codes' | 'system-config' | 'audit-log';

interface SidebarItem {
    key: AdminSection;
    label: string;
    superAdminOnly?: boolean;
}

const sidebarItems: SidebarItem[] = [
    { key: 'dashboard', label: 'Dashboard', superAdminOnly: true },
    { key: 'users', label: 'Users' },
    { key: 'tenants', label: 'Tenants', superAdminOnly: true },
    { key: 'prices', label: 'Prices', superAdminOnly: true },
    { key: 'invite-codes', label: 'Invite Codes' },
    { key: 'system-config', label: 'System Config', superAdminOnly: true },
    { key: 'audit-log', label: 'Audit Log' },
];

function getDefaultSection(role: string | null): AdminSection {
    if (role === 'super_admin') return 'dashboard';
    return 'users';
}

export default function AdminAreaPage() {
    const { role } = useAuth();
    const [activeSection, setActiveSection] = useState<AdminSection>(getDefaultSection(role));

    const visibleItems = sidebarItems.filter(
        (item) => !item.superAdminOnly || role === 'super_admin'
    );

    function renderSection() {
        switch (activeSection) {
            case 'dashboard': return <DashboardSection />;
            case 'users': return <UsersSection />;
            case 'tenants': return <TenantsSection />;
            case 'prices': return <PricesSection />;
            case 'invite-codes': return <InviteCodesSection />;
            case 'system-config': return <SystemConfigSection />;
            case 'audit-log': return <AuditLogSection />;
        }
    }

    return (
        <div style={{ display: 'flex', gap: '1.5rem', minHeight: 'calc(100vh - 4rem)' }}>
            <nav style={{
                width: '180px',
                flexShrink: 0,
                background: '#fff',
                borderRadius: '8px',
                boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
                padding: '0.5rem 0',
                alignSelf: 'flex-start',
            }}>
                {visibleItems.map((item) => (
                    <button
                        key={item.key}
                        onClick={() => setActiveSection(item.key)}
                        style={{
                            display: 'block',
                            width: '100%',
                            padding: '0.6rem 1rem',
                            border: 'none',
                            background: activeSection === item.key ? '#e3f2fd' : 'transparent',
                            color: activeSection === item.key ? '#1565c0' : '#333',
                            fontWeight: activeSection === item.key ? 600 : 400,
                            textAlign: 'left',
                            cursor: 'pointer',
                            fontSize: '0.9rem',
                            borderLeft: activeSection === item.key ? '3px solid #1976d2' : '3px solid transparent',
                        }}
                    >
                        {item.label}
                    </button>
                ))}
            </nav>
            <div style={{ flex: 1, minWidth: 0 }}>
                {renderSection()}
            </div>
        </div>
    );
}
