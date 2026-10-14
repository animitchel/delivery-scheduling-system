# Supported Spring Boot baseline

## Scope and decisions

Spring Boot **4.1.1** replaces 3.5.16. Maven Central metadata confirms this stable release; 4.2 milestones are deliberately excluded. Java 25, React/TypeScript/Vite, Spring MVC, Spring Security sessions, JPA and MySQL remain in use.

The [official Boot migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) describes the modular starters and JSON transition. This change uses MVC, JPA and security test starters, the Flyway starter, and the new server/Flyway/test package locations. Dependencies now follow the Boot BOM: Hibernate 7.4.5.Final, Flyway 12.4.0, MySQL Connector/J 9.7.0, Jackson 2.21.5 and Jackson 3.1.5. The prior Flyway override is removed. Versions come from the [4.1.1 dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom). The resolved inventory can be regenerated with `./mvnw dependency:tree -DoutputFile=target/spring-boot-upgrade-dependencies.txt`.

## JSON compatibility boundary

The deprecated `spring-boot-jackson2` module and `spring.http.converters.preferred-json-mapper=jackson2` preserve the existing strict request deserializers. Boot 4 ships Jackson 3 too, so merely adding the compatibility dependency is insufficient. A regression asserts that MVC selects Jackson 2 and excludes Jackson 3 converters for concrete write DTOs (Spring Data may retain a Jackson 3 converter restricted to projection interfaces); existing malformed, duplicate, unknown and server-owned field tests verify actual HTTP behavior and private error responses.

This is a temporary migration bridge, not the final JSON architecture. A separate focused Jackson 3 PR must port custom deserializers and offline JSON readers, retain rejection/privacy/date-time contracts, and remove both the compatibility module and preference property. Do this before that deprecated module is removed upstream.

## MySQL concurrency compatibility

Hibernate 7.4 refreshes an already exclusively locked entity using a read without another exclusive SQL lock; see [the refresh implementation](https://github.com/hibernate/hibernate-orm/blob/7.4.5/hibernate-core/src/main/java/org/hibernate/event/internal/DefaultRefreshEventListener.java). Under MySQL REPEATABLE_READ, an earlier read can establish a snapshot that causes this refresh to restore old credential state. Existing concurrent reset and reset-versus-password-change regressions detected lost authentication revisions during the upgrade.

Account-management write transactions now explicitly use READ_COMMITTED, matching the existing warehouse/operator/recipient/planner write policy. Pessimistic row locks and refreshes remain. Password verification and revision checks still run after acquiring those locks. Concurrent tests require monotonic revisions, one final credential and matching audit events; assertions are unchanged.

## Schema and test compatibility

The V1 SQL and schema contract are unchanged. Managed installations continue to migrate with Flyway and validate with Hibernate. Historical adoption remains explicit and refuses unsafe or incompatible schemas.

Hibernate 7 adds a nonnegative order-column check when generating test DDL. Hibernate 7 also changes generated LocalTime columns from TIME(6) to TIME(0). The legacy fixture restores TIME(6) and removes that generated check to reconstruct the reviewed Hibernate 6 schema, then applies the independently reviewed historical account SQL. This is confined to the disposable test fixture; it does not relax the adoption checker or alter any application database. The account JPA slice explicitly uses H2 MySQL mode because H2's newer strict default mode rejects the existing MySQL `datetime(6)` definitions.

## Dependency evidence

A CycloneDX inventory is generated into ignored build output; it is evidence for subsequent advisory review, not a vulnerability-clearance claim:

```sh
./mvnw dependency:tree -DoutputFile=target/spring-boot-upgrade-dependencies.txt
./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -Dcyclonedx.skipAttach=true
```

The JSON/XML inventories are `target/bom.json` and `target/bom.xml`. The unchanged frontend lockfile remains the frontend dependency inventory. Regenerate these against the final revision before reviewing advisories.

## Verification and release boundary

Run `./scripts/run-ci.sh` with the documented Java and Node versions. It exercises frontend type/lint/unit checks, backend tests, ten disposable MySQL suites, browser login/CSRF/rendering/logout, packaging and independent evidence validation. It includes fresh migration, restart, populated legacy adoption, unsafe-settings refusal, account concurrency and the provision-to-delivery-completion/restart journey.

The running local backend, original database, managed development database and private configuration are not changed by this branch. No deploy is performed. GitHub CI execution, required-check enforcement, staging HTTPS/session checks, advisory review of the resolved dependencies, backup restore and release rollback remain separate release gates. A framework upgrade and local passing tests do not establish production readiness.

### Recorded local result (14 October 2026)

The final full runner passed 536 backend tests, 349 frontend tests, all ten MySQL suites, the browser journey, packaged-resource verification and 19 Python tests. The final dependency tree and CycloneDX generation succeeded. A separate immutable copy of the final JAR passed fresh MySQL migration, repeated administrator provisioning, the operator-wrapper check/baseline commands against an unversioned V1-shaped schema, two HTTP server starts with login/assets/logout, and persisted account/history checks. All verification containers and temporary servers were removed. Persistent development services and configuration were preserved.
