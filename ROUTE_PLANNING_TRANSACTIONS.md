# Route planning transaction boundaries

Route generation stays synchronous and keeps the existing Spring/MySQL/Google Maps architecture, API response shape, distribution heuristic, planning budgets and labelled distance fallback. It now uses three distinct stages.

## Snapshot

A short REPEATABLE_READ read-only transaction fetches bounded due PENDING delivery IDs with no route membership, and AVAILABLE operator IDs with no non-completed route. Operator selection is ordered by ID and bounded by the delivery count, so unused operators are not locked or copied. Missing warehouse coordinates or required delivery scheduling/location data produce a conflict before provider work.

Every entity and related field used by the operation is copied into immutable records before this transaction ends. The records include delivery scheduling, priorities, instructions, existing operator links and recipient details; operator availability, metadata, capacity and vehicle type; and warehouse identity/details/coordinates. No entities, lazy proxies or database connection are passed to the calculator.

One scoring time is captured from the injected Clock, converted to the JVM default time zone to preserve the existing interpretation of LocalDate/LocalTime delivery windows. This is not a new time-window feasibility algorithm or a change to the priority/distance/overdue weights.

## Calculate

The pure RouteBuilder calculates ordered stop IDs and immutable travel metrics. It does not set operator links, mutate delivery metrics/status, alter availability or read the database. Scoring evaluates every candidate at the same captured time. The prior mutable DeliveryScore component and per-candidate/debug travel logs are removed.

The route-generation facade rejects an existing caller transaction, rather than keeping that transaction open through Maps work or committing independently of an unsuspecting caller. Snapshot and commit are separate proxied service methods. All provider calls occur after snapshot completion and before the committing transaction begins.

Existing monotonic deadlines, attempted-call budgets, response limits and per-instance admission remain. Straight-line fallback remains explicitly labelled, with unknown duration seconds represented as null. A planning failure writes no assignments. Overlapping planners may calculate the same snapshot and incur duplicate bounded provider costs; there is no reservation, background worker or automatic retry in this change.

## Revalidate and commit

A READ_COMMITTED write transaction locks only the selected operators, then selected deliveries, in ascending ID order. It then locks/refreshes relevant warehouses and recipients in ascending ID order. Related entities are refreshed under write locks because eager loading may have occurred before a concurrent edit committed. Persistence contexts are cleared at both transaction entry points so an OSIV request cannot reuse snapshot-era entities during validation.

The transaction compares current values with the snapshot, checks operators are still AVAILABLE with no active/planned route, and verifies every delivery is still PENDING and unrouted. Deletion, changed metadata or planning inputs, and competing assignment invalidate the whole plan with 409. The planner cannot silently drop or duplicate deliveries or operators: commit checks complete unique coverage before writing.

Once all checks pass, the transaction saves ordered route membership, metrics, delivery IN_PROGRESS status and operator ON_DELIVERY availability together. Any persistence failure rolls back the whole plan, including earlier route inserts. No entities from the snapshot are merged or saved. Responses are mapped inside the write transaction, and it commits before the planning admission slot is released. There is no post-commit deadline check that could report failure after successfully saving routes.

No plan is automatically recalculated after 409. Refresh current deliveries/operators before making an explicit new route-generation request. New eligible deliveries/operators arriving after the snapshot do not invalidate unchanged selected work; they can be considered by a later request. Validation compares current field values and eligibility, not a historical edit counter: a field changed and restored before validation is equivalent to the original input.

## Deployment and remaining work

No new database migration, live data reconciliation, provider endpoint change or frontend migration is included. New constraints/version columns are not required for this implementation. Existing inconsistent data is excluded or rejected, not rewritten.

Database snapshot/commit duration still depends on SQL, lock waits and flush/commit time. The planning budget is checked after waits and during writing, but does not interrupt database operations. Provider latency no longer extends assignment lock duration or holds a checked-out planning database connection. Direct SQL and future assignment writers must respect the same operator/delivery invariants; schema enforcement and distributed admission remain separate roadmap items.

## Verification

Pure planner tests verify repeatable fixed-time order, existing urgency weighting, cache reuse, immutable collections and unchanged source entities. H2 transaction tests pause Maps work and verify no active transaction or checked-out connection; edits proceed during the pause and invalidate the plan. Cases cover delivery scheduling/priority/instructions, recipient address/coordinates/priority, operator availability/capacity/warehouse changes, deletion, two simultaneous planners, existing caller transactions and request-scoped persistence-context freshness.

Disposable MySQL tests additionally verify connection release during Maps work, operator edits during calculation, fresh related data after waiting on a commit lock, two planners with one successful assignment, and full rollback when a second route insert fails. Existing route completion, ownership, Maps bounds, account and browser regressions remain part of verification. Fixtures use mocked provider responses and disposable databases; no live credentials or data changes are needed.
