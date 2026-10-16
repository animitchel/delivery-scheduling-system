# Saved-route window assessment API

`POST /api/admin/routes/{id}/assessment` performs a hypothetical calculation using an existing saved route. It does not generate a route, assign operators, edit deliveries, record actual arrivals or call Maps. Frontend and hosting work remain paused.

## Access and request

Use an authenticated administrator session, its current CSRF header/token, and `Content-Type: application/json`. Drivers are forbidden; revoked sessions and accounts requiring password setup cannot use this endpoint. POST carries the calculation assumptions; it does not imply a business-data write.

```json
{
  "departure": "2026-01-02T08:00:00",
  "serviceDurationSeconds": 600
}
```

Both fields are required. Departure is an unzoned local timestamp in exact `YYYY-MM-DDTHH:mm:ss` form, with a year from 1000 through 9999. Offsets, fractions, invalid dates, duplicate/unknown fields, trailing JSON and coerced values are rejected. Service duration is a nonnegative whole JSON integer, applied uniformly to every saved stop; arithmetic overflow is rejected. The departure date is independent of the route's planning cutoff date. No time-zone, DST or live traffic model is inferred.

## Result

```json
{
  "routeId": 123,
  "operatorId": 7,
  "routeStatus": "IN_PROGRESS",
  "hypothetical": true,
  "estimateSource": "SAVED_TRAVEL_ESTIMATES",
  "windowPolicy": "SERVICE_START_INCLUSIVE",
  "departure": "2026-01-02T08:00:00",
  "serviceDurationSeconds": 600,
  "stops": [{
    "deliveryId": 42,
    "position": 1,
    "windowStart": "2026-01-02T09:00:00",
    "windowEnd": "2026-01-02T10:00:00",
    "status": "WITHIN_WINDOW",
    "arrival": "2026-01-02T08:15:00",
    "serviceStart": "2026-01-02T09:00:00",
    "departure": "2026-01-02T09:10:00",
    "waitingSeconds": 2700,
    "latenessSeconds": 0
  }]
}
```

IDs above are illustrative. Positions are one-based and follow persisted route order, not delivery-ID order. Each delivery retains its own scheduled-date window. Closing time is inclusive for service start; service may finish after closing. Late stops report positive lateness seconds. A missing saved numeric travel duration makes that stop and every subsequent stop `UNKNOWN`; arrival/service/departure/waiting/lateness are then null. Distance or descriptive travel text never substitute for numeric time.

All saved stops are assessed, including completed/cancelled deliveries. PLANNED, IN_PROGRESS and COMPLETED routes are accepted and always labelled hypothetical. This is neither an estimate of remaining work nor proof that the original route met its windows. Persisted estimates may be old, and no actual arrival/departure events are reconstructed. No personal names, addresses, contacts or instructions are returned.

## Read consistency and errors

Header and ordered stop projections are read inside one read-only repeatable-read transaction, without OSIV or collection hydration. A database-limited query reads at most 501 records to enforce the fixed 500-stop assessment limit before producing any response. There are no persistence or provider operations in the assessment service.

| Status | Meaning |
| --- | --- |
| 200 | Hypothetical assessment, including empty saved routes |
| 400 | Invalid route ID, JSON/assumptions or unsupported timeline arithmetic |
| 401 / 403 | Session, administrator role, password-setup or CSRF requirements not met |
| 404 | Saved route not found |
| 409 | Stored window, duration or stop-order data cannot be assessed safely |
| 413 | More than 500 saved stops; no partial assessment returned |
| 500 | Assessment could not complete; no generation/retry instruction is implied |

Success and controller error responses use `Cache-Control: no-store`. Saved windows must have whole-second precision and opening before closing; missing/invalid windows, negative travel seconds and non-contiguous saved order are rejected. Errors use fixed messages rather than echoing supplied fields.

## Local curl / Postman demo

The local backend is at `http://127.0.0.1:8081`. Obtain an administrator session through the existing authentication flow: GET `/api/auth/csrf`, POST `/api/auth/login` with form credentials plus that CSRF token, retain cookies, then fetch the rotated CSRF token again. In Postman, retain the cookie jar and use the returned `headerName` and `token` on the assessment POST. Never put real credentials or session tokens in committed examples.

For curl, use an already authenticated cookie jar and jq:

```sh
BASE=http://127.0.0.1:8081
COOKIE_JAR=/path/to/your/local-session.cookies
ROUTE_ID=123 # Replace with an existing ID from GET /api/admin/routes.
csrf_json=$(curl --fail-with-body --silent --show-error --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" "$BASE/api/auth/csrf")
csrf_header=$(printf '%s' "$csrf_json" | jq -r '.headerName')
csrf_token=$(printf '%s' "$csrf_json" | jq -r '.token')
curl --fail-with-body --silent --show-error --cookie "$COOKIE_JAR" \
  --header "$csrf_header: $csrf_token" --header 'Content-Type: application/json' \
  --data '{"departure":"2026-01-02T08:00:00","serviceDurationSeconds":600}' \
  "$BASE/api/admin/routes/$ROUTE_ID/assessment"
```

Choose dates relevant to the saved deliveries. Repeat with departure `2026-01-02T10:00:00`, keeping the same route and service duration, and compare per-stop lateness. GET the route before/after: assignments and statuses remain unchanged. Tests include this reassessment, unknown propagation and raw saved-table comparisons; no persistent development database is used for validation.

See [DELIVERY_WINDOW_ASSESSMENT.md](DELIVERY_WINDOW_ASSESSMENT.md) for the calculation's assumptions and limitations. This endpoint exposes that layer for a portfolio demo without changing generation policy.

## Final local validation

Final local CI passed with 583 backend tests, 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the complete application Chromium journey and all required cross-browser fixture suites. New coverage includes 22 assessment integration cases, a real-session HTTP check and a real-MySQL ordered-read regression. Reassessment leaves saved business data unchanged and makes no extra Maps calls. Dependency scanning found zero advisory matches across 392 package/version pairs; secret scanning found zero blocking findings and the two acknowledged historical findings. Hosted GitHub Actions were not verified.
