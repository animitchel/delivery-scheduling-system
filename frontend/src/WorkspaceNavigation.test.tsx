import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
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
function PageContent() {
  const location = useLocation(); const navigate = useNavigate();
  return <><main id="main-content" tabIndex={-1}>Workspace content {location.pathname}</main>
    <button onClick={() => navigate(-1)}>Back</button><button onClick={() => navigate(1)}>Forward</button>
    <button onClick={() => navigate('/admin#details')}>Hash link</button></>;
}
function mount(role: 'ADMIN' | 'DRIVER' = 'ADMIN', path = '/admin', blocked = { value: false }) {
  render(<MemoryRouter initialEntries={[path]}><WorkspaceNavigation role={role} disabled={blocked.value} isBlocked={() => blocked.value} /><PageContent /></MemoryRouter>);
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
  it.each([false, true])('focuses destination content after keyboard navigation, mobile=%s', async mobile => {
    viewport(mobile); mount();
    const main = screen.getByRole('main'); expect(main).not.toHaveFocus();
    if (mobile) await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    screen.getByRole('link', { name: 'Warehouses' }).focus(); await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(main).toHaveFocus()); expect(main).toHaveTextContent('/admin/warehouses');
    if (mobile) expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it('focuses committed content after Back and Forward navigation', async () => {
    viewport(false); mount(); await userEvent.click(screen.getByRole('link', { name: 'Accounts' }));
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('main')).toHaveFocus(); expect(screen.getByRole('main')).toHaveTextContent('Workspace content /admin');
    await userEvent.click(screen.getByRole('button', { name: 'Forward' }));
    expect(screen.getByRole('main')).toHaveFocus(); expect(screen.getByRole('main')).toHaveTextContent('/admin/accounts');
  });
  it('keeps current focus for modified clicks, blocked navigation and hash-only changes', async () => {
    viewport(false); const blocked = { value: false }; mount('ADMIN', '/admin', blocked);
    const accounts = screen.getByRole('link', { name: 'Accounts' }); accounts.focus();
    // The browser handles modified clicks; jsdom cannot open a new tab.
    accounts.addEventListener('click', event => event.preventDefault(), { once: true });
    fireEvent.click(accounts, { ctrlKey: true }); expect(accounts).toHaveFocus();
    blocked.value = true; await userEvent.click(accounts); expect(accounts).toHaveFocus();
    expect(screen.getByRole('main')).toHaveTextContent('Workspace content /admin');
    await userEvent.click(screen.getByRole('button', { name: 'Hash link' }));
    expect(screen.getByRole('button', { name: 'Hash link' })).toHaveFocus();
  });
  it('moves focus out of the mobile menu when selecting the current page', async () => {
    viewport(true); mount(); await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    await userEvent.click(screen.getByRole('link', { name: 'Overview' }));
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument(); expect(screen.getByRole('main')).toHaveFocus();
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
