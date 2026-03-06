import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import { AuthProvider } from './context/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import DashboardPage from './pages/DashboardPage';
import AccountsListPage from './pages/AccountsListPage';
import AccountDetailPage from './pages/AccountDetailPage';
import ImportPage from './pages/ImportPage';
import PricesPage from './pages/PricesPage';
import PropertiesListPage from './pages/PropertiesListPage';
import PropertyDetailPage from './pages/PropertyDetailPage';
import ProjectionsPage from './pages/ProjectionsPage';
import ProjectionComparePage from './pages/ProjectionComparePage';
import ProjectionDetailPage from './pages/ProjectionDetailPage';
import SettingsPage from './pages/SettingsPage';
import NotFoundPage from './pages/NotFoundPage';

export default function App() {
    return (
        <AuthProvider>
            <BrowserRouter>
                <Toaster position="top-right" />
                <Routes>
                    <Route path="/login" element={<LoginPage />} />
                    <Route path="/register" element={<RegisterPage />} />
                    <Route path="/" element={<ProtectedRoute><Layout /></ProtectedRoute>}>
                        <Route index element={<DashboardPage />} />
                        <Route path="accounts" element={<AccountsListPage />} />
                        <Route path="accounts/:id" element={<AccountDetailPage />} />
                        <Route path="accounts/:id/import" element={<ImportPage />} />
                        <Route path="prices" element={<PricesPage />} />
                        <Route path="projections" element={<ProjectionsPage />} />
                        <Route path="projections/compare" element={<ProjectionComparePage />} />
                        <Route path="projections/:id" element={<ProjectionDetailPage />} />
                        <Route path="properties" element={<PropertiesListPage />} />
                        <Route path="properties/:id" element={<PropertyDetailPage />} />
                        <Route path="settings" element={<SettingsPage />} />
                        <Route path="*" element={<NotFoundPage />} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </AuthProvider>
    );
}
