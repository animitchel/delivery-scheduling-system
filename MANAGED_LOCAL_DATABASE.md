# Fresh Flyway-managed development database

This is a separate local setup for a clean installation. It preserves the original `delivery-scheduling-local` container, volume and private files. MySQL/JPA/Flyway versions remain the verified application versions.

## What was found

Read-only inspection on 14 October 2026 found MySQL 8.4.11 at `127.0.0.1:3307/scheduling_dev`: seven application tables had zero rows, while `user_accounts` had one row. No account contents or credentials were printed. The original database was not dropped, baselined or changed. A fresh database therefore avoids an unnecessary schema-adoption exercise without discarding the existing account.

## Separate managed setup

| Setting | Managed development setup |
|---|---|
| Compose project | `delivery-scheduling-managed` |
| Database | `scheduling_managed_dev` |
| Application database user | `scheduling_managed_app` |
| MySQL listener | `127.0.0.1:3308` |
| Persistent volume | `delivery-scheduling-managed_managed_mysql_data` |
| Private configuration | `.local/managed/.env.mysql.local` and `.local/managed/application-local.properties` |
| Backend | `127.0.0.1:8081` by default |
| Frontend | `127.0.0.1:5174` with the existing Vite proxy |

Java 25, Python 3, Docker Compose and a verified application JAR are required. The helper does not read or overwrite the original root-level development configuration. Its explicit Spring config location loads packaged defaults plus the managed private directory; datasource/profile arguments select only the new database. An occupied backend port is reported without stopping the existing process.

From the repository root:

```sh
python3 scripts/managed-local.py prepare
python3 scripts/managed-local.py up
```

`prepare` creates mode-600 private files with independent random database/root/administrator passwords. Existing files are retained. If the managed volume already exists and credentials are missing, preparation refuses to create replacement passwords. Restore the private files instead; changing initialization passwords does not change an existing MySQL user's password. Keep these files outside Git, and retain them when cleaning build outputs. Do not remove the persistent volume to solve a configuration problem.

Build/verify the application using the existing CI runner (see [CI.md](CI.md)); this tests disposable databases and does not seed the managed development database. Then provision the administrator offline:

```sh
python3 scripts/managed-local.py provision
```

Flyway V1 creates the schema on its first run. Hibernate validates it. The existing `local,schema-managed,provision-admin` runner provisions `managed.admin`; repeating it preserves the existing account/password. The generated login password is available privately in `.local/managed/.env.mysql.local` as `ACCOUNT_BOOTSTRAP_PASSWORD`. Do not paste that file into chats, commits or PRs. The backend never receives the MySQL root password. Normal `run` removes bootstrap credentials from the child environment and does not activate the provisioning profile.

## Run the application

Stop your previous backend through its terminal/IDE when ready to switch, then:

```sh
python3 scripts/managed-local.py run
```

For a temporary side-by-side check, choose a free port with `--port 8082`. `--config-dir` and `--jar` can select explicit absolute paths, including when verifying from an isolated worktree. Leave an existing backend running until you intentionally switch; this helper never terminates it.

Start the frontend from `frontend/`:

```sh
SCHEDULING_DEV_BACKEND=http://127.0.0.1:8081 npm run dev -- --port 5174
```

For a side-by-side backend on 8082, set `SCHEDULING_DEV_BACKEND=http://127.0.0.1:8082` instead. The backend serves the built React application directly at `/app/` as well. No frontend framework or production hosting change is introduced.

Use the application screens to create warehouses/operators, recipients and deliveries, then generate and execute routes. Actual geocoding/planning needs your separately configured Maps access; the automated journey uses provider fixtures and incurs no paid provider calls. No operational demo records are left in the new development database by this setup.

Check or stop the managed database while retaining its volume:

```sh
python3 scripts/managed-local.py status
python3 scripts/managed-local.py stop
```

Restart it with `up`; restart the backend with `run`. Stop a backend using `target/` before rebuilding that JAR, then start the newly verified build. For side-by-side verification, `--jar` can select a separate immutable copy so build output is never changed underneath a running process. Never edit deployed V1 or turn on Hibernate update/automatic Flyway baseline. New database changes need new migrations. See [DATABASE_MIGRATIONS.md](DATABASE_MIGRATIONS.md) for migration/recovery rules. This setup is local development; it does not establish production TLS, backup recovery or release readiness.

## Verification

The new managed MySQL HTTP test installs V1, provisions the initial administrator twice without resetting its hash, logs in, creates warehouse/operator/recipient/delivery records through APIs, generates a route, completes it as its assigned driver, verifies driver release and restarts the application with persisted terminal state and one migration-history entry. Maps responses are test fixtures. Existing browser tests continue to verify the React workflow.

Helper tests cover private-file permissions, repeatable credentials, missing credentials for an initialized volume, partial setup, wrong database identity, explicit config isolation and occupied-port preservation. CI runs them through `python3 -m unittest discover -s scripts -p 'test_*.py'`.

Validated locally on 14 October 2026: the full application CI run passed **535 backend tests**, **349 frontend tests**, all **ten MySQL suites**, the browser journey and packaged-resource verification. The final helper/evidence unit run passed **19 tests**. The persistent managed database passed two starts from an immutable packaged JAR, administrator login and React delivery deep-link checks; it retains one administrator, one successful V1 history entry and zero operational/demo records. Both temporary verification backends were stopped. The managed MySQL service remains healthy on 3308; the original backend on 8081 and original database remain in place. GitHub-hosted PR checks remain pending.
