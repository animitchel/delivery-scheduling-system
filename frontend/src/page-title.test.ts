import { describe, expect, it } from 'vitest';
import { workspacePageTitle } from './page-title';
import type { Role } from './api';

describe('workspace page titles', () => {
  it('matches router path casing and trailing slashes without exposing query values', () => {
    expect(workspacePageTitle('/ADMIN/WAREHOUSES/', '?username=private&password=secret', 'ADMIN')).toBe('Warehouses');
  });
  it.each([['/admin/accounts', 'DRIVER'], ['/driver/routes', 'ADMIN']])('titles restricted direct link %s for %s', (path, role) => {
    expect(workspacePageTitle(path, '', role as Role)).toBe('Access restricted');
  });
  it.each(['0', '-1', '01', '2147483648', 'abc', '1&routeId=2'])('describes invalid route links without echoing %s', value => {
    expect(workspacePageTitle('/admin/routes', `?routeId=${value}`, 'ADMIN')).toBe('Invalid route link');
  });
  it('omits private route details from a valid deep-link title', () => {
    expect(workspacePageTitle('/admin/routes', '?routeId=42&recipient=Private', 'ADMIN')).toBe('Routes');
  });
});
