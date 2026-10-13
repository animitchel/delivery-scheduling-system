# Administrator account-management API

The account-management backend supports controlled driver onboarding, revocation and password changes. Administrators can now use the Accounts link on the dashboard; see `ACCOUNTS_UI.md`. The password-setup page lets users complete onboarding without developer assistance.

## API contract

All management endpoints require an enabled ADMIN session. Mutations also require CSRF and the administrator's current password, verified again inside the locked transaction. Account usernames, roles and operator links are immutable here. There is no delete endpoint and no operation that disables or resets an administrator.

| Method/path | JSON body or result |
| --- | --- |
| GET `/api/admin/accounts?page=0&size=20` | Safe `accounts` summaries plus `page`, `size`, `totalElements`, `totalPages`; size 1–100 |
| POST `/api/admin/accounts/drivers` | `username`, `operatorId`, `currentPassword`; 201 with account summary, generated `temporaryPassword`, `expiresAt` |
| PATCH `/api/admin/accounts/{id}/enabled` | `enabled`, `currentPassword`; updated safe summary |
| POST `/api/admin/accounts/{id}/reset-password` | `currentPassword`; newly generated temporary credential response |
| POST `/api/auth/password` | Signed-in user's `currentPassword`, `newPassword`; 204, initiating session and cookie cleared |

Extra and duplicate request fields are rejected. Creation always makes an enabled DRIVER linked to one existing operator. Existing username/operator uniqueness and foreign-key constraints remain authoritative under concurrent requests; conflicts return sanitized 409 responses. Administrator or nonexistent targets return 404 for driver-management mutations. Missing/incorrect reauthentication is rejected; no supplied password is returned in errors.

Creation and reset generate a random 32-character password from 24 cryptographically random bytes, valid for 24 hours. The response has `Cache-Control: no-store`. Deliver this value to the intended user through a secure channel; it is not retrievable later and is never stored as plaintext. Do not place credentials in shell history, URLs, tracked files or client local storage. The database stores only bcrypt hashes.

Reset does not enable a disabled account. Re-enabling preserves the existing credential state; if its temporary password has expired, issue a new reset. Repeated enable/disable requests with the same current value are idempotent and create no new revision/audit event.

## Password setup and permanent revocation

New/reset driver accounts have `passwordChangeRequired=true`. Before expiry they can sign in, but the backend permits only session identity, CSRF, password setup and logout; scheduling APIs and ordinary application pages remain unavailable. The login page routes them to `/html/change-password.html`. They verify the temporary password, choose a different valid password, then sign in again with the permanent password. Users with normal credentials can also use this page to change their own password.

Passwords retain the existing 15-character minimum, valid Unicode requirement and 72 UTF-8-byte bcrypt limit. Current-password verification is required for own changes; reusing the current password is rejected. Expired temporary credentials fail login and invalidate existing restricted sessions.

Every actual enable/disable change, reset and own password change increments a nonnegative, never-reused authentication revision. The principal snapshots that revision, and each authenticated request checks it. Disabling then re-enabling cannot restore old sessions, even if those sessions made no requests while disabled. Password reset/change revokes all previous sessions. Driver ownership queries also bind the expected revision and require normal credential state, independently of filter validation.

Mutations lock and refresh account rows, recheck authorization/credentials after locking, and persist state plus audit together. Successful audit entries contain only actor ID, target ID, action and UTC timestamp. Failure of an audit write rolls back the credential/state/revision change. There is no public audit endpoint yet. Role restrictions protect administrator accounts without a fragile last-admin count check.

## Migration and deployment

Review and apply `db/changes/002-account-management.sql` after `001-create-user-accounts.sql`, through the controlled database change process. Back up first and stop the old executable before rollout. Deploy the new build with schema validation after applying the additive change; do not also run the script against a development schema already updated by Hibernate.

The migration adds revision, forced-change and temporary-expiry columns plus the audit table. Existing accounts backfill to revision 0, no forced change and no expiry, preserving their access and password hashes. Expiry/audit timestamps use UTC `Instant` mappings with microsecond database columns; MySQL tests verify round trips. Existing account uniqueness, bootstrap, role/link and operator deletion constraints remain in place.

Restart invalidates existing in-memory sessions. After management has been used, do not roll back to an older executable that ignores revisions, forced setup or expiry; preserve the new credential state and use a forward fix or a reviewed compatible rollback. No live migration or real account change is performed by the implementation tests.

Production HTTPS, trusted-ingress login throttling and session-store/scaling requirements from `SESSION_SETUP.md` still apply. Sensitive management and password-change endpoints also need ingress rate limits before public exposure. Public signup, email recovery, role changes and operator relinking remain separate work. The administrator interface is documented in `ACCOUNTS_UI.md`.

## Verification

Use Java 25. `mvn -B verify` covers real-account/filter tests with H2, account-management authorization, temporary expiry, multiple-session revocation, password validation, mass assignment and audit rollback. The MySQL/browser tests are opt-in.

`./scripts/test-account-mysql.sh` additionally covers populated-schema migration/backfill, expiry/audit UTC round trips, concurrent reset/reset, reset/password-change and duplicate creation by separate administrators. Pass the browser arguments from `SESSION_SETUP.md` to include the existing administrator/driver journeys plus API creation of a temporary driver, forced password setup, sign-out and normal login with the permanent password. All databases and credentials used are disposable test fixtures.
