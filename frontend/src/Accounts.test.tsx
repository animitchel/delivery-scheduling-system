import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Accounts } from './Accounts';
import { ApiError } from './api';
import type { AccountsClient, AccountSummary, Credential, EligibleOperator, Page } from './accounts-api';
const admin: AccountSummary = { id: 1, username: 'admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false };
const driver: AccountSummary = { id: 2, username: 'test.driver', role: 'DRIVER', operatorId: 4, enabled: true, passwordChangeRequired: false };
const credential: Credential = { account: { ...driver, passwordChangeRequired: true }, temporaryPassword: 'A'.repeat(32), expiresAt: '2026-10-14T12:00:00Z' };
function page<T>(items: T[], size: number): Page<T> { return { items, page: 0, size, totalElements: items.length, totalPages: items.length ? 1 : 0 }; }
function clientFor() { return { accounts: vi.fn<AccountsClient['accounts']>().mockResolvedValue(page([admin, driver],20)), operators: vi.fn<AccountsClient['operators']>().mockResolvedValue(page([{ operatorId: 4, name: '<img src=x onerror=alert(1)>' }],50)), create: vi.fn<AccountsClient['create']>().mockResolvedValue(credential), enabled: vi.fn<AccountsClient['enabled']>().mockResolvedValue(undefined), reset: vi.fn<AccountsClient['reset']>().mockResolvedValue(credential) }; }
function deferred<T>() { let resolve!: (v:T) => void; const promise = new Promise<T>(r=>{ resolve = r; }); return { promise, resolve }; }
async function mount(client = clientFor(), auth = vi.fn(), busy = vi.fn()) { const view = render(<Accounts client={client} onAuthError={auth} onBusyChange={busy} />); await screen.findByText('test.driver'); await waitFor(()=>expect(screen.getByLabelText('Eligible operator')).toBeEnabled()); return view; }
async function fillCreation() { await userEvent.type(screen.getByLabelText('Driver username'), 'test.driver'); await userEvent.selectOptions(screen.getByLabelText('Eligible operator'),'4'); await userEvent.type(screen.getByLabelText('Your administrator password', { selector: '#create-current-password' }), 'test password'); }
async function create() { await fillCreation(); await userEvent.click(screen.getByRole('button',{name:'Create driver account'})); }
describe('React administrator accounts', () => {
  it('renders directory and operator names as inert text with no administrator actions', async () => {
    await mount(); expect(within(screen.getByRole('row',{name:/admin ADMIN/})).queryByRole('button')).not.toBeInTheDocument(); expect(document.querySelector('img')).toBeNull();
    expect(screen.getByRole('option',{name:/<img/})).toBeInTheDocument();
  });
  it('creates once, clears the admin password immediately, and clears one-time credentials on close', async () => {
    const pending = deferred<Credential>(); const client = clientFor(); client.create.mockReturnValue(pending.promise); const busy = vi.fn(); await mount(client, vi.fn(), busy); await fillCreation();
    const button = screen.getByRole('button',{name:'Create driver account'}); fireEvent.click(button); fireEvent.submit(button.closest('form')!);
    expect(client.create).toHaveBeenCalledTimes(1); expect(screen.getByLabelText('Your administrator password', { selector: '#create-current-password' })).toHaveValue(''); expect(button).toBeDisabled();
    await act(async()=>{ pending.resolve(credential); await pending.promise; }); await screen.findByRole('dialog',{name:'Temporary driver password'});
    expect(screen.getByLabelText('Temporary password')).toHaveValue(credential.temporaryPassword); await userEvent.click(screen.getByRole('button',{name:'Done'}));
    expect(document.querySelector<HTMLInputElement>('#temporary-driver-password')?.value).toBe(''); expect(localStorage.length+sessionStorage.length).toBe(0); expect(busy.mock.calls).toEqual([[true],[false]]);
  });
  it('clears credentials on Escape/page hide and ignores late clipboard results', async () => {
    const copy = deferred<void>(); Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:vi.fn().mockReturnValue(copy.promise)}});
    await mount(); await create(); await screen.findByRole('dialog',{name:'Temporary driver password'}); await userEvent.click(screen.getByRole('button',{name:'Copy password'}));
    fireEvent(document.querySelector('.account-dialog[open]')!,new Event('cancel',{cancelable:true}));
    await act(async()=>{ copy.resolve(); await copy.promise; }); expect(document.querySelector<HTMLInputElement>('#temporary-driver-password')?.value).toBe(''); expect(screen.queryByText(/Copied/)).not.toBeInTheDocument();
    act(()=>{ window.dispatchEvent(new Event('pagehide')); }); expect(document.querySelector<HTMLInputElement>('#temporary-driver-password')?.value).toBe('');
  });
  it('offers manual copying when clipboard access fails', async () => { Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:vi.fn().mockRejectedValue(new Error('private detail'))}}); await mount(); await create(); await screen.findByRole('dialog',{name:'Temporary driver password'}); await userEvent.click(screen.getByRole('button',{name:'Copy password'})); await screen.findByText(/Clipboard unavailable/); expect(screen.queryByText('private detail')).not.toBeInTheDocument(); });
  it('requires password confirmation for disable and reloads authoritative state', async () => {
    const client = clientFor(); await mount(client); await userEvent.click(screen.getByRole('button',{name:'Disable'})); const dialog = await screen.findByRole('dialog'); expect(client.enabled).not.toHaveBeenCalled();
    await userEvent.type(within(dialog).getByLabelText('Your administrator password'),'test password'); client.accounts.mockResolvedValue(page([admin,{...driver,enabled:false}],20));
    await userEvent.click(within(dialog).getByRole('button',{name:'Confirm disable'})); await screen.findByRole('button',{name:'Enable'}); expect(client.enabled).toHaveBeenCalledWith(2,false,'test password',expect.any(AbortSignal));
    expect(document.querySelector<HTMLInputElement>('#action-current-password')?.value).toBe('');
  });
  it('resets disabled drivers without implying they have been enabled', async () => {
    const client = clientFor(); client.accounts.mockResolvedValue(page([admin,{...driver,enabled:false}],20)); client.reset.mockResolvedValue({...credential,account:{...credential.account,enabled:false}}); await mount(client);
    await userEvent.click(screen.getByRole('button',{name:'Reset password'})); const dialog = screen.getByRole('dialog'); expect(within(dialog).getByText(/disabled account remains disabled/)).toBeVisible(); await userEvent.type(within(dialog).getByLabelText('Your administrator password'),'test password'); await userEvent.click(within(dialog).getByRole('button',{name:'Confirm reset password'}));
    await screen.findByRole('dialog',{name:'Temporary driver password'}); expect(client.reset).toHaveBeenCalledTimes(1); expect(client.enabled).not.toHaveBeenCalled();
  });
  it.each([400,403,404,409,500])('blocks further mutations after error %s until explicit reload', async status => {
    const client = clientFor(); client.create.mockRejectedValueOnce(new ApiError(status)); await mount(client); await create(); await screen.findByRole('alert');
    expect(client.create).toHaveBeenCalledTimes(1); expect(client.accounts).toHaveBeenCalledTimes(1); expect(screen.getByRole('button',{name:'Create driver account'})).toBeDisabled(); expect(screen.getByLabelText('Your administrator password', { selector: '#create-current-password' })).toHaveValue('');
    await userEvent.click(screen.getByRole('button',{name:'Reload accounts'})); await screen.findByText('test.driver'); expect(client.accounts).toHaveBeenCalledTimes(2);
  });
  it('handles session expiry without showing a received credential', async () => { const client = clientFor(); const auth = vi.fn(); client.create.mockRejectedValue(new ApiError(401)); await mount(client,auth); await create(); await waitFor(()=>expect(auth).toHaveBeenCalledWith(expect.objectContaining({status:401}))); expect(document.querySelector<HTMLInputElement>('#temporary-driver-password')?.value).toBe(''); });
  it('ignores obsolete operator search results and aborts them', async () => {
    const old = deferred<Page<EligibleOperator>>(); const client = clientFor(); await mount(client); client.operators.mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([{operatorId:8,name:'Latest operator'}],50));
    await userEvent.type(screen.getByLabelText('Search eligible operators'),'old'); await userEvent.click(screen.getByRole('button',{name:'Search operators'})); await userEvent.clear(screen.getByLabelText('Search eligible operators')); await userEvent.type(screen.getByLabelText('Search eligible operators'),'new'); await userEvent.click(screen.getByRole('button',{name:'Search operators'}));
    await screen.findByRole('option',{name:'Latest operator (#8)'}); await act(async()=>{ old.resolve(page([{operatorId:9,name:'Obsolete operator'}],50)); await old.promise; }); expect(screen.queryByRole('option',{name:/Obsolete/})).not.toBeInTheDocument(); expect(client.operators.mock.calls[1]?.[2].aborted).toBe(true);
  });
  it('paginates both independent lists and recovers when the final account page disappears', async () => {
    const client = clientFor(); client.accounts.mockResolvedValueOnce({ ...page([admin,driver],20), totalElements:21,totalPages:2 }); client.operators.mockResolvedValueOnce({ ...page([{operatorId:4,name:'First operator'}],50),totalElements:51,totalPages:2 });
    await mount(client); client.accounts.mockResolvedValueOnce({ ...page([],20),page:1,totalElements:2,totalPages:1 }).mockResolvedValueOnce(page([admin,driver],20));
    await userEvent.click(screen.getByRole('button',{name:'Next accounts'})); await screen.findByText('test.driver'); expect(client.accounts.mock.calls.map(call=>call[0])).toEqual([0,1,0]);
    client.operators.mockResolvedValueOnce({ ...page([{operatorId:8,name:'Second operator'}],50),page:1,totalElements:51,totalPages:2 }); await userEvent.click(screen.getByRole('button',{name:'Next operators'})); await screen.findByRole('option',{name:'Second operator (#8)'}); expect(client.operators.mock.calls.at(-1)?.slice(0,2)).toEqual([1,'']);
  });
  it('shows empty lists without exposing enabled mutations', async () => {
    const client = clientFor(); client.accounts.mockResolvedValue(page([],20)); client.operators.mockResolvedValue(page([],50)); render(<Accounts client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />);
    await screen.findByText('No accounts found.'); await screen.findByText(/No eligible operators match/); expect(screen.getByRole('button',{name:'Create driver account'})).toBeDisabled();
  });
  it('bounds stalled account reads and allows explicit reload', async () => {
    const client = clientFor(); client.accounts.mockImplementation((_page,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); vi.useFakeTimers();
    try { render(<Accounts client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await act(async()=>{await vi.advanceTimersByTimeAsync(30000);}); expect(screen.getByRole('alert')).toHaveTextContent('Accounts could not be loaded'); expect(screen.getByRole('button',{name:'Reload accounts'})).toBeEnabled(); } finally { vi.useRealTimers(); }
  });
  it('routes mandatory password setup without rendering credentials', async () => {
    const client = clientFor(); const auth = vi.fn(); client.create.mockRejectedValue(new ApiError(403,true)); await mount(client,auth); await create(); await waitFor(()=>expect(auth).toHaveBeenCalledWith(expect.objectContaining({passwordChangeRequired:true}))); expect(document.querySelector<HTMLInputElement>('#temporary-driver-password')?.value).toBe('');
  });
  it('ignores credentials received after leaving the screen', async () => { const pending = deferred<Credential>(); const client = clientFor(); client.create.mockReturnValue(pending.promise); const view = await mount(client); await create(); view.unmount(); expect(client.create.mock.calls[0]?.[3].aborted).toBe(true); await act(async()=>{pending.resolve(credential);await pending.promise;}); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); });
  it('bounds a hanging write and never retries it automatically', async () => {
    const client = clientFor(); client.create.mockImplementation((_u,_o,_p,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); await mount(client); await fillCreation(); vi.useFakeTimers();
    try { fireEvent.click(screen.getByRole('button',{name:'Create driver account'})); await act(async()=>{await vi.advanceTimersByTimeAsync(15000);}); expect(screen.getByRole('alert')).toHaveTextContent('outcome could not be confirmed'); expect(client.create).toHaveBeenCalledTimes(1); } finally { vi.useRealTimers(); }
  });
  it('focuses account setup from the primary action without submitting', async () => {
    const client=clientFor(); await mount(client);
    await userEvent.click(screen.getByRole('button',{name:'Add driver account'}));
    expect(screen.getByRole('heading',{name:'Create driver account'})).toHaveFocus();
    expect(screen.getByRole('group',{name:'Driver identity'})).toBeVisible();
    expect(screen.getByRole('group',{name:'Administrator confirmation'})).toBeVisible();
    expect(client.create).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Driver username')).toHaveAccessibleDescription(/3–64 lowercase/);
  });
  it('clears a no-result eligible-operator search from page zero', async () => {
    const client=clientFor(); await mount(client);
    client.operators.mockResolvedValueOnce(page<EligibleOperator>([],50));
    await userEvent.type(screen.getByLabelText('Search eligible operators'),'Missing');
    await userEvent.click(screen.getByRole('button',{name:'Search operators'}));
    await screen.findByRole('heading',{name:'No eligible operators match this search.'});
    await userEvent.click(screen.getByRole('button',{name:'Clear operator search'}));
    await waitFor(()=>expect(screen.getByLabelText('Eligible operator')).toBeEnabled());
    expect(screen.getByLabelText('Search eligible operators')).toHaveValue('');
    expect(client.operators.mock.calls.at(-1)?.slice(0,2)).toEqual([0,'']);
    expect(screen.getByRole('button',{name:'Create driver account'})).toBeDisabled();
  });
  it('uses one semantic account record with readable access and setup states', async () => {
    const client=clientFor(); client.accounts.mockResolvedValue(page([admin,{...driver,enabled:false,passwordChangeRequired:true}],20));
    await mount(client); const table=screen.getByRole('table',{name:'Accounts and driver access controls'});
    expect(within(table).getAllByRole('rowheader')).toHaveLength(2);
    const record=within(table).getByRole('row',{name:/test.driver DRIVER/});
    expect(within(record).getByRole('cell',{name:'Disabled'})).toBeVisible();
    expect(within(record).getByRole('cell',{name:'Required'})).toBeVisible();
    expect(within(record).getByRole('button',{name:'Enable'})).toHaveAccessibleDescription('test.driver');
  });
  it('explicitly focuses Done and clears the credential before returning focus to setup', async () => {
    await mount(); await create(); await screen.findByRole('dialog',{name:'Temporary driver password'});
    expect(screen.getByRole('button',{name:'Done'})).toHaveFocus();
    await userEvent.click(screen.getByRole('button',{name:'Done'}));
    expect(screen.getByLabelText('Temporary password')).toHaveValue('');
    expect(screen.getByRole('heading',{name:'Create driver account'})).toHaveFocus();
    expect(localStorage.length+sessionStorage.length).toBe(0);
  });
  it('restores action focus on Escape and clears the reauthentication field', async () => {
    await mount(); const trigger=screen.getByRole('button',{name:'Disable'}); await userEvent.click(trigger);
    const dialog=await screen.findByRole('dialog',{name:'Disable test.driver'});
    await userEvent.type(within(dialog).getByLabelText('Your administrator password'),'do not retain this');
    fireEvent(dialog,new Event('cancel',{cancelable:true}));
    act(()=>{(dialog as HTMLDialogElement).close();});
    expect(document.querySelector<HTMLInputElement>('#action-current-password')?.value).toBe('');
    expect(trigger).toHaveFocus();
  });

  it('explains that operator-search recovery still requires a full account reload', async () => {
    const client=clientFor(); await mount(client); client.operators.mockRejectedValueOnce(new ApiError(500));
    await userEvent.click(screen.getByRole('button',{name:'Search operators'}));
    await screen.findByText('Eligible operators could not be loaded. Reload accounts to try again.');
    await userEvent.click(screen.getByRole('button',{name:'Search operators'}));
    await screen.findByText('Reload accounts before another action to review current access and eligible operators.');
    expect(screen.getByRole('button',{name:'Add driver account'})).toBeDisabled();
    await userEvent.click(screen.getByRole('button',{name:'Reload accounts'}));
    await waitFor(()=>expect(screen.getByRole('button',{name:'Add driver account'})).toBeEnabled());
    expect(client.create).not.toHaveBeenCalled();
  });

});
