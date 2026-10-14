# Operator interface modernisation

This focused change applies the shared design foundation to operator setup. The operator API, ETags, assignment state and account handoff remain.

## Directory and forms

The Operators page has one prominent Add operator action, search/result guidance, explicit clearing and distinct initial/filtered empty states. Result summaries use the actual loaded search, including email-based uncertain-creation review. Static loading placeholders accompany status messages; empty directories omit unusable pagination.

One semantic table contains the records. At 1100 CSS pixels and below, its existing rows become cards with operator name/ID, contact details, warehouse ID, text availability and edit/delete actions. Explicit table/row/cell roles and visually hidden headers retain semantics under display changes. Long values wrap without duplicate mobile records.

The editor appears before the directory and groups Contact details, Vehicle and availability, and warehouse selection. Client field checks use the existing `validOperator` contract. Invalid fields have descriptions/invalid-state styling, and submission focuses the relevant control or warehouse heading. Capacity is not defaulted when empty; nonnegative finite values, including zero, retain the existing unit and contract. Vehicle type remains optional.

Warehouse selection retains IDs, names, addresses and selected-state feedback. Invalid-coordinate choices remain disabled. Clearing a warehouse search requests page zero without discarding the selected ID. Starting a new editor resets its warehouse search so pagination matches the displayed choices. Enter in warehouse search searches rather than submits the operator form.

## Assignment, accounts and recovery

ON_DELIVERY still locks warehouse, vehicle, capacity and availability while allowing contact edits. Concurrent assignment changes still require explicit latest-version review; acknowledgement keeps contact drafts and adopts saved operational settings exactly as before. AVAILABLE is not a promise of routing eligibility.

A successful save presents Driver login access as a separate handoff to Accounts. Saving an operator does not create credentials or promise account eligibility. The existing saved-ID/link behavior remains.

The delete dialog identifies the operator by name, ID and email and explicitly focuses Keep operator on opening. Backend account/delivery/history guards remain authoritative. Add and row actions are disabled while an editor is open to prevent replacing its draft; cancellation restores focus after the editor closes.

Conditional writes, duplicate-submit locks, deadlines, permission/session handling and uncertain-write recovery remain. Failed review cannot enable acknowledgement, and no automatic replay occurs. Operational settings are never silently unlocked by the visual redesign.

## Verification and remaining work

Component tests cover initial/filtered empty states, search clearing, grouped validation/focus, zero capacity, warehouse-selection retention, account handoff, semantic rows and safe dialog focus. Existing regressions cover assignment races/releases, ETags, invalid-coordinate warehouses, timeouts and guarded deletion.

Chromium verifies real operator creation, warehouse search via Enter, separate account creation/login/password setup, assignment and completion, guarded deletion, responsive cards and screenshots. Empty previews use API-boundary fixtures in disposable tests; persistent databases and live Maps are not used.

Full assistive-technology and cross-browser acceptance remain separate checks. Accounts is the next focused management-page redesign. This does not complete production release gates.

Local verification on 2026-10-14 passed: 538 backend tests, 393 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, Chromium and packaged-application verification. Secret scanning passed with its reviewed historical acknowledgements; OSV reported zero advisory matches across 392 package/version pairs. Desktop form/empty-state, mobile cards and delete-dialog screenshots were inspected. Hosted GitHub CI was not independently verified.
