# Database migrations and deliberate adoption

## Scope and configuration

Flyway core and its MySQL module now use Spring Boot 4.1.1's managed **12.4.0** version. The earlier 11.20.3 override is removed; the supported framework baseline supplies aligned migration dependencies. See [SPRING_BOOT_UPGRADE.md](SPRING_BOOT_UPGRADE.md). MySQL/JPA remain the persistence stack. `V1__initial_schema.sql` creates all eight application tables, including the ordered route membership and the already reviewed account changes in `db/changes/001` and `002`. Those historical files remain for old regression fixtures; do not run them on a V1-managed database.

Activate `schema-managed` only for a fresh database or a deliberately checked/baselined existing database. The base configuration leaves Flyway disabled for compatibility with existing private local configuration and the Hibernate-created test fixtures. This is an adoption boundary, not a production setting: deployed environments must explicitly enable `schema-managed` after rehearsal. It supplies:

- Flyway migration execution and checksum/naming validation.
- `baseline-on-migrate=false` and `clean-disabled=true`.
- Hibernate `ddl-auto=validate`.

The migration strategy rejects `update`/`create` and unsafe Flyway overrides before migrating. Higher-priority external configuration can override profile properties, so a conflicting private local `ddl-auto=update` will fail explicitly. Replace that property with `validate` only as part of the checked adoption procedure. Do not use Hibernate schema updates as a recovery tool.

Spring initializes Flyway before JPA. See [Spring database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html). Non-empty databases without migration history are refused; [automatic baselining](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting) deliberately remains off.

## Fresh database

Use an empty, separately created MySQL 8.4 database and the verified application JAR. Supply connection credentials through private configuration/environment, not Git or command arguments. For local development, use the revised `application-local.properties.example` and `local,schema-managed` profiles. Your existing development files/data are not changed by this PR.

```sh
java -jar target/schedulingApp-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,schema-managed
```

V1 runs once and is recorded in `flyway_schema_history`; restart validates migration history and the Hibernate mappings. V1 contains no application data or provisioned credentials. Account provisioning remains the existing separately configured workflow. Keep schema DDL permissions scoped to this database; separate deployment migration credentials from the runtime account where required by the deployment policy. This PR does not provision database users or deployment infrastructure.

## Existing database: inspect first

No live database was connected to during implementation. The checked legacy fixture is Hibernate-generated application tables plus reviewed account changes 001/002; it cannot establish the shape of your actual database.

1. Identify the database, application version and exact verified artifact. Obtain a backup and prove restoration to an isolated copy before changing the original. Keep the old artifact and private configuration available.
2. Inspect the actual schema read-only, including `SHOW CREATE TABLE` for the eight tables, MySQL version, collation, privileges and any existing migration history. Compare with the contract and investigate drift. Do not print account/password data.
3. Run the packaged preflight tool on the restored copy, then on the actual database using read-only credentials. Set `SCHEMA_DATABASE_URL` (a JDBC MySQL URL selecting one database), `SCHEMA_DATABASE_USER` and `SCHEMA_DATABASE_PASSWORD` through your private environment. Do not embed passwords in the JDBC URL. `SCHEMA_APPLICATION_JAR` optionally chooses an absolute verified JAR path.

```sh
./scripts/schema-adoption.sh check
```

The check performs only `information_schema` queries. It compares tables/engines, column types/nullability/auto-increment, ordered primary/unique keys, foreign-key relationships/delete rules, enforced checks and the account role's ASCII binary collation with `db/schema-v1-contract.json`. Constraint names and extra non-unique indexes may differ. For the ASCII ADMIN/DRIVER literals only, equivalent ASCII/UTF-8/Latin-1 charset introducers are normalized; foreign-key NO ACTION/RESTRICT are equivalent on MySQL. Defaults, general text collations, triggers, grants, data quality and performance are outside the automated check and require manual review. The tool rejects additional tables/history, extra unique constraints, missing checks and differing types rather than guessing equivalence. A safe rejection may still represent a compatible variant; investigate and prepare an independently reviewed reconciliation, not a bypass.

The check does not alter tables, clean data, create history or baseline. Use full schema visibility: insufficient permissions should produce a mismatch, not approval. Do not continue if it fails. Missing historical account changes, incompatible enum definitions or schema drift need a reviewed reconciliation plan and copy-based tests first.

## Explicit baseline and rehearsal

Baseline is a write operation, separate from the read-only check. It records version 1 in Flyway history; it does not execute V1 or change application records. Never baseline an empty/incompatible schema or use baseline as a way to suppress an error. The packaged tool repeats the structural check before writing history.

With writers and schema changes stopped, rehearse on the restored copy using an appropriately scoped operator credential:

```sh
./scripts/schema-adoption.sh baseline-version-1
```

This command is deliberately explicit and has no default action. It is only suitable after approval for the identified database, a verified backup and successful preflight. Freeze schema changes throughout preflight/baseline/startup to avoid drift between the read-only connection and Flyway's connection. An existing history table is refused by preflight; do not repeat baseline or remove history to rerun it.

Start the same artifact with `schema-managed` and Hibernate validation, then verify account access, audit records, warehouse/operator/recipient identities, delivery status, route membership/order and history against the restored data. Rehearse restart and compare counts and representative values. Only after this passes should an approved maintenance window repeat the procedure on the actual database. This PR runs none of these actions on an existing application database.

## Future changes and recovery

Treat committed versioned migrations and the V1 contract as immutable once deployed. Add V2/V3 files for new changes; do not edit V1, disable validation, run `repair` automatically or delete migration history after a checksum failure. New constraints require a read-only data preflight and explicit remediation first.

MySQL DDL can commit implicitly. A failed migration may leave partial changes; stopping the application is not rollback. Inspect the actual state, keep the failure evidence, and use a reviewed forward correction or restore the verified backup into an isolated/replacement database. Rehearse recovery and revalidate before allowing writes. Code rollback alone does not undo DDL, and the old artifact may use automatic Hibernate updates: do not restart it against the managed database without a validated configuration/compatibility plan.

For this adoption-only V1, application tables/data are preserved; the new history table is the only baseline write. Backup restoration and deployment rollback still need operational rehearsal before production approval.

## Verification

`DatabaseMigrationMySqlTest` runs through the existing disposable MySQL harness and is required by CI's test-evidence gate. It tests fresh install with real Hibernate, restart, populated legacy adoption, non-empty refusal, missing columns/check constraints/foreign keys, cross-database references, legacy client charset notation, unsafe configuration and migration checksum drift. The full CI run also covers existing backend/frontend/browser workflows. No Maps provider, local application database or production credential is used.

Implementation validation on 14 October 2026: the final full runner passed locally on macOS/Java 25 with Flyway 11.20.3: **534 backend tests** (including **11 migration tests**), **349 frontend tests**, all **nine MySQL suites**, the full bundled-Chromium journey and **11 CI evidence-checker tests**. The final packaged JAR independently passed both operator-wrapper commands against another disposable MySQL 8.4.11 container, with exactly one baseline history row and no MySQL-version compatibility warning. Shell syntax and Git whitespace checks passed. GitHub-hosted PR execution and actual-database adoption/backup recovery remain to be verified separately.
