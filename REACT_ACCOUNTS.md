# React administrator accounts

Accounts opens at `/app/admin/accounts` from the React workspace and existing administrator dashboard. The screen uses the existing `/api/admin/accounts` endpoints. Spring remains authoritative for role checks, password verification, operator eligibility, account uniqueness, temporary password expiry and permanent session revocation.

## Behavior

The account directory is paginated at 20 accounts. Eligible operators are searched explicitly and paginated at 50 operators, independently of the directory. Linked operators, including those linked to disabled accounts, remain excluded by the server. Stale search/list responses are cancelled and cannot replace newer results. Creation selects the final account page after successful reload so the new driver is visible.

Driver creation requires a username, eligible operator and current administrator password. Enable, disable and reset use a confirmation dialog requiring that password again. Administrator rows have no driver-management buttons. Password fields clear on every submission. Pending writes disable account controls, shell navigation, session refresh and sign-out. Reads have a 30-second budget and writes a 15-second abort budget, including CSRF acquisition.

Only the create/reset response supplies a temporary password. The typed client validates its structure and expected target. The one-time dialog supports explicit copying and manual selection when clipboard access fails; Done/Escape, page hide, navigation/unmount and session expiry remove the sensitive display. Late clipboard callbacks cannot restore a closed dialog. No password is persisted in browser storage, URLs, row attributes or logs. Clipboard contents explicitly copied by the administrator remain under their control.

Writes never retry automatically. A failed/uncertain write clears the old directory and operator selection and blocks another action until Reload accounts succeeds. If creation committed without a received password, locate the account and reset its password rather than repeating creation blindly. A successful reset does not enable a disabled driver.

The generic React shell is public and contains no identity or account directory. Both the client role guard and Spring API permissions are checked in tests. Session expiry removes the workspace; mandatory password setup uses the existing protected workflow. Credential responses are not captured in screenshots; browser fixtures use disposable accounts and test-only passwords.

## Validation and rollback

Frontend tests cover validated API payloads/acknowledgements, malformed data, disabled summaries, duplicate submissions, password clearing, one-time credentials, clipboard failures/late replies, confirmation, stale searches, timeout/error recovery and role/session handling. Browser checks execute creation → password setup → disable → re-enable with old sessions rejected → reset → password setup against the running Spring application, alongside existing driver and administrator journeys.

Run `npm --prefix frontend run check` and the established Java 25 Maven/disposable MySQL suite. Optional `session.browser.*` arguments enable real Chrome verification. The Spring build packages the React assets; Node is not a production frontend server.

No new migration is introduced. The existing account-management migration remains a prerequisite for any intended live database. The protected legacy `/html/accounts.html` screen remains available for rollback; both screens use the same persisted account state and backend security rules. This branch does not deploy or modify live accounts.
