# Improvement roadmap

**Prepared:** 12 October 2026 (Europe/London)\
**Progress reconciled:** 13 October 2026; merged through PR #22\
**Audit baseline:** 98dba9eac39c453ae9b29b8a70f16e4d16770cb0\
**Issue details and source evidence:** [PROJECT_AUDIT.md](PROJECT_AUDIT.md)

The application is not production-ready. The agreed frontend direction is **React + TypeScript + Vite**, with an incremental migration from the current HTML/jQuery pages. Preserve the existing Spring MVC/JPA/MySQL backend while correcting access control, workflow integrity, provider reliability and operational readiness. Bootstrap styles can remain where useful; migrated screens will use React components for interactions. Implementation is now authorized in small, focused branches and pull requests with regression tests; this roadmap records sequencing and acceptance criteria.

## Agreed target architecture

| Area | Target | Implementation decision |
|---|---|---|
| Frontend | React + TypeScript + Vite | A browser-rendered application in a proposed `frontend/` directory; no Next.js or separate frontend server is required in production |
| Navigation | Client-side routing | Use React Router for admin and driver routes; browser route guards improve UX, while Spring enforces every permission |
| API integration | Typed client calling Spring REST APIs | Use relative `/api/...` URLs for the existing scheduling, account, driver and authentication endpoints, shared errors and runtime validation at external data boundaries; TypeScript types alone do not validate responses |
| Authentication | Spring Security identity and session | Keep identity, roles and assignment ownership in Spring; integrate secure session cookies, CSRF handling, expiry and logout into the React client |
| Development | Vite development server + Spring Boot | Proxy `/api` and the agreed authentication paths to Spring; configure origin/session behavior explicitly and keep real secrets out of frontend environment variables |
| Production | Static Vite build served by Spring Boot initially | Package frontend assets with the backend through a reproducible build; configure SPA fallback for frontend routes without intercepting API/auth/health endpoints or missing assets |
| UI | Shared React components and visual styles | Retain useful Bootstrap CSS; replace jQuery DOM mutation on migrated screens, including modal/form interactions, with React state and components |
| Verification | Type checks, lint, component and browser tests | Run TypeScript checking separately from the Vite build; use Vitest/React Testing Library for meaningful component tests and Playwright for critical journeys |

The merged frontend pins Node 24.20.0/npm 11.19.0 and dependencies through package-lock.json. Preserve these verified versions unless a concrete maintenance or security need justifies a tested upgrade. Keep ordinary UI state local to components and introduce shared state or a server-data library only where an actual workflow requires it. Do not duplicate Spring business rules in a frontend backend layer.

The original audit describes the existing application and remains the evidence baseline. This roadmap records the subsequent technology decision; the migration does not remove any audited security, database or workflow requirement.

## 1. Implementation sequence


The user has authorized implementation in focused branches and pull requests. The phases below are independently reviewable changes. Security, tests and operational checks should accompany each change rather than waiting until the final phase.

### Phase A: Critical security and functionality fixes

**Priority:** immediate release blockers. **Objective:** make access, core writes and execution state safe before real data is used. **Dependencies:** none for credential containment; a reproducible disposable environment/test baseline is the first engineering task.

Recommended order:

1. Rotate/restrict exposed Maps credentials, redact logging and add secret checks ([SEC-02](PROJECT_AUDIT.md#sec-02)/03). Rotation is an account action; verify its actual outcome.
2. Establish Java 25 build plus focused regression tests, then repair enum validation ([BE-01](PROJECT_AUDIT.md#be-01), [OPS-03](PROJECT_AUDIT.md#ops-03)/04).
3. Add Spring Security identity/admin/operator ownership, session/CSRF and constrained origins ([SEC-01](PROJECT_AUDIT.md#sec-01)); remove hardcoded driver fallback ([FE-04](PROJECT_AUDIT.md#fe-04)).
4. Replace unsafe HTML insertion ([SEC-04](PROJECT_AUDIT.md#sec-04)) and fix same-origin API paths ([FE-01](PROJECT_AUDIT.md#fe-01)).
5. Implement transition rules, route completion and safe cancellation/history preservation ([BE-03](PROJECT_AUDIT.md#be-03)/04/05).
6. Add minimum provider deadlines/bounds and correct recipient coordinate update behavior ([SEC-05](PROJECT_AUDIT.md#sec-05), [BE-11](PROJECT_AUDIT.md#be-11)).

**Expected outcome:** an authorized administrator and assigned driver can execute one complete route with reliable final state and protected data.

**Acceptance:** anonymous/cross-driver operations rejected; malicious names remain text; no active source/log key; valid operator create works; completing final delivery releases driver; deleting/archiving terminal work never recreates pending deliveries; browser calls hosted origin; provider outages have bounded duration. Known concurrency gaps remain staging-only until Phase C.

### Phase B: Backend architecture and code quality

**Priority:** High/Medium. **Objective:** make business rules and provider behavior testable without changing the monolith.

Tasks: separate Maps provider interface/implementation from geocoding/routing; inject HTTP clients/ObjectMapper/Clock; separate planning computation from database commit; eliminate scoring side effects; use small explicit DTOs for create/update/status/patch; centralize domain errors and documented defaults; fix [BE-02](PROJECT_AUDIT.md#be-02)/07/12/13/15/16/17/18 and [DB-07](PROJECT_AUDIT.md#db-07).

**Dependencies:** Phase A regressions and agreed state/assignment defaults. **Order:** provider/time boundary -> error/DTO contracts -> pure planning -> focused cleanup.

**Step-by-step execution:**

1. Introduce an injected Maps client with mocked provider fixtures and configured deadlines.
2. Define typed provider outcomes and the API error contract.
3. Split route computation from transaction-bound persistence and inject Clock.
4. Use explicit create, replace, partial-update and status DTOs; align constraints and defaults.
5. Make scoring deterministic and side-effect free, then resolve targeted builder/entity cleanup.

**Expected outcome:** deterministic tests, bounded provider failures and easier reasoning about service responsibilities.

**Acceptance:** provider fixtures cover HTTP/API denied/quota/zero-results/malformed/timeout outcomes; no API call occurs while persisting plan updates; fixed-clock outputs repeat; invalid partial writes are rejected; JSON/field error contract is consistent; public API behavior is documented.

### Phase C: Database and API improvements

**Priority:** High for concurrency/migrations; Medium for data/query refinements. **Objective:** preserve state under simultaneous requests and make environments reproducible.

Tasks: inspect actual DDL read-only; baseline/add versioned migrations; add tested constraints/delete restrictions and warehouse-ID contract; implement versioned/locked work claims plus active-assignment invariants and retry idempotency; fix client-selected create ID; design targeted reads/pagination; profile query plans and remove OSIV dependence ([BE-06](PROJECT_AUDIT.md#be-06)/14, [DB-01](PROJECT_AUDIT.md#db-01)–06, [PERF-01](PROJECT_AUDIT.md#perf-01)).

**Dependencies:** Phase B transaction/domain boundaries and disposable MySQL. Back up representative data before any future approved migration. **Order:** schema baseline -> invariants/claim design -> concurrency tests -> bounded API queries/fetches.

**Step-by-step execution:**

1. Inspect the deployed schema read-only and prepare a migration baseline against a disposable copy.
2. Check existing data, then add agreed integrity constraints and reference/delete policies.
3. Introduce atomic delivery/operator claims and optimistic conflict handling.
4. Implement idempotent generation retries and test simultaneous planners/status writers.
5. Exclude client-selected IDs from create operations and use unambiguous warehouse identifiers.
6. Add bounded pagination/filtering and targeted fetching; verify SQL counts and query plans.

**Expected outcome:** one active assignment per delivery/operator, controlled migration/recovery and bounded database reads.

**Acceptance:** fresh install and upgrade tested; two planners cannot commit overlapping assignments; stale updates return409; deletes preserve referential/history integrity; direct invalid DB writes fail; OSIV-disabled DTO queries work; measured SQL/query plans meet agreed bounds. No external production migration is part of the audit.

### Phase D: React + TypeScript + Vite migration and UI/UX modernization

**Priority:** frontend foundation after the Phase A security and API decisions; complete migration before production release. **Objective:** replace the existing page scripts incrementally with a maintainable, accessible React frontend and complete the current admin/driver workflows.

Tasks: create the frontend project and reproducible build; establish strict typing, routing, session integration and a typed API boundary; build shared components and design styles; fix batch/empty-response/modal-race behavior during migration; expose warehouse/driver setup and delivery/route review; show time windows/instructions/stop order/progress; add consistent pending/error behavior and accessibility ([FE-01](PROJECT_AUDIT.md#fe-01), [FE-02](PROJECT_AUDIT.md#fe-02), [FE-03](PROJECT_AUDIT.md#fe-03), [FE-04](PROJECT_AUDIT.md#fe-04), [FE-05](PROJECT_AUDIT.md#fe-05), [FE-06](PROJECT_AUDIT.md#fe-06), [UX-01](PROJECT_AUDIT.md#ux-01)–05, [BE-08](PROJECT_AUDIT.md#be-08) contract).

**Dependencies:** agree the Phase A identity/session and assignment rules before integration. Frontend scaffolding, mock-based components and visual design can proceed while B/C continue; each live workflow requires its corresponding API/lifecycle contract, and release requires Phase C integrity checks. **Order:** foundation -> authentication/API integration -> one complete operational journey -> remaining screens -> verification and cutover.

**Step-by-step execution:**

1. Record the screen inventory and API contracts, including create/update/status payloads, empty collections, field errors, active-driver routes and recipient batches. Define the user-to-operator mapping and automatic versus explicit assignment behavior.
2. Scaffold `frontend/` with React, TypeScript and Vite; enable strict TypeScript, linting, a lockfile and explicit scripts for type checking, tests and production build. Set up the local API/auth proxy and proposed production asset packaging without committing secrets.
3. Add routing and a shared admin/driver application shell. Implement authenticated-user loading, session expiry/logout, CSRF propagation and useful forbidden/error states. Treat backend authorization as authoritative.
4. Build a small typed API client with consistent error handling and response validation where needed. Cover empty collections, invalid data, request cancellation and stale responses; fix the recipient preload contract and scope requests to the authorized driver.
5. Establish shared form fields, buttons, alerts, tables/stop cards, dialogs and loading/empty/error states. Use React text rendering for business data; avoid unsafe HTML injection and jQuery mutation of React-owned DOM. Keep accessible focus, labels and live feedback.
6. Migrate a complete journey in testable screen-sized changes: create recipient -> create delivery -> generate/review routes -> assigned driver views stops -> complete/cancel delivery -> completed route releases driver. Use disposable fixtures and mocked provider responses while API work is underway.
7. Add warehouse/driver setup, delivery/recipient listing and editing, eligible route selection, unassigned-work feedback and history/archive views according to the agreed backend rules. Include stop order, windows, instructions, address/contact and honest estimated travel labels.
8. Verify component behavior and critical browser journeys, including two drivers, expired sessions, provider failures, duplicate submits and late modal responses. Review keyboard/screen-reader use and empty/loading/error/populated layouts at 320/375/768/desktop widths.
9. Cut over routes only after each migrated workflow meets its acceptance checks. Keep legacy pages temporarily only with the same security fixes; remove their scripts/CDN dependencies when unused, and document entry-point redirects and rollback. Rollback must preserve backend authorization, safe rendering and terminal delivery history.

**Expected outcome:** staff can set up and run the system through typed, reusable React screens; drivers see only authorized work and can complete a route without losing instructions or execution state. Spring remains the owner of persistence and business rules.

**Acceptance:** type checking, lint, meaningful component tests and production build pass independently; fresh admin setup-to-route journey and driver completion pass browser tests; cross-driver access is rejected by Spring; expired sessions and CSRF failures are handled; wrong/late data is ignored; repeated submissions follow documented idempotency; dynamic strings render inertly; keyboard/screen-reader and responsive checks pass. Production deep links reload correctly, API errors remain API responses, missing assets remain 404s, production browser requests use the served origin, and no frontend bundle contains server credentials. A successful Vite build alone is not proof of type safety or workflow correctness.

### Phase E: Performance and scalability improvements

**Priority:** measured need; route-call bounds are already urgent in A/B. **Objective:** reduce provider requests and enforce scheduling feasibility.

Tasks: benchmark representative stop counts/distributions and SQL loads; define load/shift/service-time/time-window semantics; implement capacity-aware allocation and arrival feasibility/warnings; replace quadratic paid candidate lookups with local scoring plus bounded/batched road enrichment; assess Google Routes API migration; consider bounded background jobs only if latency/recovery warrants them ([BE-09](PROJECT_AUDIT.md#be-09)/10, [SEC-05](PROJECT_AUDIT.md#sec-05), [DB-05](PROJECT_AUDIT.md#db-05), [PERF-01](PROJECT_AUDIT.md#perf-01)).

**Dependencies:** transactional claims, deterministic planner, stable UI warning contract. **Order:** baseline measurement -> correctness constraints -> request budget improvements -> optional asynchronous execution.

**Step-by-step execution:**

1. Measure provider-call count, route latency, heap and transaction duration for representative workloads.
2. Agree capacity units, departure time, service duration and delivery-window feasibility rules.
3. Implement deterministic allocation/arrival checks with explicit unassigned/infeasible work.
4. Reduce paid candidate lookups through local scoring and bounded/batched enrichment.
5. Evaluate the Legacy Distance Matrix transition using compatibility and cost evidence.
6. Add durable bounded jobs only if the measured workload requires them; test retry/backpressure recovery.

**Expected outcome:** predictable planning cost/duration and honest feasible/degraded results.

**Acceptance:** benchmark reports actual provider calls, p95 duration, memory, DB occupancy and cost assumptions against agreed workload budgets; capacity/window violations are returned explicitly; provider outage/backpressure is bounded; background retry, if introduced, cannot duplicate routes. Do not fabricate target percentages before a baseline.

### Phase F: Testing, infrastructure and production readiness

**Priority:** release gate; work incrementally throughout A–E. **Objective:** deploy, observe and recover safely.

Tasks: continuous Java/API/MySQL/browser tests; accessibility/security/secret/dependency scans; update to supported Boot baseline after migration tests; document required config, TLS/DB permissions, asset handling and release process; add protected health/readiness/metrics/audit logging; define backups/RPO/RTO and rehearse restore/rollback; correct README/API examples and remove machine-specific metadata ([OPS-01](PROJECT_AUDIT.md#ops-01)–07).

**Dependencies:** tested migrations/auth/providers/core workflows; supported host environment. **Order:** CI/toolchain/config -> supported framework -> staging artifact -> failure/recovery exercises -> release approval.

**Step-by-step execution:**

1. Pin Java and Node build environments and run backend regression tests plus frontend type checking, lint, component tests, browser tests and Vite build in CI.
2. Document required configuration and complete the supported-framework migration with compatibility tests.
3. Generate dependency/asset inventory and run secret/advisory checks with applicability review.
4. Deploy an immutable artifact containing the verified frontend build to staging with controlled migrations and protected health/readiness. Test SPA deep links, static-asset caching, API routing and session/CSRF behavior behind the actual HTTPS proxy.
5. Exercise authentication, provider/DB failures, monitoring and rollback.
6. Restore a representative backup and validate agreed recovery objectives.
7. Complete browser/accessibility and end-to-end acceptance checks before production approval.

**Expected outcome:** evidence-based operational readiness rather than a build-only declaration.

**Acceptance:** clean checkout/CI reproduces backend and frontend artifacts; real tests and independent type checks run; no known unaccepted applicable security advisory; staging end-to-end and failure tests pass; metrics/alerts capture provider/DB/security failures; backup restore meets agreed RPO/RTO; migrations and release rollback are rehearsed; no secrets/devtools/compiler-only libraries in shipped artifact. JavaScript assets have a documented caching policy, HTML updates do not strand users on incompatible assets, and the frontend dependency inventory is included in security review.

### Suggested Git and pull-request strategy

Continue using focused `aniM/` branches, such as `aniM/react-admin-accounts`, `aniM/react-admin-routes` and `aniM/react-admin-deliveries`. The merged work is recorded below; create each subsequent PR for human review and merge. Keep credential rotation separate from code publication. Separate frontend scaffolding, session/API integration, each workflow migration and legacy removal into reviewable PRs. Each PR should state trigger/before-after behavior, carry meaningful regression tests, and avoid unrelated dependency/UI churn. Separate the Spring framework upgrade from frontend migration and domain logic changes so failures are attributable.

For cross-layer API/schema changes use additive compatibility first, migrate/read back, then remove old fields/endpoints in a later reviewed change. Baseline data constraints require a preflight data report and explicit remediation path. Promote one immutable tested artifact from staging to production; maintain forward recovery and backups for migrations rather than assuming code revert can reverse data changes.

## 2. First five issues to address

| Order | Issue | Why first | Completion evidence |
|---|---|---|---|
| 1 | [SEC-02](PROJECT_AUDIT.md#sec-02): exposed Maps credential (with [SEC-03](PROJECT_AUDIT.md#sec-03) log redaction) | Limits ongoing source/log credential exposure and potential quota abuse before other work | Provider rotation/restrictions confirmed; no secret-bearing logs/source |
| 2 | [SEC-01](PROJECT_AUDIT.md#sec-01): identity, roles and ownership | Every data/read/write control otherwise remains accessible to a reachable caller | Anonymous/cross-driver negative tests plus authorized workflow |
| 3 | [SEC-04](PROJECT_AUDIT.md#sec-04): stored XSS | An existing routed recipient name can execute in a driver's browser; authentication alone does not make names safe | Browser rendering regression with inert HTML-looking names |
| 4 | [BE-01](PROJECT_AUDIT.md#be-01): enum validation | Blocks normal operator onboarding and validated updates | Valid create/update and missing-status 400 tests |
| 5 | [BE-04](PROJECT_AUDIT.md#be-04): route finalization/releasing driver, implemented with [BE-03](PROJECT_AUDIT.md#be-03)/05 lifecycle safeguards | Stops the operational workflow after one route and route deletion currently erases terminal state | Complete first route, release driver, create next route, preserve terminal history |

Begin a minimal Java 25/disposable-MySQL test harness while containment steps 1–3 are underway. These are the first five issue priorities, not a request to defer provider deadlines, same-origin URLs or migrations until deployment. Production release still requires the full Phase F acceptance gate.

## 3. Implementation progress

The original audit is a historical assessment of revision `98dba9e`, not a current vulnerability inventory. The following progress is backed by merged Git history. Code fixes do not prove provider-side credential rotation, live migration application or production deployment readiness.

| Work | Merged evidence | State |
|---|---|---|
| Operator enum validation, recipient batch contract, empty collection JSON | PRs #1–3 | Merged |
| Disposable local MySQL and reproducible Java 25 baseline | PRs #4–5 | Merged |
| Maps secret configuration and log redaction | PR #6 | Code merged; provider rotation/restrictions still require independent confirmation |
| Persisted account foundation and session/CSRF authentication | PRs #7–8 | Merged |
| Driver ownership and constrained terminal actions | PR #9 | Merged |
| Administrator account API, onboarding/reset and legacy account interface | PRs #10–11 | Merged |
| Delivery/route lifecycle and concurrency regression checks | PR #12 | Merged; does not complete every database invariant/migration task |
| Maps call/deadline bounds | PR #13 | Merged |
| Snapshot/calculate/revalidate route-planning transactions | PR #14 | Merged |
| React + TypeScript + Vite foundation, same-origin API/session integration and Spring asset packaging | PR #15 | Merged |
| React driver route execution and entry-point cutover | PR #16 | Merged |
| React administrator account directory, eligible-operator search, creation/access/reset and one-time credentials | PR #17 | Merged |
| React administrator route review, bounded route summaries and selected-stop details/actions | PR #18 | Merged |
| Recipient API foundation: bounded search, strict writes, conditional edits and guarded coordinate updates | PR #19 | Merged |
| React recipient management: searchable directory, creation, conditional edits and explicit save recovery | PR #20 | Merged |
| Delivery preparation API: strict unassigned creation, shared legacy validation and bounded directory | PR #21 | Merged |
| React delivery preparation: bounded recipient selection, creation, filtered directory/details and explicit uncertain-save review | PR #22 | Merged |
| Route generation API: strict date-only generation, compact acknowledgement and date-filtered all-status review | PR #23 | Merged |
| React route generation: assignment confirmation, bounded waiting, all-status recovery and exact-route links | PR #24 | Merged |
| Warehouse management API: bounded scalar reads, strict coordinates, conditional edits/deletion and legacy safeguards | PR #25 | Merged |
| Operator management API: warehouse IDs, conditional edits, lifecycle/history guards and serialized account creation | `aniM/operator-management-api` | Implemented in this branch; merge pending |

### Next implementation sequence

1. Recipient API and React management merged in PRs #19–20; legacy scheduling remains available.
2. Delivery preparation API merged in PR #21; strict creation and bounded reads are documented in [DELIVERY_PREPARATION_API.md](DELIVERY_PREPARATION_API.md).
3. React delivery preparation merged in PR #22; see [REACT_DELIVERIES.md](REACT_DELIVERIES.md). Route-generation API foundation merged in PR #23. React route generation merged in PR #24 with explicit assignment confirmation, configured timeout handling and all-status reconciliation after uncertainty. See [REACT_ROUTE_GENERATION.md](REACT_ROUTE_GENERATION.md) and [ROUTE_GENERATION_API.md](ROUTE_GENERATION_API.md).
4. Warehouse API foundation merged in PR #25; see [WAREHOUSE_MANAGEMENT_API.md](WAREHOUSE_MANAGEMENT_API.md). Review and merge `aniM/operator-management-api` for warehouse IDs and guarded availability/account relationships; see [OPERATOR_MANAGEMENT_API.md](OPERATOR_MANAGEMENT_API.md). Then build React warehouse/operator setup screens. Add history/archive workflows only against agreed backend contracts; remove unused legacy scripts/CDN dependencies after cutover verification.
5. Continue database/schema work, planner feasibility and bounded API reads from Phases C/E; do not mark a phase complete solely because one related PR merged.
6. Establish CI as a near-term focused PR (no checked-in workflow currently exists) to enforce backend, frontend and browser checks on subsequent changes; then complete staging HTTPS/session checks, protected health/readiness, secrets/advisory checks, backup restore and release rollback evidence from Phase F.

The deployed schema, provider-account restrictions, operational monitoring, backup/restore and live release behavior remain unverified by local/disposable tests. Production approval requires those gates as well as the remaining domain and UI acceptance checks.
