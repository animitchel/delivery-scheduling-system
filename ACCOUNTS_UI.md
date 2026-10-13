# Administrator Accounts interface

Open `/html/accounts.html` from the Accounts link on the administrator dashboard. The page uses the existing Bootstrap frontend and the account-management API. Roles, password verification, CSRF and revocation remain enforced by the backend. The page, JavaScript and stylesheet are administrator-only.

## Workflow

- The account directory displays username, role, linked operator ID, access state and password-setup state. It loads 20 accounts per page, with Previous/Next and loading/empty/error states. Administrator rows have no driver-management controls.
- Driver creation asks for a username, an eligible operator and the administrator's current password. Operator names/IDs are displayed as text. Eligibility is queried on the server across all accounts, including disabled accounts, independently of account-directory pagination.
- Eligible operators are searchable by name and paginated 50 at a time through GET `/api/admin/accounts/eligible-operators`. The API allows page sizes 1–100 and search text up to 100 characters. It returns only IDs and names. Database uniqueness still decides concurrent creation conflicts; a picker result is not a reservation.
- Disable, enable and reset each open an action dialog explaining the effect and requiring the administrator's password again. Actual access changes revoke prior sessions. Reset leaves disabled accounts disabled.
- Creation/reset show the generated temporary password, username and local expiry time in a one-time dialog. Copy is an explicit user action; when clipboard access is unavailable the password can be selected manually. Done/Escape, navigation and session expiry clear the dialog. Closing does not retrieve or regenerate the password. Clipboard contents explicitly copied by the user are not automatically erased.
- Administrator password fields clear on every attempt. No credentials are placed in URLs, browser storage, row attributes or logs. Writes are disabled while pending and are never automatically retried. After an uncertain network outcome, refresh the directory; if an account was created without a received credential, use reset to issue a new one.
- Invalid credentials, duplicate usernames/operator links, stale targets and read failures show fixed text messages. Session expiry clears sensitive UI state and redirects to sign-in. Forced-password sessions route to password setup.

The page renders API data through text nodes/DOM properties, not dynamic HTML. Request-generation guards prevent older list/search responses from replacing newer results. Native dialogs provide focus trapping and Escape support; form labels, table headings, live status regions and keyboard focus styles are included. The directory can scroll horizontally on small screens.

## Rollout

This PR needs no new migration. The prior `002-account-management.sql` migration remains a prerequisite for the account-management backend. Confirm it has been applied to the intended database before using the screen. Implementation tests validate disposable schemas, not your live schema; no live migration, account creation or reset is performed here.

See `ACCOUNT_MANAGEMENT.md` for the API, temporary-password policy, onboarding and deployment requirements. The React + TypeScript + Vite target remains on the roadmap; this focused screen completes the existing operational workflow.

## Verification

With Java 25, run `mvn -B verify` for integration/regression checks. `./scripts/test-account-mysql.sh` additionally checks eligibility search/pagination and exclusion of linked disabled operators on real disposable MySQL. Browser arguments are documented in `SESSION_SETUP.md`.

The browser checks cover dashboard navigation, account pagination, safely rendered operator names, incorrect passwords, duplicate submissions/usernames, explicit clipboard copy and immediate credential clearing, creation/onboarding, disable/re-enable with stale sessions rejected, reset/password setup, read-error recovery, driver denial and expired-administrator-session redirects. Desktop/mobile screenshots contain only synthetic account-directory fixtures and no credentials. Existing administrator and driver journeys also remain covered.
