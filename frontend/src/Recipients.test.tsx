import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Recipients } from './Recipients';
import { ApiError } from './api';
import type { RecipientsClient, RecipientPage, RecipientVersion } from './recipients-api';
const recipient = { recipientId: 1, firstName: 'Alex', lastName: 'Smith', address: 'Example', contactNumber: null, priorityLevel: 'MEDIUM' as const };
const version = { recipient, etag: '"' + 'a'.repeat(64) + '"' };
const page: RecipientPage = { recipients: [recipient], page: 0, size: 20, totalElements: 1, totalPages: 1 };
function clientFor() { return { directory: vi.fn<RecipientsClient['directory']>().mockResolvedValue(page), read: vi.fn<RecipientsClient['read']>().mockResolvedValue(version), create: vi.fn<RecipientsClient['create']>().mockResolvedValue(version), update: vi.fn<RecipientsClient['update']>().mockResolvedValue(version) }; }
async function mount(client = clientFor(), auth = vi.fn(), busy = vi.fn()) { const view = render(<Recipients client={client} onAuthError={auth} onBusyChange={busy} />); await screen.findByText('Alex Smith'); return view; }
async function edit() { await userEvent.click(screen.getByRole('button',{name:'Edit Alex Smith'})); await screen.findByLabelText('First name'); }
async function create() { await userEvent.click(screen.getByRole('button',{name:'Add recipient'})); await userEvent.type(screen.getByLabelText('First name'),'Alex'); await userEvent.type(screen.getByLabelText('Last name'),'Smith'); await userEvent.type(screen.getByLabelText('Address'),'Example'); }
describe('recipient management', () => {
  it('loads fresh details before editing and sends the exact version', async () => { const client = clientFor(); await mount(client); await edit(); expect(client.read).toHaveBeenCalledWith(1,expect.any(AbortSignal)); expect(screen.getByText(/Existing route travel estimates/)).toBeVisible(); await userEvent.click(screen.getByRole('button',{name:'Save recipient'})); await screen.findByText('Recipient Alex Smith saved.'); expect(client.update).toHaveBeenCalledWith(1,version.etag,expect.objectContaining({address:'Example'}),expect.any(AbortSignal)); });
  it.each([409,412,404,500,0])('blocks resubmission after %s until explicit reload', async status => { const client = clientFor(); client.update.mockRejectedValue(new ApiError(status)); await mount(client); await edit(); await userEvent.click(screen.getByRole('button',{name:'Save recipient'})); await screen.findByRole('alert'); expect(screen.getByRole('button',{name:'Save recipient'})).toBeDisabled(); expect(client.update).toHaveBeenCalledTimes(1); await userEvent.click(screen.getByRole('button',{name:'Reload directory'})); await screen.findByText('Alex Smith'); await edit(); expect(screen.getByRole('button',{name:'Save recipient'})).toBeEnabled(); });
  it.each([400,503])('preserves form after known rejection %s', async status => { const client = clientFor(); client.create.mockRejectedValue(new ApiError(status)); await mount(client); await create(); await userEvent.click(screen.getByRole('button',{name:'Save recipient'})); await screen.findByRole('alert'); expect(screen.getByLabelText('Address')).toHaveValue('Example'); expect(screen.getByRole('button',{name:'Save recipient'})).toBeEnabled(); expect(client.create).toHaveBeenCalledTimes(1); });
  it('guards double submission and locks navigation during a write', async () => { let resolve!: (v:RecipientVersion)=>void; const client = clientFor(); client.create.mockReturnValue(new Promise(r=>{resolve=r;})); const busy = vi.fn(); await mount(client,vi.fn(),busy); await create(); const button = screen.getByRole('button',{name:'Save recipient'}); fireEvent.submit(button.closest('form')!); fireEvent.submit(button.closest('form')!); expect(client.create).toHaveBeenCalledTimes(1); expect(screen.getByRole('button',{name:'Reload directory'})).toBeDisabled(); await act(async()=>resolve(version)); await screen.findByText('Recipient Alex Smith saved.'); expect(busy.mock.calls).toEqual([[true],[false]]); });
  it('does not expose raw errors and handles expired sessions', async () => { const client=clientFor(); const auth=vi.fn(); client.read.mockRejectedValue(new ApiError(401)); await mount(client,auth); await userEvent.click(screen.getByRole('button',{name:'Edit Alex Smith'})); await waitFor(()=>expect(auth).toHaveBeenCalledWith(expect.objectContaining({status:401}))); expect(screen.queryByLabelText('First name')).not.toBeInTheDocument(); });
  it('searches from page zero and paginates bounded results', async () => { const client=clientFor(); await mount(client); await userEvent.type(screen.getByLabelText('Search name or address'),'Alex'); client.directory.mockResolvedValue({...page,totalElements:21,totalPages:2}); await userEvent.click(screen.getByRole('button',{name:'Search recipients'})); await screen.findByText('Page 1 of 2'); expect(client.directory.mock.calls.at(-1)?.slice(0,2)).toEqual([0,'Alex']); client.directory.mockResolvedValue({...page,page:1,totalElements:21,totalPages:2}); await userEvent.click(screen.getByRole('button',{name:'Next recipients'})); await screen.findByText('Page 2 of 2'); expect(client.directory.mock.calls.at(-1)?.slice(0,2)).toEqual([1,'Alex']); });
  it('ignores late editor results after unmount', async () => { let resolve!: (v:RecipientVersion)=>void; const client=clientFor(); client.read.mockReturnValue(new Promise(r=>{resolve=r;})); const view=await mount(client); await userEvent.click(screen.getByRole('button',{name:'Edit Alex Smith'})); view.unmount(); expect(client.read.mock.calls[0]?.[1].aborted).toBe(true); await act(async()=>resolve(version)); expect(screen.queryByLabelText('First name')).not.toBeInTheDocument(); });
  it('renders HTML-looking recipient names as text', async () => { const client=clientFor(); client.directory.mockResolvedValue({...page,recipients:[{...recipient,firstName:'<img src=x onerror=alert(1)>'}]}); render(<Recipients client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await screen.findByText('<img src=x onerror=alert(1)> Smith'); expect(document.querySelector('img')).toBeNull(); });
  it('shows empty results and read failures with explicit recovery', async () => { const client=clientFor(); client.directory.mockRejectedValueOnce(new Error('private detail')).mockResolvedValue({...page,recipients:[],totalElements:0,totalPages:0}); render(<Recipients client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await screen.findByRole('alert'); expect(screen.queryByText('private detail')).not.toBeInTheDocument(); await userEvent.click(screen.getByRole('button',{name:'Reload directory'})); await screen.findByText('Your recipient directory starts here.'); });
  it('ignores obsolete directory results after a new search', async () => {
    let resolve!: (v:RecipientPage)=>void; const client=clientFor(); await mount(client);
    client.directory.mockReturnValueOnce(new Promise(r=>{resolve=r;})).mockResolvedValueOnce({...page,recipients:[{...recipient,firstName:'Latest'}]});
    await userEvent.type(screen.getByLabelText('Search name or address'),'old'); await userEvent.click(screen.getByRole('button',{name:'Search recipients'}));
    await userEvent.clear(screen.getByLabelText('Search name or address')); await userEvent.type(screen.getByLabelText('Search name or address'),'new'); await userEvent.click(screen.getByRole('button',{name:'Search recipients'}));
    await screen.findByText('Latest Smith'); await act(async()=>resolve({...page,recipients:[{...recipient,firstName:'Obsolete'}]}));
    expect(screen.queryByText('Obsolete Smith')).not.toBeInTheDocument(); expect(client.directory.mock.calls[1]?.[2].aborted).toBe(true);
  });
  it('bounds hanging writes and blocks a blind creation retry', async () => {
    const client=clientFor(); client.create.mockImplementation((_input,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); await mount(client); await create(); vi.useFakeTimers();
    try { fireEvent.click(screen.getByRole('button',{name:'Save recipient'})); await act(async()=>{await vi.advanceTimersByTimeAsync(15000);}); expect(screen.getByRole('alert')).toHaveTextContent('save outcome could not be confirmed'); expect(screen.getByRole('button',{name:'Save recipient'})).toBeDisabled(); expect(client.create).toHaveBeenCalledTimes(1); } finally { vi.useRealTimers(); }
  });
  it('bounds hanging reads and allows explicit recovery', async () => {
    const client=clientFor(); client.directory.mockImplementation((_page,_search,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); vi.useFakeTimers();
    try { render(<Recipients client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />); await act(async()=>{await vi.advanceTimersByTimeAsync(30000);}); expect(screen.getByRole('alert')).toHaveTextContent('Recipients could not be loaded'); expect(screen.getByRole('button',{name:'Reload directory'})).toBeEnabled(); } finally { vi.useRealTimers(); }
  });

  it('releases the workspace write lock after leaving during a pending save', async () => {
    let resolve!: (v:RecipientVersion)=>void; const client=clientFor(); const busy=vi.fn(); client.create.mockReturnValue(new Promise(r=>{resolve=r;}));
    const view=await mount(client,vi.fn(),busy); await create(); await userEvent.click(screen.getByRole('button',{name:'Save recipient'})); view.unmount();
    expect(client.create.mock.calls[0]?.[1].aborted).toBe(true); await act(async()=>resolve(version)); expect(busy.mock.calls).toEqual([[true],[false]]);
  });

  it('guides an empty directory without confusing it with filtered results', async () => {
    const client = clientFor(); client.directory.mockResolvedValue({ ...page, recipients: [], totalElements: 0, totalPages: 0 });
    render(<Recipients client={client} onAuthError={vi.fn()} onBusyChange={vi.fn()} />);
    await screen.findByRole('heading', { name: 'Your recipient directory starts here.' });
    expect(screen.queryByRole('heading', { name: 'No recipients match your search.' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Add recipient' })).toHaveLength(1);
    expect(screen.queryByRole('navigation', { name: 'Recipient pages' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add recipient' }));
    expect(screen.getByRole('heading', { name: 'Create recipient' })).toHaveFocus();
    expect(screen.getByRole('group', { name: 'Contact details' })).toBeVisible();
    expect(screen.getByRole('group', { name: 'Delivery details' })).toBeVisible();
  });
  it('clears a no-results search and loads the unfiltered first page', async () => {
    const client = clientFor(); await mount(client);
    client.directory.mockResolvedValueOnce({ ...page, recipients: [], totalElements: 0, totalPages: 0 });
    await userEvent.type(screen.getByLabelText('Search name or address'), 'Missing');
    await userEvent.click(screen.getByRole('button', { name: 'Search recipients' }));
    await screen.findByRole('heading', { name: 'No recipients match your search.' });
    expect(screen.getByText(/Results for “Missing”/)).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    await screen.findByText('Alex Smith');
    expect(screen.getByLabelText('Search name or address')).toHaveValue('');
    expect(client.directory.mock.calls.at(-1)?.slice(0, 2)).toEqual([0, '']);
  });
  it('associates required-field errors and focuses the first missing field without sending a write', async () => {
    const client = clientFor(); await mount(client);
    await userEvent.click(screen.getByRole('button', { name: 'Add recipient' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save recipient' }));
    const name = screen.getByLabelText('First name');
    expect(name).toHaveFocus(); expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription('Enter a first name.');
    expect(screen.getByLabelText('Address')).toHaveAccessibleDescription(/Include the street.*Enter an address/);
    expect(client.create).not.toHaveBeenCalled();
    await userEvent.type(name, 'Alex');
    expect(name).toHaveAttribute('aria-invalid', 'false');
  });
  it('uses one semantic record and gives priority a readable text label', async () => {
    await mount();
    const table = screen.getByRole('table', { name: 'Recipients' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(within(table).getAllByText('Alex Smith')).toHaveLength(1);
    expect(within(table).getByText('Medium')).toBeVisible();
    expect(within(table).getByRole('button', { name: 'Edit Alex Smith' })).toBeEnabled();
  });
  it('keeps uncertain-save recovery visible after cancelling the blocked editor', async () => {
    const client = clientFor(); client.create.mockRejectedValue(new ApiError(0)); await mount(client); await create();
    await userEvent.click(screen.getByRole('button', { name: 'Save recipient' }));
    await screen.findByRole('alert');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('alert')).toHaveTextContent('save outcome could not be confirmed');
    expect(screen.getByRole('button', { name: 'Add recipient' })).toBeDisabled();
    client.directory.mockRejectedValueOnce(new Error('network unavailable'));
    await userEvent.click(screen.getByRole('button', { name: 'Reload and review recipients' }));
    await screen.findByText('Recipients could not be loaded. Please reload the directory.');
    expect(screen.getByRole('button', { name: 'Add recipient' })).toBeDisabled();
    expect(client.create).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Reload and review recipients' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add recipient' })).toBeEnabled());
  });

});
