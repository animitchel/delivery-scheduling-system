#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check-secrets.py
python3 -m unittest discover -s scripts -p 'test_*.py'
ci_node=$(command -v node)
if [[ "$("$ci_node" --version)" != "v24.20.0" ]]; then echo 'CI requires Node 24.20.0.' >&2; exit 1; fi
export SESSION_BROWSER_ARTIFACTS_DIR="$PWD/.ci-artifacts/browser"
# Remove previous evidence, not the runtime or any application data.
rm -rf target/surefire-reports target/frontend-reports .ci-artifacts/browser
mkdir -p .ci-artifacts/browser
./scripts/test-account-mysql.sh \
  "-Dsession.browser.node=$ci_node" \
  "-Dsession.browser.modules=$PWD/tests/browser/node_modules" \
  "-Dsession.browser.artifacts=$SESSION_BROWSER_ARTIFACTS_DIR"
python3 scripts/verify-ci-results.py
"$ci_node" tests/browser/test-workspace-reflow.cjs
"$ci_node" tests/browser/test-render-recovery.cjs
"$ci_node" tests/browser/test-admin-route-directory.cjs
"$ci_node" tests/browser/test-generation-mobile-review.cjs

./scripts/check-dependencies.sh
