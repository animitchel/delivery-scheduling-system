# React driver routes

Driver sign-in and the React workspace navigation now open `/app/driver/routes`. The screen uses the existing Spring driver APIs and ownership checks. Administrator pages and login destinations remain unchanged. No backend DTO, database schema or provider configuration changes are required.

## Workflow

The screen loads assigned routes and then the selected route. It prefers an active route, supports explicit selection, shows loading/empty states, and displays ordered stops, route status, terminal-stop progress and travel estimates. Missing travel estimates are described as unavailable rather than inventing times or distances.

Stop details fetch delivery and recipient information only when opened. The dialog shows address, contact, scheduled date, time window, priority and instructions. Details are cleared and requests cancelled on close, route reload or unmount. Obsolete responses cannot restore a previous recipient. All API text is rendered as React text.

Only an in-progress stop on an in-progress route offers an enabled terminal action. Completion uses one PATCH. Cancellation first asks the driver to confirm or keep the delivery. A pending update disables other stop actions, route reload/selection, shell navigation, session refresh and sign-out. The client never retries a write automatically. After success, assignments and the selected route are read again so the backend determines progress and completion.

An update has a 15-second abort budget, including CSRF acquisition. Network failures, aborts and malformed acknowledgements may occur after the server commits: the screen therefore describes the outcome as uncertain, clears the old assignment and requires an explicit reload before another action. A 404 describes an unavailable assignment; a 409 describes a changed delivery. A missing on-demand stop also blocks writes until reload. Session expiry removes the authenticated workspace, and required password setup uses the existing protected workflow.

Client controls improve the workflow; persisted Spring authorization and guarded database transitions remain authoritative. Another driver's route, delivery or recipient ID remains unavailable even if a browser changes its request. An already authorized read can complete while an assignment subsequently changes; recipient data is not persisted in browser storage or embedded in public HTML.

## Validation and rollout

`npm --prefix frontend run check` runs TypeScript, ESLint and component/API tests. Tests cover safe rendering, on-demand details, obsolete responses, cancellation confirmation, duplicate writes, terminal progress, read failures, missing assignments, conflicts, session errors, update timeouts and abort propagation through CSRF acquisition.

The existing Maven browser verification now executes the React driver journey against the running application with disposable fixtures. It checks deep links, role and ownership restrictions, route selection, native-dialog Escape/focus return, persisted completion/cancellation, conflict recovery, empty routes and widths 320/375/768/1440. It also visits the legacy page to confirm it displays the same terminal outcomes. Password onboarding/reset and administrator journeys remain covered.

Build and deploy the complete Spring jar using the established frontend Maven build. This change does not deploy anything or modify live accounts. For rollback, revert the application artifact or restore the driver login destination to `/html/operators.html`; both pages use the same persisted route state. Neither page reopens completed/cancelled deliveries.

Remaining frontend migrations include administrator accounts and scheduling. Wider production readiness still depends on the project's operational, schema and scheduling gates.
