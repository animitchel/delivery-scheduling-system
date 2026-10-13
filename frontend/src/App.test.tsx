import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError } from './api';
import type { Account, SessionClient } from './api';
const admin: Account = { id: 1, username: 'admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false };
const driver: Account = { ...admin, username: 'driver', role: 'DRIVER', operatorId: 1 };
function clientFor(account = admin) { return { session: vi.fn<SessionClient['session']>().mockResolvedValue(account), logout: vi.fn<SessionClient['logout']>().mockResolvedValue(undefined) }; }
function mount(client: SessionClient, path = '/', redirect = vi.fn<(path: string) => void>()) {
  render(<MemoryRouter initialEntries={[path]}><App client={client} redirect={redirect} /></MemoryRouter>); return redirect;
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
describe('authenticated workspace', () => {
  it('shows loading before administrator tools and links to working pages', async () => {
    mount(clientFor()); expect(screen.getByRole('status')).toHaveTextContent('Preparing');
    expect(await screen.findByRole('heading', { name: 'Keep deliveries moving.' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Open accounts' })).toHaveAttribute('href', '/html/accounts.html');
    expect(screen.getByRole('link', { name: 'Open scheduling' })).toHaveAttribute('href', '/html/admin.html');
  });
  it('shows driver tools without administrator navigation', async () => {
    mount(clientFor(driver)); await screen.findByRole('heading', { name: 'Your next stop starts here.' });
    expect(screen.queryByRole('link', { name: 'Accounts' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View assigned routes' })).toHaveAttribute('href', '/html/operators.html');
  });
  it('rejects the wrong workspace without mounting its tools', async () => {
    mount(clientFor(driver), '/admin'); await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' });
    expect(screen.queryByRole('link', { name: 'Open accounts' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open your workspace' })).toHaveAttribute('href', '/driver');
  });
  it('provides sign-in for anonymous users', async () => {
    const client = clientFor(); client.session.mockRejectedValue(new ApiError(401)); mount(client);
    expect(await screen.findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/html/login.html');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it('recovers from a failed session read only after an explicit retry', async () => {
    const client = clientFor(); client.session.mockRejectedValueOnce(new ApiError(500)); mount(client);
    await screen.findByRole('alert'); expect(client.session).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', { name: 'Keep deliveries moving.' });
  });
  it('clears authenticated content when the session expires on focus', async () => {
    const client = clientFor(); client.session.mockResolvedValueOnce(admin).mockRejectedValue(new ApiError(401)); mount(client);
    await screen.findByRole('heading', { name: 'Keep deliveries moving.' });
    act(() => { window.dispatchEvent(new Event('focus')); });
    await screen.findByRole('link', { name: 'Sign in' }); expect(screen.queryByText('admin')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it('ignores a delayed identity response after a newer session check', async () => {
    const old = deferred<Account>(); const client = clientFor(driver); client.session.mockReturnValueOnce(old.promise); mount(client);
    act(() => { window.dispatchEvent(new Event('focus')); });
    await screen.findByRole('heading', { name: 'Your next stop starts here.' });
    await act(async () => { old.resolve(admin); await old.promise; });
    expect(screen.queryByRole('heading', { name: 'Keep deliveries moving.' })).not.toBeInTheDocument();
    expect(client.session.mock.calls[0]?.[0].aborted).toBe(true);
  });
  it.each([false, true])('routes mandatory password setup to the existing secure workflow', async forbidden => {
    const client = clientFor({ ...admin, passwordChangeRequired: true });
    if (forbidden) client.session.mockRejectedValue(new ApiError(403, true));
    const redirect = mount(client); await waitFor(() => expect(redirect).toHaveBeenCalledWith('/html/change-password.html'));
    expect(screen.queryByRole('link', { name: 'Open accounts' })).not.toBeInTheDocument();
  });
  it('prevents duplicate sign-out and clears the identity on success', async () => {
    const pending = deferred<void>(); const client = clientFor(); client.logout.mockReturnValue(pending.promise); const redirect = mount(client);
    await screen.findByRole('button', { name: 'Sign out' });
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' })); fireEvent.click(screen.getByRole('button', { name: 'Signing out…' }));
    expect(client.logout).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: 'Signing out…' })).toBeDisabled();
    await act(async () => { pending.resolve(); await pending.promise; });
    expect(redirect).toHaveBeenCalledWith('/html/login.html?loggedOut'); expect(screen.queryByText('admin')).not.toBeInTheDocument();
  });
  it('shows a fixed sign-out error and allows an explicit retry', async () => {
    const client = clientFor(); client.logout.mockRejectedValueOnce(new Error('private details')); mount(client);
    await screen.findByRole('button', { name: 'Sign out' }); await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign out failed');
    expect(screen.queryByText('private details')).not.toBeInTheDocument(); expect(client.logout).toHaveBeenCalledTimes(1);
  });
  it('renders HTML-looking usernames as text', async () => {
    const attack = '<img src=x onerror=alert(1)>'; mount(clientFor({ ...admin, username: attack }));
    await screen.findByText(attack); expect(document.querySelector('img')).toBeNull();
  });
});
