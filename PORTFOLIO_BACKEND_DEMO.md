# Reproducible backend portfolio demo

Run a fictional scheduling workflow through the real authenticated backend APIs, without starting the frontend, hosting the application, using your development database or making paid Maps requests.

## Run

Prerequisites: Java 25 (`JAVA_HOME` configured), Docker running, Python 3 and this repository's Maven wrapper. The runner uses MySQL 8.4.11 and the existing pinned build tooling. Initial Maven/npm/image downloads may require internet; the demo itself makes no live Maps/geocoding requests.

```sh
./scripts/run-backend-demo.sh
```

On the current macOS setup, if Java 25 is not already selected:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  ./scripts/run-backend-demo.sh
```

The command creates a fresh, volume-free MySQL container on a random loopback port, builds the application and runs the API walkthrough on another random loopback port. It does not bind 8081/5174 or use the managed local database. Container/database arguments are not accepted. The reserved demo schema must be new; creation refuses an existing schema instead of adopting or clearing it.

This is a short-lived automated demonstration, not a persistent interactive server. When it finishes, the HTTP server stops and the script removes its own MySQL container. Normal errors and handled interrupts also trigger cleanup. No database volumes are created. Repeating the command starts fresh data, rather than resetting your development data.

For a timed walkthrough and likely interview questions, see [PORTFOLIO_PRESENTATION.md](PORTFOLIO_PRESENTATION.md). The demo command remains an automated API demonstration, not a persistent interactive session.

## What to show an employer

| Stage | Backend behaviour | Expected result |
| --- | --- | --- |
| Fictional setup and authentication | Two depots, three drivers, three recipients; real cookie sessions and CSRF | One administrator and linked driver accounts authenticate |
| Geographic selection | One delivery near the higher-ID East driver | East is selected despite West having the lower ID |
| Window assessment | Same saved route assessed departing at 08:00 and 10:00, with ten-minute service | 3,000 seconds waiting versus 2,400 seconds late; no additional provider calls or assignment changes |
| Driver lifecycle | Owning driver completes the delivery | Route becomes COMPLETED; driver becomes AVAILABLE |
| Allocation and history | Five new deliveries across three available drivers | Counts 2/2/1; each delivery appears once; all routes complete; four historical routes remain |

The fixed fictional delivery date is 2 January 2026. The planning cutoff includes that date, so the scenario remains reproducible rather than depending on today's date. Accounts, depots and recipient coordinates are seeded through internal fixture/provisioning code. Delivery preparation, generation, route reads, assessments, driver-owned reads/completion and availability/history checks use actual HTTP APIs and normal security filters. Direct fixture setup is not a public registration endpoint.

The walk-through does not certify globally optimal routes, actual road feasibility or live traffic accuracy. Geographic selection/allocation still use local great-circle ranking; count balancing does not enforce vehicle load capacity.

## Simulation is explicit and isolated

Every selected leg uses **SIMULATED DEMO: 10 minutes and 2.5 km**, including zero-distance/repeated stops. These deliberately fixed values are demonstration inputs, not real road measurements. Route detail travel text labels the simulation; the result report repeats that label. Assessment responses remain hypothetical estimates based on saved durations.

The substitute `MapsProvider` is defined only inside `BackendPortfolioDemoTest` test-support configuration, imported by that test with the explicit `portfolio-demo` profile. It has no network transport, and live geocoding is unavailable. It is not part of the normal application configuration. The runner inspects the packaged JAR and fails if demo-support classes appear in it; activating a profile on the normal JAR cannot install this test provider. The production-source provider is unchanged.

The demo generates temporary credentials in memory, never prints them and does not store session cookies, CSRF tokens or passwords in its report. Fictional addresses/contact details are not personal data.

## Evidence and cleanup

- `.ci-artifacts/portfolio-demo/result.json`: completion marker, explicit simulation label and verified workflow results. It is marked complete only after all assertions pass.
- `.ci-artifacts/portfolio-demo/run.log`: build/test diagnostics for the standalone runner.
- `target/surefire-reports/`: normal test reports.

Successful output lists four PASS stages and confirms that demo support is excluded from the application JAR. Evidence is ignored by Git. A failed run exits nonzero; do not treat a partial report as a successful demo. The runner removes an old completion report before starting.

To inspect remaining demo containers, use `docker ps -a --filter name=portfolio-backend-demo-`. The runner only removes the unique container it created; it does not remove other databases or containers.

The normal full CI pipeline also runs this scenario. With the disposable MySQL URL it creates its own new schema in that container; without that test URL it uses isolated H2 for a quick rehearsal (`./mvnw -Dtest=BackendPortfolioDemoTest test`). CI uploads the scenario report with its other test artifacts. Frontend and hosting work remain paused.

## Final local verification

Local verification passed the H2 rehearsal, two fresh standalone MySQL runs with identical semantic reports, cleanup checks and the complete CI pipeline: 603 backend tests, 434 frontend tests, eleven disposable MySQL suites, 57 tooling tests, the application Chromium journey and all required cross-browser fixtures. The final CI demo report matches both standalone reports. The packaged JAR excludes demo support. Dependency scanning found zero advisory matches across 392 package/version pairs; secret scanning found zero blocking findings and two acknowledged historical findings. Hosted GitHub Actions were not verified.
