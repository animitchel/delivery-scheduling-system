import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import './warehouses.css';
import { warehousesApi, validWarehouse, coordinate } from './warehouses-api';
import type { WarehouseInput, WarehousePage, WarehouseVersion, WarehousesClient } from './warehouses-api';
type Draft = { name: string; address: string; latitude: string; longitude: string };
type Recovery = { kind: 'create' | 'update' | 'delete'; id: number | null; name: string };
const decimal = (value: string) => /^-?(?:\d+(?:\.\d{0,6})?|\.\d{1,6})$/.test(value.trim());
function fieldErrors(draft: Draft) {
  return {
    name: !draft.name.trim() || draft.name.length > 255 ? 'Enter a warehouse name of up to 255 characters.' : '',
    address: !draft.address.trim() || draft.address.length > 1000 ? 'Enter an address of up to 1000 characters.' : '',
    latitude: !decimal(draft.latitude) || !coordinate(Number(draft.latitude), 90) ? 'Enter latitude from −90 to 90 with at most six decimal places.' : '',
    longitude: !decimal(draft.longitude) || !coordinate(Number(draft.longitude), 180) ? 'Enter longitude from −180 to 180 with at most six decimal places.' : '',
  };
}
const blank: Draft = { name: '', address: '', latitude: '', longitude: '' };
export function Warehouses({ client = warehousesApi, onAuthError, onBusyChange }: { client?: WarehousesClient; onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void }) {
  const [directory, setDirectory] = useState<WarehousePage | null>(null); const [search, setSearch] = useState(''); const [query, setQuery] = useState('');
  const [validationAttempted, setValidationAttempted] = useState(false);
  const [appliedSearch, setAppliedSearch] = useState('');
  const addButton = useRef<HTMLButtonElement>(null);
  const [loading, setLoading] = useState(true); const [reading, setReading] = useState(false); const [pending, setPending] = useState(false);
  const [draft, setDraft] = useState<Draft>({ ...blank }); const [editor, setEditor] = useState(false); const [version, setVersion] = useState<WarehouseVersion | null>(null);
  const [error, setError] = useState(''); const [message, setMessage] = useState(''); const [permission, setPermission] = useState(false);
  const [deletion, setDeletion] = useState<WarehouseVersion | null>(null); const [recovery, setRecovery] = useState<Recovery | null>(null);
  const [ready, setReady] = useState(false); const [latest, setLatest] = useState<WarehouseVersion | null>(null); const [missing, setMissing] = useState(false);
  const alive = useRef(true); const busy = useRef(false); const writes = useRef<AbortController | null>(null);
  const lanes = useRef({ directory: { sequence: 0, controller: null as AbortController | null }, detail: { sequence: 0, controller: null as AbortController | null } });
  const modal = useRef<HTMLDialogElement>(null); const keepButton = useRef<HTMLButtonElement>(null); const returnFocus = useRef<HTMLButtonElement | null>(null); const formHeading = useRef<HTMLHeadingElement>(null); const listHeading = useRef<HTMLHeadingElement>(null); const recoveryHeading = useRef<HTMLHeadingElement>(null);
  const auth = useCallback((cause: unknown) => { if (cause instanceof ApiError && (cause.status === 401 || cause.passwordChangeRequired)) { onAuthError(cause); return true; } return false; }, [onAuthError]);
  const run = useCallback(async <T,>(lane: 'directory' | 'detail', operation: (signal: AbortSignal) => Promise<T>, success: (value: T) => void, failed: (cause: unknown) => void) => {
    const current = lanes.current[lane]; const sequence = ++current.sequence; current.controller?.abort(); const controller = new AbortController(); current.controller = controller;
    const timer = window.setTimeout(() => controller.abort(), 30000);
    try { const value = await operation(controller.signal); if (!alive.current || sequence !== current.sequence) return; if (controller.signal.aborted) throw new ApiError(0); success(value); }
    catch (cause) { if (alive.current && sequence === current.sequence && !auth(cause)) failed(cause); }
    finally { window.clearTimeout(timer); }
  }, [auth]);
  const load = useCallback((page: number, term: string, review = false) => run('directory', async signal => {
    let result = await client.directory(page, term, signal); if (page > 0 && !result.warehouses.length && !signal.aborted) result = await client.directory(0, term, signal); return result;
  }, result => { setDirectory(result); setAppliedSearch(term); setLoading(false); if (review) setReady(true); }, () => { setLoading(false); setReady(false); setError('Warehouses could not be loaded. Reload the directory.'); }), [client, run]);
  useEffect(() => { alive.current = true; const reads = lanes.current; void Promise.resolve().then(() => { if (alive.current) return load(0, ''); }); return () => { alive.current = false; reads.directory.controller?.abort(); reads.detail.controller?.abort(); writes.current?.abort(); }; }, [load]);
  useEffect(() => { if (deletion) { modal.current?.showModal(); keepButton.current?.focus(); } else modal.current?.close(); }, [deletion]);
  useEffect(() => { if (editor) formHeading.current?.focus(); }, [editor]);
  useEffect(() => { if (recovery) recoveryHeading.current?.focus(); }, [recovery]);
  function cancelReads() { for (const lane of Object.values(lanes.current)) { ++lane.sequence; lane.controller?.abort(); } setReading(false); }
  function reload(page = 0, term = query) { if (busy.current) return; if (recovery?.kind === 'create') term = recovery.name.slice(0, 100); setLoading(true); setDirectory(null); setError(''); setReady(false); void load(page, term, recovery?.kind === 'create'); }
  function create() { if (busy.current || recovery || permission) return; returnFocus.current = addButton.current; setValidationAttempted(false); cancelReads(); setLoading(false); setVersion(null); setDraft({ ...blank }); setEditor(true); setError(''); setMessage(''); }
  function open(id: number, remove: boolean, button: HTMLButtonElement) {
    if (busy.current || recovery || permission) return; setValidationAttempted(false); returnFocus.current = button; setReading(true); setError(''); setMessage('');
    void run('detail', signal => client.read(id, signal), result => { setReading(false); if (remove) setDeletion(result); else { setVersion(result); setDraft({ name: result.warehouse.name, address: result.warehouse.address, latitude: String(result.warehouse.latitude), longitude: String(result.warehouse.longitude) }); setEditor(true); formHeading.current?.focus(); } }, () => { setReading(false); setError('Warehouse details could not be loaded. Reload and try again.'); });
  }
  function closeDelete() { if (busy.current) return; setDeletion(null); returnFocus.current?.focus(); }
  function recover(value: Recovery) { cancelReads(); setRecovery(value); setDirectory(null); setLoading(false); setReady(false); setLatest(null); setMissing(false); }
  function review() {
    if (!recovery || busy.current) return; setError(''); setReady(false); setLatest(null); setMissing(false);
    if (recovery.kind === 'create') { reload(0, recovery.name.slice(0, 100)); return; }
    if (recovery.id === null) return; setReading(true);
    void run('detail', signal => client.read(recovery.id!, signal), result => { setReading(false); setLatest(result); setReady(true); }, cause => { setReading(false); if (cause instanceof ApiError && cause.status === 404) { setMissing(true); setReady(true); } else setError('Current warehouse details could not be loaded. Review again before another write.'); });
  }
  function acknowledge() {
    if (!recovery || !ready || reading || loading) return;
    if (recovery.kind === 'update' && latest) setVersion(latest);
    if (recovery.kind === 'delete' || missing) { setEditor(false); setVersion(null); }
    setRecovery(null); setReady(false); setError(''); setLatest(null); setMissing(false); setMessage('Review completed. Any further save requires your explicit action.');
    if (recovery.kind !== 'delete' && !missing) formHeading.current?.focus(); else listHeading.current?.focus();
  }
  async function mutate(remove = false) {
    if (busy.current || recovery || permission || reading) return;
    const target = remove ? deletion : version;
    if (remove && !target || !remove && !editor) return;
    const input: WarehouseInput = { name: draft.name, address: draft.address, latitude: Number(draft.latitude), longitude: Number(draft.longitude) };
    if (!remove) {
      setValidationAttempted(true);
      const errors = fieldErrors(draft);
      const invalid = (Object.keys(errors) as (keyof Draft)[]).find(field => errors[field]);
      if (invalid || !validWarehouse(input)) {
        setError('Enter a name, address and valid coordinates with at most six decimal places.');
        if (invalid) document.getElementById(`warehouse-${invalid}`)?.focus();
        return;
      }
    }
    const kind = remove ? 'delete' : target ? 'update' : 'create'; const submitted: Recovery = { kind, id: target?.warehouse.warehouseId ?? null, name: remove ? target!.warehouse.name : input.name.trim() };
    cancelReads(); setLoading(false); busy.current = true; setPending(true); onBusyChange(true); setError(''); setMessage(''); setDeletion(null);
    const controller = new AbortController(); writes.current = controller; const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      if (remove && target) await client.remove(target.warehouse.warehouseId, target.etag, controller.signal);
      else if (target) await client.update(target.warehouse.warehouseId, target.etag, input, controller.signal);
      else await client.create(input, controller.signal);
      if (!alive.current) return; if (controller.signal.aborted) throw new ApiError(0);
      setValidationAttempted(false); setEditor(false); setVersion(null); setDraft({ ...blank }); setMessage(remove ? 'Warehouse deleted.' : 'Warehouse saved.'); listHeading.current?.focus(); setLoading(true); setDirectory(null); void load(0, query);
    } catch (cause) {
      if (!alive.current || auth(cause)) return; const status = cause instanceof ApiError ? cause.status : 0;
      if (status === 400) setError('Check the warehouse fields and coordinates, then submit again.');
      else if (status === 403) { setPermission(true); setError('Administrator access is required. Refresh your session before another write.'); }
      else { recover(submitted); setError(status === 409 && remove ? 'Deletion conflicted with current data. A warehouse assigned to operators cannot be deleted. Review current details.' : status === 412 || status === 409 ? 'Warehouse data changed or conflicted. Your draft is preserved. Review current details before another write.' : status === 404 ? 'This warehouse is no longer available. Review before continuing.' : 'The write outcome could not be confirmed. Review saved warehouses before deciding on another write.'); }
    } finally { window.clearTimeout(timer); busy.current = false; onBusyChange(false); if (alive.current) setPending(false); }
  }
  const locked = pending || reading || recovery !== null || permission;
  const errors = validationAttempted ? fieldErrors(draft) : { name: '', address: '', latitude: '', longitude: '' };
  function restoreEditFocus() {
    window.requestAnimationFrame(() => { if (alive.current) (returnFocus.current?.isConnected ? returnFocus.current : listHeading.current)?.focus(); });
  }
  function cancelEdit() { setEditor(false); setVersion(null); setValidationAttempted(false); setError(''); restoreEditFocus(); }
  function clearSearch() { if (busy.current || recovery || permission) return; setSearch(''); setQuery(''); reload(0, ''); }
  return <div className="warehouses-page">
    <section className="page-intro warehouse-page-heading"><div><p className="eyebrow">Warehouse management</p><h1>Warehouses</h1><p className="intro-text">Keep depot details ready for operator setup and route planning.</p></div><button ref={addButton} className="button" disabled={locked || loading || editor} onClick={create}><span aria-hidden="true">＋</span>Add warehouse</button></section>
    {error && <p role="alert" className="error-message">{error}</p>}{message && <p className="success-message" role="status">{message}</p>}
    {recovery && <section className="recipient-panel" aria-labelledby="warehouse-recovery"><h2 id="warehouse-recovery" ref={recoveryHeading} tabIndex={-1}>Review before another write</h2><p>{recovery.kind === 'create' ? 'A warehouse may already have been saved. Matching names or an empty list do not prove the outcome. Review all relevant pages before creating again.' : `Review the current record for warehouse #${recovery.id}. Your draft remains unchanged.`}</p><button className="button" disabled={pending || reading || loading} onClick={review}>Review saved warehouse data</button>
      {latest && <div className="warehouse-current"><h3>Current saved details</h3><p>{latest.warehouse.name}</p><p>{latest.warehouse.address}</p><p>{latest.warehouse.latitude}, {latest.warehouse.longitude}</p><p>Your draft is preserved below. Continuing uses this latest version; compare it before saving.</p></div>}{missing && <p>The warehouse is no longer available. Continuing closes the previous action.</p>}
      <button className="button secondary" disabled={!ready || pending || reading || loading} onClick={acknowledge}>{recovery.kind === 'update' && latest ? 'I compared details; continue with latest version' : 'I reviewed results; continue'}</button></section>}
    {editor && <section className="recipient-panel warehouse-editor-panel" aria-labelledby="warehouse-editor"><div className="warehouse-section-heading"><div><p className="eyebrow">{version ? 'Update depot' : 'New depot'}</p><h2 id="warehouse-editor" ref={formHeading} tabIndex={-1}>{version ? `Edit warehouse #${version.warehouse.warehouseId}` : 'Create warehouse'}</h2><p>All fields are required. Coordinates are entered explicitly; the address is not looked up automatically.</p></div></div>
      <form noValidate onSubmit={event => { event.preventDefault(); void mutate(); }}>
        <fieldset disabled={locked}><legend>Warehouse details</legend><div className="warehouse-fields">
          <div className="warehouse-wide-field"><label htmlFor="warehouse-name">Warehouse name</label><input id="warehouse-name" required maxLength={255} value={draft.name} aria-invalid={Boolean(errors.name)} aria-describedby={errors.name ? 'warehouse-name-error' : undefined} onChange={event => setDraft({ ...draft, name: event.target.value })} />{errors.name && <p id="warehouse-name-error" className="warehouse-field-error">{errors.name}</p>}</div>
          <div className="warehouse-wide-field"><label htmlFor="warehouse-address">Warehouse address</label><textarea id="warehouse-address" required maxLength={1000} rows={3} value={draft.address} aria-invalid={Boolean(errors.address)} aria-describedby={errors.address ? 'warehouse-address-help warehouse-address-error' : 'warehouse-address-help'} onChange={event => setDraft({ ...draft, address: event.target.value })} /><p id="warehouse-address-help" className="warehouse-field-help">Include the street, town or city, and postcode for this depot.</p>{errors.address && <p id="warehouse-address-error" className="warehouse-field-error">{errors.address}</p>}</div>
        </div></fieldset>
        <fieldset disabled={locked}><legend>Coordinates</legend><p className="warehouse-field-help">Use at most six decimal places. Check both values before saving; coordinates are not rounded automatically.</p><div className="warehouse-fields">
          {(['latitude', 'longitude'] as const).map(field => <div key={field}><label htmlFor={`warehouse-${field}`}>{field === 'latitude' ? 'Latitude' : 'Longitude'}</label><input id={`warehouse-${field}`} type="number" min={field === 'latitude' ? -90 : -180} max={field === 'latitude' ? 90 : 180} step="0.000001" required value={draft[field]} aria-invalid={Boolean(errors[field])} aria-describedby={`warehouse-${field}-help${errors[field] ? ` warehouse-${field}-error` : ''}`} onChange={event => setDraft({ ...draft, [field]: event.target.value })} /><p id={`warehouse-${field}-help`} className="warehouse-field-help">{field === 'latitude' ? 'Latitude: −90 to 90.' : 'Longitude: −180 to 180.'}</p>{errors[field] && <p id={`warehouse-${field}-error`} className="warehouse-field-error">{errors[field]}</p>}</div>)}
        </div></fieldset>
        <div className="warehouse-editor-actions"><button className="button" disabled={locked}>Save warehouse</button><button type="button" className="button secondary" disabled={locked} onClick={cancelEdit}>Cancel warehouse edit</button></div>
      </form>
    </section>}
    <section className="recipient-panel" aria-labelledby="warehouse-directory"><div className="warehouse-section-heading"><div><h2 id="warehouse-directory" ref={listHeading} tabIndex={-1}>Warehouse directory</h2><p>Find a depot and review its address and coordinates.</p></div></div>
      <form className="warehouse-search-toolbar" role="search" aria-label="Find warehouses" onSubmit={event => { event.preventDefault(); if (locked) return; setQuery(search.trim()); reload(0, search.trim()); }}><label htmlFor="warehouse-search">Search warehouses<input id="warehouse-search" type="search" placeholder="Enter a warehouse name or address" value={search} maxLength={100} disabled={locked} onChange={event => setSearch(event.target.value)} /></label><button className="button secondary" disabled={locked}>Search warehouse directory</button><button type="button" className="text-button" disabled={pending || reading} onClick={() => reload()}>Reload warehouses</button>{appliedSearch && <button type="button" className="text-button" disabled={locked} onClick={clearSearch}>Clear warehouse search</button>}</form>
      {loading ? <div className="warehouse-loading"><p role="status">Loading warehouses…</p><div aria-hidden="true"><span /><span /></div></div> : directory && <><p className="warehouse-result-count" role="status">{directory.totalElements} {directory.totalElements === 1 ? 'warehouse' : 'warehouses'} found.{appliedSearch && <> Results for “{appliedSearch}”.</>}</p>{directory.warehouses.length ? <><div className="recipient-table warehouse-directory-results" role="region" aria-label="Warehouse directory table" tabIndex={0}><table role="table" aria-label="Warehouse records"><thead role="rowgroup"><tr role="row"><th scope="col">Warehouse</th><th scope="col">Address</th><th scope="col">Coordinates</th><th scope="col">Actions</th></tr></thead><tbody role="rowgroup">{directory.warehouses.map(warehouse => <tr key={warehouse.warehouseId} role="row"><th scope="row"><span className="warehouse-record-name">{warehouse.name}</span><span className="warehouse-record-id">Warehouse #{warehouse.warehouseId}</span></th><td role="cell"><span className="warehouse-mobile-label" aria-hidden="true">Address</span><span>{warehouse.address}</span></td><td role="cell"><span className="warehouse-mobile-label" aria-hidden="true">Coordinates</span><span>{warehouse.latitude}, {warehouse.longitude}</span></td><td role="cell" className="warehouse-table-actions"><button className="button secondary" disabled={locked || editor} aria-label={`Edit warehouse #${warehouse.warehouseId}`} onClick={event => open(warehouse.warehouseId, false, event.currentTarget)}>Edit</button><button className="text-button warehouse-delete-action" disabled={locked || editor} aria-label={`Delete warehouse #${warehouse.warehouseId}`} onClick={event => open(warehouse.warehouseId, true, event.currentTarget)}>Delete</button></td></tr>)}</tbody></table></div></> : <div className="warehouse-empty"><h3>{appliedSearch ? 'No warehouses match this search.' : 'Start with your first warehouse'}</h3><p>{appliedSearch ? 'Try another name or address, or clear the search to see all depots.' : 'Add a depot with its address and coordinates, then use it when setting up operators.'}</p></div>}
        {directory.warehouses.length > 0 && <nav className="warehouse-pagination" aria-label="Warehouse pagination"><button className="button secondary" disabled={pending || reading || directory.page === 0} onClick={() => reload(directory.page - 1)}>Previous warehouses</button><span>Page {directory.totalPages ? directory.page + 1 : 0} of {directory.totalPages}</span><button className="button secondary" disabled={pending || reading || directory.page + 1 >= directory.totalPages || directory.page >= 10000} onClick={() => reload(directory.page + 1)}>Next warehouses</button></nav>}</>}
    </section>
    {reading && <p role="status">Loading current warehouse details…</p>}
    {pending && <p role="status">Saving warehouse changes…</p>}
    <dialog className="generation-dialog warehouse-delete-dialog" ref={modal} aria-labelledby="warehouse-delete-title" onCancel={() => { setDeletion(null); returnFocus.current?.focus(); }}><h2 id="warehouse-delete-title">Delete this warehouse?</h2><div className="warehouse-delete-summary"><strong>{deletion?.warehouse.name}</strong><p>Warehouse #{deletion?.warehouse.warehouseId}</p><p>{deletion?.warehouse.address}</p></div><p>Confirm deletion only if this depot is no longer needed.</p><p>A warehouse assigned to operators cannot be deleted. This does not delete or detach operators.</p><div className="recipient-toolbar"><button className="button danger" disabled={pending} onClick={() => { void mutate(true); }}>Confirm warehouse deletion</button><button className="button secondary" ref={keepButton} onClick={closeDelete}>Keep warehouse</button></div></dialog>
  </div>;
}
