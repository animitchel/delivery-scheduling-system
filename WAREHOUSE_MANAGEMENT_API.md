# Warehouse management API foundation

## Scope and implementation

This additive administrator API supports warehouse setup before the operator-management and React setup screens. It retains Spring Boot, the existing warehouse/operator schema and legacy endpoints. No migration, frontend dependency or geocoding call is introduced. Coordinates are explicitly supplied, not inferred from the address.

Implementation: `WarehouseManagementController`, `WarehouseService`, `WarehouseInput`, `WarehouseState`, the strict warehouse write DTOs and `WarehouseRepository`. The existing session, enabled-account, password-setup and CSRF safeguards apply through `SecurityConfiguration`. `WarehouseManagementErrors` returns fixed safe errors without SQL, submitted values or exception details.

## Endpoints

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/admin/warehouses?page=0&size=20&search=` | Bounded scalar directory |
| GET | `/api/admin/warehouses/{id}` | Scalar detail plus strong ETag; 404 if missing |
| POST | `/api/admin/warehouses` | Create; 201, Location and ETag |
| PUT | `/api/admin/warehouses/{id}` | Full replacement requiring one strong ETag in If-Match |
| DELETE | `/api/admin/warehouses/{id}` | Conditional deletion; 204 only when unreferenced |

Successful responses and controller errors use `Cache-Control: no-store`. IDs are positive database-generated integers. The scalar response contains `warehouseId`, `name`, `address`, `latitude` and `longitude`; it never contains assigned drivers or account data.

The directory contains `warehouses`, `page`, `size`, `totalElements` and `totalPages`. Page is 0–10,000, size is 1–50 (default 20), and search is at most 100 characters. Search trims outer whitespace, matches names or addresses without case sensitivity, and escapes `%`, `_` and `!` as literals. Results sort by name then warehouse ID. Reads project scalar DTOs without loading operator collections; a repeatable-read transaction keeps page/count queries consistent. Deep offset and substring-search costs still require production query profiling.

## Write contract

```json
{
  "name": "Manchester depot",
  "address": "Depot address",
  "latitude": 53.480759,
  "longitude": -2.242631
}
```

All four fields are required. Name and address must be nonblank JSON strings, with raw limits of 255 and 1,000 characters respectively; outer whitespace is stripped. Coordinates must be JSON numbers: latitude −90 to 90 and longitude −180 to 180 inclusive. Values must be exactly representable at six decimal places, without rounding. Defensive numeric limits are 18 significant digits and a parsed decimal scale between −6 and 12. Strings, booleans, arrays, nulls, duplicate keys, unknown fields, supplied IDs and operator lists are rejected. Valid markup-looking names remain ordinary JSON data; a future frontend must render them as text.

Names are not made unique by this PR. New consumers must select by ID; legacy operator setup still looks up warehouses by name and will be addressed in the operator-management foundation.

## Conditional edits and deletion

Read the detail endpoint and send its quoted strong ETag in `If-Match` when replacing or deleting. Missing/blank headers return 428; weak tags, wildcard tags, lists and malformed tags return 400; a stale fingerprint returns 412. A missing row returns 404 after validating the precondition format. Reload and ask the user to reconcile a stale edit; do not automatically overwrite it.

The ETag hashes the ID and normalized warehouse content. It is a content fingerprint, not a monotonically increasing revision or operation identity. Equivalent decimal scales produce the same fingerprint; unchanged content and changes that return to the same content may retain the same ETag. Operator membership is checked separately at deletion and is not part of the scalar representation.

Writes lock the warehouse and refresh any OSIV-cached entity before comparing its current fingerprint. Waiting edits/deletions therefore see committed state instead of stale first-level-cache data. No schema version column is required for this contract.

Deletion rejects warehouses assigned to **any** operators, regardless of availability or route status, with 409. It never detaches or deletes operators. The assignment check, deletion and flush happen within the warehouse transaction; existing foreign keys prevent dangling assignments during races. The disposable-MySQL tests verify that a deletion waiting for an operator assignment rechecks references after that assignment commits. Integrity and lock conflicts return safe 409 responses. Operational database lock timeouts remain deployment settings.

## Compatibility and recovery

Legacy `/api/v1/scheduling-app/warehouses` paths and successful response shapes remain available. Create/update share name/address/coordinate validation; deletion uses the same lock/reference safeguard. Legacy PUT/DELETE remain unconditional for existing callers and therefore do not offer the new API's client stale-edit guarantee. Valid inputs remain supported; invalid/out-of-range/overprecision coordinates now fail before persistence, and referenced deletion returns a deliberate conflict.

Legacy collection reads retain their existing unbounded response contract. New setup screens must use the bounded administrator API. Do not remove the legacy page or operator endpoints in this slice.

The API does not provide idempotency keys or durable operation tracking. If a create/update/delete acknowledgement is lost, review current warehouse data before deciding on another write. Duplicate names or a matching directory entry do not prove which request created a warehouse. Unexpected failures use a safe message that asks for review instead of promising rollback.

## Verification and release boundaries

Tests cover strict HTTP types/duplicate keys, coordinate boundaries, precision, required-field limits, scalar bounded queries, literal search, ETag persistence, stale edits/deletions, administrator/session/password/CSRF checks, legacy response compatibility and safe error status handling. Real HTTP tests run with OSIV enabled; scalar query tests run with it disabled. Disposable MySQL tests exercise concurrent edits, waiting edits/deletes, stale OSIV state, deleted-row resurrection prevention and operator-assignment races.

Verified in this branch: **487 backend tests**, **272 frontend tests**, TypeScript/ESLint checks, Vite production build and the existing Spring/Chrome workflow all passed. The 48 added backend cases include 39 API/integration cases, seven MySQL cases and two real HTTP cases.

Run the established Java 25 disposable test harness with `./scripts/test-account-mysql.sh`; the full validation also includes the existing frontend checks, Vite build and Spring/Chrome workflow. No live database or Maps provider is used by these tests. Local results do not verify deployed foreign keys, database lock settings, staging HTTPS, backups or production readiness.

The next application slice is safe operator management with warehouse IDs and guarded availability changes, followed by React setup screens. CI remains a separate near-term improvement.

## React consumer

The administrator screen is described in [REACT_WAREHOUSES.md](REACT_WAREHOUSES.md), including explicit coordinates, ETag comparison, deletion confirmation and outcome recovery.
