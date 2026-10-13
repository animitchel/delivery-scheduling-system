# Administrator Accounts interface

Open `/app/admin/accounts` from Accounts on the administrator dashboard or React workspace. The primary screen now uses React + TypeScript and the existing account-management API. `/html/accounts.html` remains the protected legacy rollback page. Roles, password verification, CSRF and revocation remain enforced by the backend. Spring protects the APIs and legacy page/assets. The public React shell contains no account data; only authenticated administrators mount the Accounts screen and can access its APIs.

## Workflow

- The account directory displays username, role, linked operator ID, access state and password-setup state. It loads 20 accounts per page, with Previous/Next and loading/empty/error states. Administrator rows have no driver-management controls.
- Driver creation asks for a username, an eligible operator and the administrator's current password. Operator names/IDs are displayed as text. Eligibility is queried on the server across all accounts, including disabled accounts, independently of account-directory pagination.
- Eligible operators are searchable by name and paginated 50 at a time through GET `/api/admin/accounts/eligible-operators`. The API allows page sizes 1–100 and search text up to 100 characters. It returns only IDs and names. Database uniqueness still decides concurrent creation conflicts; a picker result is not a reservation.
- Disable, enable and reset each open an action dialog explaining the effect and requiring the administrator's password again. Actual access changes revoke prior sessions. Reset leaves disabled accounts disabled.
- Creation/reset show the generated temporary password, username and local expiry time in a one-time dialog. Copy is an explicit user action; when clipboard access is unavailable the password can be selected manually. Done/Escape, navigation and session expiry clear the dialog. Closing does not retrieve or regenerate the password. Clipboard contents explicitly copied by the user are not automatically erased.
- Administrator password fields clear on every attempt. No credentials are placed in URLs, browser storage, row attributes or logs. Writes are disabled while pending and are never automatically retried. All failed writes block further changes until Reload accounts succeeds. Write requests have a 15-second abort budget and reads have a 30-second budget. After an uncertain network outcome, reload the directory; if an account was created without a received credential, use reset to issue a new one.
- Invalid credentials, duplicate usernames/operator links, stale targets and read failures show fixed text messages. Session expiry clears sensitive UI state and redirects to sign-in. Forced-password sessions route to password setup.

The React page renders API data as text, with runtime validation of account summaries, pagination and temporary credential acknowledgements. Request-generation guards prevent older list/search responses from replacing newer results. Native dialogs provide focus trapping and Escape support; form labels, table headings, live status regions and keyboard focus styles are included. The directory can scroll horizontally on small screens.

## Rollout

This PR needs no new migration. The prior `002-account-management.sql` migration remains a prerequisite for the account-management backend. Confirm it has been applied to the intended database before using the screen. Implementation tests validate disposable schemas, not your live schema; no live migration, account creation or reset is performed here.

See `REACT_ACCOUNTS.md` for migration-specific behavior and tests, and `ACCOUNT_MANAGEMENT.md` for the API, temporary-password policy, onboarding and deployment requirements. The migration adds `/app/admin/accounts` to explicit Spring deep links and updates administrator navigation. Existing login and scheduling destinations remain unchanged. Revert the artifact or restore the Accounts link to `/html/accounts.html` for rollback.

## Verification

With Java 25, run `mvn -B verify` for integration/regression checks. `./scripts/test-account-mysql.sh` additionally checks eligibility search/pagination and exclusion of linked disabled operators on real disposable MySQL. Browser arguments are documented in `SESSION_SETUP.md`.

The browser checks cover dashboard navigation, account pagination, safely rendered operator names, incorrect passwords, duplicate submissions/usernames, explicit clipboard copy and immediate credential clearing, creation/onboarding, disable/re-enable with stale sessions rejected, reset/password setup, read-error recovery, driver denial and expired-administrator-session redirects. Desktop/mobile screenshots contain only synthetic account-directory fixtures and no credentials. Existing administrator and driver journeys also remain covered.
