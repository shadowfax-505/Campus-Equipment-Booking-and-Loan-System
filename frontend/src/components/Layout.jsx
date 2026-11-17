import { NavLink, Outlet } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useAuth } from '../auth';
import { get } from '../api';
import * as studentArea from '../routes/student';
import * as staffArea from '../routes/staff';
import * as adminArea from '../routes/admin';

const NAV = {
  STUDENT: [['/', 'Dashboard'], ...studentArea.nav],
  STAFF: [['/', 'Dashboard'], ...staffArea.nav],
};
NAV.ADMIN = [...NAV.STAFF, ...adminArea.nav];

export default function Layout() {
  const { user, signOut } = useAuth();
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    let alive = true;
    const tick = () => get('/notifications?size=1').then((n) => alive && setUnread(n.unread)).catch(() => {});
    tick();
    const t = setInterval(tick, 60000);
    return () => { alive = false; clearInterval(t); };
  }, []);
  return (
    <div className="shell">
      <aside className={`side ${open ? 'open' : ''}`}>
        <div className="brand">Campus<span>Equipment</span></div>
        <nav onClick={() => setOpen(false)}>
          {NAV[user.role].map(([to, l]) => <NavLink key={to} to={to} end={to === '/'}>{l}</NavLink>)}
        </nav>
      </aside>
      <div className="main">
        <header className="top">
          <button className="ghost menu" onClick={() => setOpen(!open)} aria-label="Menu">☰</button>
          <div className="grow" />
          <NavLink to="/notifications" className="bell">Notifications{unread > 0 && <span className="dot">{unread}</span>}</NavLink>
          <NavLink to="/profile" className="who">{user.fullName} <small>{user.role.toLowerCase()}</small></NavLink>
          <button className="ghost" onClick={signOut}>Sign out</button>
        </header>
        <main className="content"><Outlet /></main>
      </div>
    </div>
  );
}
