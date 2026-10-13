# Driver ownership and workflow

Drivers can now sign in at `/html/login.html` and use `/html/operators.html`. The page lists only their assigned routes, supports explicit selection when there are multiple routes, shows an empty state when there are none, and retrieves recipient details through an assigned delivery. Administrator management APIs remain administrator-only; administrators retain access to the route page through their existing APIs.

## Driver API

Every endpoint requires an enabled DRIVER session. The operator link is read from the current persisted account inside the ownership query; no caller-supplied account or operator ID controls access.

| Method and path | Result |
| --- | --- |
| GET `/api/driver/routes` | Own consistent routes, sorted by scheduled date and ID descending; `[]` if none |
| GET `/api/driver/routes/{routeId}` | One own consistent route |
| GET `/api/driver/deliveries/{deliveryId}` | Own delivery details |
| GET `/api/driver/deliveries/{deliveryId}/recipient` | Only first/last name, address and contact number needed for that delivery |
| PATCH `/api/driver/deliveries/{deliveryId}/status` | CSRF-protected terminal transition |

For reads and writes, the delivery must be assigned to the account's current operator and belong to exactly one route with that operator. Every member of the route must have matching ownership and single route membership. Contradictory routes are hidden in their entirety. Individual foreign, missing, unassigned or inconsistent objects all return 404. Anonymous requests return 401; administrators cannot use these driver-only endpoints. Drivers cannot access the general recipient directory, batch recipient lookup, other operators, warehouses or administrator mutations.

The PATCH body accepts only `deliveryStatus`, with the exact value `COMPLETED` or `CANCELLED`. Only an `IN_PROGRESS` delivery on an `IN_PROGRESS` route can transition; pending or already terminal deliveries return 409 for those target statuses. Unsupported statuses, missing or duplicate status fields and extra fields return 400. Missing/invalid CSRF returns 403. There is no driver operation for reassignment, editing scheduling/recipient fields, record deletion or reopening deliveries.

## Consistency and concurrency

Read mapping happens in a repeatable-read transaction so the authorization query and related route/delivery/recipient fields use one database snapshot. Status changes use one guarded database UPDATE under read committed: source status, persisted account, expected authentication revision, completed password setup and assignment checks are part of the write. The driver path never saves an old entity that could overwrite reassignment or resurrect deleted data. The integrity view is grouped/materialized to support MySQL updates that reference delivery integrity without MySQL's target-table subquery restriction.

Competing driver terminal updates have one winner; the loser receives 409. Disposable MySQL tests also hold administrator changes uncommitted and verify that a waiting driver cannot overwrite reassignment, route deletion or delivery deletion. Administrator and driver status updates now finalize routes and release operators through the shared lifecycle described in `DELIVERY_LIFECYCLE.md`. Active and historical route deletion is rejected; overlapping route generation locks and rechecks eligible operators and deliveries.

As with ordinary database snapshots, an already authorized read can finish while an assignment subsequently changes. New requests use the current persisted link and assignment. Previously displayed data cannot be retroactively removed from a browser; recipient details are not cached and each modal open is reauthorized. Account-management and password-reset APIs now use permanent session revocation as documented in `ACCOUNT_MANAGEMENT.md`.

## Setup and tests

No schema migration or new configuration is required. Drivers need an enabled account linked to an existing operator, provisioned through the existing internal account service. There is no public signup; administrators can use the Accounts interface in `ACCOUNTS_UI.md`, and the protected onboarding/reset API and required password setup are documented in `ACCOUNT_MANAGEMENT.md`. Route generation assigns deliveries and starts them as `IN_PROGRESS`, ready for the driver's terminal updates.

Use Java 25. `mvn -B verify` runs H2 ownership/filter tests, the full status-transition matrix, and existing regression checks. `./scripts/test-account-mysql.sh` additionally runs the disposable MySQL ownership/concurrency tests. Browser verification uses the opt-in Maven arguments documented in `SESSION_SETUP.md` and tests administrator and driver login, multiple route selection, recipient details, completing/cancelling deliveries, cross-driver access rejection, empty assignments and logout.

No live database or real accounts are modified by these tests. Production HTTPS, session-store/scaling and login-throttling requirements in `SESSION_SETUP.md` still apply.
