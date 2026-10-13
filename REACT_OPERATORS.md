# React operator management

Administrators open `/app/admin/operators` from workspace navigation, the overview or legacy scheduling. The screen completes driver-record setup using the existing operator and warehouse APIs. It adds no schema migration, dependency or provider call. Login account creation remains a separate explicit action through Accounts.

## Directory, forms and warehouse selection

`frontend/src/Operators.tsx` provides name/email search, bounded 20-row pagination, loading/error/empty states, safe text rendering and keyboard-scrollable tables. Edit/delete actions fetch the current operator detail and ETag before acting. Directory, selected-detail and warehouse-choice reads use independent sequence/abort lanes, preventing obsolete results from replacing newer selections.

Creation/editing captures name, phone, email, optional vehicle type, finite nonnegative capacity, warehouse ID and availability. Field lengths and phone format follow [OPERATOR_MANAGEMENT_API.md](OPERATOR_MANAGEMENT_API.md); native email checks and basic client validation improve feedback while the backend remains authoritative. Capacity retains the application's existing unit; no new unit or physical limit is invented. Legacy null capacity/warehouse references remain readable and require correction before valid writes.

Warehouse choices come from the existing bounded administrator directory, with independent search and pagination. Names, addresses and IDs are shown; duplicate names are distinguished by ID. Selection remains explicit and is retained across pages/searches, including when it is outside the visible choices. No first-row selection or name-based assignment is introduced. Invalid-coordinate choices are disabled with a link to warehouse management. Empty/failed choices explain how to create/correct a depot or retry. Enter in warehouse search performs search rather than submitting the operator form.

`frontend/src/operators-api.ts` validates IDs, recognized string statuses, field types, bounded page metadata, unique rows, strong ETags and HTTP statuses. POST/PUT send only setup fields, with server-generated IDs. Successful acknowledgements must match the normalized submitted values; edits must match the selected ID. Wrong/malformed responses enter outcome review. Credentials, role changes and account links cannot be supplied through this contract.

## Assignment restrictions and conditional edits

An operator's AVAILABLE status is not a guarantee of routing eligibility. Existing planned routes or unfinished deliveries may still exclude it; the server is authoritative.

For a selected ON_DELIVERY record, availability, warehouse, vehicle and capacity controls are read-only, while contact/name/email fields remain editable. ON_DELIVERY is never offered for creation or manual switching; the API client rejects creation with that status too. Other active/planned-work conflicts are shown after the backend rejects the change; the scalar detail does not expose complete assignment membership, so the client cannot infer every restriction in advance.

Edits send the fresh selected ETag in If-Match. Stale/conflicting writes preserve the draft and require a successful read of the exact operator, a separate presentation of saved details and explicit acknowledgement before another save. Adopting a latest version normally leaves the full draft unchanged. If the operator has become ON_DELIVERY during editing or its previous ON_DELIVERY assignment has been released, the distinct **Keep contact draft and saved assignment settings** action explicitly adopts saved operational settings while retaining the contact draft. It does not submit another request. Inconsistent legacy records still require controlled backend reconciliation rather than an availability override.

## Deletion, access handoff and recovery

Deletion has a native confirmation dialog showing the operator ID/name. Escape and **Keep operator** return focus without writing. The request uses the ETag from the fresh detail. Linked accounts, deliveries and route history block deletion; the UI explains the conflict and requires review. Nothing is detached, cascaded or revoked.

After a successful save, the screen displays the saved operator ID and a **Manage driver login access** link to Accounts. Administrators select that operator if eligible and explicitly grant access there. Creating the driver record does not generate credentials, automatically provision an account or change account access.

During writes, duplicate submissions, form changes, workspace links, sign-out and session refresh are blocked through the established shell. Reads use 30-second deadlines, writes 15 seconds. Unmount aborts outstanding requests; late responses cannot restore private state and settling releases the shell lock. Session revocation/password setup clears the screen through existing shell callbacks; permission failures block writes until session refresh.

Timeouts, transport failures, malformed acknowledgements and unexpected errors never replay writes automatically. Creation review searches the submitted email across paginated results; duplicate email/setup conflicts also require review. A matching or empty result cannot prove request identity. Edit/delete review reads the exact ID; an absent record explicitly closes the previous action after acknowledgement rather than converting an edit into creation. Failed/stale reads cannot unlock another write. The API has no idempotency key or durable operation-status endpoint.

Drafts/recovery live in component memory; navigation, reload or the shell's focus/session refresh can clear them. No browser storage, durable draft tracking or new account UI is introduced.

## Verification

API/component tests cover strict response contracts, setup payloads, ID-based warehouse selection across pages, invalid/failed/stale choices, explicit selection, Enter behaviour, conditional writes, stale drafts, assignment changes, deletion/history conflicts, unknown outcomes, deadlines, duplicate submissions, unmount, permission/session guards and safe markup rendering.

Verified in this branch: **523 backend tests**, **349 frontend tests**, TypeScript/ESLint, Vite production build and the Spring/Chrome journey passed. The final route-completion draft fix was verified by a new component regression and a rebuilt Chrome smoke run.

The Spring/Chrome journey creates an operator using an existing warehouse, grants driver access through Accounts, sets a permanent password, generates assigned work, verifies ON_DELIVERY controls, completes the route as the driver and verifies backend release to AVAILABLE. It also checks the driver-role guard and protected account/history deletion. Existing account/recipient/delivery/warehouse/generation journeys remain in the same run. Responsive checks cover 320, 375, 768 and 1440 px; desktop/mobile screenshots are inspected. Test databases are disposable and Maps is mocked.

Spring serves the Vite bundle under `/app/` with the explicit operator entry path. The established local development ports remain:

```sh
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
```

After merge, CI is the next recommended focused PR. Legacy cleanup, history/archive contracts, durable draft handling, staging HTTPS, deployed constraints, monitoring, backup restore and rollback evidence remain production-readiness work.
