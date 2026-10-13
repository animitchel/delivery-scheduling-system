# Route generation API foundation

This contract supports the upcoming React generation screen. It reuses the existing snapshot/calculate/revalidate planner and lifecycle rules. It does not add a preview mode, manual driver assignment, a new solver, frontend cutover, schema migration or live deployment.

## Endpoints and access

All endpoints require an enabled administrator session with completed password setup. Generation requires session CSRF. Success and handled errors use `Cache-Control: no-store`; Spring security/framework errors may include additional no-cache directives.

| Method and path | Contract |
| --- | --- |
| POST `/api/admin/routes/generate` | Strict date-only generation; 201 after the planner commit completes |
| GET `/api/admin/routes?page=0&size=20&scheduledDate=2026-10-13` | Scalar, paginated summaries, optionally filtered by exact route date |
| GET `/api/admin/routes/{id}` | Ordered route details using the existing transactional mapping; missing routes return 404 |

## Date-only generation

```json
{"scheduledDate":"2026-10-13"}
```

The field must be a JSON string containing a valid calendar date in `YYYY-MM-DD` form and within the MySQL DATE range 1000–9999. Missing/null dates, timestamps, numeric/boolean dates, unknown fields and duplicates return 400 before provider calls. Operator IDs, route IDs, statuses and delivery lists are unsupported fields in this contract.

The date is a **planning cutoff**, not an exact-delivery-date filter: generation selects pending deliveries scheduled on or before it that have no route membership. Overdue work remains included; future work beyond the cutoff is excluded. The planner chooses available operators without active/planned routes. It requires the existing warehouse/location and scheduling inputs.

**Generation has immediate operational effects.** It persists routes and operator assignments, sets routes/deliveries to `IN_PROGRESS`, and marks assigned operators `ON_DELIVERY`. There is no separate confirmation/activation endpoint or preview. The later React screen must explain these effects before submitting.

The response is a compact acknowledgement:

```json
{
  "scheduledDate":"2026-10-13",
  "routes":[{
    "routeId":7,"operatorId":3,"operatorName":"Driver",
    "scheduledDate":"2026-10-13","routeStatus":"IN_PROGRESS",
    "stopCount":2,"finishedCount":0
  }],
  "processingTimeMs":42
}
```

The response contains the generated route IDs and commit-time summaries, without embedding all stops/recipient data. `processingTimeMs` uses elapsed monotonic time; it is not a promised maximum duration. Read individual routes to obtain current status/ordered stops. Driver activity after commit can make acknowledgement status stale before it arrives.

## Review and reconciliation

Directory bounds match existing route review: page 0–10000, size 1–50, defaults 0/20. Results retain `{routes,page,size,totalElements,totalPages}` and date-descending/ID-descending order. An omitted date includes all route dates; a supplied date matches `Route.scheduledDate` exactly, including every route status (`PLANNED`, `IN_PROGRESS`, `COMPLETED`). Do not infer creation failure from an empty active-only list.

The directory uses scalar headers/counts and an aggregate restricted to page route IDs, without hydrating off-page entities/collections. The populated-page regression permits at most three statements (content, count, page metrics). Pagination does not make full-table filtering/counting constant-cost; index/EXPLAIN and scale acceptance remain roadmap tasks.

Generation is not idempotent. Do not automatically retry after network cancellation, unreadable acknowledgement or an uncertain server outcome. Review the selected cutoff date across all route statuses and inspect route members and delivery state before deciding on another generation. Dates/matching members alone cannot prove request identity; there is no operation ID or exactly-once guarantee. A repeat request might pick up newly created pending work, even if the original request already committed. Cancelling the client request does not undo a server commit.

## Errors and existing safeguards

Handled errors use sanitized `{error}` messages. Unknown exception messages, SQL/provider diagnostics and submitted extra-field values are not returned. Unexpected failures are logged by exception type without raw exception messages. Existing framework 4xx statuses (for example 415 for unsupported media) remain intact.

| Status | Meaning |
| --- | --- |
| 400 | Invalid date, JSON fields, page bounds or filter values |
| 401 / 403 | Expired/revoked session, role/CSRF rejection, or password setup required |
| 409 | No eligible work/available operators, missing required inputs, stale planning inputs or failed persistence/concurrency |
| 413 | Too many eligible deliveries for one planning operation |
| 422 | Provider-call budget exceeded |
| 429 | This application instance's planning slots are busy |
| 504 | Planning deadline exceeded |
| 500 | Unexpected outcome; review existing routes/deliveries before another generation |

The existing provider/request limits and planning budget remain configured through `scheduling.maps`. Defaults include a 10-second checked planning budget, 50 deliveries, 100 provider calls and two concurrent planning slots per instance. These are checked application budgets; database lock waits and deployment/proxy timeouts still require operational configuration. The frontend generation timeout must account for deployment settings rather than assume every operation fits the short delivery-write timeout.

Provider calls remain outside database transactions. Commit locks/reloads operator, delivery, warehouse and recipient inputs and revalidates the snapshot. Concurrent plans for the same work cannot both commit. Persistence failure rolls back all routes and assignments from that transaction. There is no post-commit budget check that falsely reports a rolled-back generation.

Some provider failures retain the existing estimated-distance fallback: route details say `Estimated distance; time unavailable`, with no travel-time value. Generation success therefore does not promise provider-backed travel estimates. The existing scoring/ordering planner does not guarantee delivery-window feasibility, vehicle-capacity feasibility or optimal routing; those remain separate improvements.

## Legacy compatibility and verification

Legacy POST `/api/v1/scheduling-app/routes` retains its payload/response and the existing behavior of ignoring its optional operator ID. New callers should use the strict contract. Shared service validation now rejects null/out-of-storage-range dates before acquiring a planning slot. Legacy GET `/routes/directory` also accepts the optional exact `scheduledDate` filter; existing callers omitting it retain their results. Scoped safe error handling applies to both route controllers. Legacy deletion/lifecycle rules are preserved.

Focused H2/MockMvc tests cover strict parsing, roles/CSRF/password setup, cutoff/overdue selection, acknowledgement shape, framework errors, planning limits, fallback labels, stale inputs, concurrent requests, whole-plan rollback and scalar all-status filtering. Real HTTP tests use cookies/CSRF with OSIV enabled; scalar tests use OSIV disabled. Disposable MySQL verifies concurrency, rollback, completed-route review, paging and storage date bounds. Maps is mocked; no live provider/database calls are required. Existing Java/MySQL/frontend/Chrome journeys remain regression gates.
