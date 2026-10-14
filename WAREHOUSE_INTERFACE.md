# Warehouse interface modernisation

This focused change applies the shared design foundation to warehouse setup. Warehouse API contracts, ETags, coordinate precision and lifecycle guards remain.

## Directory and editor

The Warehouses page has one prominent Add warehouse action, search/result guidance, explicit search clearing and a distinction between an empty directory and an empty search. Static loading placeholders accompany the status message. Empty lists omit unusable pagination. Result summaries show the actual loaded search, including a recovery search for a potentially saved warehouse name.

One semantic table contains the records. At 1100 CSS pixels and below, its existing rows become cards with warehouse name/ID, address, coordinates and edit/delete actions. Explicit table/row/cell roles and visually hidden headers retain semantics under display changes. Long values wrap without requiring horizontal scrolling or duplicate mobile records.

The editor appears before the directory and groups Warehouse details and Coordinates. Inputs use 1rem text, required-field/coordinate errors use `aria-describedby` and `aria-invalid`, and invalid submission focuses the first relevant control. Raw coordinate precision is checked before numeric conversion can mask it; invalid values are never rounded. Zero and boundary coordinates remain valid. The address is not looked up automatically.

Opening an editor or delete dialog still reads the latest record/ETag. While an editor is open, Add and row actions are disabled to prevent replacing that draft. Explicit search/reload can refresh the directory without discarding the editor, as before. Cancel returns focus to its initiating action after the editor closes, with the directory heading as fallback.

## Deletion and recovery

The delete dialog shows the warehouse name, ID and address plus the existing assigned-operator restriction. Opening the native dialog explicitly focuses Keep warehouse. Confirmation still performs the conditional delete; the frontend cannot bypass the backend guard or detach operators.

Known validation rejection preserves the draft; permission failure blocks writes. Stale/unknown writes require explicit review. Updates compare fresh saved details against the preserved draft before adopting the latest version. An absent record after an uncertain deletion is a review result, not proof of request identity. Failed review cannot enable acknowledgement, and no automatic replay occurs.

## Verification and remaining work

Component coverage includes initial/filtered empty states, search clearing, required/range/precision validation, focus restoration, single-record semantics and failed recovery review. Existing tests retain ETag, conflict, timeout, duplicate-write, revoked-session and safe-text coverage. Chromium exercises real creation, stale updates, assigned-warehouse rejection and a committed deletion whose acknowledgement is lost, plus mobile cards, coordinate validation and dialog keyboard focus. Empty previews use API-boundary fixtures in disposable tests; persistent databases and live Maps are not used.

Full assistive-technology and cross-browser acceptance remain separate work. Operators and Accounts are the next focused interface changes. This does not complete production release gates.

Local verification on 2026-10-14 passed: 538 backend tests, 387 frontend tests, 10 disposable MySQL suites, 57 Python tooling tests, Chromium and packaged-application verification. Secret scanning passed with its reviewed historical acknowledgements; OSV reported zero advisory matches across 392 package/version pairs. Desktop editor/empty-state, mobile card and delete-dialog screenshots were inspected. Hosted GitHub CI was not independently verified.
