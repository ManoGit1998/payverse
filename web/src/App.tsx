import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import { useAuth } from './auth/AuthContext';
import Layout from './components/Layout';
import { Spinner } from './components/ui';
import LoginPage from './pages/LoginPage';

const ForgotPinPage = lazy(() => import('./pages/ForgotPinPage'));
const OnboardingPage = lazy(() => import('./pages/OnboardingPage'));
const HomePage = lazy(() => import('./pages/HomePage'));
const PayPage = lazy(() => import('./pages/PayPage'));
const RequestsPage = lazy(() => import('./pages/RequestsPage'));
const SplitPage = lazy(() => import('./pages/SplitPage'));
const WalletPage = lazy(() => import('./pages/WalletPage'));
const TransactionsPage = lazy(() => import('./pages/TransactionsPage'));
const BillsPage = lazy(() => import('./pages/BillsPage'));
const RewardsPage = lazy(() => import('./pages/RewardsPage'));
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'));
const DisputesPage = lazy(() => import('./pages/DisputesPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const UsersAdminPage = lazy(() => import('./pages/admin/UsersAdminPage'));
const DisputesAdminPage = lazy(() => import('./pages/admin/DisputesAdminPage'));
const RefundsAdminPage = lazy(() => import('./pages/admin/RefundsAdminPage'));
const RiskAdminPage = lazy(() => import('./pages/admin/RiskAdminPage'));
const ReconAdminPage = lazy(() => import('./pages/admin/ReconAdminPage'));
const RewardsAdminPage = lazy(() => import('./pages/admin/RewardsAdminPage'));
const NotificationsAdminPage = lazy(() => import('./pages/admin/NotificationsAdminPage'));
const AuditAdminPage = lazy(() => import('./pages/admin/AuditAdminPage'));

function RequireAuth({ children }: { children: ReactNode }) {
  const { status, me } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <div className="content"><Spinner label="Signing you in…" /></div>;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (me && !me.pinSet && location.pathname !== '/onboarding') return <Navigate to="/onboarding" replace />;
  return <>{children}</>;
}

function RequireStaff({ children }: { children: ReactNode }) {
  const { isStaff } = useAuth();
  return isStaff ? <>{children}</> : <Navigate to="/" replace />;
}

function AnonymousOnly({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') return <div className="content"><Spinner /></div>;
  return status === 'authenticated' ? <Navigate to="/" replace /> : <>{children}</>;
}

export default function App() {
  return (
    <Suspense fallback={<div className="content"><Spinner /></div>}>
      <Routes>
        <Route path="/login" element={<AnonymousOnly><LoginPage /></AnonymousOnly>} />
        <Route path="/forgot-pin" element={<ForgotPinPage />} />
        <Route path="/onboarding" element={<RequireAuth><OnboardingPage /></RequireAuth>} />
        <Route element={<RequireAuth><Layout /></RequireAuth>}>
          <Route index element={<HomePage />} />
          <Route path="pay" element={<PayPage />} />
          <Route path="requests" element={<RequestsPage />} />
          <Route path="split" element={<SplitPage />} />
          <Route path="wallet" element={<WalletPage />} />
          <Route path="transactions" element={<TransactionsPage />} />
          <Route path="bills" element={<BillsPage />} />
          <Route path="rewards" element={<RewardsPage />} />
          <Route path="notifications" element={<NotificationsPage />} />
          <Route path="disputes" element={<DisputesPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="admin" element={<RequireStaff><Navigate to="/admin/users" replace /></RequireStaff>} />
          <Route path="admin/users" element={<RequireStaff><UsersAdminPage /></RequireStaff>} />
          <Route path="admin/disputes" element={<RequireStaff><DisputesAdminPage /></RequireStaff>} />
          <Route path="admin/refunds" element={<RequireStaff><RefundsAdminPage /></RequireStaff>} />
          <Route path="admin/risk" element={<RequireStaff><RiskAdminPage /></RequireStaff>} />
          <Route path="admin/recon" element={<RequireStaff><ReconAdminPage /></RequireStaff>} />
          <Route path="admin/rewards" element={<RequireStaff><RewardsAdminPage /></RequireStaff>} />
          <Route path="admin/notifications" element={<RequireStaff><NotificationsAdminPage /></RequireStaff>} />
          <Route path="admin/audit" element={<RequireStaff><AuditAdminPage /></RequireStaff>} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
