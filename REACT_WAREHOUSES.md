# React warehouse management

Administrators open `/app/admin/warehouses` from workspace navigation, the overview or legacy scheduling. The screen uses the existing warehouse-management API; no schema migration, new dependency or Maps call is introduced. Operator setup remains a separate next slice.

## Directory and setup

`frontend/src/Warehouses.tsx` provides bounded name/address search, 20-row pagination, empty/error/loading states and keyboard-accessible horizontal table scrolling. Selecting edit or delete fetches the current warehouse detail and its ETag instead of writing from a directory snapshot. Directory and detail requests have separate abort/sequence lanes; obsolete responses are ignored. A disappeared last page falls back to page zero.

`frontend/src/warehouses-api.ts` validates response IDs, field types, page bounds, unique rows, strong ETags and expected HTTP statuses. Writes send only name, address, latitude and longitude. Acknowledgements must match the submitted normalized values and the selected ID for edits; malformed or mismatched acknowledgements enter recovery rather than claiming success.

Names/addresses use the API's 255/1,000 character limits. Coordinates are explicitly entered in decimal form, within latitude −90 to 90 and longitude −180 to 180, at most six decimal places. Blank, nonfinite, exponential and overprecision drafts are rejected before sending; the UI never silently rounds a draft. The backend remains authoritative. Readable legacy numeric coordinates outside these limits remain visible and editable so they can be corrected.

Creation and editing use the established form styles and responsive layout. Stored names and addresses are rendered as React text. There is no automatic address lookup or map picker.

## Safe writes, editing and deletion

During a write, duplicate actions, form edits, workspace navigation, sign-out and session refresh are blocked through the existing shell. Reads have 30-second deadlines and writes 15-second deadlines. Unmount aborts outstanding requests and late responses cannot restore private content; the parent write lock is released when the write settles. API transport uses same-origin sessions and CSRF.

PUT and DELETE send the ETag from the fresh selected detail. A stale/conflicting edit preserves the draft and blocks another write. **Review saved warehouse data** loads that exact ID, displays the current saved values separately and requires an explicit comparison acknowledgement before adopting the latest ETag. The draft itself stays unchanged; another save is always a separate user action. A missing record is shown explicitly; acknowledging it closes the previous editor/action rather than turning the old edit into a create.

Deletion has a native confirmation dialog showing the warehouse ID/name. Escape or **Keep warehouse** returns focus without a write. Referenced warehouses cannot be deleted, detached or cascaded. Conflicts explain that assignment can prevent deletion and require current-record review before another action. Confirmation is still required for any later deletion.

Validation failures allow field correction. Permission failures block writes until the shell refreshes the session. Expired/revoked sessions or mandatory password setup delegate to the existing shell, clearing the screen.

## Unknown outcomes and limits

Timeouts, transport failures, malformed acknowledgements and unexpected server failures never replay writes automatically. The screen clears obsolete directory results and enters review. Creation recovery searches the submitted name (first 100 characters, matching the search limit), permits paging and requires a successful read plus explicit acknowledgement. A matching name or empty directory cannot prove which request saved a warehouse. Editing/deletion recovery reads the exact selected ID; an absent record after uncertain deletion is a review result, not proof of request identity.

Failed review reads keep writes blocked. Acknowledgement enables another explicit decision without submitting a request. Older responses started before recovery cannot unlock it. The backend has no idempotency key or operation-status endpoint.

Drafts and recovery live in component memory. Navigation, page reload and the shell's session/focus refresh may clear them; this slice does not introduce browser storage or durable draft tracking. Copy important unsaved text before leaving the screen. Deployment proxy/database deadlines and durable operation tracking remain separate work.

## Verification and development

API/component tests cover input precision, response contracts, safe text rendering, conditional headers, deletion confirmation, stale drafts, uncertain outcomes, failed/stale reads, permission/session guards, duplicate prevention, deadlines, unmount and pagination. Shell tests cover the driver-role guard and navigation/session behaviour during a write.

Verified in this branch: **522 backend tests**, **311 frontend tests**, TypeScript/ESLint, the Vite production build and the Spring/Chrome workflow passed. The final layout/focus and cancelled-read refinements were rechecked with a rebuilt frontend and Chrome smoke run.

The Chrome journey runs against Spring and disposable fixtures: real create/edit, a concurrent administrator edit returning 412, explicit comparison with draft preservation, referenced deletion returning 409, and a committed delete with a malformed acknowledgement followed by a 404 review. Responsive checks cover 320, 375, 768 and 1440 px; desktop/mobile screenshots are inspected. The existing driver/account/recipient/delivery/generation journeys remain in the same verification run. No live database or provider is changed.

Spring serves the production Vite bundle under `/app/`, including the explicit `/app/admin/warehouses` entry point. For the established development ports:

```sh
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
```

Backend details are in [WAREHOUSE_MANAGEMENT_API.md](WAREHOUSE_MANAGEMENT_API.md). Next: React operator setup using [OPERATOR_MANAGEMENT_API.md](OPERATOR_MANAGEMENT_API.md), followed by CI and remaining production-readiness gates.
