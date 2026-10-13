# Recipient management API foundation

This branch prepares recipient management for the next React screen. It adds administrator-only bounded reads and strict create/edit contracts, while keeping legacy `/api/v1/scheduling-app/recipients` endpoints available. It introduces no schema migration and does not deploy or change live records. Local ports remain Spring 8081 and Vite 5174.

## Endpoints

| Method/path | Behavior |
| --- | --- |
| GET `/api/admin/recipients?page=0&size=20&search=` | Paginated recipient directory |
| GET `/api/admin/recipients/{id}` | Recipient details and a strong `ETag` |
| POST `/api/admin/recipients` | Create; returns 201, details, `Location` and `ETag` |
| PUT `/api/admin/recipients/{id}` | Full edit; requires the current `ETag` in `If-Match` |

All endpoints require an enabled administrator session. Writes require Spring CSRF. The new responses use `Cache-Control: no-store`; ETags support edit preconditions, not public caching or authentication.

The directory returns `{recipients, page, size, totalElements, totalPages}`. Each item contains recipient ID, first/last name, address, contact number and priority. It uses scalar projections with no entity hydration and a bounded page (default 20, maximum 50). Page numbers are 0–10000 and search is at most 100 characters. Ordering is last name, first name, then ID. Case-insensitive search matches full name or address; `%`, `_` and `!` are treated literally. Empty/out-of-range pages retain metadata. This bounds returned rows; it does not claim constant database cost for sorting, substring search or counts. Query-plan/index profiling remains a production task.

## Write contract

Create and full edit accept only these fields:

```json
{
  "firstName": "Alex",
  "lastName": "Smith",
  "address": "Example postal address",
  "contactNumber": "0123456789",
  "priorityLevel": "MEDIUM"
}
```

Names are required and at most 50 characters; address is required and at most 1000. Contact is optional/null and at most 20. Priority is required (`HIGH`, `MEDIUM`, `LOW`). Outer whitespace is trimmed; blank contact becomes null. IDs, coordinates, timestamps, unknown fields and duplicate fields are rejected on the strict API. Text fields must be JSON strings; priority must be a named JSON string, not a numeric enum ordinal. Identity and coordinates belong to the server.

Read the recipient before editing, retain the exact quoted ETag, and send it in `If-Match`. PUT replaces all writable fields; it is not a partial patch. The server accepts one issued strong token, not weak tags, lists or `*`. Missing preconditions return 428; malformed tokens return 400; an already-stale token returns 412 before geocoding. A change discovered during the prepared commit returns 409. A deleted/missing target returns 404. Reload after 409/412 before deciding on another edit.

Version tokens are derived from all persisted recipient fields, including coordinates and creation time. They require no revision column. Numerically equivalent decimal scales produce the same token. Token construction uses length-prefixed fields, distinguishes null from empty text, and is opaque to clients. Do not construct a token from edited form values.

## Address and transaction safety

Creation geocodes before starting the write transaction. An edit with the same normalized address preserves stored coordinates without another lookup. A changed address is geocoded outside a database transaction; only finite coordinates within latitude ±90 and longitude ±180 are accepted and stored at six decimal places. Missing/malformed provider location fields do not become fabricated zeros.

After lookup, the short commit transaction locks the target, refreshes it from the database, compares it with the immutable snapshot, and then saves. The explicit refresh prevents Spring's request-scoped persistence context (Open Session in View) from supplying stale entity fields despite a fresh SQL row lock. This protects both OSIV-enabled and OSIV-disabled applications. Concurrent changes are not silently overwritten, and deletion cannot cause recreation.

Provider failure/invalid coordinates return a fixed 503 message and do not insert or alter a recipient. Provider text, addresses and URL-bearing exceptions are not included in failure messages. Existing Maps transport deadlines/response bounds remain in effect. No lookup occurs while holding the recipient write lock.

Legacy creation still generates its own ID and obtains coordinates from the provider. Legacy PUT remains a full edit without a mandatory client ETag, but now preserves coordinates for unchanged addresses, geocodes changed addresses, ignores caller coordinates, and rejects changes detected during preparation. New validation bounds apply to legacy writes too. Legacy callers lack protection against edits made before their request begins; use the strict API for stale-form protection. Existing oversized stored addresses require remediation before editing through the new bounded contract.

## Verification and scope

H2 tests cover strict payloads, validation, role/CSRF enforcement, ETags, bounded scalar search, coordinate preservation/rounding, provider failures, concurrent edits and deletion during lookup. Real-HTTP tests exercise session/CSRF and conditional headers, including a reproduced cached-entity race with OSIV enabled. Disposable MySQL tests check directory/search behavior, version stability, one winning concurrent commit, and waiting writers against uncommitted edits/deletes. Existing Java, frontend and Chrome journeys remain regression gates; provider responses are mocked and no real Maps requests or live database operations occur.

Run Java 25 `mvn -B verify`, or `scripts/test-account-mysql.sh` with the documented optional `session.browser.*` arguments for MySQL and Chrome checks. No frontend screen is migrated in this branch; React recipient management is next.

Recipient edits still affect the current recipient record referenced by deliveries. This branch does not add historical address snapshots, regenerate route travel estimates, introduce deletion/archive workflows, or make POST creation idempotent. After an uncertain create outcome, inspect the directory before retrying; do not blindly repeat the write. Historical-recipient and active-route address policy remains a separate domain decision. Provider-side credential restrictions, live schema verification and operational recovery gates remain outstanding.
