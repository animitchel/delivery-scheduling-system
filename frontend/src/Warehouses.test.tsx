import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Warehouses } from './Warehouses';
import { ApiError } from './api';
import type { WarehousesClient, WarehouseVersion, WarehousePage } from './warehouses-api';
const warehouse = { warehouseId: 3, name: 'Depot', address: 'Address', latitude: 53.123456, longitude: -2.5 };
const version: WarehouseVersion = { warehouse, etag: `"${'a'.repeat(64)}"` };
const page: WarehousePage = { warehouses: [warehouse], page: 0, size: 20, totalElements: 1, totalPages: 1 };
function clientFor() { return { directory: vi.fn<WarehousesClient['directory']>().mockResolvedValue(page), read: vi.fn<WarehousesClient['read']>().mockResolvedValue(version), create: vi.fn<WarehousesClient['create']>().mockResolvedValue(version), update: vi.fn<WarehousesClient['update']>().mockResolvedValue(version), remove: vi.fn<WarehousesClient['remove']>().mockResolvedValue(undefined) }; }
async function mount(client = clientFor(), auth = vi.fn(), busy = vi.fn()) { const view = render(<Warehouses client={client} onAuthError={auth} onBusyChange={busy} />); await screen.findByRole('button', { name: 'Edit warehouse #3' }); return view; }
async function createDraft() { await userEvent.click(screen.getByRole('button', { name: 'Add warehouse' })); fireEvent.change(screen.getByLabelText('Warehouse name'), { target: { value: 'New depot' } }); fireEvent.change(screen.getByLabelText('Warehouse address'), { target: { value: 'New address' } }); fireEvent.change(screen.getByLabelText('Latitude'), { target: { value: '0' } }); fireEvent.change(screen.getByLabelText('Longitude'), { target: { value: '180' } }); }
function save() { fireEvent.submit(screen.getByRole('button', { name: 'Save warehouse' }).closest('form')!); }
describe('warehouse workflow', () => {
  it('creates with explicit coordinates and refreshes the directory', async () => { const client = clientFor(); await mount(client); await createDraft(); save(); await screen.findByText('Warehouse saved.'); expect(client.create).toHaveBeenCalledWith({ name: 'New depot', address: 'New address', latitude: 0, longitude: 180 }, expect.any(AbortSignal)); });
  it('reads the selected version before editing and cancels without a write', async () => { const client = clientFor(); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit warehouse #3' })); await screen.findByLabelText('Warehouse name'); expect(screen.getByLabelText('Warehouse name')).toHaveValue('Depot'); await userEvent.click(screen.getByRole('button', { name: 'Cancel warehouse edit' })); expect(client.update).not.toHaveBeenCalled(); });
  it('validates coordinate precision and never rounds an invalid draft', async () => { const client = clientFor(); await mount(client); await createDraft(); fireEvent.change(screen.getByLabelText('Latitude'), { target: { value: '1.0000000000000000001' } }); save(); expect(await screen.findByRole('alert')).toHaveTextContent('six decimal places'); expect(client.create).not.toHaveBeenCalled(); });
  it('holds duplicate writes and releases the shell lock', async () => { let resolve!: (value: WarehouseVersion) => void; const client = clientFor(); client.create.mockReturnValue(new Promise(done => { resolve = done; })); const busy = vi.fn(); await mount(client, vi.fn(), busy); await createDraft(); save(); save(); expect(client.create).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: 'Add warehouse' })).toBeDisabled(); await act(async () => resolve(version)); await screen.findByText('Warehouse saved.'); expect(busy.mock.calls).toEqual([[true], [false]]); });
  it.each([0, 500, 502, 503, 504])('requires explicit directory review after uncertain creation %s', async status => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(status)); await mount(client); await createDraft(); save(); await screen.findByRole('heading', { name: 'Review before another write' }); expect(screen.getByLabelText('Warehouse name')).toHaveValue('New depot'); expect(screen.getByRole('button', { name: 'Save warehouse' })).toBeDisabled(); await userEvent.click(screen.getByRole('button', { name: 'Review saved warehouse data' })); await waitFor(() => expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeEnabled()); expect(client.directory.mock.calls.at(-1)?.[1]).toBe('New depot'); await userEvent.click(screen.getByRole('button', { name: 'I reviewed results; continue' })); expect(client.create).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: 'Save warehouse' })).toBeEnabled(); });
  it('preserves an edited draft and requires comparison before using the latest ETag', async () => { const client = clientFor(); client.update.mockRejectedValueOnce(new ApiError(412)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit warehouse #3' })); await screen.findByLabelText('Warehouse name'); fireEvent.change(screen.getByLabelText('Warehouse name'), { target: { value: 'My draft' } }); save(); await screen.findByRole('heading', { name: 'Review before another write' }); const latest = { warehouse: { ...warehouse, name: 'Other administrator edit' }, etag: `"${'b'.repeat(64)}"` }; client.read.mockResolvedValue(latest); await userEvent.click(screen.getByRole('button', { name: 'Review saved warehouse data' })); await screen.findByText('Other administrator edit'); expect(screen.getByLabelText('Warehouse name')).toHaveValue('My draft'); await userEvent.click(screen.getByRole('button', { name: 'I compared details; continue with latest version' })); expect(client.update).toHaveBeenCalledTimes(1); save(); await screen.findByText('Warehouse saved.'); expect(client.update.mock.calls[1]?.[1]).toBe(latest.etag); });
  it('failed review keeps another save blocked', async () => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(0)); await mount(client); await createDraft(); save(); await screen.findByRole('heading', { name: 'Review before another write' }); client.directory.mockRejectedValue(new ApiError(500)); await userEvent.click(screen.getByRole('button', { name: 'Review saved warehouse data' })); await screen.findByRole('alert'); expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeDisabled(); });
  it('confirms deletion using a fresh version and supports Keep/Escape without writing', async () => { const client = clientFor(); await mount(client); const button = screen.getByRole('button', { name: 'Delete warehouse #3' }); await userEvent.click(button); await screen.findByRole('dialog', { name: 'Delete this warehouse?' }); await userEvent.click(screen.getByRole('button', { name: 'Keep warehouse' })); expect(button).toHaveFocus(); expect(client.remove).not.toHaveBeenCalled(); await userEvent.click(button); fireEvent(screen.getByRole('dialog', { name: 'Delete this warehouse?' }), new Event('cancel')); await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument()); expect(client.remove).not.toHaveBeenCalled(); await userEvent.click(button); await screen.findByRole('dialog'); await userEvent.click(screen.getByRole('button', { name: 'Confirm warehouse deletion' })); await screen.findByText('Warehouse deleted.'); expect(client.remove).toHaveBeenCalledWith(3, version.etag, expect.any(AbortSignal)); });
  it('shows referenced deletion conflicts and reviews the current record', async () => { const client = clientFor(); client.remove.mockRejectedValue(new ApiError(409)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Delete warehouse #3' })); await screen.findByRole('dialog'); await userEvent.click(screen.getByRole('button', { name: 'Confirm warehouse deletion' })); expect(await screen.findByRole('alert')).toHaveTextContent('assigned to operators'); await userEvent.click(screen.getByRole('button', { name: 'Review saved warehouse data' })); await screen.findByText('Current saved details'); expect(client.remove).toHaveBeenCalledTimes(1); });
  it('treats an absent record after uncertain deletion as review, not proof of request identity', async () => { const client = clientFor(); client.remove.mockRejectedValue(new ApiError(0)); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Delete warehouse #3' })); await screen.findByRole('dialog'); await userEvent.click(screen.getByRole('button', { name: 'Confirm warehouse deletion' })); await screen.findByRole('heading', { name: 'Review before another write' }); client.read.mockRejectedValue(new ApiError(404)); await userEvent.click(screen.getByRole('button', { name: 'Review saved warehouse data' })); await screen.findByText(/warehouse is no longer available/); expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeEnabled(); });
  it('allows correcting known validation failures and blocks permission failures', async () => { const client = clientFor(); client.create.mockRejectedValueOnce(new ApiError(400)).mockRejectedValue(new ApiError(403)); await mount(client); await createDraft(); save(); await screen.findByRole('alert'); expect(screen.getByRole('button', { name: 'Save warehouse' })).toBeEnabled(); save(); await waitFor(() => expect(screen.getByRole('button', { name: 'Save warehouse' })).toBeDisabled()); });
  it.each([new ApiError(401), new ApiError(403, true)])('delegates revoked sessions %j', async cause => { const client = clientFor(); const auth = vi.fn(); client.create.mockRejectedValue(cause); await mount(client, auth); await createDraft(); save(); await waitFor(() => expect(auth).toHaveBeenCalledWith(cause)); });
  it('aborts writes on unmount and ignores late acknowledgements', async () => { let resolve!: (value: WarehouseVersion) => void; const client = clientFor(); client.create.mockReturnValue(new Promise(done => { resolve = done; })); const busy = vi.fn(); const view = await mount(client, vi.fn(), busy); await createDraft(); save(); view.unmount(); expect(client.create.mock.calls[0]?.[1].aborted).toBe(true); await act(async () => resolve(version)); expect(busy.mock.calls).toEqual([[true], [false]]); });
  it('ignores obsolete directory responses during uncertain-write recovery', async () => { let resolve!: (value: WarehousePage) => void; const client = clientFor(); await mount(client); await createDraft(); client.directory.mockReturnValueOnce(new Promise(done => { resolve = done; })); await userEvent.click(screen.getByRole('button', { name: 'Reload warehouses' })); client.create.mockRejectedValue(new ApiError(0)); save(); await screen.findByRole('heading', { name: 'Review before another write' }); await act(async () => resolve(page)); expect(screen.getByRole('button', { name: 'I reviewed results; continue' })).toBeDisabled(); });
  it('recovers a disappeared last page and renders stored markup as text', async () => { const client = clientFor(); client.directory.mockResolvedValueOnce({ ...page, totalElements: 21, totalPages: 2 }).mockResolvedValueOnce({ ...page, page: 1, warehouses: [] }).mockResolvedValueOnce({ ...page, warehouses: [{ ...warehouse, name: '<img src=x onerror=attack()>' }] }); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Next warehouses' })); await screen.findByText(/<img src=x/); expect(document.querySelector('img')).toBeNull(); expect(client.directory.mock.calls.map(call => call[0])).toEqual([0, 1, 0]); });
  it('keeps an invalid legacy coordinate editable so it can be corrected', async () => {
    const client = clientFor(); client.read.mockResolvedValue({ ...version, warehouse: { ...warehouse, latitude: 91 } }); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Edit warehouse #3' })); await screen.findByLabelText('Latitude'); expect(screen.getByLabelText('Latitude')).toHaveValue(91); fireEvent.change(screen.getByLabelText('Latitude'), { target: { value: '90' } }); save(); await screen.findByText('Warehouse saved.'); expect(client.update.mock.calls[0]?.[2].latitude).toBe(90);
  });
  it('aborts a stalled write at its deadline without replay', async () => {
    const client = clientFor(); client.create.mockImplementation((_input, signal) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))); await mount(client); await createDraft(); vi.useFakeTimers(); try { save(); await act(async () => { await vi.advanceTimersByTimeAsync(15000); }); expect(screen.getByRole('heading', { name: 'Review before another write' })).toBeVisible(); expect(client.create).toHaveBeenCalledTimes(1); } finally { vi.useRealTimers(); }
  });
  it('reports a stalled directory read after its deadline', async () => {
    const client = clientFor(); client.directory.mockImplementation((_page, _query, signal) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))); vi.useFakeTimers(); try { render(<Warehouses client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await act(async () => { await Promise.resolve(); }); await act(async () => { await vi.advanceTimersByTimeAsync(30000); }); expect(screen.getByRole('alert')).toHaveTextContent('could not be loaded'); } finally { vi.useRealTimers(); }
  });

  it('clears a cancelled directory spinner when a save has a known validation failure', async () => {
    const client = clientFor(); await mount(client); await createDraft(); client.directory.mockReturnValueOnce(new Promise(() => {})); await userEvent.click(screen.getByRole('button', { name: 'Reload warehouses' })); client.create.mockRejectedValue(new ApiError(400)); save(); await screen.findByRole('alert'); expect(screen.queryByText('Loading warehouses…')).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Save warehouse' })).toBeEnabled();
  });

  it('shows empty-directory guidance and only one primary add action', async () => {
    const client = clientFor(); client.directory.mockResolvedValue({ ...page, warehouses: [], totalElements: 0, totalPages: 0 });
    render(<Warehouses client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />);
    await screen.findByRole('heading', {name:'Start with your first warehouse'});
    expect(screen.getAllByRole('button', {name:'Add warehouse'})).toHaveLength(1);
    expect(screen.queryByRole('navigation', {name:'Warehouse pagination'})).not.toBeInTheDocument();
  });
  it('clears a no-results search and requests the unfiltered first page', async () => {
    const client = clientFor(); await mount(client);
    client.directory.mockResolvedValueOnce({...page, warehouses:[], totalElements:0, totalPages:0});
    await userEvent.type(screen.getByLabelText('Search warehouses'), 'Missing');
    await userEvent.click(screen.getByRole('button', {name:'Search warehouse directory'}));
    await screen.findByRole('heading', {name:'No warehouses match this search.'});
    await userEvent.click(screen.getByRole('button', {name:'Clear warehouse search'}));
    await screen.findByRole('button', {name:'Edit warehouse #3'});
    expect(screen.getByLabelText('Search warehouses')).toHaveValue('');
    expect(client.directory.mock.calls.at(-1)?.slice(0,2)).toEqual([0,'']);
  });
  it('associates required and coordinate errors and focuses the first invalid input', async () => {
    const client = clientFor(); await mount(client);
    await userEvent.click(screen.getByRole('button', {name:'Add warehouse'})); save();
    expect(screen.getByLabelText('Warehouse name')).toHaveFocus();
    expect(screen.getByLabelText('Warehouse name')).toHaveAccessibleDescription(/Enter a warehouse name/);
    expect(screen.getByRole('group', {name:'Warehouse details'})).toBeVisible();
    expect(screen.getByRole('group', {name:'Coordinates'})).toBeVisible();
    fireEvent.change(screen.getByLabelText('Warehouse name'), {target:{value:'Depot'}});
    fireEvent.change(screen.getByLabelText('Warehouse address'), {target:{value:'Address'}});
    fireEvent.change(screen.getByLabelText('Latitude'), {target:{value:'91'}});
    fireEvent.change(screen.getByLabelText('Longitude'), {target:{value:'0'}}); save();
    expect(screen.getByLabelText('Latitude')).toHaveFocus();
    expect(screen.getByLabelText('Latitude')).toHaveAttribute('aria-invalid','true');
    expect(screen.getByLabelText('Latitude')).toHaveAccessibleDescription(/at most six decimal places/);
    expect(client.create).not.toHaveBeenCalled();
  });
  it('returns focus to the add action when a new draft is cancelled', async () => {
    const client = clientFor(); await mount(client); await createDraft();
    await userEvent.click(screen.getByRole('button', {name:'Cancel warehouse edit'}));
    await waitFor(()=>expect(screen.getByRole('button', {name:'Add warehouse'})).toHaveFocus());
    expect(client.create).not.toHaveBeenCalled();
  });
  it('uses one semantic row and clearly identifies the record in the delete dialog', async () => {
    await mount(); const table=screen.getByRole('table', {name:'Warehouse records'});
    expect(within(table).getAllByRole('rowheader')).toHaveLength(1);
    expect(within(table).getByRole('rowheader')).toHaveTextContent('Depot');
    await userEvent.click(screen.getByRole('button', {name:'Delete warehouse #3'}));
    const dialog=await screen.findByRole('dialog');
    expect(within(dialog).getByRole('button', {name:'Keep warehouse'})).toHaveFocus();
    expect(within(dialog).getByText('Depot')).toBeVisible();
    expect(within(dialog).getByText('Address')).toBeVisible();
    await userEvent.click(within(dialog).getByRole('button', {name:'Keep warehouse'}));
    expect(screen.getByRole('button', {name:'Delete warehouse #3'})).toHaveFocus();
  });
  it('labels the actual recovery search and keeps acknowledgement blocked on review failure', async () => {
    const client=clientFor(); await mount(client);
    await userEvent.type(screen.getByLabelText('Search warehouses'), 'Old search');
    await userEvent.click(screen.getByRole('button', {name:'Search warehouse directory'}));
    await screen.findByRole('button', {name:'Edit warehouse #3'});
    await createDraft(); client.create.mockRejectedValue(new ApiError(0)); save();
    await screen.findByRole('heading', {name:'Review before another write'});
    client.directory.mockRejectedValueOnce(new ApiError(500));
    await userEvent.click(screen.getByRole('button', {name:'Review saved warehouse data'}));
    await screen.findByText('Warehouses could not be loaded. Reload the directory.');
    expect(screen.getByRole('button', {name:'I reviewed results; continue'})).toBeDisabled();
    await userEvent.click(screen.getByRole('button', {name:'Review saved warehouse data'}));
    await waitFor(()=>expect(screen.getByRole('button', {name:'I reviewed results; continue'})).toBeEnabled());
    expect(screen.getByRole('status')).toHaveTextContent('Results for “New depot”');
    expect(client.create).toHaveBeenCalledTimes(1);
  });

});
