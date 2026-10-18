# Legacy recipient deletion conflicts

## Scope and reproduction

The existing administrator-only endpoint is `DELETE /api/v1/scheduling-app/recipients/{id}`. The modern recipient-management API still has no delete operation. No frontend, schema or assignment behaviour is added.

The backend review's R1 candidate is now reproduced: a real authenticated HTTP request with CSRF against disposable MySQL returned HTTP 500 when deliveries referenced the recipient. Five regression cases failed before the fix: four delivery states (PENDING, IN_PROGRESS, COMPLETED, CANCELLED), plus a new reference committed while deletion was waiting. The FK already prevented deletion; this was an error-contract bug, not demonstrated data loss.

## Behaviour

[RecipientService.deleteRecipient](src/main/java/com/mmu/schedulingapp/service/RecipientService.java) now flushes deletion inside its existing transaction. A `DataIntegrityViolationException` from delete/flush becomes a fixed `ResponseStatusException` with HTTP 409. Existing [RecipientManagementErrors](src/main/java/com/mmu/schedulingapp/restfulcontroller/RecipientManagementErrors.java) renders a no-store JSON response:

```json
{"error":"Recipient is still referenced and cannot be deleted."}
```

The transaction rolls back. Provider messages, SQL, constraint names and the original exception cause are not included in the HTTP response. This does not add a general database-error translator or change other write operations. Database diagnostic logs retain their existing behaviour; the HTTP contract does not imply SQL logging redaction.

The foreign key is the authoritative reference check, avoiding a check-then-delete race. If an uncommitted delivery insert holds the parent reference, deletion waits for it. A committed insert leads to 409; a rolled-back insert allows deletion to succeed. No application-level reference-count precheck is relied on, and no FK or cascade rules change.

| Situation | Response | Data result |
| --- | --- | --- |
| Existing unreferenced recipient | 200 with existing recipient DTO | Recipient removed |
| Missing recipient | 404 | No deletion |
| Recipient referenced by any delivery state | 409, fixed JSON, no-store | Recipient and linked data retained |
| Concurrent reference commits | 409 | New reference and recipient retained |
| Concurrent reference rolls back | 200 | Unreferenced recipient removed |
| Missing session/CSRF or driver identity | Existing security rejection | Recipient retained |

## Verification

[RecipientDeletionMySqlTest](src/test/java/com/mmu/schedulingapp/account/RecipientDeletionMySqlTest.java) runs a loopback HTTP server with real administrator/driver sessions and CSRF, and uses only a new schema in the disposable MySQL harness. No Maps requests or development database access are needed. Snapshot assertions compare recipient/delivery/route/membership/operator data after rejected deletion, including terminal route history. Driver, anonymous and missing-CSRF cases verify access checks. The race test observes MySQL's actual lock-wait queue before resolving the competing insert; it covers both commit and rollback.

Run the focused suite with Java 25 and Docker available:

```sh
./scripts/test-account-mysql.sh -Dtest=RecipientDeletionMySqlTest
```

All nine focused cases passed after the fix. Whole-pipeline results are recorded in the roadmap after verification. `DeliveryPreparationMySqlTest.waitingCreationRejectsRecipientDeletedBeforeItObtainsTheLock` separately covers the inverse race: deletion wins before modern delivery creation obtains its recipient lock. Neither test suite establishes arbitrary direct-SQL business-rule safety beyond the tested FK/locking behaviour.

Recipient-deletion validation (18 October 2026): Five real HTTP/MySQL cases failed with 500 before the fix; all nine focused cases now pass. Full local CI passed with 618 backend tests, 434 frontend tests, twelve disposable MySQL suites, 57 tooling tests, the real-backend Chromium journey and all twelve cross-browser fixture suites (468 layout and twelve confirmation-keyboard cases). Dependency scanning found zero advisory matches across 392 package/version pairs; secret scanning found zero blocking findings and two previously acknowledged historical findings. Existing development services/database were not used. Hosted GitHub Actions were not verified.
