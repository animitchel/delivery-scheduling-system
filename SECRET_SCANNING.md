# Repository secret scanning

Run `python3 scripts/check-secrets.py` from a full checkout. CI runs the same check before application tests, and checkout uses `fetch-depth: 0`.

Gitleaks 8.30.1 is downloaded from its official release and verified against committed SHA-256 checksums. Supported hosts are macOS/Linux on arm64/x64. Each run re-extracts the executable from the verified archive. Installation/network failures, shallow history, missing tracked files, symlinks and scanner errors fail the check.

The check scans a temporary copy of tracked working files and every commit reachable from HEAD. It does not copy ignored private environment/configuration files. New files must be staged to enter the tracked snapshot. Inline allow comments and repository `.gitleaksignore` files cannot suppress findings. The explicit config extends the scanner's default rules.

Raw scanner output is captured and never printed. Temporary reports are deleted. `.ci-artifacts/secrets/summary.json` retains only rules, paths, line numbers, commit IDs, fingerprints and acknowledgement status; CI uploads this location-only report for seven days. Incomplete scans leave a failing summary.

## Reviewed historical credential

The initial scan found the old Google Maps credential in `GeocodingService.java:15` and `DistanceCalculator.java:16`, both in commit `3b80d8fa8f81112b255357db990aae3eb42438f2`. The repository owner confirmed revocation or rotation on 2026-10-14. Provider state was not independently verified, and no provider requests were made.

`security/secret-exceptions.json` acknowledges only those exact historical commit/path/rule/line fingerprints. These acknowledgements never apply to the current snapshot, a different commit or another location. No credential values or raw baseline reports are stored. History is preserved. A newly exposed credential must be revoked at its provider before any historical acknowledgement is reviewed; deleting it from code is insufficient.

## Coverage limits

Pattern matching is not proof that the repository contains no secrets. Default rules may miss custom credentials, binary/archive contents, obfuscated values and low-entropy passwords. Only tracked regular files and HEAD-reachable history are covered; ignored files and unrelated refs are excluded. Maintain private configuration outside Git and review scanner/config/exception changes carefully.

GitHub branch enforcement remains blocked as documented in `CI_MERGE_GATE.md`; this CI check does not override that repository-plan limitation.
