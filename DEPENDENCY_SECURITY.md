# Dependency security checks

## Scope and policy

`./scripts/check-dependencies.sh` generates a fresh resolved Maven JSON tree and a CycloneDX JSON/XML inventory (including test dependencies), matches the packaged JAR's libraries against that tree (and recognizes Boot's manifest-identified, version-aligned packager-added jarmode tools), then queries the public OSV API for exact Maven and npm package/version pairs. It includes provided/compiler dependencies, Java tests, both npm v3 lockfiles, browser tooling and optional platform packages. The application root is excluded from advisory requests. Only dependency names/ecosystems/versions are sent; no source, configuration, credentials or application data is sent.

The CycloneDX files describe the resolved project tree; `inventory.json` additionally includes the packager-added library. The scanner records Maven scopes, physically packaged libraries and frontend runtime/development/browser contexts separately. A development dependency is not silently exempted. All active advisory matches require review; there is no severity-based blanket suppression. Withdrawn records remain in the report without blocking.

The final scan also includes the packager-added Boot tools, bringing coverage to **392 distinct package/version pairs**.

No API key, paid scanner or GitHub administration permission is required. See the [OSV API](https://google.github.io/osv.dev/api/) and its [batch pagination contract](https://google.github.io/osv.dev/post-v1-querybatch/). This queries known advisories, not unknown flaws, malware or complete exploitability. Database/OS images, JDK/Node binaries, CDN assets and Maven plugin transitive libraries are outside this inventory and remain separate review work. In particular, a zero OSV match count is not a claim that every vendor advisory is covered.

## Baseline review on 14 October 2026

The initial project/lock scan covered **391 distinct package/version pairs** and returned **10 advisory matches** in three runtime packages:

| Package before this change | Matches | Compatibility-preserving fix |
| --- | --- | --- |
| Jackson databind 3.1.5 | GHSA-cxp5-3px4-pw24, GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-wv8q-qhhj-9h54 | Jackson BOM 3.1.7 |
| Jackson core 3.1.5 | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 | Jackson BOM 3.1.7 |
| Tomcat embed core 11.0.24 | GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 | Tomcat family 11.0.27 |

`pom.xml` overrides Boot's `jackson-bom.version` and `tomcat.version`, keeping each family aligned and retaining Boot 4.1.1. Remove these narrow overrides when a reviewed Boot release manages equal/newer patched versions. Gson 2.10.1 had no match in this check and is unchanged. Frontend/browser lockfiles are unchanged and had no OSV matches; independent lockfile-only npm audits also reported zero advisories for both.

Upstream Jackson reports establish patched ranges: [forward-reference completion](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24), [polymorphic base validation](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-gx83-3vf8-gh7j), [XML duration/calendar parsing](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-q4xh-88c3-wmh7), [Path provider resolution](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wjgm-6hv5-3cvf), [unknown type-ID caching](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54), [DataInput token errors](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf), and [numeric-string parsing](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89). The [Tomcat vendor security page](https://tomcat.apache.org/security-11.html) also describes later fixes beyond the three OSV matches; 11.0.27 includes the 11.0.25/26 fixes and stays in the existing major/minor line.

Static applicability review found no application use of JsonIdentityInfo/JsonTypeInfo/default typing, XMLGregorianCalendar/DatatypeFactory JSON binding or DataInput JSON parsing in `src/main/java`. The Path advisory requires binding an untrusted Path and a relevant provider; no such request field was found. Legacy numeric coercion and other library internals are not exhaustively traced, so application exploitability is not asserted. `security/SecurityConfiguration.java` uses Spring Security session/form authentication; Tomcat's native DIGEST/FORM authenticator and servlet-container constraints are not the configured application authorization mechanism. Actual deployment/proxy configuration is not verified here. These conditions are reasons to avoid exaggerating exposure, not exceptions from patching. No exploit or production validation was performed.

## Execution and evidence

Build/test/package first, then run:

```sh
./scripts/check-dependencies.sh
```

The complete `./scripts/run-ci.sh` now runs this after its application evidence validator. An advisory or incomplete scan fails the existing **Verify application** job, so the success-only application upload cannot publish from that failed job. The diagnostic upload includes `.ci-artifacts/dependencies/` even on failure. The check does not change branch protection; its previously documented plan/access block remains.

Reports include `inventory.json`, raw public `advisories.json`, normalized `findings.json`, CycloneDX `bom.json`/`bom.xml` and `summary.json` with input hashes, scopes/counts, completion status and UTC timestamp. Maven/lock/policy inputs are read fresh. Generated evidence is cleared before inventory generation; an initial incomplete marker remains if Maven fails. A packaged/resolved version mismatch, empty/missing inventory, malformed/short API response, pagination problem, missing/mismatched advisory detail or network failure cannot establish a clean result. Reports are ignored build artifacts, not committed vulnerability-clearance certificates.

## Exceptions

`security/dependency-exceptions.json` starts empty. An exception must identify the exact ecosystem, name, version, advisory ID and reviewed contexts, plus an explanation, owner, HTTPS review reference and expiry. Expiry is checked against UTC and must be within 90 days; expired, duplicate, unused/stale or differently scoped exceptions fail. There are no active exceptions in this branch. Review reachability and vendor evidence before proposing one; do not use exceptions merely to make CI green.

## Verification

The Python tests exercise inventory/scopes, artifact matching, incomplete API evidence, pagination, service failure, withdrawal handling and exact expiring exceptions. Full backend/MySQL/frontend/browser tests verify the patch overrides preserve behavior. Packaged-library matching also rejects development/compiler libraries in the executable artifact. Separate secret scanning, operational security, vendor advisories outside OSV, and staging/recovery remain open release requirements.

### Recorded result (14 October 2026)

The completed CI runner passed **538 backend tests**, **356 frontend tests**, all **ten MySQL suites**, the bundled browser journey, packaged-resource validation and **52 Python tests**. The final advisory run completed for **392 distinct package/version pairs**, including **79 packaged libraries**, with **zero matches and zero exceptions** in OSV at that timestamp. Supplemental npm lockfile audits reported zero advisories for frontend and browser tooling. This is known-advisory evidence with the coverage limits above, not a vulnerability-free certification.

An independent immutable-JAR check confirmed Jackson core/databind 3.1.7 and Tomcat core/EL/WebSocket 11.0.27, unchanged V1 bytes, fresh disposable MySQL migration, repeated provisioning, schema check/baseline commands, strict HTTP JSON/private errors, login/assets/logout and restart. Temporary servers/containers were removed; persistent application databases were untouched. Workflow lint (checksum-verified actionlint 1.7.12), Python compilation, Bash syntax and Git whitespace checks passed. GitHub-hosted verification remains pending.
