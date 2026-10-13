# React delivery preparation

Administrators can open `/app/admin/deliveries` from the React workspace or legacy scheduling dashboard. Spring serves the same Vite bundle. Local development remains Spring 8081 and Vite 5174; no new frontend dependencies or database migration are introduced.

## Creation and recipient selection

Recipient selection uses the bounded administrator directory, 20 results per page with a name/address search of at most 100 characters. Results show the name, address and recipient ID to distinguish similar names. Selection fetches current recipient details before displaying the chosen address. Superseded reads and old selections cannot overwrite newer results.

The creation form accepts the recipient, calendar date, same-day start/end window, named priority and optional instructions (255 UTF-16 code units). It mirrors the API's whole-second time/window and date validation, including past dates for overdue work. Native date/time inputs add no timezone conversion. The screen explains that new deliveries are pending/unassigned and route generation assigns the operator.

The typed client submits only writable fields and validates a 201 response, matching saved fields, generated ID, relative Location and pending/unassigned acknowledgement. Malformed or mismatched acknowledgements are uncertain outcomes, never automatic retries. A confirmed save clears the creation selection/draft, shows the saved ID/details, and reloads a directory scoped to that recipient/date across all statuses.

## Directory and details

The directory uses 20 rows per page with date, status and selected-recipient filters. It displays ID, recipient, date/window, priority and status; full instructions/operator details are fetched only when selected. Existing null scheduling fields and fractional read times remain displayable; creation follows the strict new contract. A `PENDING` row is not treated as permission to edit/delete. No editing, deletion or route generation is added here.

Filter/read failures have explicit reload controls. The two paginated lists are independent. Empty/out-of-range pages recover to page zero where appropriate; obsolete directory/details responses are ignored. The horizontally scrollable table is a named keyboard region with a scroll hint. Forms use explicit label associations and focus handover; layouts are checked at 320, 375, 768 and desktop widths.

## Uncertain-save recovery

Validation rejection retains the draft. Missing/conflicting recipient references clear the selected recipient and require selection of current details. Permission failures require session refresh; expired sessions/password setup use the existing parent guard.

Timeout, network/server failures or malformed creation acknowledgements preserve and freeze the draft. Another submission is blocked. The explicit review action loads the submitted recipient/date across **all statuses**, including work assigned after creation. Filters remain fixed during this review, with pagination and on-demand details available. A failed review does not unlock submission; a successful read enables an explicit administrator acknowledgement before another submission is permitted. The UI explains that another submission creates a new delivery.

An empty list or matching fields do not prove request identity or whether creation failed. This is manual reconciliation, not idempotency or an exactly-once guarantee. No POST is automatically replayed. Leaving the screen or a session refresh clears its in-memory draft/recovery state; neither cancellation nor navigation undoes a committed write. Review existing work before manually re-entering an uncertain submission.

## Session, deadlines and regression checks

Requests use the shared same-origin session/CSRF client. Reads have 30-second UI deadlines and writes 15 seconds. Shared workspace navigation, sign-out and session refresh are paused while a write is pending. Unmount aborts requests and releases the workspace write lock; late callbacks do not render data. No recipient/delivery data is written to browser storage. Names and instructions are rendered as React text.

Tests cover strict acknowledgements/payloads, creation, overdue/invalid windows, missing recipients, permission/session failures, independent pagination, stale responses, timeout/unmount behavior and uncertain outcomes across all statuses. The Chrome journey creates real pending work against Spring, then lets another creation commit while replacing its acknowledgement with invalid JSON. The screen must find that saved work through explicit review without issuing another POST. Existing Java/MySQL/frontend/Chrome journeys remain regression gates. No live Maps calls or live database changes are required.

Route generation remains available in `/html/admin.html` until its own React migration. Historical recipient policy, stronger creation idempotency, planner feasibility, database release and operational readiness remain separate roadmap tasks.
