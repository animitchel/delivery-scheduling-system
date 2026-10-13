# React frontend foundation

The React + TypeScript + Vite workspace is in `frontend/`. Spring remains responsible for authentication, authorization, business rules and persistence. Production needs one Spring application; Node is a build/development tool, not a production frontend server.

## First migration slice

| Screen | Current entry point | Behavior |
| --- | --- | --- |
| React entry | `/app/` | Loads the session and selects the administrator or driver overview |
| Administrator overview | `/app/admin` | Responsive shell with links to scheduling, accounts and routes |
| Driver overview | `/app/driver` | Assigned-routes entry point; administrator navigation is absent |
| Sign-in | `/html/login.html` | Existing session/CSRF login, including account/password setup handling |
| Password setup | `/html/change-password.html` | Existing required password-change workflow |
| Scheduling | `/html/admin.html` | Existing recipient/delivery forms and route generation |
| Accounts | `/app/admin/accounts` | React paginated account directory, eligible-operator search and protected driver actions |
| Driver route execution | `/app/driver/routes` | React assigned routes, on-demand stop details and terminal delivery actions |
| Administrator route review | `/app/admin/routes` | Bounded route directory, selected stops and terminal delivery actions |
| Legacy route rollback | `/html/operators.html` | Existing role-authorized route page |

Driver sign-in now opens `/app/driver/routes`. Administrator sign-in and operational pages retain their existing destinations. The driver journey is documented in `REACT_DRIVER_ROUTES.md`; the legacy route page remains available for rollback.

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

Only `/app`, `/app/`, `/app/admin`, `/app/admin/accounts`, `/app/admin/routes`, `/app/driver` and `/app/driver/routes` forward to the shell. Add deliberate server entry mappings when introducing new frontend routes. APIs, missing assets and unknown server routes do not receive SPA HTML. The shell is public but contains no identity; session data is fetched from protected endpoints. Password-setup enforcement still applies at Spring's filter layer.

Shell HTML and session responses are not cached. Vite assets have content-hashed names, and `/app/assets/**` receives public immutable caching for one year. Deploy the complete jar as one artifact so its HTML and assets remain matched; avoid caching shell HTML at a proxy/CDN. Deploying this jar adds Workspace alongside existing pages; reverting the artifact restores the prior entry points without changing database records.

## Verification and scope

Frontend tests exercise roles, anonymous/expired sessions, password setup, delayed responses, malformed identity/JSON, cancellation, poisoned CSRF metadata, duplicate logout, fixed errors and HTML-looking usernames. A real local Vite/proxy test checks API/session headers, CSRF/logout and legacy paths.

Spring running-server tests check public entry/deep-link HTML, immutable hashed assets, missing-path 404s and protected API JSON errors. Browser tests exercise both roles against the actual packaged build, server-enforced API permissions, sign-out/expiry, safe text rendering, retry behavior, keyboard entry and widths 320/375/768/desktop, while preserving prior operational journeys. Use the existing optional `session.browser.*` properties and `scripts/test-account-mysql.sh` for the complete browser/disposable-MySQL regression run.

No database migration, live deployment, account creation or credential change is included. Production readiness still requires the remaining roadmap's schema, scheduling and operational gates.

References: [React TypeScript guidance](https://react.dev/learn/typescript), [Vite production builds](https://vite.dev/guide/build), [frontend Maven build plugin](https://github.com/eirslett/frontend-maven-plugin).
