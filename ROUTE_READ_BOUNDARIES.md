# Route-read transaction boundaries

The legacy route-list reads now load entities and build response DTOs inside read-only, repeatable-read service transactions:

- `RouteService.getAllRoutes()` — GET `/api/v1/scheduling-app/routes`.
- `RouteService.getRouteByDriverId(...)` — GET `/api/v1/scheduling-app/routes/operator/{operatorId}`.

Previously, the repository operation could finish before `RouteMapper` accessed the lazy delivery collection. With Open Session in View (OSIV) disabled, direct service calls threw `LazyInitializationException` and both HTTP reads returned 500. Four regressions reproduced those failures before the fix. Keeping loading and mapping inside the service transaction makes the returned DTOs usable after the persistence context closes. Repeatable-read matches the existing single-route read boundary.

The global OSIV setting is unchanged. JSON shapes, saved stop order, administrator access and legacy empty/missing-operator semantics remain: all routes returns 200 with an empty array, an existing operator without routes returns 204, and a missing operator retains the existing 400 response. This change does not add pagination, reduce query counts, alter generation or replace the mapper.

Regression coverage includes direct service calls without a caller transaction, HTTP reads with OSIV disabled, two-stop order, access/empty responses and real MySQL service calls. Before/after DTOs, delivery assignments/statuses and operator availability remain equal; read paths make no provider calls. Tests use only H2 and disposable MySQL databases.

Frontend and hosting work remain paused; this is a backend reliability correction for the dissertation/portfolio project.

## Final local validation

Full local CI passed with 589 backend tests, 434 frontend tests, ten disposable MySQL suites, 57 tooling tests, the complete application Chromium journey and all required cross-browser fixture suites. Four new cases reproduced lazy-initialisation failures/HTTP 500s before the fix; those cases plus the legacy access/empty-response and MySQL checks now pass. Dependency scanning found zero advisory matches across 392 package/version pairs; secret scanning found zero blocking findings and two previously acknowledged historical findings. Hosted GitHub Actions were not verified.
