# React route generation

## Workflow and scope

Administrators open `/app/admin/routes/generate` through the workspace navigation or legacy scheduling page. Choose a planning cutoff date, review the confirmation, then explicitly generate and assign. The cutoff includes eligible overdue deliveries. Confirmation explains that this immediately assigns operators, starts routes and deliveries, and marks operators on delivery. This is an assignment operation, not a preview.

The existing planner and its API remain authoritative: the client submits only `scheduledDate`, never an operator selection. There is no solver rewrite, schema migration or new dependency. Legacy scheduling remains available. Routing estimates do not guarantee delivery-window feasibility.

The implementation is in `frontend/src/RouteGeneration.tsx` and `frontend/src/route-generation-api.ts`. Role/session guards and navigation live in `frontend/src/App.tsx`; Spring serves the explicit entry path through `FrontendController`.

## Confirmation, deadlines and failures

The modal captures the selected cutoff date. Escape or **Keep reviewing** dismisses it without a write. During generation the workspace blocks duplicate submissions, navigation, session refresh and sign-out; reads and writes have separate cancellation lanes. Session expiration or mandatory password setup clears private content through the existing shell.

Generation has a dedicated 180,000 ms browser deadline. Set the public, build-time Vite variable `VITE_ROUTE_GENERATION_TIMEOUT_MS` to an integer between 1,000 and 600,000 ms when building the frontend. Invalid configuration disables generation with a configuration error; read-only route review still works. This variable is not a secret and does not change the server planning budget, database lock waits or reverse-proxy timeout. Align these operational settings for the deployment; increasing a browser timeout alone cannot prevent a proxy timeout.

**Stop waiting and review** cancels browser waiting. It does not undo assignments or guarantee that the server stopped. Timeouts, transport failures, malformed acknowledgements and unexpected server failures all enter outcome recovery. No failed write is automatically replayed.

Known setup failures use a small allowlist of the API's fixed reasons, mapped to safe categories in `frontend/src/api.ts`. The screen explains missing pending work, available operators, warehouse coordinates or delivery data without rendering arbitrary server messages. Stale-input and commit-conflict responses require review. Permission failures disable further generation until the shell refreshes the session.

## Acknowledgements and recovery

A successful acknowledgement must match the submitted date, contain unique positive route/operator IDs, valid assignment summaries, and no more than the server's hard maximum of 500 assigned stops. Acknowledgement summaries represent commit-time assignments; they are not a live progress view.

Recovery clears obsolete rows and aborts earlier directory reads. Review is fixed to the submitted date and includes **all route statuses**, including completed routes. The administrator can page through the directory and inspect current stop IDs/statuses. Failed reads keep another generation blocked. A successful read enables an explicit **I reviewed results; allow another request** decision. That decision does not submit a request; another assignment still needs confirmation.

A matching or empty directory cannot prove the identity or outcome of one request. The API has no idempotency key or operation-status endpoint. Read carefully before deciding to generate again. Recovery state lives in the mounted screen; leaving the screen, refreshing the page or refreshing the session clears it. Durable operation tracking remains separate future work.

Generated and reviewed routes link to `/app/admin/routes?routeId=<id>`. The existing route review screen opens that exact route even when it is outside the first directory page, and rejects invalid or duplicate IDs without substituting another route. Read-only stop inspection here leaves lifecycle actions on the existing route review screen. Missing travel estimates are explicitly labelled unavailable.

## Verification and development

Component/API tests cover confirmation, duplicate prevention, date-only contracts, acknowledgement validation, deadlines, cancellation, safe failures, recovery, all-status filtering, obsolete responses, permission guards and route deep links. The Chrome journey runs against Spring with deterministic Maps test doubles: it confirms a real assignment, hides a second committed acknowledgement, reconciles its route/stop IDs, and opens the exact route. Responsive checks cover 320, 375, 768 and 1440 px. Disposable databases and mocked Maps avoid live data changes and provider calls.

The implementation was verified with 439 backend tests against disposable MySQL, 272 frontend tests, TypeScript/ESLint checks, the Vite production build and the Spring/Chrome journey. The final route-link reload change was also checked through frontend regression tests and a rebuilt Chrome smoke run. Local evidence does not replace staging HTTPS, operational timeout, backup/restore or deployment checks.

For the established local ports, run the frontend with:

```sh
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
```

The production artifact still serves the Vite bundle from Spring under `/app/`. Backend endpoint details and release limitations are documented in [ROUTE_GENERATION_API.md](ROUTE_GENERATION_API.md).
