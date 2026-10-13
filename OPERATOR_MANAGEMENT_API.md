# Operator management API foundation

## Scope

This additive administrator API prepares the backend for React operator setup. It uses the existing Spring Boot application, operator/warehouse tables and session controls. No schema migration, routing algorithm change, frontend dependency or provider call is introduced.

Implementation: `OperatorManagementController`, `OperatorService`, `OperatorInput`, `OperatorState`, strict operator write DTOs and scalar `OperatorRepository` queries. Relevant safeguards are also applied to legacy operator writes. Both `AccountService.createDriver` and `AccountManagementService.createDriver` now lock the operator before creating its login account.

## Contract

| Method | Path | Behaviour |
|---|---|---|
| GET | `/api/admin/operators?page=0&size=20&search=` | Bounded, searchable scalar directory |
| GET | `/api/admin/operators/{id}` | Scalar detail with strong ETag |
| POST | `/api/admin/operators` | Create with generated ID; 201, Location and ETag |
| PUT | `/api/admin/operators/{id}` | Full replacement requiring If-Match |
| DELETE | `/api/admin/operators/{id}` | Conditional deletion of an unreferenced operator; 204 |

Responses contain `operatorId`, `name`, `phoneNumber`, `email`, `vehicleType`, `maxCapacity`, `warehouseId` and `availabilityStatus`. They never include login credentials, accounts or route history. The directory adds `operators`, `page`, `size`, `totalElements` and `totalPages`. Successful responses and controller errors use `Cache-Control: no-store`.

Page is 0–10,000, size is 1–50 (default 20), and search is at most 100 characters. Search matches name/email without case sensitivity, trims outer whitespace and treats `%`, `_` and `!` literally. Results sort by name then ID. A repeatable-read transaction keeps page/count queries consistent; scalar projection avoids loading warehouse, account or route collections. Deep offsets and substring searches still need production profiling. Availability is not a promise of routing eligibility: the planner separately excludes operators with unfinished routes.

Example create/replace body:

```json
{
  "name": "Delivery driver",
  "phoneNumber": "+447123456789",
  "email": "driver@example.test",
  "vehicleType": "Van",
  "maxCapacity": 25,
  "warehouseId": 7,
  "availabilityStatus": "AVAILABLE"
}
```

Required fields: name, phone number, email, capacity, warehouse ID and availability. Name/email limits are 100 characters, phone limit 20, vehicle limit 50. Names/contact text are trimmed; blank vehicle values become null. Phone uses the existing optional-plus/10–15 digit rule; email uses the existing Bean Validation email rule. Capacity must be a finite, nonnegative JSON number, warehouse ID a positive JSON integer and status a recognized enum string. Vehicle is optional/nullable. Duplicate keys, unknown fields, supplied operator IDs, warehouse names and credential fields are rejected. Email uniqueness remains enforced by the existing database constraint; conflicts return a safe 409. Submitted private fields/values and SQL errors are not returned.

Warehouse selection uses an existing warehouse ID and requires valid stored coordinates. It holds the warehouse lock during the write; a missing selection fails rather than creating a detached operator. Warehouse names are not made unique in this PR.

## Conditional writes and lifecycle guards

Read the operator detail and submit its quoted ETag in `If-Match`. Missing/blank headers return 428, malformed/weak/wildcard/list tags 400, changed content 412 and missing records 404 after precondition-format validation. The ETag is a content fingerprint of scalar operator fields, including availability and warehouse ID. It is not a monotonic version, an operation ID or a fingerprint of account/route membership. Changes that return to equivalent content can reuse a fingerprint. Membership is checked independently before editing/deleting.

Operator edits and deletion lock the operator and refresh OSIV-cached state before comparing its fingerprint. The lock is shared with route generation/terminal transitions and both account-creation paths. Operator edits then lock the selected warehouse, following the planner's operator-before-warehouse order.

- Active **PLANNED or IN_PROGRESS routes**, or assigned **PENDING or IN_PROGRESS deliveries** even without a route, block changes to availability, warehouse, vehicle or capacity.
- Contact/name/email edits are permitted when they preserve operational fields and pass validation.
- Administrators cannot create or manually switch an operator into `ON_DELIVERY`; assignment owns that transition.
- Administrators cannot manually clear `ON_DELIVERY`, even for inconsistent legacy records with no active route. Such records require controlled reconciliation through the lifecycle, not an unchecked availability override.
- After normal route finalization releases an operator, administrators can select AVAILABLE, UNAVAILABLE or OFF_DUTY.
- Deletion rejects any linked login account, route or assigned delivery, including disabled accounts and terminal history. Nothing is cascaded, detached, revoked or rewritten.

These checks run under the held operator lock. A setup edit during route calculation causes existing planner revalidation to reject the stale plan. If assignment has already committed, its changed fingerprint or membership guard prevents an availability override. Both account creation and deletion serialize on the operator; the winner either protects the new account or makes creation fail against the missing operator. Existing foreign keys and uniqueness constraints remain the database backstop. Deployment lock deadlines/constraint verification are separate operational work.

## Legacy compatibility

Legacy URLs and successful response shapes remain available. PUT/PATCH/DELETE share the lock, validation and lifecycle/history safeguards; legacy PUT/DELETE remain unconditional and do not supply the new API's client stale-edit guarantee. PATCH merges non-null supplied fields with stored values before validating; use full PUT to clear optional vehicle information. Invalid legacy data may require explicit remediation before a merged edit can validate.

Legacy creation rejects supplied operator IDs and now honors requested AVAILABLE/UNAVAILABLE/OFF_DUTY status; it rejects ON_DELIVERY. The mapper also no longer copies client-selected IDs. Legacy warehouse-name lookup is bounded to two matches and rejects ambiguity instead of choosing a depot silently. New consumers must use warehouse IDs. Invalid legacy writes that previously bypassed limits or assignment checks now fail deliberately.

No new account-creation or password-management UI is included. Operator setup remains distinct from login account creation and access management.

## Tests, recovery and next steps

Integration tests cover strict types/duplicate keys, field limits, search bounds, ETags, missing warehouses, duplicate email, authorization/session/password/CSRF controls, contact edits during active work, operational-change guards, orphan deliveries, preserved history and legacy compatibility. Real HTTP tests run with OSIV enabled. Disposable MySQL tests cover persisted fingerprints, concurrent edits, stale OSIV state, planner calculation/commit interactions, waiting edits after deletion and both account-creation paths in both race orders. Account race tests inspect the actual database lock queue before releasing the blocker.

Verified in this branch: **521 backend tests**, **272 frontend tests**, TypeScript/ESLint, Vite production build and the Spring/Chrome workflow passed. The 34 added backend cases comprise 23 API/integration cases, 10 disposable-MySQL cases and one real HTTP journey.

A pre-existing frontend focus test now waits for the focus effect before asserting; frontend runtime behaviour is unchanged. The full verification uses the Java 25 disposable-MySQL harness, existing TypeScript/ESLint/component checks, Vite production build and Spring/Chrome workflow.

Writes are not idempotent. An unknown acknowledgement requires reviewing current operators/accounts/routes before deciding on another request; a matching email/name alone does not identify the request. Local tests do not establish staging HTTPS, live constraints, operational timeouts, backups or production readiness.

After review and merge, build React warehouse/operator setup screens against these contracts. CI remains a separate near-term task. History/archive operations and repairing inconsistent legacy assignments need their own reviewed contracts.

## React consumer

The administrator screen is documented in [REACT_OPERATORS.md](REACT_OPERATORS.md), including warehouse ID selection, lifecycle-aware edits, account onboarding and explicit outcome review.
