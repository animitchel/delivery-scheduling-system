import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PasswordSetup } from './PasswordSetup';
import { ApiError } from './api';
const account = { id: 2, username: 'driver', role: 'DRIVER' as const, operatorId: 1, enabled: true, passwordChangeRequired: true };
function mount() {
  const client = { session: vi.fn().mockResolvedValue(account), request: vi.fn().mockResolvedValue(new Response(null, { status: 204 })), logout: vi.fn().mockResolvedValue(undefined) };
  const redirect = vi.fn(); render(<PasswordSetup client={client} redirect={redirect} />); return { client, redirect };
}
async function fill(newPassword = 'a new permanent passphrase', confirm = newPassword) {
  await waitFor(() => expect(screen.getByLabelText('Current password')).toBeEnabled());
  fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'a temporary private passphrase' } });
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: newPassword } });
  fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: confirm } });
}
describe('password setup', () => {
  it('sends verified credentials and returns to login after saving', async () => {
    const { client, redirect } = mount(); await fill(); fireEvent.submit(document.querySelector('form')!);
    await waitFor(() => expect(redirect).toHaveBeenCalledWith('/app/login?passwordChanged'));
    expect(client.request).toHaveBeenCalledWith('/api/auth/password', expect.objectContaining({ method: 'POST', body: JSON.stringify({ currentPassword: 'a temporary private passphrase', newPassword: 'a new permanent passphrase' }) }));
    expect(screen.getByLabelText('Current password')).toHaveValue('');
    expect(screen.getByLabelText('New password')).toHaveValue('');
    expect(screen.getByLabelText('Confirm new password')).toHaveValue('');
  });
  it.each([['short','short'], ['a new permanent passphrase','different confirmation'], ['é'.repeat(37),'é'.repeat(37)], ['a temporary private passphrase','a temporary private passphrase']])('rejects invalid or unmatched passwords before posting %#', async (password, confirmation) => {
    const { client } = mount(); await fill(password, confirmation); fireEvent.submit(document.querySelector('form')!);
    expect(await screen.findByRole('alert')).not.toBeEmptyDOMElement(); expect(client.request).not.toHaveBeenCalled();
  });
  it('clears credentials and reports current-password verification failure', async () => {
    const { client } = mount(); client.request.mockRejectedValue(new ApiError(403)); await fill(); fireEvent.submit(document.querySelector('form')!);
    expect(await screen.findByRole('alert')).toHaveTextContent('Current password verification failed.');
    expect(screen.getByLabelText('New password')).toHaveValue('');
  });
  it('signs out through the protected API', async () => {
    const { client, redirect } = mount(); await fill(); fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(redirect).toHaveBeenCalledWith('/app/login?loggedOut')); expect(client.logout).toHaveBeenCalledTimes(1);
  });
  it('prevents duplicate password mutations', async () => {
    const { client } = mount(); let finish!: () => void;
    client.request.mockImplementation(() => new Promise(resolve => { finish = () => resolve(new Response(null, { status: 204 })); }));
    await fill(); fireEvent.submit(document.querySelector('form')!); fireEvent.submit(document.querySelector('form')!);
    expect(client.request).toHaveBeenCalledTimes(1); finish();
    await waitFor(() => expect(screen.getByLabelText('New password')).toHaveValue(''));
  });
});
