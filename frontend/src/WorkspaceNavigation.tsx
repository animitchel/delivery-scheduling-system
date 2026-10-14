import { useEffect, useRef, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import type { Role } from './api';
import './navigation.css';

const mobileQuery = '(max-width: 900px)';
const setup = [
  { label: 'Warehouses', path: '/admin/warehouses', icon: 'warehouse' },
  { label: 'Operators', path: '/admin/operators', icon: 'people' },
  { label: 'Accounts', path: '/admin/accounts', icon: 'lock' },
] as const;
const operations = [
  { label: 'Recipients', path: '/admin/recipients', icon: 'people' },
  { label: 'Deliveries', path: '/admin/deliveries', icon: 'package' },
  { label: 'Generate routes', path: '/admin/routes/generate', icon: 'plan' },
  { label: 'Routes', path: '/admin/routes', icon: 'route' },
] as const;
const paths = {
  home: 'M3 10 12 3l9 7v11h-6v-7H9v7H3Z',
  warehouse: 'M3 21V8l9-5 9 5v13M7 21V11h10v10M7 15h10M7 18h10',
  people: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M22 21v-2a4 4 0 0 0-3-3.87M15 3.13a4 4 0 0 1 0 7.75M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  lock: 'M5 10h14v11H5ZM8 10V6a4 4 0 0 1 8 0v4M12 14v3',
  package: 'm3 7 9-4 9 4v10l-9 4-9-4ZM3 7l9 4 9-4M12 11v10M7.5 5l9 4',
  plan: 'M4 4h6v6H4ZM14 14h6v6h-6ZM14 4h6M17 1v6M7 14v3h7',
  route: 'M7 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM23 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM7 6h8a4 4 0 0 1 0 8H9a2 2 0 0 0 0 4h8',
};
function Icon({ name }: { name: keyof typeof paths }) {
  return <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>;
}

export function WorkspaceNavigation({ role, disabled, isBlocked }: {
  role: Role; disabled: boolean; isBlocked: () => boolean;
}) {
  const location = useLocation();
  const [mobile, setMobile] = useState(() => window.matchMedia?.(mobileQuery).matches ?? false);
  const [openAt, setOpenAt] = useState<string | null>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const expanded = mobile && openAt === location.key;
  useEffect(() => {
    const query = window.matchMedia?.(mobileQuery);
    if (!query) return;
    const resize = () => { setMobile(query.matches); setOpenAt(null); };
    query.addEventListener('change', resize);
    return () => query.removeEventListener('change', resize);
  }, []);
  function close() { setOpenAt(null); toggle.current?.focus(); }
  function item(label: string, path: string, icon: keyof typeof paths, end = false) {
    return <NavLink key={path} to={path} end={end} className={({ isActive }) => isActive ? 'nav-home' : undefined}
      aria-disabled={disabled} onClick={event => {
        if (isBlocked()) { event.preventDefault(); return; }
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        setOpenAt(null);
        if (mobile) window.requestAnimationFrame(() => document.getElementById('main-content')?.focus());
      }}><Icon name={icon} /><span>{label}</span></NavLink>;
  }
  return <div className="workspace-navigation" onKeyDown={event => {
    if (event.key === 'Escape' && expanded) { event.preventDefault(); close(); }
  }}>
    <button ref={toggle} className="navigation-toggle" hidden={!mobile} type="button" aria-controls="workspace-navigation-links" aria-expanded={expanded}
      onClick={() => setOpenAt(expanded ? null : location.key)}>
      <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8"><path d={expanded ? 'm6 6 12 12M6 18 18 6' : 'M4 6h16M4 12h16M4 18h16'} /></svg>
      {expanded ? 'Close menu' : 'Menu'}
    </button>
    <nav id="workspace-navigation-links" aria-label="Main navigation" hidden={mobile && !expanded}>
      {item('Overview', role === 'ADMIN' ? '/admin' : '/driver', 'home', true)}
      {role === 'ADMIN' ? <>
        <div className="nav-group"><p className="nav-label">Setup &amp; access</p>{setup.map(link => item(link.label, link.path, link.icon))}</div>
        <div className="nav-group"><p className="nav-label">Delivery operations</p>{operations.map(link => item(link.label, link.path, link.icon, link.path === '/admin/routes'))}</div>
      </> : <div className="nav-group"><p className="nav-label">On the road</p>{item('Assigned routes', '/driver/routes', 'route')}</div>}
    </nav>
  </div>;
}
