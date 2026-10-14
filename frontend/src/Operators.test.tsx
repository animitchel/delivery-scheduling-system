import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Operators } from './Operators';
import { ApiError } from './api';
import type { OperatorVersion, OperatorsClient, OperatorPage } from './operators-api';
import type { WarehousesClient } from './warehouses-api';
const operator = { operatorId: 3, name: 'Driver', phoneNumber: '+447123456789', email: 'driver@example.test', vehicleType: 'Van', maxCapacity: 25, warehouseId: 7, availabilityStatus: 'AVAILABLE' as const };
const version: OperatorVersion = { operator, etag: `"${'a'.repeat(64)}"` }; const page: OperatorPage = { operators: [operator], page: 0, size: 20, totalElements: 1, totalPages: 1 };
function clientFor() { return { directory: vi.fn<OperatorsClient['directory']>().mockResolvedValue(page), read: vi.fn<OperatorsClient['read']>().mockResolvedValue(version), create: vi.fn<OperatorsClient['create']>().mockResolvedValue(version), update: vi.fn<OperatorsClient['update']>().mockResolvedValue(version), remove: vi.fn<OperatorsClient['remove']>().mockResolvedValue(undefined) }; }
function warehouseClient() { return { directory: vi.fn<WarehousesClient['directory']>().mockResolvedValue({ warehouses: [{ warehouseId: 7, name: 'Depot', address: 'Address', latitude: 1, longitude: 1 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }), read: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() }; }
async function mount(client = clientFor(), warehouses = warehouseClient(), busy = vi.fn(), auth = vi.fn()) { const view = render(<MemoryRouter><Operators client={client} warehouseClient={warehouses} onAuthError={auth} onBusyChange={busy} /></MemoryRouter>); await screen.findByRole('button', { name: 'Edit operator #3' }); return view; }
async function createDraft() { await userEvent.click(screen.getByRole('button', { name: 'Add operator' })); for (const [label, value] of [['Operator name', 'New driver'], ['Phone number', '+447123456789'], ['Operator email', 'new@example.test'], ['Maximum capacity', '25']]) fireEvent.change(screen.getByLabelText(label!), { target: { value } }); await userEvent.click(await screen.findByRole('button', { name: 'Choose warehouse #7' })); }
function save() { fireEvent.submit(screen.getByRole('button', { name: 'Save operator' }).closest('form')!); }
describe('operator setup workflow', () => {
  it('creates a driver record and links to separate account onboarding', async () => { const client = clientFor(); await mount(client); await createDraft(); save(); await screen.findByText('Operator saved.'); expect(client.create.mock.calls[0]?.[0].warehouseId).toBe(7); expect(screen.getByRole('link', { name: 'Manage driver login access' })).toHaveAttribute('href', '/admin/accounts'); });
  it('requires capacity and warehouse selection without defaulting missing input', async () => { const client = clientFor(); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Add operator' })); save(); await screen.findByRole('alert'); expect(client.create).not.toHaveBeenCalled(); });
  it('uses paginated warehouse IDs to distinguish duplicate names and retains selection across search', async () => { const warehouses = warehouseClient(); warehouses.directory.mockResolvedValueOnce({ warehouses: [{ warehouseId: 7, name: 'Depot', address: 'First depot', latitude: 1, longitude: 1 }], page: 0, size: 20, totalElements: 21, totalPages: 2 }).mockResolvedValueOnce({ warehouses: [{ warehouseId: 8, name: 'Depot', address: 'Second depot', latitude: 1, longitude: 1 }], page: 1, size: 20, totalElements: 21, totalPages: 2 }); await mount(clientFor(), warehouses); await userEvent.click(screen.getByRole('button', { name: 'Add operator' })); await userEvent.click(await screen.findByRole('button', { name: 'Choose warehouse #7' })); await userEvent.click(screen.getByRole('button', { name: 'Next warehouse choices' })); await screen.findByRole('button', { name: 'Choose warehouse #8' }); expect(screen.getByText('Selected warehouse: #7 · Depot')).toBeVisible(); expect(warehouses.directory.mock.calls.map(call => call[0])).toEqual([0, 1]); });
  it('Enter in warehouse search searches rather than submitting the operator form', async () => { const client = clientFor(); const warehouses = warehouseClient(); await mount(client, warehouses); await createDraft(); const input = screen.getByLabelText('Search warehouses for operator'); fireEvent.change(input, { target: { value: 'Depot' } }); fireEvent.keyDown(input, { key: 'Enter' }); await waitFor(() => expect(warehouses.directory).toHaveBeenCalledWith(0, 'Depot', expect.any(AbortSignal))); expect(client.create).not.toHaveBeenCalled(); });
  it('disables warehouse choices with invalid coordinates and supports failed read retry', async () => { const warehouses = warehouseClient(); warehouses.directory.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue({ warehouses: [{ warehouseId: 7, name: 'Depot', address: 'Address', latitude: 91, longitude: 1 }], page: 0, size: 20, totalElements: 1, totalPages: 1 }); await mount(clientFor(), warehouses); await userEvent.click(screen.getByRole('button', { name: 'Add operator' })); await screen.findByText('Warehouse choices could not be loaded. Reload choices.'); await userEvent.click(screen.getByRole('button', { name: 'Reload warehouse choices' })); expect(await screen.findByRole('button', { name: 'Choose warehouse #7' })).toBeDisabled(); });
  it('preserves drafts and explicitly compares the latest version after a stale edit', async () => { const client = clientFor(); client.update.mockRejectedValueOnce(new ApiError(412)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit operator #3' })); await screen.findByLabelText('Operator name'); fireEvent.change(screen.getByLabelText('Operator name'), { target: { value: 'My draft' } }); save(); await screen.findByRole('heading', { name: 'Review before another write' }); client.read.mockResolvedValue({ operator: { ...operator, name: 'Other edit' }, etag: `"${'b'.repeat(64)}"` }); await userEvent.click(screen.getByRole('button', { name: 'Review saved operator data' })); await screen.findByText('Other edit'); expect(screen.getByLabelText('Operator name')).toHaveValue('My draft'); await userEvent.click(screen.getByRole('button', { name: 'I compared details; continue with latest version' })); expect(client.update).toHaveBeenCalledTimes(1); save(); await screen.findByText('Operator saved.'); expect(client.update.mock.calls[1]?.[1]).toBe(`"${'b'.repeat(64)}"`); });
  it('locks operational fields for ON_DELIVERY while keeping contact edits available', async () => { const client = clientFor(); client.read.mockResolvedValue({ ...version, operator: { ...operator, availabilityStatus: 'ON_DELIVERY' } }); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit operator #3' })); await screen.findByLabelText('Operator name'); expect(screen.getByLabelText('Vehicle type')).toBeDisabled(); expect(screen.getByLabelText('Maximum capacity')).toBeDisabled(); expect(screen.getByLabelText('Availability')).toBeDisabled(); expect(screen.getByLabelText('Operator name')).toBeEnabled(); save(); await screen.findByText('Operator saved.'); expect(client.update.mock.calls[0]?.[2].availabilityStatus).toBe('ON_DELIVERY'); });
  it('after assignment races keeps contact draft with explicitly acknowledged current operational settings', async () => { const client = clientFor(); client.update.mockRejectedValueOnce(new ApiError(409)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit operator #3' })); await screen.findByLabelText('Operator name'); fireEvent.change(screen.getByLabelText('Operator name'), { target: { value: 'My contact draft' } }); save(); await screen.findByRole('heading', { name: 'Review before another write' }); client.read.mockResolvedValue({ ...version, operator: { ...operator, warehouseId: 8, vehicleType: 'Truck', maxCapacity: 30, availabilityStatus: 'ON_DELIVERY' } }); await userEvent.click(screen.getByRole('button', { name: 'Review saved operator data' })); await userEvent.click(await screen.findByRole('button', { name: 'Keep contact draft and saved assignment settings' })); expect(screen.getByLabelText('Operator name')).toHaveValue('My contact draft'); expect(screen.getByLabelText('Vehicle type')).toHaveValue('Truck'); save(); await screen.findByText('Operator saved.'); expect(client.update.mock.calls[1]?.[2].warehouseId).toBe(8); });
  it.each([0, 500, 502, 504])('requires explicit successful review after uncertain creation %s', async status => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(status)); await mount(client); await createDraft(); save(); await screen.findByRole('heading', { name: 'Review before another write' }); expect(screen.getByRole('button', { name: 'Save operator' })).toBeDisabled(); await userEvent.click(screen.getByRole('button', { name: 'Review saved operator data' })); await waitFor(() => expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeEnabled()); await userEvent.click(screen.getByRole('button', { name: 'I reviewed results; continue' })); expect(client.create).toHaveBeenCalledTimes(1); });
  it('failed outcome review keeps a draft blocked', async () => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(0)); await mount(client); await createDraft(); save(); await screen.findByRole('heading', { name: 'Review before another write' }); client.directory.mockRejectedValue(new ApiError(500)); await userEvent.click(screen.getByRole('button', { name: 'Review saved operator data' })); await screen.findByRole('alert'); expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeDisabled(); });
  it('confirms fresh conditional deletion and handles linked-history conflicts', async () => { const client = clientFor(); client.remove.mockRejectedValue(new ApiError(409)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Delete operator #3' })); await screen.findByRole('dialog'); await userEvent.click(screen.getByRole('button', { name: 'Keep operator' })); expect(client.remove).not.toHaveBeenCalled(); await userEvent.click(screen.getByRole('button', { name: 'Delete operator #3' })); await screen.findByRole('dialog'); await userEvent.click(screen.getByRole('button', { name: 'Confirm operator deletion' })); expect(await screen.findByRole('alert')).toHaveTextContent('history cannot be deleted'); expect(client.remove).toHaveBeenCalledWith(3, version.etag, expect.any(AbortSignal)); });
  it('blocks duplicate writes, aborts on unmount and releases shell state after settling', async () => { let resolve!: (v: OperatorVersion) => void; const client = clientFor(); client.create.mockReturnValue(new Promise(done => { resolve = done; })); const busy = vi.fn(); const view = await mount(client, warehouseClient(), busy); await createDraft(); save(); save(); expect(client.create).toHaveBeenCalledTimes(1); view.unmount(); expect(client.create.mock.calls[0]?.[1].aborted).toBe(true); await act(async () => resolve(version)); expect(busy.mock.calls).toEqual([[true], [false]]); });
  it.each([new ApiError(401), new ApiError(403, true)])('clears revoked sessions through the shell %j', async cause => { const client = clientFor(); const auth = vi.fn(); client.create.mockRejectedValue(cause); await mount(client, warehouseClient(), vi.fn(), auth); await createDraft(); save(); await waitFor(() => expect(auth).toHaveBeenCalledWith(cause)); });
  it('renders stored markup as text and recovers a disappeared directory page', async () => { const client = clientFor(); client.directory.mockResolvedValueOnce({ ...page, totalElements: 21, totalPages: 2 }).mockResolvedValueOnce({ ...page, page: 1, operators: [] }).mockResolvedValueOnce({ ...page, operators: [{ ...operator, name: '<img src=x onerror=attack()>' }] }); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Next operators' })); await screen.findByText(/<img src=x/); expect(document.querySelector('img')).toBeNull(); expect(client.directory.mock.calls.map(call => call[0])).toEqual([0, 1, 0]); });
  it('ignores obsolete warehouse choices after a newer search', async () => {
    const warehouses = warehouseClient(); let resolve!: (page: Awaited<ReturnType<WarehousesClient['directory']>>) => void; warehouses.directory.mockReturnValueOnce(new Promise(done => { resolve = done; })); await mount(clientFor(), warehouses); await userEvent.click(screen.getByRole('button', { name: 'Add operator' })); await waitFor(() => expect(warehouses.directory).toHaveBeenCalledTimes(1)); const input = screen.getByLabelText('Search warehouses for operator'); fireEvent.change(input, { target: { value: 'Fresh' } }); fireEvent.keyDown(input, { key: 'Enter' }); await screen.findByRole('button', { name: 'Choose warehouse #7' }); await act(async () => resolve({ warehouses: [{ warehouseId: 8, name: 'Old depot', address: 'Old address', latitude: 1, longitude: 1 }], page: 0, size: 20, totalElements: 1, totalPages: 1 })); expect(screen.queryByRole('button', { name: 'Choose warehouse #8' })).not.toBeInTheDocument(); expect(warehouses.directory.mock.calls[0]?.[2].aborted).toBe(true);
  });
  it('a stalled write enters review after its deadline without replay', async () => {
    const client = clientFor(); client.create.mockImplementation((_input, signal) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))); await mount(client); await createDraft(); vi.useFakeTimers(); try { save(); await act(async () => { await vi.advanceTimersByTimeAsync(15000); }); expect(screen.getByRole('heading', { name: 'Review before another write' })).toBeVisible(); expect(client.create).toHaveBeenCalledTimes(1); } finally { vi.useRealTimers(); }
  });
  it('permission rejection blocks writes until the shell refreshes', async () => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(403)); await mount(client); await createDraft(); save(); await screen.findByRole('alert'); expect(screen.getByRole('button', { name: 'Save operator' })).toBeDisabled(); expect(client.create).toHaveBeenCalledTimes(1); });

  it('uses released assignment settings after route completion rather than retaining a hidden ON_DELIVERY value', async () => {
    const client = clientFor(); client.read.mockResolvedValueOnce({ ...version, operator: { ...operator, availabilityStatus: 'ON_DELIVERY' } }); client.update.mockRejectedValueOnce(new ApiError(412)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit operator #3' })); await screen.findByLabelText('Operator name'); fireEvent.change(screen.getByLabelText('Operator name'), { target: { value: 'My contact draft' } }); save(); await screen.findByRole('heading', { name: 'Review before another write' }); client.read.mockResolvedValue(version); await userEvent.click(screen.getByRole('button', { name: 'Review saved operator data' })); await userEvent.click(await screen.findByRole('button', { name: 'Keep contact draft and saved assignment settings' })); expect(screen.getByLabelText('Operator name')).toHaveValue('My contact draft'); expect(screen.getByLabelText('Availability')).toHaveValue('AVAILABLE'); expect(screen.getByLabelText('Availability')).toBeEnabled(); save(); await screen.findByText('Operator saved.'); expect(client.update.mock.calls[1]?.[2].availabilityStatus).toBe('AVAILABLE');
  });

  it('guides an empty directory and exposes only one add action', async () => {
    const client=clientFor(); client.directory.mockResolvedValue({...page,operators:[],totalElements:0,totalPages:0});
    render(<MemoryRouter><Operators client={client} warehouseClient={warehouseClient()} onAuthError={vi.fn()} onBusyChange={vi.fn()} /></MemoryRouter>);
    await screen.findByRole('heading',{name:'Start with your first operator'});
    expect(screen.getAllByRole('button',{name:'Add operator'})).toHaveLength(1);
    expect(screen.queryByRole('navigation',{name:'Operator pagination'})).not.toBeInTheDocument();
  });
  it('clears a filtered empty directory from page zero', async () => {
    const client=clientFor(); await mount(client);
    client.directory.mockResolvedValueOnce({...page,operators:[],totalElements:0,totalPages:0});
    await userEvent.type(screen.getByLabelText('Search operators'),'Missing');
    await userEvent.click(screen.getByRole('button',{name:'Search operator directory'}));
    await screen.findByRole('heading',{name:'No operators match this search.'});
    await userEvent.click(screen.getByRole('button',{name:'Clear operator search'}));
    await screen.findByRole('button',{name:'Edit operator #3'});
    expect(client.directory.mock.calls.at(-1)?.slice(0,2)).toEqual([0,'']);
    expect(screen.getByLabelText('Search operators')).toHaveValue('');
  });
  it('groups fields and associates contact errors without sending a write', async () => {
    const client=clientFor(); await mount(client); await userEvent.click(screen.getByRole('button',{name:'Add operator'})); save();
    expect(screen.getByLabelText('Operator name')).toHaveFocus();
    expect(screen.getByLabelText('Operator name')).toHaveAccessibleDescription(/Enter an operator name/);
    expect(screen.getByRole('group',{name:'Contact details'})).toBeVisible();
    expect(screen.getByRole('group',{name:'Vehicle and availability'})).toBeVisible();
    fireEvent.change(screen.getByLabelText('Operator name'),{target:{value:'Driver'}}); save();
    expect(screen.getByLabelText('Phone number')).toHaveFocus();
    expect(screen.getByLabelText('Phone number')).toHaveAttribute('aria-invalid','true');
    expect(client.create).not.toHaveBeenCalled();
  });
  it('allows zero capacity and presents account access as a separate handoff', async () => {
    const client=clientFor(); await mount(client); await createDraft();
    fireEvent.change(screen.getByLabelText('Maximum capacity'),{target:{value:'0'}}); save();
    await screen.findByText('Operator saved.');
    expect(client.create.mock.calls[0]?.[0].maxCapacity).toBe(0);
    const handoff=screen.getByRole('region',{name:'Driver login access'});
    expect(handoff).toHaveTextContent('does not create credentials');
    expect(within(handoff).getByRole('link',{name:'Manage driver login access'})).toHaveAttribute('href','/admin/accounts');
  });
  it('retains the chosen warehouse when a no-result warehouse search is cleared', async () => {
    const warehouses=warehouseClient(); await mount(clientFor(),warehouses); await createDraft();
    warehouses.directory.mockResolvedValueOnce({warehouses:[],page:0,size:20,totalElements:0,totalPages:0});
    await userEvent.type(screen.getByLabelText('Search warehouses for operator'),'Missing');
    await userEvent.click(screen.getByRole('button',{name:'Find warehouses'}));
    await screen.findByRole('heading',{name:'No warehouses match this search.'});
    await userEvent.click(screen.getByRole('button',{name:'Clear warehouse search'}));
    await screen.findByRole('button',{name:'Choose warehouse #7'});
    expect(screen.getByText('Selected warehouse: #7 · Depot')).toBeVisible();
    expect(warehouses.directory.mock.calls.at(-1)?.slice(0,2)).toEqual([0,'']);
  });
  it('restores add focus after cancellation and opens deletion on the safe action', async () => {
    await mount(); await createDraft(); await userEvent.click(screen.getByRole('button',{name:'Cancel operator edit'}));
    await waitFor(()=>expect(screen.getByRole('button',{name:'Add operator'})).toHaveFocus());
    const table=screen.getByRole('table',{name:'Operator records'});
    expect(within(table).getAllByRole('rowheader')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button',{name:'Delete operator #3'}));
    const dialog=await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button',{name:'Keep operator'})).toHaveFocus();
    expect(within(dialog).getByText('driver@example.test')).toBeVisible();
  });

});
