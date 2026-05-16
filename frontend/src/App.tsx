import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import { Toaster } from 'react-hot-toast';
import { AuthProvider } from './context/AuthContext';
import { ProjectionCacheProvider } from './context/ProjectionCacheContext';
import ProtectedRoute from './components/ProtectedRoute';
import Layout from './components/Layout';
import LoadingState from './components/LoadingState';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';

// Authenticated pages are lazily loaded so each route ships as its own chunk.
// This keeps the initial download (login form) small — heavy dependencies such
// as recharts fall into the chart-bearing pages' chunks rather than the entry
// bundle. See docs/quality/2026-05-15-performance-findings.md finding 4.1/4.2.
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const AccountsListPage = lazy(() => import('./pages/AccountsListPage'));
const AccountDetailPage = lazy(() => import('./pages/AccountDetailPage'));
const ImportPage = lazy(() => import('./pages/ImportPage'));
const PricesPage = lazy(() => import('./pages/PricesPage'));
const PropertiesListPage = lazy(() => import('./pages/PropertiesListPage'));
const PropertyDetailPage = lazy(() => import('./pages/PropertyDetailPage'));
const ProjectionsPage = lazy(() => import('./pages/ProjectionsPage'));
const ProjectionComparePage = lazy(() => import('./pages/ProjectionComparePage'));
const ProjectionDetailPage = lazy(() => import('./pages/ProjectionDetailPage'));
const SpendingOptimizerPage = lazy(() => import('./pages/SpendingOptimizerPage'));
const SpendingProfilesPage = lazy(() => import('./pages/SpendingProfilesPage'));
const IncomeSourcesPage = lazy(() => import('./pages/IncomeSourcesPage'));
const HoldingDetailPage = lazy(() => import('./pages/HoldingDetailPage'));
const AdminAreaPage = lazy(() => import('./pages/AdminAreaPage'));
const DataExportPage = lazy(() => import('./pages/DataExportPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

export default function App() {
    return (
        <AuthProvider>
            <ProjectionCacheProvider>
            <BrowserRouter>
                <Toaster position="top-right" />
                <Suspense fallback={<LoadingState message="Loading…" />}>
                    <Routes>
                        <Route path="/login" element={<LoginPage />} />
                        <Route path="/register" element={<RegisterPage />} />
                        <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
                            <Route index element={<DashboardPage />} />
                            <Route path="accounts" element={<AccountsListPage />} />
                            <Route path="accounts/:id" element={<AccountDetailPage />} />
                            <Route path="accounts/:id/import" element={<ImportPage />} />
                            <Route path="holdings/:id" element={<HoldingDetailPage />} />
                            <Route path="prices" element={<PricesPage />} />
                            <Route path="projections" element={<ProjectionsPage />} />
                            <Route path="projections/compare" element={<ProjectionComparePage />} />
                            <Route path="projections/:id" element={<ProjectionDetailPage />} />
                            <Route path="projections/:id/optimize" element={<SpendingOptimizerPage />} />
                            <Route path="spending-profiles" element={<SpendingProfilesPage />} />
                            <Route path="income-sources" element={<IncomeSourcesPage />} />
                            <Route path="properties" element={<PropertiesListPage />} />
                            <Route path="properties/:id" element={<PropertyDetailPage />} />
                            <Route path="admin" element={<AdminAreaPage />} />
                            <Route path="admin/prices" element={<Navigate to="/admin" replace />} />
                            <Route path="audit-log" element={<Navigate to="/admin" replace />} />
                            <Route path="export" element={<DataExportPage />} />
                            <Route path="settings" element={<Navigate to="/admin" replace />} />
                            <Route path="*" element={<NotFoundPage />} />
                        </Route>
                    </Routes>
                </Suspense>
            </BrowserRouter>
            </ProjectionCacheProvider>
        </AuthProvider>
    );
}
