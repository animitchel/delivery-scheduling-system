# Frontend cross-browser acceptance

## Scope and environment

The original acceptance baseline was recorded on 16 October 2026; recipient-picker coverage was added on 19 October 2026. Against the production Vite build with locked Playwright 1.64.0 engines, five disposable HTTP fixture suites run in Chromium, Firefox and WebKit. The official matching Linux Playwright container is used for the complete local matrix; GitHub Ubuntu CI installs all three engines directly. Reports record runtime versions and engine-specific evidence. No production or persistent development data is used.

## Required checks

| Suite | Per engine | Coverage |
| --- | ---: | --- |
| Workspace reflow | 72 layout cases | Administrator/driver roles, long identities, three font settings, 320–1440px widths, 100%/200% CSS text sizing |
| Render recovery | 24 layout cases | Focused private fallback, meaningful title, no private exception disclosure, explicit keyboard reload without writes |
| Administrator route directory | 30 layout cases | Semantic records, all statuses, long inert names, keyboard inspection, paging focus, deep links, empty/read-error recovery; desktop/mobile menu, Escape, navigation and Back/Forward focus |
| Generation review | 30 layout cases + four keyboard cases | Route fields/actions, date filtering, inspection, paging, confirmation focus, Enter/Escape/Tab, cancelled requests, deliberate assignment acknowledgement and exact-date/all-status uncertain-response review |
| Delivery recipient picker | 30 layout cases + four keyboard cases | Compact desktop/mobile rows, long inert names/addresses, 44px Select actions, verified selection/collapse, focus, preserved drafts/search/page and read-error recovery with zero writes |

The matrix requires 15 passing suites, 558 layout cases, 12 confirmation-keyboard cases and 12 recipient-picker keyboard cases. Screenshots and case measurements live under `.ci-artifacts/browser/<engine>/`; the aggregate `cross-browser-results.json` is incomplete until every required suite passes. Missing engines and incomplete/failed reports fail acceptance; there is no fallback to Chromium.

## Observations

The initial complete Linux matrix passed all three engines without an application compatibility fix. That original change extended acceptance tooling and CI without changing application behaviour; the current recipient-picker follow-up changes the selection UI as documented in COMPACT_RECIPIENT_PICKER.md. The real-backend Chromium journey remains a separate required check; Firefox/WebKit fixture results do not establish backend end-to-end coverage for those engines. Date-control coverage uses ISO values through the browser input interface; operating-system date-picker presentation is not certified.

Local macOS 27.0.1 Chromium passed all four suites and WebKit passed workspace reflow. Bundled Firefox exited before page creation with `Could not find profile folder`, including when using a fresh canonical temporary directory. This resembles the upstream macOS startup issue described in [Playwright issue 42768](https://github.com/microsoft/playwright/issues/42768). The observed failure is a runtime limitation; its precise OS permission cause was not independently established. The required matrix therefore runs in Linux without changing browser app branding, macOS permissions or personal browser profiles.

## Remaining acceptance

- Actual Safari/macOS and Safari/iPhone checks; Playwright WebKit is engine coverage, not device certification.
- Manual screen-reader checks of navigation, forms, tables/cards, errors and modal announcements.
- Native browser zoom, including 200%/400%; CSS text enlargement is a different test.
- Wider management-form, driver mutation and real-backend Firefox/WebKit journeys; this matrix covers the named fixture scenarios.
- Staging HTTPS/session/security, operational monitoring, backup restore and rollback gates.
- GitHub-hosted execution and required-check enforcement; local success does not verify hosted CI or remove the existing private-plan restriction.

## Running

See [CI.md](CI.md) for the full pipeline and the explicit `ACCEPTANCE_USE_CONTAINER=1` option. To run only the matrix against an already built frontend:

```sh
node tests/browser/run-acceptance.cjs
# Or the identical suites in the matching Linux runtime:
ACCEPTANCE_USE_CONTAINER=1 node tests/browser/run-acceptance.cjs
```

## Original local validation (16 October 2026)

The complete pipeline passed with `ACCEPTANCE_USE_CONTAINER=1`: 538 backend tests, 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the full real-backend Chromium journey, all 12 cross-engine suites/468 layout cases and 12 confirmation-keyboard cases. The aggregate report is complete. Dependency scanning checked 392 package/version pairs with zero advisory matches. Secret scanning found zero blocking findings and the two previously acknowledged historical findings. Hosted GitHub Actions were not verified.

Observed engine versions from the final reports:

| Engine | Version |
| --- | --- |
| chromium | 156.0.8078.4 |
| firefox | 157.0 |
| webkit | 27.2 |

Matrix runtime: linux, Node v24.21.0, Playwright 1.64.0. The host application pipeline used its pinned Node 24.20.0 and Java 25.

## Recipient-picker follow-up validation (19 October 2026)

Compact-picker validation (19 October 2026): Final full local CI passed with 618 backend tests, 437 frontend tests, twelve disposable MySQL suites, 57 tooling tests, the real-backend Chromium journey and all fifteen required cross-browser fixture suites (558 layout cases, twelve confirmation-keyboard cases and twelve picker keyboard cases). The three picker engines each reported 30 layout/four keyboard cases and zero writes. Desktop/mobile screenshots were visually inspected. Dependency scanning reported zero advisory matches across 392 package/version pairs; secret scanning reported zero blocking findings and two acknowledged historical findings. An intermediate run found the real-backend browser test's obsolete card CSS selector; it was replaced with the semantic table and expanded to verify change/draft/reselection before saving. The final fresh pipeline passed. Hosted CI, manual screen-reader, native zoom and actual-device acceptance were not verified. Development services and data were untouched.
