# Delivery preparation API

This foundation supports the upcoming React delivery preparation screen. It does not migrate the frontend, change route generation, add delivery editing/deletion to the new API, or replace the existing scheduling endpoints.

## Endpoints and permissions

All endpoints require an enabled administrator session with completed password setup. POST requires session CSRF. Responses use `Cache-Control: no-store` (Spring security errors may include additional no-cache directives).

| Method and path | Contract |
| --- | --- |
| GET `/api/admin/deliveries?page=0&size=20` | Bounded scalar directory |
| GET `/api/admin/deliveries/{id}` | Existing delivery response DTO; 404 for a missing delivery |
| POST `/api/admin/deliveries` | Strict creation; 201 with delivery details and a relative `Location` for reading the saved delivery |

Directory defaults are page 0, size 20. Allowed bounds: page 0–10000, size 1–50. Optional filters are exact ISO `scheduledDate`, named `status` (`PENDING`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`), and positive integer `recipientId`. Omitted filters include all matching statuses/dates/recipients; no historical rows are silently excluded. An unknown positive recipient filter produces an empty list, not a creation-reference error.

The directory returns `{deliveries, page, size, totalElements, totalPages}`. Each scalar item contains delivery ID, recipient ID/first/last name, optional operator ID, date, time window, priority and status. It omits instructions, coordinates and entity collections. Ordering is scheduled date ascending, then delivery ID ascending. Empty/out-of-range pages return empty arrays with metadata. A directory status is descriptive; `PENDING` alone does not establish edit/delete eligibility or absence of route membership.

The projection bounds rows and avoids entity hydration/N+1 loads; a populated page uses at most a content query and count query in the regression test. Pagination does not make filtering/counting constant-cost; index/EXPLAIN and larger dataset acceptance remain roadmap work. The legacy all-delivery endpoint remains available during migration.

## Strict creation payload

```json
{
  "recipientId": 42,
  "scheduledDate": "2026-10-20",
  "startTimeWindow": "09:00",
  "endTimeWindow": "12:00:00",
  "priorityLevel": "MEDIUM",
  "specialInstructions": "Use side entrance"
}
```

The recipient ID must be a positive JSON integer identifying an existing recipient. Dates use `YYYY-MM-DD` and valid calendar dates within the MySQL DATE range 1000–9999. Past dates remain accepted so overdue pending work remains eligible for the existing planner. There is no new future-date-only business policy.

Windows use local time strings `HH:mm` or `HH:mm:ss`, with whole seconds and an end strictly later than the start on the same scheduled day. Equal/reversed/overnight windows and fractional seconds are rejected rather than silently truncated. The API adds no timezone conversion; an overnight or timezone-specific model would require a separate domain decision.

Priority must be a named JSON string: `HIGH`, `MEDIUM` or `LOW`. Instructions are optional or null, at most 255 Java string characters (UTF-16 code units) before normalization, with outer whitespace stripped and blank values stored as null. The generated disposable MySQL schema confirms the existing VARCHAR(255) storage bound; no schema migration is introduced.

Unknown and duplicate fields are rejected, including delivery ID, operator ID, lifecycle status and coordinates. Text, dates, times and priorities are not coerced from numbers/booleans; stringified/fractional/out-of-range recipient IDs are rejected. IDs are generated and new deliveries are always `PENDING` with no operator. Operator assignment belongs to the existing route-generation flow.

Creation locks the referenced recipient within its short database transaction to serialize with deletion. A committed concurrent deletion produces 404 without a delivery; a rolled-back deletion allows creation. The foreign key continues protecting saved references. Creation uses stored recipient data and performs no Maps calls.

## Errors and uncertain writes

Invalid fields/filters return 400 with a sanitized `{error}` message. Missing creation recipients return 404; database reference/integrity conflicts return a fixed 409 response. Role/CSRF failures return 403, expired/revoked sessions return 401, and mandatory password setup follows the existing `passwordChangeRequired` contract. Raw JSON values, SQL and exception diagnostics are not included in these error messages.

POST is not idempotent: repeating a successful payload creates another delivery. After timeout/network uncertainty, do not blindly retry. Read the directory filtered by recipient and date, inspect matching records, then decide whether another creation is appropriate. Matching details alone cannot prove which request created a record; stronger idempotency is separate work. Date/status filters and the returned Location support reconciliation, but do not constitute an exactly-once guarantee.

## Legacy compatibility and boundaries

Legacy POST/PUT use the same date/window/instructions/reference validation. Invalid supplied operator IDs now return 400 rather than silently becoming null. Existing valid optional operator assignment is retained on those endpoints; server-side `PENDING` creation and routed/terminal edit safeguards remain in place. Legacy JSON coercion behavior is retained; clients should use the strict API for its narrower payload contract.

Existing oversized instructions or fractional/reversed windows require correction before a legacy full edit can succeed. Current recipient references, historical address behavior, route feasibility, deletion policy, schema deployment and operational release gates remain separate concerns.

## Validation

Focused tests exercise strict JSON types/duplicates, validation and storage boundaries, overdue work, filters/order/empty pages, scalar SQL/entity counts, missing references, roles/session/CSRF, safe errors and valid/invalid legacy edits. Real HTTP tests use session cookies with OSIV enabled; integration projection tests run with OSIV disabled. Disposable MySQL tests cover storage/date boundaries, FK protection and recipient-deletion commit/rollback races. Maps responses are mocked and no live database/provider calls are required. Existing lifecycle/planner/frontend/Chrome journeys remain full-suite regression gates.
