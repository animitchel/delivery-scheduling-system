# Account interface modernisation

This focused change applies the shared design foundation to driver access management. Authentication/API contracts, administrator reauthentication and one-time credential handling remain.

## Creation and directory

The Accounts header provides Add driver account, which focuses setup without submitting, and a globally reachable Reload accounts action. The setup section groups Driver identity and Administrator confirmation, explains username rules and distinguishes an operator record from its login account. Eligible-operator search supports explicit clearing from page zero and distinct initial/filtered empty guidance. Linked operators remain excluded even when their account is disabled. No unsupported account-directory search was added.

The directory shows total accounts and text access/password-setup badges. One semantic table becomes cards at 1100 CSS pixels and below, retaining explicit roles and visually hidden headers. There are no duplicate records or actions. Administrator rows remain read-only; driver controls retain their original accessible names with username descriptions. Long values wrap without horizontal scrolling.

Read/write failures retain the conservative blocked state. A successful eligible-operator search does not silently unlock writes after an earlier read failure; the interface explains when a full reload is still required. Reload must successfully refresh both account and eligible-operator data before unlocking actions.

## Sensitive actions

Creation and access/reset actions still require the administrator's password. Administrator password fields remain uncontrolled DOM inputs, are cleared immediately when submitting and on cancellation/page hide, and are never placed in React state or persistent browser storage. Action dialogs identify the driver/operator and retain their existing session-revocation and disabled-account explanations. Cancelling restores initiating-action focus.

The credential dialog still displays the returned temporary password only for the one-time handoff. It explicitly focuses Done when opened. Done, Escape/page hide and close clear the field/state; late clipboard results remain guarded. Closing returns focus to the nonsensitive setup heading. Credentials are not fetched again or retained in local/session storage. Clipboard copying remains an explicit user action with manual-copy fallback.

## Verification and remaining work

Component tests cover grouped setup/focus, username guidance, search clearing, status semantics, credential focus/clearing, action cancellation and conservative reload recovery. Existing tests retain duplicate-write, reauthentication, private-error suppression, timeout, clipboard, revoked-session and one-time credential behavior.

Chromium verifies creation, access changes, password reset and required-password setup alongside responsive cards and keyboard focus. Normal previews are captured before entering passwords and do not include temporary credentials. Failure screenshots mask password inputs and the temporary-password field. Tests use disposable accounts/databases, not local persistent application data.

Full assistive-technology and cross-browser acceptance remain separate checks. Next review the full frontend for visual consistency, keyboard/accessibility behavior, loading/error handling and production acceptance. This does not complete production release gates.

Local verification on 2026-10-14 passed: 538 backend tests, 399 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, Chromium and packaged-application verification. Secret scanning passed with its reviewed historical acknowledgements; OSV reported zero advisory matches across 392 package/version pairs. Desktop setup and mobile account/form previews were inspected without displaying temporary credentials. Hosted GitHub CI was not independently verified.
