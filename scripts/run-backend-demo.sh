#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 0 ]]; then
  printf 'Usage: ./scripts/run-backend-demo.sh\nRuns a short-lived isolated MySQL/API demonstration; no database arguments are accepted.\n'
  if [[ $# -eq 1 && "$1" == '--help' ]]; then exit 0; fi
  exit 2
fi
command -v docker >/dev/null
command -v python3 >/dev/null
container="portfolio-backend-demo-$(date +%s)-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p .ci-artifacts/portfolio-demo
rm -f .ci-artifacts/portfolio-demo/result.json
printf 'Starting isolated portfolio demo (fictional data and SIMULATED travel estimates).\n'
docker run --rm -d --name "$container" -e MYSQL_ROOT_PASSWORD=disposable-test-password -e MYSQL_ROOT_HOST=% -e MYSQL_DATABASE=account_foundation_test -p 127.0.0.1::3306 mysql:8.4.11 >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if docker exec "$container" mysqladmin ping -h 127.0.0.1 -uroot -pdisposable-test-password --silent >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then printf 'Disposable demo MySQL did not become ready.\n' >&2; exit 1; fi
port=$(docker port "$container" 3306/tcp | awk -F: '{print $NF}')
printf 'Running authenticated API walkthrough; build output: .ci-artifacts/portfolio-demo/run.log\n'
if ! ./mvnw -B verify -Dtest=BackendPortfolioDemoTest "-Daccount.mysql.url=jdbc:mysql://127.0.0.1:$port/account_foundation_test" > .ci-artifacts/portfolio-demo/run.log 2>&1; then
  tail -n 50 .ci-artifacts/portfolio-demo/run.log >&2
  exit 1
fi
python3 - <<'PY'
import json
import zipfile
from pathlib import Path
report=json.loads(Path('.ci-artifacts/portfolio-demo/result.json').read_text())
assert report['complete'] and report['database']=='MySQL'
assert report['simulation'].startswith('SIMULATED DEMO')
assert len(report['steps'])==4 and all(step['passed'] for step in report['steps'])
with zipfile.ZipFile('target/schedulingApp-0.0.1-SNAPSHOT.jar') as jar:
    assert not any(name.startswith('BOOT-INF/classes/com/mmu/schedulingapp/demo/') for name in jar.namelist()), 'Demo support must not ship in the application'
print(report['simulation'])
for number,step in enumerate(report['steps'],1):
    print(f"{number}. PASS: {step['name']}")
print('PASS: demo provider/fixtures excluded from the application JAR.')
print('Report: .ci-artifacts/portfolio-demo/result.json')
PY
printf 'Walkthrough finished. Temporary HTTP server stopped; disposable MySQL is removed on exit.\n'
