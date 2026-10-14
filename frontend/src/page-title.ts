import { useEffect } from 'react';
import type { Role } from './api';

export function usePageTitle(title: string) {
  useEffect(() => { document.title = `${title} | Dispatch`; }, [title]);
}

const pages: Record<string, { title: string; role: Role }> = {
  '/admin': { title: 'Operations overview', role: 'ADMIN' },
  '/admin/warehouses': { title: 'Warehouses', role: 'ADMIN' },
  '/admin/operators': { title: 'Operators', role: 'ADMIN' },
  '/admin/accounts': { title: 'Accounts', role: 'ADMIN' },
  '/admin/recipients': { title: 'Recipients', role: 'ADMIN' },
  '/admin/deliveries': { title: 'Deliveries', role: 'ADMIN' },
  '/admin/routes/generate': { title: 'Generate routes', role: 'ADMIN' },
  '/admin/routes': { title: 'Routes', role: 'ADMIN' },
  '/driver': { title: 'Driver overview', role: 'DRIVER' },
  '/driver/routes': { title: 'Assigned routes', role: 'DRIVER' },
};

export function invalidRouteLink(params: URLSearchParams) {
  const values = params.getAll('routeId'); const value = values[0];
  return values.length > 1 || value !== undefined && (!/^[1-9][0-9]*$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) > 2147483647);
}

export function workspacePageTitle(pathname: string, search: string, role: Role) {
  // Match the router's case-insensitive paths and optional trailing slash.
  const path = pathname.replace(/\/+$/, '').toLowerCase();
  if (!path) return role === 'ADMIN' ? 'Operations overview' : 'Driver overview';
  const page = pages[path];
  if (!page) return 'Page not found';
  if (page.role !== role) return 'Access restricted';
  if (path === '/admin/routes' && invalidRouteLink(new URLSearchParams(search))) return 'Invalid route link';
  return page.title;
}
