# React administrator route review

Administrator route review opens at `/app/admin/routes` from Routes in the React workspace and the legacy administrator dashboard. Driver execution remains at `/app/driver/routes`. Both screens share the stop cards and native detail dialog; Spring remains authoritative for role permissions, lifecycle transitions and operator release.

## Bounded directory API

`GET /api/v1/scheduling-app/routes/directory?page=0&size=20` is additive. The legacy all-routes API remains compatible. Page numbers are 0–10000 and sizes 1–50; invalid bounds return 400. Results are ordered by scheduled date descending, then route ID descending. Empty/out-of-range pages return empty `routes` with page metadata.

The response contains `{routes, page, size, totalElements, totalPages}`. Each summary contains route ID, operator ID/name, scheduled date, route status, `stopCount` and `finishedCount`. Finished includes completed and cancelled deliveries; an empty route reports zero for both counts. No stop list or recipient data is returned in the directory.

The query uses scalar headers, a count query when needed, and one aggregate restricted to the selected page's route IDs. It does not materialize route, delivery or recipient entities or load off-page stop collections. Mapping and metrics run in one repeatable-read transaction. SQL still needs to order/count the route directory; pagination is not a claim of constant database cost at arbitrary scale. Index/query-plan profiling remains a production task.

Only the selected route fetches its ordered stops through the existing route detail endpoint. Detail mapping now completes within a read transaction, so this path works with Open Session in View disabled. No new schema migration is introduced.

## Review and actions

The screen supports directory pagination, explicit route selection with focus/scroll to its summary, status/progress, ordered stops and honest missing-travel labels. Stop details fetch delivery and recipient data on demand, showing address/contact, scheduling windows, priority and instructions. Responses are validated at runtime; changed recipient/operator relationships or stop status require a route reload. Closing, switching routes or unmounting cancels requests and clears private details. Directory/selected-route reads and stop details have 30-second abort budgets.

Completion uses the existing administrator PATCH with only `deliveryStatus`. Cancellation requires confirmation. Only in-progress stops on in-progress routes offer enabled actions; completed/cancelled work cannot be reopened here. Pending writes pause route controls, shell navigation, session refresh and sign-out. Writes have a 15-second abort budget, including CSRF acquisition, and never automatically retry.

After success the directory and selected route reload from Spring. Missing/stale targets or uncertain outcomes clear the previous route and require explicit reload before another write. A driver's competing terminal update is handled as a conflict, not overwritten. Finishing the final stop completes the route and releases the operator under the existing shared lifecycle rules. Session expiry removes the workspace; mandatory password setup uses the existing protected page.

## Development, verification and rollback

Your local ports are Spring 8081 and Vite 5174. From `frontend/`, run:

```sh
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
```

Open `http://127.0.0.1:5174/app/admin/routes`. Vite defaults match these ports; production uses relative API URLs and Spring-packaged static assets. Tests run against disposable H2/MySQL and randomly bound local servers, not your running application or database.

Frontend tests cover runtime contracts, role guards, stale detail/route responses, paging changes, cancellation, duplicate writes, read/write budgets, session errors and conflict/uncertain-outcome recovery. H2 tests verify stable paging, scalar query/statement bounds, metrics, empty pages, permissions and OSIV-disabled detail mapping. Disposable MySQL verifies the aggregate and terminal metrics. Chrome exercises administrator/driver concurrent changes, persisted completion/cancellation, operator release, pagination, native-dialog keyboard behavior and responsive widths while retaining prior account and driver journeys.

Run `npm --prefix frontend run check` and the established Java 25 `scripts/test-account-mysql.sh` suite with optional `session.browser.*` arguments. The protected legacy `/html/operators.html` page remains a rollback path. Route generation, recipient/delivery editing, deletion and archive management are separate tasks. This branch does not deploy, migrate a live database or change live accounts.
