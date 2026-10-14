import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WorkspaceNavigation } from './WorkspaceNavigation';

function viewport(initial: boolean) {
  let mobile = initial;
  const listeners = new Set<() => void>();
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    get matches() { return mobile; },
    addEventListener: (_event: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_event: string, listener: () => void) => listeners.delete(listener),
  })));
  return (value: boolean) => act(() => { mobile = value; listeners.forEach(listener => listener()); });
}
function mount(role: 'ADMIN' | 'DRIVER' = 'ADMIN', path = '/admin', blocked = { value: false }) {
  render(<MemoryRouter initialEntries={[path]}><WorkspaceNavigation role={role} disabled={blocked.value} isBlocked={() => blocked.value} /><main id="main-content" tabIndex={-1}>Workspace content</main></MemoryRouter>);
}
afterEach(() => vi.unstubAllGlobals());

describe('workspace navigation', () => {
  it('keeps mobile links hidden until requested and returns focus on Escape', async () => {
    viewport(true); mount();
    const menu = screen.getByRole('button', { name: 'Menu' });
    expect(menu).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    await userEvent.click(menu);
    expect(menu).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('navigation')).toHaveAttribute('id', menu.getAttribute('aria-controls'));
    await userEvent.tab();
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(menu).toHaveFocus();
  });
  it('closes the mobile menu after navigation and identifies the selected page', async () => {
    viewport(true); mount();
    await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    await userEvent.click(screen.getByRole('link', { name: 'Routes' }));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    expect(screen.getByRole('link', { name: 'Routes' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });
  it('exposes desktop links, closes on resize and does not reopen stale mobile menus', async () => {
    const resize = viewport(true); mount();
    await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    resize(false);
    expect(screen.queryByRole('button', { name: 'Menu' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Accounts' })).toBeVisible();
    resize(true);
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it('keeps pending-write protection even when the ref changes before rendering', async () => {
    viewport(true); const blocked = { value: false }; mount('ADMIN', '/admin', blocked);
    await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    blocked.value = true;
    await userEvent.click(screen.getByRole('link', { name: 'Accounts' }));
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('navigation')).toBeVisible();
  });
  it('does not expose administrator links to drivers', () => {
    viewport(false); mount('DRIVER', '/driver/routes');
    expect(screen.getByRole('link', { name: 'Assigned routes' })).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByRole('link', { name: 'Accounts' })).not.toBeInTheDocument();
  });
  it('marks only generation active on its nested route', () => {
    viewport(false); mount('ADMIN', '/admin/routes/generate');
    expect(screen.getByRole('link', { name: 'Generate routes' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Routes' })).not.toHaveAttribute('aria-current');
  });
});
