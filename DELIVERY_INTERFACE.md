# Delivery interface modernisation

This focused change applies the shared frontend design foundation to delivery preparation and review. Existing API contracts, pending/unassigned creation semantics and uncertain-save safeguards remain.

## Preparation flow

The page has a concise Deliveries heading and a Prepare delivery action that moves keyboard focus to recipient selection without creating work. Numbered sections distinguish choosing a recipient from preparing the delivery. Recipient search shows applied search text/result counts and supports explicit clearing from page zero. Recipient cards show names, addresses, IDs and the selected state. Choosing still fetches current recipient details; directory summaries are not used as fresh selection data.

An empty recipient list explains how to add a recipient and reload. Empty searches explain how to broaden or clear the query. The create form separates Schedule from Priority and instructions, includes date/time guidance, an instruction counter and a clearly identified selected recipient. Inputs use readable 1rem text.

Client validation still uses the existing input contract, including past dates, same-day windows and whole seconds. Invalid dates/windows have associated descriptions and invalid-state styling, and the first relevant control receives focus. Server validation remains authoritative. Pending writes disable preparation/search/filter controls and retain the workspace mutation lock.

## Saved work

The directory shows applied date/status/recipient criteria separately from editable filter inputs. Result counts, explicit clearing and bounded pagination remain. An unfiltered empty directory explains how to prepare the first delivery; filtered empty results explain how to broaden criteria. Empty lists omit unusable pagination. Static placeholders accompany loading messages without motion.

One semantic table contains the delivery records. At 1100 CSS pixels and below, its existing rows become cards with delivery ID, recipient, date/window, priority, status and a details action. Explicit table/row/cell roles and visually hidden headers preserve semantics under display changes. Status always includes text; there are no duplicate mobile records or actions. Long values wrap. Selected details still load separately and retain explicit refresh/close controls.

## Save recovery

Successful creation displays and focuses the saved delivery details and filters review to the saved recipient/date. Draft data remains after known validation rejection. Reference rejection requires a current recipient; permission rejection blocks saving until session refresh. Unknown results freeze creation and require review across all statuses for the same recipient/date. A failed review cannot enable the acknowledgement action. Empty or matching results still do not prove which request saved a delivery; another submission remains an explicit user decision, never an automatic retry.

## Verification and remaining work

Component tests cover focus, grouped fields, empty setup guidance, recipient search clearing, accessible validation, applied-filter display and single-record semantics, alongside the existing save/timeout/race/privacy tests. Chromium checks real creation, conditional recipient selection, missing-date focus, no-result clearing, directory/card viewport fit and a committed creation whose acknowledgement is lost. Empty-state screenshots use API-boundary fixtures in disposable tests, not persistent database changes. Tests do not call live Maps.

Full assistive-technology and cross-browser acceptance remain separate work. Next modernise Warehouses, Operators and Accounts independently while retaining their lifecycle/concurrency safeguards. This change does not complete production release gates.

Local verification on 2026-10-14 passed: 538 backend tests, 381 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, Chromium and packaged-application verification. Secret scanning passed with its reviewed historical acknowledgements; OSV reported zero advisory matches across 392 package/version pairs. Desktop/mobile preparation, directory/card and empty-state screenshots were inspected. Hosted GitHub CI was not independently verified.
