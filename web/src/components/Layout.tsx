import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { notificationApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { Button } from './ui';

type NavItem = { to: string; icon: string; label: string; end?: boolean };

const MAIN: NavItem[] = [
  { to: '/', icon: '🏠', label: 'Home', end: true },
  { to: '/pay', icon: '💸', label: 'Pay' },
  { to: '/requests', icon: '🤝', label: 'Requests' },
  { to: '/wallet', icon: '👛', label: 'Wallet' },
  { to: '/transactions', icon: '📜', label: 'History' },
  { to: '/bills', icon: '🧾', label: 'Bills & recharge' },
  { to: '/rewards', icon: '🎁', label: 'Rewards' },
  { to: '/split', icon: '🍕', label: 'Split a bill' },
];
const ACCOUNT: NavItem[] = [
  { to: '/notifications', icon: '🔔', label: 'Notifications' },
  { to: '/disputes', icon: '🛟', label: 'Help & disputes' },
  { to: '/profile', icon: '👤', label: 'Profile & security' },
];
const ADMIN: NavItem[] = [
  { to: '/admin/users', icon: '🔎', label: 'Users' },
  { to: '/admin/disputes', icon: '⚖️', label: 'Disputes' },
  { to: '/admin/refunds', icon: '↩️', label: 'Refunds' },
  { to: '/admin/risk', icon: '🛡️', label: 'Risk' },
  { to: '/admin/recon', icon: '🧮', label: 'Reconciliation' },
  { to: '/admin/rewards', icon: '🏷️', label: 'Rules & coupons' },
  { to: '/admin/notifications', icon: '📨', label: 'Templates & DLT' },
  { to: '/admin/audit', icon: '🗂️', label: 'Audit log' },
];
const BOTTOM: NavItem[] = [MAIN[0], MAIN[1], MAIN[4], MAIN[6], ACCOUNT[2]];

export default function Layout() {
  const { isStaff, me, signOut } = useAuth();
  const navigate = useNavigate();
  const inbox = useQuery({ queryKey: ['inbox', 0], queryFn: () => notificationApi.inbox(0), refetchInterval: 30_000 });
  const unread = inbox.data?.unread ?? 0;

  const link = (item: NavItem) => (
    <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
      <span className="nav-icon" aria-hidden>{item.icon}</span>
      {item.label}
      {item.to === '/notifications' && unread > 0 && <span className="nav-count" aria-label={`${unread} unread`}>{unread}</span>}
    </NavLink>
  );

  return (
    <div className="shell">
      <aside className="sidebar" aria-label="Main navigation">
        <div className="brand"><span className="brand-mark">P</span> PayVerse</div>
        {MAIN.map(link)}
        <div className="nav-section">Account</div>
        {ACCOUNT.map(link)}
        {isStaff && (
          <>
            <div className="nav-section">Back office</div>
            {ADMIN.map(link)}
          </>
        )}
        <div className="sidebar-footer">
          <span className="small muted">Signed in{me?.roles.length ? ` · ${me.roles.join(', ').toLowerCase()}` : ''}</span>
          <Button variant="secondary" onClick={async () => { await signOut(); navigate('/login'); }}>Sign out</Button>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="brand" style={{ padding: 0 }}><span className="brand-mark">P</span> PayVerse</div>
          <NavLink to="/notifications" className="icon-btn" aria-label="Notifications">🔔{unread > 0 && <span className="nav-count">{unread}</span>}</NavLink>
        </header>
        <main className="content">
          <Outlet />
        </main>
        <nav className="bottom-nav" aria-label="Quick navigation">
          {BOTTOM.map((i) => (
            <NavLink key={i.to} to={i.to} end={i.end} className={({ isActive }) => (isActive ? 'active' : '')}>
              <span aria-hidden>{i.icon}</span><span>{i.label.split(' ')[0]}</span>
            </NavLink>
          ))}
        </nav>
      </div>
    </div>
  );
}
