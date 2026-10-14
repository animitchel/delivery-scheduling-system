# Recipient interface modernisation

This focused change builds on the shared frontend design foundation. It keeps the recipient API, conditional writes and existing authentication/CSRF integration.

## Directory and mobile records

The page has a concise Recipients heading, a single Add recipient action and a search/reload toolbar. Applied search text appears beside the result count, with an explicit Clear search action that reloads the unfiltered first page. A loading placeholder reserves space without animation.

An empty unfiltered directory explains how to add the first recipient and prepare deliveries. An empty search explains how to broaden or clear the query. Neither invents operational data. Empty results omit unusable pagination; nonempty results retain the existing bounded pagination and its labels.

One semantic table contains every visible record. At 1100 CSS pixels and below, the same rows become cards with visible address, contact and priority labels; there are no duplicate desktop/mobile records or actions. Explicit table/row/cell roles and visually hidden column headers retain accessibility semantics when display changes. Priority always has a text label alongside colour. Long names, addresses and contact values wrap inside the available width.

## Forms and recovery

The editor appears before the directory and groups Contact details and Delivery details. Required labels, optional-contact guidance and address guidance clarify data entry. Client validation rejects missing/whitespace-only required fields, associates messages through `aria-describedby`, marks invalid inputs and focuses the first missing field. Existing server-side validation remains authoritative.

Opening the editor still fetches the latest recipient and ETag. Existing address-change warnings, duplicate-submit locks, timeouts, known rejection recovery, stale-version blocking and uncertain-save review remain. While an editor is open, the page-level Add action and row edit actions are disabled to avoid replacing that draft with another record. Search/reload remain explicit ways to abandon the editor, as before.

An uncertain or stale save shows a Reload and review recipients action. Cancelling the blocked editor does not erase its warning or enable blind retries. An explicit reload clears the blocked state only after the directory is successfully loaded; failed review requests keep creation/editing blocked. No API contract or database migration changes were made.

## Verification

Component coverage includes empty-directory guidance, filtered empty-state recovery, accessible field errors/focus, single-record table semantics and cancelling an uncertain save. Existing recipient regressions continue to cover versioned updates, provider rejections, stale reads, timeouts, duplicate writes and private-error suppression.

Chromium exercises real conditional updates and conflicts, no-results clearing, required-field focus, responsive table/card semantics, viewport fit and screenshots. The empty-directory screenshot uses an API-boundary fixture; tests use a disposable database and do not clear local persistent data or call live Maps.

Full assistive-technology and cross-browser acceptance remain separate checks. This does not complete the other management-page redesigns or production release gates.

Local verification on 2026-10-14 passed: 538 backend tests, 375 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, Chromium and packaged-application verification. Secret scanning passed with its reviewed historical acknowledgements; OSV reported zero advisory matches across 392 package/version pairs. Directory, empty-state and editor screenshots were inspected on desktop/mobile. Hosted GitHub CI was not independently verified.
