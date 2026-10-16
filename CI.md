# Continuous integration

## Workflow and merge gate

**Live gate status (14 October 2026): blocked.** Main is unprotected; GitHub reports a plan restriction for this private repository and insufficient Actions/protection API permissions for the available credential. The owner chose to keep it private and document the block. See [CI_MERGE_GATE.md](CI_MERGE_GATE.md) for evidence, the reviewable policy and read-only verification tooling. Neither workflow YAML nor the committed policy template enables enforcement.

`.github/workflows/ci.yml` runs **CI / Verify application** for pull requests, pushes to `main`, and manual dispatch. It uses an Ubuntu 24.04 hosted runner, read-only repository permissions, SHA-pinned actions, a 25-minute job limit and concurrency cancellation for superseded runs. Checkout does not persist Git credentials. There are no path filters that could leave documentation-only PR checks pending.

The workflow verifies and packages the application. It does not deploy, modify branch protection, use live data or require application secrets. After opening this PR, confirm its first GitHub-hosted run is green. Once the check exists, use the repository's branch rules/ruleset for `main` to require **Verify application** before merging. Select the actual GitHub Actions check shown by that run. Workflow YAML alone does not make checks mandatory, and available branch-rule features depend on repository/account settings. The initial CI PR can run the new PR workflow; manual dispatch becomes available after the workflow reaches the default branch.

## Reproducible inputs

- Hosted runner: `ubuntu-24.04` (the hosted image receives maintenance updates; it is not a frozen image digest).
- CI JDK: Temurin **25.0.4.1+1**. The action input is Adoptium's published SemVer **25.0.4+101.0.LTS**, because the pinned setup-java action validates SemVer rather than accepting a four-part display version.
- Node: **24.20.0**. The runner checks the exact version; Maven independently installs the frontend's pinned Node and npm **11.19.0**.
- Maven: repository wrapper **3.9.10**, now used by the disposable-MySQL harness instead of an ambient `mvn` installation.
- MySQL: the existing disposable **8.4.11** container. The image tag is versioned, not digest-pinned.
- Browser tooling: separate `tests/browser/package.json` and integrity-locked dependency graph, with **Playwright 1.64.0** and its matched Chromium. Browser tooling is not a production frontend dependency and is not packaged into the application.

The workflow installs Chromium and Linux dependencies with the pinned Playwright CLI, following [Playwright's CI installation guidance](https://playwright.dev/docs/ci). Java pin interpretation follows the verified [setup-java version matcher](https://github.com/actions/setup-java/blob/0f481fcb613427c0f801b606911222b5b6f3083a/src/util.ts) and [Adoptium release metadata](https://api.adoptium.net/v3/assets/feature_releases/25/ga?architecture=x64&image_type=jdk&os=linux&vendor=eclipse). Updating toolchain/actions/browser pins requires a reviewed change and a complete validation run; avoid silently mixing arbitrary browser binaries with a newer Playwright package.

## Execution and evidence

`./scripts/run-ci.sh`:

1. Runs the CI evidence checker's negative/positive unit tests.
2. Verifies Node, removes previous test reports and browser evidence, and sets a repository-local artifact directory.
3. Calls `scripts/test-account-mysql.sh` through the Maven wrapper, with explicit browser Node/module/artifact paths.
4. Runs `verify-ci-results.py` after Maven succeeds.

The existing Maven pipeline checks Java 25, installs frontend dependencies from the lockfile, runs TypeScript/ESLint/Vitest, builds Vite, executes backend tests and packages Spring plus its frontend. Vitest now writes JUnit XML to `target/frontend-reports/vitest.xml` alongside its console report, including during normal local tests.

The MySQL harness creates a uniquely named disposable container bound to loopback, waits for readiness and removes it on exit. Its static password belongs only to this disposable test database. Tests create isolated databases in that container; no application database, Maps account or repository/application credential is required. Browser routes use test fixtures and mocked Maps.

The browser smoke test no longer requires an installed Chrome path. Without an explicit `session.browser.executable`, it launches Playwright's bundled browser. Existing developers can still supply an override deliberately. The browser writes screenshots, a log and a success marker under `.ci-artifacts/browser` in CI, with a `target/browser-artifacts` default for standalone local runs. Failure attempts capture a screenshot; process timeout logs are retained. No trace/video capture is introduced.

The evidence checker discovers backend test classes from source and requires each to have a passing report, including the ten current MySQL suites (with migration installation/adoption tests). It independently requires the browser method and its completion marker, every frontend test file, no failure/error/skipped cases or error counters, and the packaged backend class plus Vite HTML/JS/CSS and migration resources/libraries in the JAR. Missing/malformed evidence fails the job even if Maven returned success. `.ci-artifacts/coverage-summary.json` records verified counts and coverage categories; it is execution evidence, not code-coverage measurement. Fresh-checkout CI prevents stale compiled classes, and the runner clears stale reports/markers before every invocation.

## Artifacts and diagnosis

The workflow attempts to upload Surefire reports, frontend JUnit, browser diagnostics and the execution summary even after verification fails. A successful job also uploads the packaged JAR. Artifacts retain for seven days and use run/attempt or commit/attempt names to prevent rerun collisions. Upload paths are limited to those reports and browser outputs; ignored configuration and browser `node_modules` are not uploaded. Diagnostics may contain disposable test identities/credentials and should be treated as test data, not production data.

A green job confirms the required local checks and packaging ran. It does not prove deployed schema constraints, production advisories/secret rotation, staging HTTPS, monitoring, backup restore or rollback readiness. GitHub Actions availability, artifact storage and branch-rule enforcement still require repository-side confirmation.

## Local reproduction

Use Java 25, Node 24.20.0, Docker and Python 3. Install the separate browser tooling:

```sh
npm ci --prefix tests/browser --ignore-scripts --no-audit --no-fund
node tests/browser/node_modules/playwright/cli.js install chromium firefox webkit
./scripts/run-ci.sh
```

On supported Linux hosts, use `install --with-deps chromium firefox webkit` to install system dependencies too. The workflow handles that setup. `run-ci.sh` clears only prior test evidence; it does not install system dependencies or alter a live database. The original optional system-Chrome smoke invocation remains compatible, but CI uses the matched bundled browser.

Validate workflow syntax with `actionlint .github/workflows/ci.yml` and shell syntax with `bash -n scripts/run-ci.sh scripts/test-account-mysql.sh`. The evidence checker tests are `python3 -m unittest discover -s scripts -p 'test_verify_ci_results.py'`.

## Implementation validation

The migration-foundation runner passed locally on 14 October 2026 on macOS with Java 25, Flyway 11.20.3 and the locked bundled Playwright browser: 534 backend tests (including 11 migration tests), 349 frontend tests, all nine MySQL suites, the full browser journey, and 11 evidence-checker tests. Migration/frontend packaging verification, shell syntax and Git diff whitespace checks passed. The final JAR operator wrapper also passed against separate disposable MySQL. GitHub-hosted verification of this PR remains pending; branch protection has not been changed.

## Database migration verification

`DatabaseMigrationMySqlTest` is automatically required by the existing evidence gate. It exercises Flyway V1, real Hibernate validation, restart, populated legacy adoption, missing constraints/columns, unsafe startup overrides and checksum validation on disposable MySQL. Other suites retain their Hibernate-created fixtures; the migration suite and fresh managed-development journey exercise migration-backed configuration. Production database adoption remains an explicit operator step documented in [DATABASE_MIGRATIONS.md](DATABASE_MIGRATIONS.md).

## Fresh managed development verification

`ManagedDevelopmentMySqlTest` is required by the existing source-discovered evidence gate and verifies the fresh MySQL/Flyway provisioning-to-completion/restart journey with mocked Maps. The runner now includes all `test_*.py` helper/evidence tests, including the new managed-local setup guards. Real local managed installation is documented in [MANAGED_LOCAL_DATABASE.md](MANAGED_LOCAL_DATABASE.md); CI never connects to either persistent development database.

Fresh managed setup verification: 535 backend tests, 349 frontend tests, ten MySQL suites and the full browser journey passed locally. The final Python helper/evidence run passed 19 tests. The persistent local setup separately passed two immutable-JAR starts and administrator login without retaining operational fixtures. GitHub-hosted verification remains required on the PR.

## Supported framework verification

The Spring Boot 4.1.1 upgrade passed the full local runner on 14 October 2026: **536 backend tests**, **349 frontend tests**, **ten MySQL suites**, the bundled-Chromium journey and packaged-resource evidence gate. All **19 Python helper/evidence tests** passed. This includes the concrete DTO converter-selection regression, strict payload/privacy checks, account concurrency, fresh Flyway installation, legacy adoption and managed lifecycle/restart. See [SPRING_BOOT_UPGRADE.md](SPRING_BOOT_UPGRADE.md). GitHub-hosted PR checks and required-check configuration remain to be verified.

An independent immutable-JAR check also passed fresh disposable MySQL migration, repeat provisioning, both schema-adoption wrapper commands against an unversioned V1-shaped schema, and HTTP login/assets/logout across restart. All temporary servers and containers were removed.

## Jackson 3 verification

On 14 October 2026 the final Jackson 3 runner passed **538 backend tests**, **349 frontend tests**, **ten MySQL suites**, the bundled browser journey, packaged-resource checks and all **19 Python tests**. Converter selection, precision/temporal output and real HTTP trailing-document/overflow regressions supplement the existing strict validation/privacy tests. Dependency tree and CycloneDX generation succeeded; Jackson 2 core/databind and the compatibility module are absent. See [JACKSON3_MIGRATION.md](JACKSON3_MIGRATION.md). GitHub-hosted PR checks remain required before merge.

The independent immutable-JAR check also passed Jackson 3-only core/databind packaging, unchanged V1 bytes, fresh disposable MySQL migration, repeat provisioning, schema check/baseline commands, strict HTTP JSON/private errors, login/assets/logout and restart. Temporary servers and containers were removed.

## Dependency advisory gate

`run-ci.sh` now runs `scripts/check-dependencies.sh` after application packaging/evidence validation. It scans resolved Maven dependencies, the actual packaged runtime and both npm lockfiles through OSV; all active unaccepted matches or incomplete checks fail the job. Dependency/SBOM/advisory diagnostics are included in the always-attempted report upload; application upload still requires overall job success. See [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md). This does not remove the documented GitHub plan/permission block for enforcing merges.

Dependency baseline validation on 14 October 2026 passed 538 backend tests, 356 frontend tests, ten MySQL suites, browser/packaging checks and 52 Python tests. The final OSV check completed for 392 distinct package/version pairs with no matches or exceptions. The independent patched-JAR provisioning/schema/HTTP/restart checks and workflow/shell lint passed. Hosted GitHub verification remains unconfirmed.

### Repository secret scanning

`aniM/repository-secret-scanning` adds pinned, checksum-verified Gitleaks checks of tracked files and full HEAD history, location-only CI evidence and exact historical acknowledgements. The owner confirmed the previously exposed Maps credential was revoked or rotated on 2026-10-14; provider state was not independently verified. See [SECRET_SCANNING.md](SECRET_SCANNING.md). Staging HTTPS/configuration, readiness, recovery and blocked GitHub enforcement remain separate release gates.


### Required cross-browser frontend acceptance

`run-ci.sh` runs `tests/browser/run-acceptance.cjs` after backend/browser/packaging evidence verification. All four built-frontend fixture suites must pass in Chromium, Firefox and WebKit; a missing runtime, failed process, timeout or incomplete report fails the command. Results/screenshots are isolated under `.ci-artifacts/browser/<engine>/`, with an aggregate `cross-browser-results.json`. The matrix covers 468 layout cases, 12 confirmation-keyboard cases, desktop/mobile navigation/history focus, route inspection/pagination, date controls, explicit generation and uncertain-response recovery. The separate full backend application journey remains Chromium-only. Fixtures are disposable loopback HTTP servers and do not use the development database.

On hosts unable to launch a bundled engine, run the same required matrix in the official Linux Playwright image matching the lockfile version:

```sh
ACCEPTANCE_USE_CONTAINER=1 ./scripts/run-ci.sh
```

This opt-in container runs only frontend fixtures, has no external network access, and mounts the checkout for built assets and test evidence. Docker must be running and the matching image must be available; failures are not skipped. Node 24.20.0 and Java 25 remain required for the host CI pipeline. GitHub's Ubuntu job installs all three engines and runs them directly. Native Safari/iPhone, screen-reader and native browser-zoom acceptance remain separate checks. See [FRONTEND_ACCEPTANCE.md](FRONTEND_ACCEPTANCE.md) for observed results and limitations.
