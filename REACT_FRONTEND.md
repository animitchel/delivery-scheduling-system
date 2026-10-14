# React frontend foundation

The React + TypeScript + Vite workspace is in `frontend/`. Spring remains responsible for authentication, authorization, business rules and persistence. Production needs one Spring application; Node is a build/development tool, not a production frontend server.

## First migration slice

| Screen | Current entry point | Behavior |
| --- | --- | --- |
| React entry | `/app/` | Loads the session and selects the administrator or driver overview |
| Administrator overview | `/app/admin` | Responsive shell with links to scheduling, accounts and routes |
| Driver overview | `/app/driver` | Assigned-routes entry point; administrator navigation is absent |
| Sign-in | `/app/login` | React sign-in with session CSRF, role destinations and password setup |
| Password setup | `/html/change-password.html` | Existing required password-change workflow |
| Legacy scheduling | `/html/admin.html` | Direct-access compatibility page; absent from React navigation |
| Accounts | `/app/admin/accounts` | React paginated account directory, eligible-operator search and protected driver actions |
| Driver route execution | `/app/driver/routes` | React assigned routes, on-demand stop details and terminal delivery actions |
| Administrator route review | `/app/admin/routes` | Bounded route directory, selected stops and terminal delivery actions |
| Legacy route rollback | `/html/operators.html` | Existing role-authorized route page |

Driver sign-in now opens `/app/driver/routes`. Administrator sign-in now opens `/app/admin` directly. React navigation uses migrated screens; the legacy scheduling page remains available at `/html/admin.html` for compatibility. The driver journey is documented in `REACT_DRIVER_ROUTES.md`; the legacy route page remains available for rollback.

## Session and API contracts

`GET /api/auth/me` supplies `{id, username, role, operatorId, enabled, passwordChangeRequired}`. The client validates the response at runtime, including role and operator linkage. Unknown/malformed identities cannot mount authenticated tools.

`GET /api/auth/csrf` supplies the X-CSRF-TOKEN header name/token. Unsafe requests fetch/cache the token only in memory, then send it with same-origin credentials. `POST /api/auth/logout` uses that token; success clears the displayed identity and navigates to existing sign-in. The shell prevents duplicate submissions. There is no token, identity or password storage in localStorage/sessionStorage, and no automatic retry of mutations.

The typed API boundary accepts only the current origin's `/api/` paths, forces same-origin credentials/no-store, and rejects fetch redirects. Fixed client messages replace raw server error bodies. A 401 removes authenticated content and offers sign-in. Required-password-change responses use the existing protected setup page; ordinary forbidden responses have a separate classification. CSRF failures clear the in-memory token for the next explicit request.

Session loading is cancellable and ignores superseded responses. Focus, visibility restoration and an explicit Refresh session action recheck persisted identity. These refreshes, shell navigation and sign-out are paused while a route or account update is pending so the active operation is not discarded by the shell. Expiry, role guards and malformed responses are covered by component tests. Client role guards determine navigation only: Spring continues enforcing every API permission. There is no customer or account-directory data embedded in public shell HTML/assets.

## Development

Use Node 24.20.0 and npm 11.19.0 (`frontend/.nvmrc`, package engines). From `frontend/`:

```sh
npm ci
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
npm run typecheck
npm run lint
npm test
npm run build
```

Open `http://127.0.0.1:5174/app/`. Start Spring separately on port 8081 using your existing local configuration (or the `--server.port=8081` argument). This frontend setting does not change Spring’s production listener or use your local database during tests. Vite proxies `/api`, `/html`, `/js` and `/css` to `http://127.0.0.1:8081` by default, so session cookies and legacy links remain on the browser's current origin. For a different local port set `SCHEDULING_DEV_BACKEND=http://127.0.0.1:PORT`. Only loopback HTTP origins are accepted; remote targets, credentials and non-origin paths are rejected. This variable is development configuration and is not included in the production bundle. `npm run preview` previews static output; it is not a replacement for the authenticated backend/proxy workflow.

Dependencies and transitive resolutions are locked in package-lock.json. npm lifecycle install scripts are disabled; the verified native build packages work without them. React/Vite are pinned, and TypeScript 6.0.3 is selected within the installed lint tooling's supported range. Type checking runs independently: a successful Vite build is not proof of TypeScript correctness.

## Build and Spring serving

`mvn -B clean verify` installs pinned Node/npm under ignored `target/frontend-runtime`, runs npm ci, type checking/lint/component tests, builds Vite assets, and packages them at `BOOT-INF/classes/static/app/` in the Spring jar. A globally installed Node is not required for Maven builds. `-DskipTests` explicitly skips Java tests and frontend quality checks; it still builds the frontend, so do not use it for release validation.

There are no committed generated bundles or node_modules. Build/runtime application secrets must not be placed in frontend files or VITE-prefixed variables.

Only `/app`, `/app/`, `/app/admin`, `/app/admin/accounts`, `/app/admin/routes`, `/app/admin/recipients`, `/app/admin/deliveries`, `/app/driver` and `/app/driver/routes` forward to the shell. Add deliberate server entry mappings when introducing new frontend routes. APIs, missing assets and unknown server routes do not receive SPA HTML. The shell is public but contains no identity; session data is fetched from protected endpoints. Password-setup enforcement still applies at Spring's filter layer.

Shell HTML and session responses are not cached. Vite assets have content-hashed names, and `/app/assets/**` receives public immutable caching for one year. Deploy the complete jar as one artifact so its HTML and assets remain matched; avoid caching shell HTML at a proxy/CDN. Deploying this jar adds Workspace alongside existing pages; reverting the artifact restores the prior entry points without changing database records.

## Verification and scope

Frontend tests exercise roles, anonymous/expired sessions, password setup, delayed responses, malformed identity/JSON, cancellation, poisoned CSRF metadata, duplicate logout, fixed errors and HTML-looking usernames. A real local Vite/proxy test checks API/session headers, CSRF/logout and legacy paths.

Spring running-server tests check public entry/deep-link HTML, immutable hashed assets, missing-path 404s and protected API JSON errors. Browser tests exercise both roles against the actual packaged build, server-enforced API permissions, sign-out/expiry, safe text rendering, retry behavior, keyboard entry and widths 320/375/768/desktop, while preserving prior operational journeys. Use the existing optional `session.browser.*` properties and `scripts/test-account-mysql.sh` for the complete browser/disposable-MySQL regression run.

No database migration, live deployment, account creation or credential change is included. Production readiness still requires the remaining roadmap's schema, scheduling and operational gates.

References: [React TypeScript guidance](https://react.dev/learn/typescript), [Vite production builds](https://vite.dev/guide/build), [frontend Maven build plugin](https://github.com/eirslett/frontend-maven-plugin).

Recipient directory, creation and conditional editing are documented in [REACT_RECIPIENTS.md](REACT_RECIPIENTS.md). Delivery preparation and route generation remain in the legacy scheduling page during migration.

React delivery creation and review are documented in [REACT_DELIVERIES.md](REACT_DELIVERIES.md). Route generation remains in the scheduling page.

## Administrator route generation

`/app/admin/routes/generate` provides explicit generation/assignment confirmation, a dedicated configurable browser deadline and all-status review after uncertain writes. See [REACT_ROUTE_GENERATION.md](REACT_ROUTE_GENERATION.md) for the timeout setting, recovery limits and browser verification.

## Administrator warehouse setup

`/app/admin/warehouses` provides bounded depot search, explicit coordinates, conditional editing/deletion and review after uncertain writes. See [REACT_WAREHOUSES.md](REACT_WAREHOUSES.md) for draft and recovery behaviour.

## Administrator operator setup

`/app/admin/operators` provides driver-record setup, bounded warehouse ID selection, conditional edits/deletion and a separate Accounts handoff. See [REACT_OPERATORS.md](REACT_OPERATORS.md) for assignment safeguards, recovery and end-to-end verification.

## Administrator entry point

The existing CSRF-protected sign-in page redirects administrators directly to `/app/admin`. React overview/navigation use migrated delivery, recipient, operator, warehouse, account and route screens. Legacy scheduling remains accessible by its direct URL for compatibility, but is no longer an overview card or navigation link. Password setup and driver sign-in destinations are unchanged. Browser checks cover direct administrator entry and explicit legacy compatibility separately.

## React sign-in

`/app/login` is the canonical sign-in screen. The former `/html/login.html` address redirects there with recognized sign-out/password-change notices only. Protected HTML, React expiry/sign-out and password setup now link to the canonical screen. Passwords stay in component memory, clear after submission, and are never persisted. Sign-in fetches anonymous CSRF, submits URL-encoded credentials, discards the old token after session rotation and validates server identity before choosing the administrator, driver or required-password destination. There is no public registration or automatic retry.
