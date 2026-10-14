import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Deliveries } from './Deliveries';
import { ApiError } from './api';
import type { DeliveriesClient, DeliveryDetails, DeliveryPage } from './deliveries-api';
import type { RecipientsClient, RecipientPage, RecipientVersion } from './recipients-api';
const recipient = { recipientId: 1, firstName: 'Alex', lastName: 'Smith', address: 'Stored address', contactNumber: null, priorityLevel: 'MEDIUM' as const };
const recipients: RecipientPage = { recipients:[recipient],page:0,size:20,totalElements:1,totalPages:1 };
const details: DeliveryDetails = { deliveryId:7,recipientId:1,operatorId:null,clientName:'Alex Smith',operatorName:null,scheduledDate:'2020-01-02',startTimeWindow:'09:00:00',endTimeWindow:'12:00:00',priorityLevel:'MEDIUM',deliveryStatus:'PENDING',specialInstructions:'Side entrance' };
const page: DeliveryPage = { deliveries:[{...details,firstName:'Alex',lastName:'Smith'}],page:0,size:20,totalElements:1,totalPages:1 };
function clients() {
  return { client:{directory:vi.fn<DeliveriesClient['directory']>().mockResolvedValue(page),read:vi.fn<DeliveriesClient['read']>().mockResolvedValue(details),create:vi.fn<DeliveriesClient['create']>().mockResolvedValue(details)}, recipientClient:{directory:vi.fn<RecipientsClient['directory']>().mockResolvedValue(recipients),read:vi.fn<RecipientsClient['read']>().mockResolvedValue({recipient:{...recipient,address:'Fresh address'},etag:'"'+'a'.repeat(64)+'"'}),create:vi.fn(),update:vi.fn()} };
}
async function mount(pair=clients(),auth=vi.fn(),busy=vi.fn()) { const view=render(<Deliveries {...pair} onAuthError={auth} onBusyChange={busy} />); await screen.findByRole('button',{name:'Choose Alex Smith, recipient 1'}); await screen.findByRole('button',{name:'View delivery 7'}); return view; }
async function fill() { await userEvent.click(screen.getByRole('button',{name:'Choose Alex Smith, recipient 1'})); await screen.findByText('Selected recipient: Alex Smith'); fireEvent.change(screen.getByLabelText('Scheduled date'),{target:{value:'2020-01-02'}}); fireEvent.change(screen.getByLabelText('Start time'),{target:{value:'09:00'}}); fireEvent.change(screen.getByLabelText('End time'),{target:{value:'12:00'}}); fireEvent.change(screen.getByLabelText('Special instructions (optional)'),{target:{value:'Side entrance'}}); }
async function submit() { await userEvent.click(screen.getByRole('button',{name:'Create delivery'})); }
describe('React delivery preparation', () => {
  it('selects fresh recipient details, creates overdue work and reviews the saved ID', async () => { const pair=clients(); await mount(pair); await fill(); expect(screen.getByText('Fresh address')).toBeVisible(); await submit(); await screen.findByText('Delivery #7 created.'); expect(pair.client.create).toHaveBeenCalledWith(expect.objectContaining({recipientId:1,scheduledDate:'2020-01-02',specialInstructions:'Side entrance'}),expect.any(AbortSignal)); expect(screen.getByRole('heading',{name:'Delivery #7'})).toHaveFocus(); expect(screen.getByRole('button',{name:'Create delivery'})).toBeDisabled(); expect(pair.client.directory.mock.calls.at(-1)?.[1]).toEqual({recipientId:1,scheduledDate:'2020-01-02',status:''}); });
  it('prevents a reversed window from reaching the server', async () => { const pair=clients(); await mount(pair); await fill(); fireEvent.change(screen.getByLabelText('End time'),{target:{value:'08:00'}}); await submit(); expect(await screen.findByRole('alert')).toHaveTextContent('end after'); expect(pair.client.create).not.toHaveBeenCalled(); });
  it('rejects double submission and freezes mutation controls', async () => { let resolve!: (v:DeliveryDetails)=>void; const pair=clients(); pair.client.create.mockReturnValue(new Promise(r=>{resolve=r;})); const busy=vi.fn(); await mount(pair,vi.fn(),busy); await fill(); const button=screen.getByRole('button',{name:'Create delivery'}); fireEvent.submit(button.closest('form')!); fireEvent.submit(button.closest('form')!); expect(pair.client.create).toHaveBeenCalledTimes(1); expect(screen.getByRole('button',{name:'Reload recipients'})).toBeDisabled(); await act(async()=>resolve(details)); await screen.findByText('Delivery #7 created.'); expect(busy.mock.calls).toEqual([[true],[false]]); });
  it('preserves a draft on validation rejection without displaying raw diagnostics', async () => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(400)); await mount(pair); await fill(); await submit(); await screen.findByRole('alert'); expect(screen.getByLabelText('Special instructions (optional)')).toHaveValue('Side entrance'); expect(screen.getByRole('button',{name:'Create delivery'})).toBeEnabled(); });
  it.each([404,409])('requires current recipient selection after a known reference rejection %s', async status => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(status)); await mount(pair); await fill(); await submit(); await screen.findByRole('alert'); expect(screen.queryByText('Selected recipient: Alex Smith')).not.toBeInTheDocument(); expect(screen.getByLabelText('Scheduled date')).toHaveValue('2020-01-02'); expect(screen.getByRole('button',{name:'Create delivery'})).toBeDisabled(); });
  it.each([0,500,502,503])('blocks blind retries for uncertain outcome %s and reviews all statuses', async status => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(status)); await mount(pair); await fill(); await submit(); await screen.findByRole('heading',{name:'Check before submitting again'}); expect(screen.getByRole('button',{name:'Create delivery'})).toBeDisabled(); expect(screen.getByRole('button',{name:'I reviewed results; allow another submission'})).toBeDisabled(); pair.client.directory.mockResolvedValue({...page,deliveries:[{...page.deliveries[0]!,deliveryStatus:'IN_PROGRESS',operatorId:9}]}); await userEvent.click(screen.getByRole('button',{name:'Review possible saved deliveries'})); await screen.findByRole('cell',{name:'in progress'}); expect(pair.client.directory.mock.calls.at(-1)?.[1]).toEqual({recipientId:1,scheduledDate:'2020-01-02',status:''}); expect(pair.client.create).toHaveBeenCalledTimes(1); await userEvent.click(screen.getByRole('button',{name:'I reviewed results; allow another submission'})); expect(screen.getByRole('button',{name:'Create delivery'})).toBeEnabled(); expect(pair.client.create).toHaveBeenCalledTimes(1); });
  it('does not unblock an uncertain write when directory review fails', async () => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(0)); await mount(pair); await fill(); await submit(); await screen.findByRole('heading',{name:'Check before submitting again'}); pair.client.directory.mockRejectedValue(new ApiError(500)); await userEvent.click(screen.getByRole('button',{name:'Review possible saved deliveries'})); await screen.findByText('Deliveries could not be loaded. Please reload the directory.'); expect(screen.getByRole('button',{name:'I reviewed results; allow another submission'})).toBeDisabled(); });
  it('keeps an empty reconciliation result separate from a creation failure', async () => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(0)); await mount(pair); await fill(); await submit(); await screen.findByRole('heading',{name:'Check before submitting again'}); pair.client.directory.mockResolvedValue({...page,deliveries:[],totalElements:0,totalPages:0}); await userEvent.click(screen.getByRole('button',{name:'Review possible saved deliveries'})); await screen.findByText('No deliveries match these filters.'); expect(screen.getByText(/empty list or a matching delivery cannot prove/)).toBeVisible(); expect(screen.getByRole('button',{name:'Create delivery'})).toBeDisabled(); });
  it.each([new ApiError(401),new ApiError(403,true)])('clears the screen through the parent for an invalid session %j', async error => { const pair=clients(); const auth=vi.fn(); pair.client.create.mockRejectedValue(error); await mount(pair,auth); await fill(); await submit(); await waitFor(()=>expect(auth).toHaveBeenCalledWith(error)); });
  it('blocks permission failures until the workspace session is refreshed', async () => { const pair=clients(); pair.client.create.mockRejectedValue(new ApiError(403)); await mount(pair); await fill(); await submit(); await screen.findByRole('alert'); expect(screen.getByRole('button',{name:'Create delivery'})).toBeDisabled(); expect(screen.queryByRole('heading',{name:'Check before submitting again'})).not.toBeInTheDocument(); });
  it('reads instructions only when selected and supports directory filters', async () => { const pair=clients(); await mount(pair); expect(pair.client.read).not.toHaveBeenCalled(); await userEvent.click(screen.getByRole('button',{name:'View delivery 7'})); await screen.findByRole('heading',{name:'Delivery #7'}); expect(screen.getByText('Side entrance')).toBeVisible(); fireEvent.change(screen.getByLabelText('Filter scheduled date'),{target:{value:'2020-01-02'}}); await userEvent.selectOptions(screen.getByLabelText('Filter status'),'COMPLETED'); await userEvent.click(screen.getByRole('button',{name:'Apply filters'})); await screen.findByRole('button',{name:'View delivery 7'}); expect(pair.client.directory.mock.calls.at(-1)?.[1]).toEqual({scheduledDate:'2020-01-02',status:'COMPLETED',recipientId:null}); expect(screen.queryByText('Side entrance')).not.toBeInTheDocument(); });
  it('paginates recipient searches and delivery summaries independently', async () => { const pair=clients(); pair.recipientClient.directory.mockResolvedValue({...recipients,totalElements:21,totalPages:2}); pair.client.directory.mockResolvedValue({...page,totalElements:21,totalPages:2}); await mount(pair); pair.recipientClient.directory.mockResolvedValue({...recipients,page:1,totalElements:21,totalPages:2}); await userEvent.click(screen.getByRole('button',{name:'Next recipients'})); await waitFor(()=>expect(pair.recipientClient.directory.mock.calls.at(-1)?.[0]).toBe(1)); pair.client.directory.mockResolvedValue({...page,page:1,totalElements:21,totalPages:2}); await userEvent.click(screen.getByRole('button',{name:'Next deliveries'})); await waitFor(()=>expect(pair.client.directory.mock.calls.at(-1)?.[0]).toBe(1)); });
  it('renders hostile names and instructions as inert text', async () => { const pair=clients(); pair.recipientClient.directory.mockResolvedValue({...recipients,recipients:[{...recipient,firstName:'<img src=x>'}]}); pair.client.read.mockResolvedValue({...details,specialInstructions:'<img src=x onerror=alert(1)>'}); render(<Deliveries {...pair} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await screen.findByText('<img src=x> Smith'); await userEvent.click(await screen.findByRole('button',{name:'View delivery 7'})); await screen.findByText('<img src=x onerror=alert(1)>'); expect(document.querySelector('img')).toBeNull(); });
  it('aborts a pending write on unmount and releases the workspace lock without rendering late results', async () => { let resolve!: (v:DeliveryDetails)=>void; const pair=clients(); pair.client.create.mockReturnValue(new Promise(r=>{resolve=r;})); const busy=vi.fn(); const view=await mount(pair,vi.fn(),busy); await fill(); await submit(); view.unmount(); expect(pair.client.create.mock.calls[0]?.[1].aborted).toBe(true); await act(async()=>resolve(details)); expect(busy.mock.calls).toEqual([[true],[false]]); expect(screen.queryByText('Delivery #7 created.')).not.toBeInTheDocument(); });
  it('bounds hanging writes without retrying them', async () => { const pair=clients(); pair.client.create.mockImplementation((_input,signal)=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError'))))); await mount(pair); await fill(); vi.useFakeTimers(); try { fireEvent.click(screen.getByRole('button',{name:'Create delivery'})); await act(async()=>{await vi.advanceTimersByTimeAsync(15000);}); expect(screen.getByRole('heading',{name:'Check before submitting again'})).toBeVisible(); expect(pair.client.create).toHaveBeenCalledTimes(1); } finally { vi.useRealTimers(); } });
  it('ignores obsolete recipient selection after a new search', async () => { let resolve!: (v:RecipientVersion)=>void; const pair=clients(); pair.recipientClient.read.mockReturnValueOnce(new Promise(r=>{resolve=r;})); await mount(pair); await userEvent.click(screen.getByRole('button',{name:'Choose Alex Smith, recipient 1'})); await userEvent.type(screen.getByLabelText('Search recipient name or address'),'new'); await userEvent.click(screen.getByRole('button',{name:'Search recipients'})); await screen.findByRole('button',{name:'Choose Alex Smith, recipient 1'}); await act(async()=>resolve({recipient,etag:'"'+'a'.repeat(64)+'"'})); expect(screen.queryByText('Selected recipient: Alex Smith')).not.toBeInTheDocument(); expect(pair.recipientClient.read.mock.calls[0]?.[1].aborted).toBe(true); });
  it('allows scrolling the directory as a named keyboard region', async () => { await mount(); const region=screen.getByRole('region',{name:'Deliveries'}); expect(region).toHaveAttribute('tabindex','0'); expect(within(region).getByRole('table')).toBeVisible(); });
  it('ignores stale delivery details after changing directory filters', async () => {
    let resolve!: (v:DeliveryDetails)=>void; const pair=clients(); pair.client.read.mockReturnValueOnce(new Promise(r=>{resolve=r;})); await mount(pair);
    await userEvent.click(screen.getByRole('button',{name:'View delivery 7'})); await userEvent.click(screen.getByRole('button',{name:'Apply filters'})); await screen.findByRole('button',{name:'View delivery 7'});
    await act(async()=>resolve(details)); expect(screen.queryByRole('heading',{name:'Delivery #7'})).not.toBeInTheDocument(); expect(pair.client.read.mock.calls[0]?.[1].aborted).toBe(true);
  });
  it('ignores obsolete directory results after newer filters', async () => {
    let resolve!: (v:DeliveryPage)=>void; const pair=clients(); await mount(pair); pair.client.directory.mockReturnValueOnce(new Promise(r=>{resolve=r;})).mockResolvedValueOnce({...page,deliveries:[{...page.deliveries[0]!,deliveryId:8,firstName:'Latest'}]});
    fireEvent.change(screen.getByLabelText('Filter scheduled date'),{target:{value:'2020-01-02'}}); await userEvent.click(screen.getByRole('button',{name:'Apply filters'}));
    fireEvent.change(screen.getByLabelText('Filter scheduled date'),{target:{value:'2020-01-03'}}); await userEvent.click(screen.getByRole('button',{name:'Apply filters'})); await screen.findByRole('button',{name:'View delivery 8'});
    await act(async()=>resolve({...page,deliveries:[{...page.deliveries[0]!,firstName:'Obsolete'}]})); expect(screen.queryByRole('cell',{name:'Obsolete Smith'})).not.toBeInTheDocument(); expect(pair.client.directory.mock.calls[1]?.[2].aborted).toBe(true);
  });
  it('recovers when the last page disappears during pagination', async () => {
    const pair=clients(); pair.client.directory.mockResolvedValueOnce({...page,totalElements:21,totalPages:2}).mockResolvedValueOnce({...page,page:1,deliveries:[],totalElements:1,totalPages:1}).mockResolvedValueOnce(page); await mount(pair);
    await userEvent.click(screen.getByRole('button',{name:'Next deliveries'})); await screen.findByRole('button',{name:'View delivery 7'}); expect(pair.client.directory.mock.calls.map(call=>call[0])).toEqual([0,1,0]);
  });
  it('bounds stalled recipient and directory reads with explicit recovery', async () => {
    const pair=clients(); const hang=(signal:AbortSignal)=>new Promise<never>((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError'))));
    pair.client.directory.mockImplementation((_page,_filters,signal)=>hang(signal)); pair.recipientClient.directory.mockImplementation((_page,_search,signal)=>hang(signal)); vi.useFakeTimers();
    try { render(<Deliveries {...pair} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await act(async()=>{await vi.advanceTimersByTimeAsync(30000);}); expect(screen.getByText('Recipients could not be loaded. Reload or search again.')).toBeVisible(); expect(screen.getByText('Deliveries could not be loaded. Please reload the directory.')).toBeVisible(); expect(screen.getByRole('button',{name:'Reload deliveries'})).toBeEnabled(); } finally { vi.useRealTimers(); }
  });

  it('focuses recipient selection from the primary action without creating work', async () => {
    const pair = clients(); await mount(pair);
    await userEvent.click(screen.getByRole('button', { name: 'Prepare delivery' }));
    expect(screen.getByRole('heading', { name: 'Choose a recipient' })).toHaveFocus();
    expect(pair.client.create).not.toHaveBeenCalled();
    expect(screen.getByRole('group', { name: 'Schedule' })).toBeVisible();
    expect(screen.getByRole('group', { name: 'Priority and instructions' })).toBeVisible();
  });
  it('gives an empty database setup guidance without calling it a filtered result', async () => {
    const pair = clients();
    pair.recipientClient.directory.mockResolvedValue({ ...recipients, recipients: [], totalElements: 0, totalPages: 0 });
    pair.client.directory.mockResolvedValue({ ...page, deliveries: [], totalElements: 0, totalPages: 0 });
    render(<Deliveries {...pair} onAuthError={vi.fn()} onBusyChange={vi.fn()} />);
    await screen.findByRole('heading', { name: 'Start with a recipient' });
    await screen.findByRole('heading', { name: 'Your delivery workspace starts here.' });
    expect(screen.getByRole('button', { name: 'Create delivery' })).toBeDisabled();
    expect(screen.queryByRole('navigation', { name: 'deliveries pagination' })).not.toBeInTheDocument();
  });
  it('clears an unsuccessful recipient search and reloads the unfiltered first page', async () => {
    const pair = clients(); await mount(pair);
    pair.recipientClient.directory.mockResolvedValueOnce({ ...recipients, recipients: [], totalElements: 0, totalPages: 0 });
    await userEvent.type(screen.getByLabelText('Search recipient name or address'), 'Missing');
    await userEvent.click(screen.getByRole('button', { name: 'Search recipients' }));
    await screen.findByRole('heading', { name: 'No recipients match your search.' });
    await userEvent.click(screen.getByRole('button', { name: 'Clear recipient search' }));
    await screen.findByRole('button', { name: 'Choose Alex Smith, recipient 1' });
    expect(screen.getByLabelText('Search recipient name or address')).toHaveValue('');
    expect(pair.recipientClient.directory.mock.calls.at(-1)?.slice(0, 2)).toEqual([0, '']);
  });
  it('associates date and window errors with controls and focuses the invalid field', async () => {
    const pair = clients(); await mount(pair);
    await userEvent.click(screen.getByRole('button', { name: 'Choose Alex Smith, recipient 1' }));
    await screen.findByText('Selected recipient: Alex Smith');
    await submit();
    const date = screen.getByLabelText('Scheduled date');
    expect(date).toHaveFocus(); expect(date).toHaveAttribute('aria-invalid', 'true');
    expect(date).toHaveAccessibleDescription(/valid scheduled date/);
    expect(pair.client.create).not.toHaveBeenCalled();
    fireEvent.change(date, {target:{value:'2020-01-02'}});
    fireEvent.change(screen.getByLabelText('Start time'), {target:{value:'09:00'}});
    fireEvent.change(screen.getByLabelText('End time'), {target:{value:'08:00'}});
    await submit();
    expect(screen.getByLabelText('End time')).toHaveFocus();
    expect(screen.getByLabelText('End time')).toHaveAccessibleDescription(/must end after/);
    expect(pair.client.create).not.toHaveBeenCalled();
  });
  it('shows applied criteria rather than unsaved filter input and clears them explicitly', async () => {
    const pair = clients(); await mount(pair);
    await userEvent.selectOptions(screen.getByLabelText('Filter status'), 'COMPLETED');
    expect(screen.queryByLabelText('Applied delivery filters')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await screen.findByRole('button', { name: 'View delivery 7' });
    expect(screen.getByLabelText('Applied delivery filters')).toHaveTextContent('Status: completed');
    await userEvent.selectOptions(screen.getByLabelText('Filter status'), 'PENDING');
    expect(screen.getByLabelText('Applied delivery filters')).toHaveTextContent('Status: completed');
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await screen.findByRole('button', { name: 'View delivery 7' });
    expect(screen.queryByLabelText('Applied delivery filters')).not.toBeInTheDocument();
    expect(pair.client.directory.mock.calls.at(-1)?.[1]).toEqual({scheduledDate:'',status:'',recipientId:null});
  });
  it('retains one semantic delivery record and text status labels for responsive cards', async () => {
    await mount();
    const table = screen.getByRole('table', { name: 'Delivery records' });
    expect(within(table).getAllByRole('rowheader')).toHaveLength(1);
    expect(within(table).getByRole('rowheader')).toHaveTextContent('Delivery #7');
    expect(within(table).getAllByRole('button', { name: 'View delivery 7' })).toHaveLength(1);
    expect(within(table).getByRole('cell', { name: 'pending' })).toBeVisible();
  });

});
