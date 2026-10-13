# Delivery and route lifecycle

Route assignment starts work: eligible PENDING deliveries become IN_PROGRESS when a route is created. Neither administrator nor driver status patches can start or reopen work.

Both status APIs accept COMPLETED or CANCELLED only. A delivery must currently be IN_PROGRESS and belong to one active route with a matching operator. Invalid transitions return 409; unsupported status payloads return 400; missing assignments remain 404. Repeating a successful terminal mutation returns 409 rather than changing its result. Driver ownership, persisted account revision and CSRF checks remain enforced.

A route becomes COMPLETED once every stop is either COMPLETED or CANCELLED. This means execution is finished, not that every recipient received a delivery. Mixed and all-cancelled routes retain their individual stop outcomes. Empty routes never complete automatically.

Finalization changes an ON_DELIVERY operator to AVAILABLE only if there is no other PLANNED or IN_PROGRESS route. OFF_DUTY/UNAVAILABLE choices remain intact. Delivery/operator links, ordered route membership and travel metrics are retained for history.

## History protection

DELETE route endpoints accept only empty PLANNED routes. Active routes, completed routes and any route with delivery history return 409. Bulk deletion checks the entire batch before deleting anything. No deletion resets delivery status or operator availability.

Delivery deletion accepts only unrouted PENDING work. Routed work cannot be edited or deleted, and terminal work cannot be deleted. Explicit route cancellation and archival are separate future operations; deletion is not a substitute.

## Transactions and concurrency

Status changes, route completion and operator release share a READ_COMMITTED transaction. Operator row locks serialize final-stop checks even when two requests update different stops. Administrator writes also lock the delivery and verify that its assignment has not changed while waiting. Driver writes retain the existing atomic ownership/status SQL predicate and require an IN_PROGRESS route.

Route creation locks available operators in ID order, rechecks availability and excludes operators with existing non-completed routes. It then locks candidate pending deliveries in ID order and rechecks their status and membership. Work cannot be claimed twice by overlapping route-generation requests through this API.

Provider I/O, candidate delivery count, shared planning deadlines/call budgets and per-instance admission are now bounded as documented in `MAPS_REQUEST_BOUNDS.md`. Route calculation now runs outside database transactions; `ROUTE_PLANNING_TRANSACTIONS.md` describes immutable snapshots and a short revalidated commit. Direct SQL or future assignment writers must respect these invariants; these locks are not a replacement for the planned schema-integrity work.

## Deployment and verification

No new database migration or live data changes are included. Existing inconsistent records are not silently rewritten. Before production rollout, inspect routes with terminal stops but IN_PROGRESS status, active routes with AVAILABLE operators, and ambiguous/mismatched memberships. Any reconciliation needs a separate reviewed data-change plan.

Integration coverage includes multi-stop completion, mixed/all-cancelled outcomes, another active route, off-duty preservation, rejected transitions/deletions, bulk rollback, retained history, subsequent route creation, and concurrent final-stop updates. MySQL tests exercise ownership, administrator/driver races, simultaneous route generation, and rollback of delivery/route changes if operator release fails. The browser test verifies the driver route header changes to COMPLETED after its final stop.

Run the normal Maven suite with Java 25. Use `scripts/test-account-mysql.sh` for the disposable MySQL suite and the existing `session.browser.*` Maven properties for browser verification. Provider responses in route-generation tests are mocked; no live Google Maps calls are needed.
