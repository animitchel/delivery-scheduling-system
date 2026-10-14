import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError } from './api';
import { adminRoutesApi } from './admin-routes-api';
import { deliveriesApi } from './deliveries-api';
import { operatorsApi } from './operators-api';
import { warehousesApi } from './warehouses-api';
import { recipientsApi } from './recipients-api';
import { accountsApi } from './accounts-api';
import { driverApi } from './driver-api';
import { generationApi } from './route-generation-api';
import type { GenerationResult } from './route-generation-api';
import type { Credential } from './accounts-api';
import type { DriverRoute } from './driver-api';
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
    mount(clientFor()); expect(screen.getByRole('status')).toHaveTextContent('Preparing'); expect(document.title).toBe('Checking session | Dispatch');
    expect(await screen.findByRole('heading', { name: 'Keep deliveries moving.' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Open accounts' })).toHaveAttribute('href', '/app/admin/accounts');
    expect(screen.getByRole('link', { name: 'Open recipients' })).toHaveAttribute('href', '/app/admin/recipients');
    expect(screen.queryByRole('link', { name: 'Open scheduling' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open deliveries' })).toHaveAttribute('href', '/app/admin/deliveries');
    expect(within(screen.getByRole('navigation', { name: 'Main navigation' })).getAllByRole('link').map(link => link.textContent)).toEqual([
      'Overview', 'Warehouses', 'Operators', 'Accounts', 'Recipients', 'Deliveries', 'Generate routes', 'Routes',
    ]);
    const cards = within(screen.getByRole('region', { name: 'Delivery tools' })).getAllByRole('article');
    expect(cards.map(card => within(card).getByRole('heading').textContent)).toEqual([
      'Manage warehouses', 'Manage operators', 'Manage accounts', 'Manage recipients', 'Prepare deliveries', 'Generate routes', 'Review routes',
    ]);
    expect(cards.map(card => card.querySelector('.card-number')?.textContent)).toEqual(['01', '02', '03', '04', '05', '06', '07']);

  });
  it('shows driver tools without administrator navigation', async () => {
    mount(clientFor(driver)); await screen.findByRole('heading', { name: 'Your next stop starts here.' });
    expect(screen.queryByRole('link', { name: 'Accounts' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View assigned routes' })).toHaveAttribute('href', '/app/driver/routes');
  });
  it('rejects the wrong workspace without mounting its tools', async () => {
    mount(clientFor(driver), '/admin'); await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' });
    expect(document.title).toBe('Access restricted | Dispatch');
    expect(screen.queryByRole('link', { name: 'Open accounts' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open your workspace' })).toHaveAttribute('href', '/driver');
  });
  it('provides sign-in for anonymous users', async () => {
    const client = clientFor(); client.session.mockRejectedValue(new ApiError(401)); mount(client);
    expect(await screen.findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/app/login');
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it('recovers from a failed session read only after an explicit retry', async () => {
    const client = clientFor(); client.session.mockRejectedValueOnce(new ApiError(500)); mount(client);
    await screen.findByRole('alert'); expect(document.title).toBe('Workspace unavailable | Dispatch'); expect(client.session).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', { name: 'Keep deliveries moving.' });
  });
  it('clears authenticated content when the session expires on focus', async () => {
    const client = clientFor(); client.session.mockResolvedValueOnce(admin).mockRejectedValue(new ApiError(401)); mount(client);
    await screen.findByRole('heading', { name: 'Keep deliveries moving.' });
    act(() => { window.dispatchEvent(new Event('focus')); });
    await screen.findByRole('link', { name: 'Sign in' }); expect(document.title).toBe('Sign in required | Dispatch'); expect(screen.queryByText('admin')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
  it.each(['focus', 'visibility', 'manual'])('preserves an unsaved draft through %s revalidation without writing', async trigger => {
    const reads = vi.spyOn(warehousesApi, 'directory').mockResolvedValue({ warehouses: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const writes = vi.spyOn(warehousesApi, 'create');
    const check = deferred<Account>(); const client = clientFor(); client.session.mockResolvedValueOnce(admin).mockReturnValueOnce(check.promise);
    mount(client, '/admin/warehouses'); await userEvent.click(await screen.findByRole('button', { name: 'Add warehouse' }));
    const input = screen.getByLabelText('Warehouse name'); fireEvent.change(input, { target: { value: 'Unsaved depot' } });
    if (trigger === 'manual') await userEvent.click(screen.getByRole('button', { name: 'Refresh session' }));
    else if (trigger === 'visibility') {
      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
      act(() => document.dispatchEvent(new Event('visibilitychange'))); expect(client.session).toHaveBeenCalledTimes(1);
      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
      act(() => document.dispatchEvent(new Event('visibilitychange')));
    } else act(() => window.dispatchEvent(new Event('focus')));
    expect(document.title).toBe('Warehouses | Dispatch');
    input.focus(); expect(client.session).toHaveBeenCalledTimes(2); expect(input).toBeInTheDocument(); expect(input).toHaveValue('Unsaved depot');
    await act(async () => { check.resolve({ ...admin }); await check.promise; });
    expect(screen.getByLabelText('Warehouse name')).toBe(input); expect(input).toHaveValue('Unsaved depot');
    expect(input).toHaveFocus(); expect(document.title).toBe('Warehouses | Dispatch');
    expect(reads).toHaveBeenCalledTimes(1); expect(writes).not.toHaveBeenCalled();
  });
  it.each(['identity', 'role', 'expired', 'password', 'failed'])('clears the previous draft after %s session change', async change => {
    vi.spyOn(warehousesApi, 'directory').mockResolvedValue({ warehouses: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const client = clientFor(); const redirect = mount(client, '/admin/warehouses');
    await userEvent.click(await screen.findByRole('button', { name: 'Add warehouse' }));
    fireEvent.change(screen.getByLabelText('Warehouse name'), { target: { value: 'Private draft' } });
    if (change === 'expired' || change === 'failed') client.session.mockRejectedValue(new ApiError(change === 'expired' ? 401 : 500));
    else client.session.mockResolvedValue(change === 'identity' ? { ...admin, id: 2 } : change === 'role' ? driver : { ...admin, passwordChangeRequired: true });
    act(() => window.dispatchEvent(new Event('focus')));
    await waitFor(() => expect(screen.queryByLabelText('Warehouse name')).not.toBeInTheDocument());
    expect(screen.queryByDisplayValue('Private draft')).not.toBeInTheDocument();
    if (change === 'expired') expect(screen.getByRole('link', { name: 'Sign in' })).toBeVisible();
    if (change === 'password') expect(redirect).toHaveBeenCalledWith('/app/change-password');
    if (change === 'failed') expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
  it('reloads driver route state when the operator assignment changes for the same account', async () => {
    const reads = vi.spyOn(driverApi, 'routes').mockResolvedValue([]);
    const client = clientFor(driver); mount(client, '/driver/routes');
    await screen.findByRole('heading', { name: 'Assigned routes' }); await waitFor(() => expect(reads).toHaveBeenCalledTimes(1));
    client.session.mockResolvedValue({ ...driver, operatorId: 2 });
    act(() => window.dispatchEvent(new Event('focus')));
    await waitFor(() => expect(reads).toHaveBeenCalledTimes(2));
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
    const redirect = mount(client); await waitFor(() => expect(redirect).toHaveBeenCalledWith('/app/change-password'));
    expect(screen.queryByRole('link', { name: 'Open accounts' })).not.toBeInTheDocument();
  });
  it('prevents duplicate sign-out and clears the identity on success', async () => {
    const pending = deferred<void>(); const client = clientFor(); client.logout.mockReturnValue(pending.promise); const redirect = mount(client);
    await screen.findByRole('button', { name: 'Sign out' });
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' })); fireEvent.click(screen.getByRole('button', { name: 'Signing out…' }));
    expect(client.logout).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: 'Signing out…' })).toBeDisabled();
    await act(async () => { pending.resolve(); await pending.promise; });
    expect(redirect).toHaveBeenCalledWith('/app/login?loggedOut'); expect(screen.queryByText('admin')).not.toBeInTheDocument();
  });
  it('shows a fixed sign-out error and allows an explicit retry', async () => {
    const client = clientFor(); client.logout.mockRejectedValueOnce(new Error('private details')); mount(client);
    await screen.findByRole('button', { name: 'Sign out' }); await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign out failed');
    expect(screen.queryByText('private details')).not.toBeInTheDocument(); expect(client.logout).toHaveBeenCalledTimes(1);
  });
  it('denies the administrator route screen to a driver without calling its directory API', async () => {
    const requests=vi.spyOn(adminRoutesApi,'directory'); mount(clientFor(driver),'/admin/routes'); await screen.findByRole('heading',{name:'You don’t have access to this workspace.'}); expect(requests).not.toHaveBeenCalled();
  });
  it('denies the React account directory to a driver without calling its API', async () => {
    const requests = vi.spyOn(accountsApi, 'accounts'); mount(clientFor(driver), '/admin/accounts');
    await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' }); expect(requests).not.toHaveBeenCalled();
  });
  it('keeps account mutations mounted during focus and clears credentials when navigation resumes', async () => {
    const target = {...driver,id:2,username:'test.driver',operatorId:4};
    vi.spyOn(accountsApi,'accounts').mockResolvedValue({items:[admin,target],page:0,size:20,totalElements:2,totalPages:1}); vi.spyOn(accountsApi,'operators').mockResolvedValue({items:[{operatorId:4,name:'Operator'}],page:0,size:50,totalElements:1,totalPages:1});
    const pending = deferred<Credential>(); const create = vi.spyOn(accountsApi,'create').mockReturnValue(pending.promise); const client = clientFor(); mount(client,'/admin/accounts');
    await screen.findByText('test.driver'); await waitFor(()=>expect(screen.getByLabelText('Eligible operator')).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Driver username'),{target:{value:'test.driver'}}); fireEvent.change(screen.getByLabelText('Eligible operator'),{target:{value:'4'}}); fireEvent.change(screen.getByLabelText('Your administrator password',{selector:'#create-current-password'}),{target:{value:'test password'}});
    fireEvent.submit(screen.getByRole('button',{name:'Create driver account'}).closest('form')!); act(()=>{window.dispatchEvent(new Event('focus'));}); await userEvent.click(screen.getByRole('link',{name:'Overview'}));
    expect(client.session).toHaveBeenCalledTimes(1); expect(create).toHaveBeenCalledTimes(1); expect(screen.getByRole('button',{name:'Sign out'})).toBeDisabled();
    await act(async()=>{pending.resolve({account:{...target,passwordChangeRequired:true},temporaryPassword:'A'.repeat(32),expiresAt:'2026-10-14T12:00:00Z'});await pending.promise;}); await screen.findByRole('dialog',{name:'Temporary driver password'});
    expect(screen.getByLabelText('Temporary password')).toHaveValue('A'.repeat(32));
    await userEvent.click(screen.getByRole('button',{name:'Done'})); await userEvent.click(screen.getByRole('link',{name:'Overview'})); await screen.findByRole('heading',{name:'Keep deliveries moving.'}); expect(document.querySelector('#temporary-driver-password')).toBeNull();
  });
  it('keeps administrator route writes mounted during focus and blocks shell navigation', async () => {
    const assignment: DriverRoute={routeId:1,operatorId:4,operatorName:'Driver',scheduledDate:'2026-10-13',routeStatus:'IN_PROGRESS',deliveries:[{deliveryId:1,recipientId:1,clientName:'Test recipient',status:'IN_PROGRESS',fromPrevDistanceKm:null,fromPrevDurationText:null,fromPrevDurationSeconds:null}]};
    vi.spyOn(adminRoutesApi,'directory').mockResolvedValue({routes:[{routeId:1,operatorId:4,operatorName:'Driver',scheduledDate:'2026-10-13',routeStatus:'IN_PROGRESS',stopCount:1,finishedCount:0}],page:0,size:20,totalElements:1,totalPages:1});
    const read=vi.spyOn(adminRoutesApi,'route').mockResolvedValue(assignment); const pending=deferred<void>();const update=vi.spyOn(adminRoutesApi,'update').mockReturnValue(pending.promise); const client=clientFor(); mount(client,'/admin/routes'); await screen.findByRole('button',{name:'Mark completed'});
    fireEvent.click(screen.getByRole('button',{name:'Mark completed'})); act(()=>{window.dispatchEvent(new Event('focus'));}); await userEvent.click(screen.getByRole('link',{name:'Accounts'}));
    expect(screen.getByRole('heading',{name:'Routes'})).toBeVisible();expect(client.session).toHaveBeenCalledTimes(1);expect(update).toHaveBeenCalledTimes(1);expect(screen.getByRole('button',{name:'Sign out'})).toBeDisabled();
    read.mockResolvedValue({...assignment,routeStatus:'COMPLETED',deliveries:[{...assignment.deliveries[0]!,status:'COMPLETED'}]}); await act(async()=>{pending.resolve();await pending.promise;});await waitFor(()=>expect(screen.getByRole('status')).toHaveTextContent('1 of 1 stops finished'));expect(screen.getByRole('button',{name:'Mark completed'})).toBeDisabled();
  });
  it('does not mount driver endpoints for an administrator deep link', async () => {
    const requests = vi.spyOn(driverApi, 'routes'); mount(clientFor(), '/driver/routes');
    await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' }); expect(requests).not.toHaveBeenCalled();
  });
  it('keeps pending delivery updates mounted during focus and shell navigation', async () => {
    const assignment: DriverRoute = { routeId: 1, operatorId: 1, operatorName: 'Driver', scheduledDate: '2026-10-13', routeStatus: 'IN_PROGRESS', deliveries: [{ deliveryId: 1, recipientId: 1, clientName: 'Alex', status: 'IN_PROGRESS', fromPrevDistanceKm: null, fromPrevDurationText: null, fromPrevDurationSeconds: null }] };
    vi.spyOn(driverApi, 'routes').mockResolvedValue([assignment]); vi.spyOn(driverApi, 'route').mockResolvedValue(assignment);
    const write = deferred<void>(); const update = vi.spyOn(driverApi, 'update').mockReturnValue(write.promise); const client = clientFor(driver);
    mount(client, '/driver/routes'); await screen.findByRole('button', { name: 'Mark completed' });
    fireEvent.click(screen.getByRole('button', { name: 'Mark completed' })); act(() => { window.dispatchEvent(new Event('focus')); });
    await userEvent.click(screen.getByRole('link', { name: 'Overview' }));
    expect(screen.getByRole('heading', { name: 'Assigned routes' })).toBeVisible(); expect(screen.getByRole('button', { name: 'Sign out' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Refresh session' })).toBeDisabled(); expect(client.session).toHaveBeenCalledTimes(1); expect(update).toHaveBeenCalledTimes(1);
    await act(async () => { write.resolve(); await write.promise; }); await screen.findByRole('button', { name: 'Mark completed' });
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
    client.session.mockRejectedValue(new ApiError(401)); act(() => { window.dispatchEvent(new Event('focus')); });
    await screen.findByRole('link', { name: 'Sign in' }); expect(screen.queryByText('Alex')).not.toBeInTheDocument();
  });
  it('updates titles and focus through navigation and history without exposing identity text', async () => {
    function History() { const navigate = useNavigate(); return <><button onClick={() => navigate(-1)}>History back</button><button onClick={() => navigate(1)}>History forward</button></>; }
    vi.spyOn(warehousesApi, 'directory').mockResolvedValue({ warehouses: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    render(<MemoryRouter initialEntries={['/admin/warehouses']}><App client={clientFor({ ...admin, username: 'Private identity' })} redirect={vi.fn()} /><History /></MemoryRouter>);
    await screen.findByRole('heading', { name: 'Warehouses' }); expect(document.title).toBe('Warehouses | Dispatch');
    await userEvent.click(screen.getByRole('link', { name: 'Overview' }));
    expect(document.title).toBe('Operations overview | Dispatch'); expect(screen.getByRole('main')).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'History back' }));
    expect(document.title).toBe('Warehouses | Dispatch'); expect(screen.getByRole('main')).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: 'History forward' }));
    expect(document.title).toBe('Operations overview | Dispatch'); expect(screen.getByRole('main')).toHaveFocus();
    expect(document.title).not.toContain('Private identity');
  });
  it('titles a missing page on a direct link', async () => {
    mount(clientFor(), '/missing?private=value'); await screen.findByRole('heading', { name: 'Page not found.' });
    expect(document.title).toBe('Page not found | Dispatch');
  });
  it('renders HTML-looking usernames as text', async () => {
    const attack = '<img src=x onerror=alert(1)>'; mount(clientFor({ ...admin, username: attack }));
    await screen.findByText(attack); expect(document.querySelector('img')).toBeNull();
  });
  it('guards recipient administration from drivers before requesting data', async () => {
    const requests=vi.spyOn(recipientsApi,'directory'); mount(clientFor(driver),'/admin/recipients');
    await screen.findByRole('heading',{name:'You don’t have access to this workspace.'}); expect(requests).not.toHaveBeenCalled(); requests.mockRestore();
  });

  it('guards delivery preparation from drivers before requesting data', async () => {
    const requests=vi.spyOn(deliveriesApi,'directory'); mount(clientFor(driver),'/admin/deliveries');
    await screen.findByRole('heading',{name:'You don’t have access to this workspace.'}); expect(requests).not.toHaveBeenCalled();
  });
  it('keeps a pending creation mounted through focus and workspace navigation, then clears it on session expiry', async () => {
    const recipient={recipientId:1,firstName:'Alex',lastName:'Smith',address:'Stored address',contactNumber:null,priorityLevel:'MEDIUM' as const};
    vi.spyOn(recipientsApi,'directory').mockResolvedValue({recipients:[recipient],page:0,size:20,totalElements:1,totalPages:1});
    vi.spyOn(recipientsApi,'read').mockResolvedValue({recipient,etag:'"'+'a'.repeat(64)+'"'});
    vi.spyOn(deliveriesApi,'directory').mockResolvedValue({deliveries:[],page:0,size:20,totalElements:0,totalPages:0});
    const pending=deferred<Awaited<ReturnType<typeof deliveriesApi.create>>>(); const create=vi.spyOn(deliveriesApi,'create').mockReturnValue(pending.promise); const client=clientFor();
    mount(client,'/admin/deliveries'); await userEvent.click(await screen.findByRole('button',{name:'Choose Alex Smith, recipient 1'})); await screen.findByText('Selected recipient: Alex Smith');
    fireEvent.change(screen.getByLabelText('Scheduled date'),{target:{value:'2020-01-02'}}); fireEvent.change(screen.getByLabelText('Start time'),{target:{value:'09:00'}}); fireEvent.change(screen.getByLabelText('End time'),{target:{value:'12:00'}});
    fireEvent.click(screen.getByRole('button',{name:'Create delivery'})); act(()=>window.dispatchEvent(new Event('focus'))); await userEvent.click(screen.getByRole('link',{name:'Overview'}));
    expect(screen.getByRole('heading',{name:'Deliveries'})).toBeVisible(); expect(screen.getByRole('button',{name:'Sign out'})).toBeDisabled(); expect(screen.getByRole('button',{name:'Refresh session'})).toBeDisabled(); expect(client.session).toHaveBeenCalledTimes(1); expect(create).toHaveBeenCalledTimes(1);
    await act(async()=>pending.resolve({deliveryId:7,recipientId:1,operatorId:null,clientName:'Alex Smith',operatorName:null,scheduledDate:'2020-01-02',startTimeWindow:'09:00:00',endTimeWindow:'12:00:00',priorityLevel:'MEDIUM',deliveryStatus:'PENDING',specialInstructions:null}));
    await screen.findByText('Delivery #7 created.'); expect(screen.getByRole('button',{name:'Sign out'})).toBeEnabled();
    client.session.mockRejectedValue(new ApiError(401)); act(()=>window.dispatchEvent(new Event('focus'))); await screen.findByRole('link',{name:'Sign in'}); expect(screen.queryByText('Delivery #7 created.')).not.toBeInTheDocument();
  });

  it('denies route generation to drivers without reading or writing administrator endpoints', async () => {
    const reads = vi.spyOn(generationApi, 'directory'); const writes = vi.spyOn(generationApi, 'generate');
    mount(clientFor(driver), '/admin/routes/generate');
    await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' });
    expect(reads).not.toHaveBeenCalled(); expect(writes).not.toHaveBeenCalled();
  });
  it.each(['0', '-1', '01', '2147483648', 'abc', '1&routeId=2'])('rejects invalid route link %s without loading a different route', async value => {
    const reads = vi.spyOn(adminRoutesApi, 'directory'); const details = vi.spyOn(adminRoutesApi, 'route');
    mount(clientFor(), `/admin/routes?routeId=${value}`);
    await screen.findByRole('heading', { name: 'Invalid route link.' }); expect(document.title).toBe('Invalid route link | Dispatch');
    expect(reads).not.toHaveBeenCalled(); expect(details).not.toHaveBeenCalled();
  });
  it('holds the workspace during generation and clears recovery after session expiry', async () => {
    vi.spyOn(generationApi, 'directory').mockResolvedValue({ routes: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    const pending = deferred<GenerationResult>(); const writes = vi.spyOn(generationApi, 'generate').mockReturnValue(pending.promise);
    const client = clientFor(); mount(client, '/admin/routes/generate');
    await screen.findByRole('button', { name: 'Review assignment' });
    await userEvent.click(screen.getByRole('button', { name: 'Review assignment' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm generation and assignment' }));
    act(() => { window.dispatchEvent(new Event('focus')); });
    await userEvent.click(screen.getByRole('link', { name: 'Accounts' }));
    expect(client.session).toHaveBeenCalledTimes(1); expect(writes).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Refresh session' })).toBeDisabled();
    await act(async () => { pending.resolve({ scheduledDate: '2026-10-13', routes: [], processingTimeMs: 0 }); await pending.promise; });
    client.session.mockRejectedValue(new ApiError(401)); act(() => { window.dispatchEvent(new Event('focus')); });
    await screen.findByRole('link', { name: 'Sign in' });
    expect(screen.queryByLabelText('Planning cutoff date')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it('denies warehouse management to a driver before reading warehouse data', async () => {
    const read = vi.spyOn(warehousesApi, 'directory'); mount(clientFor(driver), '/admin/warehouses');
    await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' }); expect(read).not.toHaveBeenCalled();
  });
  it('holds shell navigation during a warehouse write and clears private content on expiry', async () => {
    const warehouse = { warehouseId: 3, name: 'Depot', address: 'Address', latitude: 0, longitude: 0 };
    vi.spyOn(warehousesApi, 'directory').mockResolvedValue({ warehouses: [warehouse], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    vi.spyOn(warehousesApi, 'read').mockResolvedValue({ warehouse, etag: `"${'a'.repeat(64)}"` }); const pending = deferred<{ warehouse: typeof warehouse; etag: string }>(); vi.spyOn(warehousesApi, 'update').mockReturnValue(pending.promise);
    const client = clientFor(); mount(client, '/admin/warehouses'); await screen.findByRole('button', { name: 'Edit warehouse #3' }); await userEvent.click(screen.getByRole('button', { name: 'Edit warehouse #3' })); await screen.findByLabelText('Warehouse name');
    fireEvent.submit(screen.getByRole('button', { name: 'Save warehouse' }).closest('form')!); act(() => { window.dispatchEvent(new Event('focus')); }); await userEvent.click(screen.getByRole('link', { name: 'Accounts' })); expect(client.session).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: 'Sign out' })).toBeDisabled();
    await act(async () => { pending.resolve({ warehouse, etag: `"${'b'.repeat(64)}"` }); await pending.promise; }); await screen.findByText('Warehouse saved.');
    client.session.mockRejectedValue(new ApiError(401)); act(() => { window.dispatchEvent(new Event('focus')); }); await screen.findByRole('link', { name: 'Sign in' }); expect(screen.queryByRole('heading', { name: 'Warehouse directory' })).not.toBeInTheDocument();
  });

  it('denies operator management to drivers before mounting administrator endpoints', async () => {
    const reads = vi.spyOn(operatorsApi, 'directory'); mount(clientFor(driver), '/admin/operators'); await screen.findByRole('heading', { name: 'You don’t have access to this workspace.' }); expect(reads).not.toHaveBeenCalled();
  });

});
