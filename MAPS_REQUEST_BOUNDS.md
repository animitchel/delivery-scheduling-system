# Maps request and route planning limits

The existing Google Geocoding and Distance Matrix integrations remain. Production recipient creation and route generation use an injected, reusable HTTP transport. Credentials still come from GOOGLE_MAPS_API_KEY, and production destinations remain fixed HTTPS Google endpoints. Redirects are rejected so credentials cannot be forwarded elsewhere.

## Configuration

Validated Spring properties under `scheduling.maps` have these conservative initial defaults:

| Property | Default | Purpose |
| --- | ---: | --- |
| connect-timeout-ms | 1000 | TCP/TLS connection deadline |
| read-timeout-ms | 2000 | Request/header deadline and maximum idle interval during body reception |
| request-timeout-ms | 3000 | Total request deadline, including body reception |
| max-response-bytes | 262144 | Maximum buffered UTF-8 response bytes |
| planning-timeout-ms | 10000 | Shared monotonic deadline for a route-generation operation |
| max-deliveries | 50 | Maximum due pending deliveries considered per operation |
| max-operator-candidates | 500 | Maximum available/no-active-route operator candidates; oversize pools fail instead of truncating IDs |
| max-provider-calls | 100 | Shared attempted distance lookup budget across all driver routes |
| max-concurrent-planning | 2 | Per-application-instance planning slots, with immediate rejection when full |

For environment configuration, use Spring's standard names such as `SCHEDULING_MAPS_CONNECTTIMEOUTMS`, `SCHEDULING_MAPS_PLANNINGTIMEOUTMS` and `SCHEDULING_MAPS_MAXDELIVERIES`. Invalid zero, negative or excessive settings fail startup. These defaults are safeguards, not measured production capacity targets; tune them using representative staging workloads and provider quotas.

The header deadline includes connection time and is additionally limited by the read timeout. Body reception has an idle watchdog and a separate total deadline, so a slow drip cannot extend execution indefinitely. Each routing request is capped by the remaining planning time. Responses exceeding the size limit are cancelled, including chunked responses without Content-Length. Non-success HTTP bodies are not consumed or logged. There are no automatic provider retries.

## Planning and failure behavior

Candidate unrouted delivery IDs are fetched with a maximum of max-deliveries + 1, in a short snapshot transaction before provider calls. An oversized workload is rejected rather than silently routing only part of it. Individual driver caches remain; only cache misses consume a provider-call attempt, and failed attempts count too. The deadline and call budget are shared by the entire operation, not reset per driver.

| Result | HTTP behavior |
| --- | --- |
| Too many deliveries | 413 |
| No planning slot | 429 |
| Shared provider-call budget exhausted | 422 |
| Planning deadline exhausted | 504 |
| Ordinary provider failure within the budgets | Existing straight-line fallback, explicitly labelled |

Transport failures are internally classified as timeout, HTTP failure, quota, denied, oversized response or unavailable. Distance parsing also classifies provider API/element status failures and malformed responses. Only fixed messages and enumerated classifications cross this boundary; provider URLs, bodies, addresses, keys and exception causes are not exposed.

Fallback distance is labelled `Estimated distance; time unavailable` in existing route travel text and API fields. It is a straight-line estimate, not a road-distance or journey-time guarantee. Its duration seconds are null, rather than zero. Deadline and call-budget exceptions bypass fallback and roll back all routes, delivery assignments, metrics and operator availability changed by that operation. All driver routes are calculated before any assignment write. Persistence failures during the final commit roll back earlier route inserts too.

Planning slots are released on every exit. There is no queue or automatic mutation retry. After a rejected or uncertain operation, refresh persisted state before requesting another plan.

## Scope and rollout

No database migration, credential change or live data reconciliation is included. Existing records with old fallback metrics are not rewritten. The API already permits nullable travel seconds. Existing clients should display the supplied travel text and treat null duration as unknown.

This change bounds provider I/O, response memory, delivery count, attempted calls and local concurrent computation. It does not impose a hard timeout on database lock waits, SQL execution or transaction commit. Checks after those waits reject an expired plan, but cannot interrupt the database wait itself. Route computation now occurs outside database transactions, between a short immutable snapshot and a separately locked/revalidated atomic commit; see `ROUTE_PLANNING_TRANSACTIONS.md`. Multi-instance deployments also require a distributed work/admission design; the local semaphore alone is not a global quota.

## Verification

Local HTTP fixtures cover successful UTF-8 responses, stalled TLS/headers/body, slow-drip body, total/remaining deadlines, declared and chunked oversized bodies, denied/quota responses, redirects and recovery. Typed outcome and credential-sanitization tests remain. Configuration tests reject unsafe limits.

Disposable database tests cover an oversized workload with no provider calls or writes; shared call-budget rejection before any driver route is written; deadline failures that cannot be swallowed by fallback; admission rejection; successful later requests; and the existing lifecycle, ownership and real-MySQL concurrency regressions. No live provider calls or credentials are needed.

Geographic selection also reads at most `max-operator-candidates + 1` candidate IDs. Configure with `scheduling.maps.max-operator-candidates` or `SCHEDULING_MAPS_MAXOPERATORCANDIDATES` (1–500). Exceeding the limit returns 413 before provider calls or assignment; narrowing delivery dates does not reduce this operator-pool limit. Within the complete bounded pool, drivers lacking warehouse coordinates are skipped; if none are routable, the warehouse-setup conflict remains. See [GEOGRAPHIC_DRIVER_SELECTION.md](GEOGRAPHIC_DRIVER_SELECTION.md).
