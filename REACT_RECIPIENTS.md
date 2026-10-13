# React recipient management

Administrators can open `/app/admin/recipients` from the workspace or legacy scheduling navigation. Spring serves the same Vite bundle; development remains backend 8081 and Vite 5174. Scheduling, delivery preparation and route generation remain available in the existing scheduling page.

The directory uses `/api/admin/recipients` with 20 rows per page and a name/address search of at most 100 characters. Editing always loads fresh details and retains the exact quoted ETag for a full replacement with If-Match. Only names, address, optional contact and named priority are sent; server IDs, coordinates and timestamps are never submitted.

Creates and changed addresses can require geocoding. Known validation/503 lookup failures preserve the form. Conflicts (409/412), deleted targets and uncertain writes block resubmission until an explicit directory reload and review. Writes are never retried automatically. After uncertain creation, inspect the directory before deciding whether to create again. Reads are cancelled when superseded; reads and writes have 30/15 second UI deadlines. Cancellation does not undo a committed server write.

Address edits affect the current recipient referenced by existing deliveries and do not recalculate existing route estimates; the edit form explains this. Historical snapshots, active-route address restrictions and deletion/archive workflows remain separate domain decisions.

The UI uses existing session/CSRF handling, administrator guards and shared navigation/session-refresh locks while saving. Recipient data stays in component memory. React renders names/addresses as text. Forms have labels, native validation and focus handover; the responsive table has a focusable horizontal scroll region.

Validation covers typed response/version checks, bounded search, exact conditional writes, double-submit prevention, known failures, conflicts, session expiry, stale responses, unmount cancellation, pagination and inert HTML-looking text. The Chrome journey exercises real Spring reads and conditional updates, concurrent conflict, mobile overflow and mocked provider failure. Existing backend recipient tests cover provider failure and database concurrency with mocked Maps responses.
