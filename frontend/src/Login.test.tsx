import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Login } from './Login';
import { ApiError } from './api';
const account = { id: 1, username: 'admin', role: 'ADMIN' as const, operatorId: null, enabled: true, passwordChangeRequired: false };
describe('React sign-in', () => {
  it.each([
    [account, '/app/admin'],
    [{ ...account, role: 'DRIVER' as const, operatorId: 4 }, '/app/driver/routes'],
    [{ ...account, passwordChangeRequired: true }, '/html/change-password.html'],
  ])('uses the server identity destination %#', async (identity, destination) => {
    const login = vi.fn().mockResolvedValue(identity), redirect = vi.fn();
    render(<Login client={{ login }} redirect={redirect} />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'admin');
    await user.type(screen.getByLabelText('Password'), 'private test password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(redirect).toHaveBeenCalledWith(destination));
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });
  it('reports invalid credentials, clears the password, and allows another attempt', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError(401));
    render(<Login client={{ login }} />);
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'incorrect' } });
    fireEvent.submit(document.querySelector('form')!);
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password.');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });
  it('prevents duplicate submissions while the login request is pending', async () => {
    let finish!: (value: typeof account) => void;
    const login = vi.fn().mockReturnValue(new Promise(resolve => { finish = resolve; }));
    render(<Login client={{ login }} redirect={vi.fn()} />);
    fireEvent.submit(document.querySelector('form')!); fireEvent.submit(document.querySelector('form')!);
    expect(login).toHaveBeenCalledTimes(1);
    finish(account); await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled());
  });
  it('lets users reveal and hide their password with an accessible button', async () => {
    render(<Login />); const user = userEvent.setup();
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
  });
});
