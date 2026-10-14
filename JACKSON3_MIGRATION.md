# Jackson 3 migration

## Scope

This focused follow-up to merged PR #34 completes the JSON transition on Spring Boot 4.1.1. It removes `spring-boot-jackson2` from `pom.xml` and removes the `jackson2` HTTP converter preference from `application.yml`. The MVC starter supplies Boot-managed Jackson 3.1.5; no separate version override is added.

Custom delivery/route object readers and warehouse/operator/recipient field readers now use `tools.jackson`, `ValueDeserializer`, `DatabindException`, property-name tokens and string accessors. Jackson 3 exceptions are unchecked, so custom deserializer signatures no longer declare `IOException`. Geocoding JSON, the offline schema-contract reader and test mappers use Jackson 3 too. Standalone JSON mappers use the JsonMapper builder; geocoding reuses one immutable mapper.

The shared annotations remain in `com.fasterxml.jackson.annotation`, as required by the [official Jackson migration notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0). Their presence does not indicate that Jackson 2 databind or core is still required.

## Contracts to preserve

- Reject malformed documents, duplicate, unknown and server-owned fields.
- Reject numeric/string coercion in strict write requests, invalid enums and integer overflow.
- Keep coordinate decimals exact and date/time responses in ISO string form.
- Keep account credentials and private request/provider contents out of responses and error bodies.
- Retain API response structures, authorization, session/CSRF handling and provider-failure behavior.
- Keep schema-adoption JSON readable without changing V1 SQL or its structural contract.

`AccountManagementIntegrationTest` verifies that the first MVC converter accepting each of the five scheduling write DTOs uses Jackson 3 and that no Jackson 2 converter handles it. Existing strict API/privacy tests remain in place. Added regressions exercise the configured mapper's coordinate/temporal behavior and real HTTP rejection of trailing JSON documents, trailing private text and overflowing recipient IDs without writes or Maps calls.

## Verification

Run `./scripts/run-ci.sh` with the documented Java/Node versions. Its evidence gate requires every backend and frontend test suite, all disposable MySQL suites, the browser journey and packaged resources. Independently check the packaged JAR's schema CLI and fresh provisioning/login/restart workflow against disposable MySQL.

Regenerate the dependency tree with `./mvnw dependency:tree -DoutputFile=target/jackson3-dependencies.txt` and the CycloneDX inventory using the command in [SPRING_BOOT_UPGRADE.md](SPRING_BOOT_UPGRADE.md). Verify that no `spring-boot-jackson2`, Jackson 2 `jackson-core` or `jackson-databind` is resolved or packaged. The shared annotations artifact is expected to remain.

This change requires no database migration or frontend changes. Verification uses disposable databases; private configuration and persistent development databases remain unchanged. GitHub CI enforcement and staging/production release gates remain separate work.

## Recorded local result (14 October 2026)

The final full runner passed **538 backend tests**, **349 frontend tests**, all **ten MySQL suites**, the browser journey, packaged-resource checks and all **19 Python tests**. Dependency tree and CycloneDX generation succeeded; the inventory contains 91 components and no Jackson 2 core/databind or compatibility module. This inventory is not an advisory-clearance claim.

A separate immutable copy of the final JAR confirmed Jackson 3.1.5 packaging and unchanged V1 bytes, then passed fresh disposable MySQL migration, repeated administrator provisioning, the schema-adoption wrapper's check/baseline commands, private HTTP errors for invalid JSON, login/assets/logout and restart. All temporary servers and containers were removed. Persistent development databases/private configuration were not modified. GitHub-hosted PR verification remains pending.
