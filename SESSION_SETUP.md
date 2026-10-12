# Session authentication and administrator access

The application now authenticates persisted accounts and restricts all existing `/api/v1/scheduling-app/**` endpoints, `/html/admin.html`, and `/html/operators.html` to administrators. DRIVER accounts can sign in, use `/html/operators.html`, and access the ownership-checked `/api/driver/**` endpoints. The existing management API and administrator page remain administrator-only. See `DRIVER_ACCESS.md` for the driver contract.

## HTTP contract

| Request | Behaviour |
| --- | --- |
| GET `/html/login.html` | Public sign-in page |
| GET `/api/auth/csrf` | Public same-origin session CSRF discovery: `headerName`, `parameterName`, masked `token` |
| POST `/api/auth/login` | URL-encoded `username` and `password`, with CSRF header; 200 on success, generic 401 for invalid or disabled accounts |
| GET `/api/auth/me` | Signed-in account summary: ID, username, role, operator ID and enabled flag; no credentials |
| POST `/api/auth/logout` | Requires CSRF; invalidates session, clears authentication/cookie; 204 |
| Scheduling API | ADMIN required; anonymous reads return JSON 401, authenticated drivers return JSON 403 |
| Protected HTML | Anonymous visitors redirect to sign-in; drivers can access only the driver page |

Unsafe requests with a missing or invalid CSRF token return 403, including login and logout. GET never signs in or signs out. Fetch a token before login and fetch a fresh token after successful login because Spring clears the pre-login token. Do not automatically retry writes after a failure. Unknown resources are denied by default. HTTP Basic, remember-me and public registration are not enabled.

Spring's form-login filter handles authentication, session context persistence and session-ID rotation. bcrypt matching uses the same Unicode and 72-byte limits as account provisioning. The session stores a detached account-ID principal with credentials erased. Each authenticated request rechecks the persisted account ID, enabled status, username and role; deletion, disablement or a role/username change invalidates the session. Password changes are not exposed in this release; a future password-reset feature must revoke existing sessions too.

## Deployment and local setup

1. Ensure the account schema and initial administrator were provisioned using `ACCOUNT_SETUP.md`; merging code does not perform these operations. No new schema migration is required by this PR.
2. Serve the frontend and backend through the same origin over HTTPS. Relative frontend URLs replace hardcoded localhost addresses. Wildcard CORS has been removed. A future Vite development server should proxy `/api` to Spring rather than opening a broad credentialed CORS policy.
3. Outside the explicit `local` profile, session cookies are `Secure`, `HttpOnly`, `SameSite=Lax`, and use `/` as their path. The `local` profile allows HTTP cookies for development; never activate it on a public deployment. Sessions expire after 30 minutes of inactivity and use cookies only, not URL session IDs.
4. Run one application instance for now. Sessions are held in memory and restart invalidates them. Horizontal scaling needs a deliberately configured shared session store or suitable routing before rollout.
5. Before public deployment, configure login throttling at the trusted ingress, restrict direct backend access, and validate HTTPS/proxy behaviour in staging. Application-level throttling, password recovery, account-management UI, frontend migration and the wider audit items remain separate work.

For local development with your existing local database configuration, start the built application explicitly in the local profile and open `/html/login.html`:

```sh
java -jar target/schedulingApp-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.address=127.0.0.1
```

The existing administrator/route pages share `auth.js` for identity, CSRF and logout. Dynamic API values/errors are rendered as text or escaped before insertion into HTML. The route page uses dedicated ownership-checked endpoints for drivers and existing management endpoints for administrators. It no longer contains a hardcoded operator ID or a fallback to another driver's route.

## Verification

With Java 25, `mvn -B verify` runs H2-backed real-account/filter tests and real HTTP login smoke tests, plus existing regression tests. The opt-in MySQL and browser tests are skipped by this command.

`./scripts/test-account-mysql.sh` runs the same suite plus the existing disposable MySQL schema tests; it forwards Maven arguments. To include the browser checks, pass `-Dsession.browser.node=<node executable>`, `-Dsession.browser.modules=<directory containing playwright>`, and `-Dsession.browser.executable=<Chrome/Chromium executable>` to that script. Browser tests run only against the randomly bound localhost test server, use test-only credentials, and verify login, CSRF transmission, safe rendering and logout.

No live database, real account or Google Maps request is needed for these checks.
