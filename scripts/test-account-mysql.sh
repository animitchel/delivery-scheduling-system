#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="account-foundation-test-$(date +%s)-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Only this disposable container is changed; no existing application database is used.
docker run --rm -d --name "$container" -e MYSQL_ROOT_PASSWORD=disposable-test-password -e MYSQL_ROOT_HOST=% -e MYSQL_DATABASE=account_foundation_test -p 127.0.0.1::3306 mysql:8.4.11 >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if docker exec "$container" mysqladmin ping -h 127.0.0.1 -uroot -pdisposable-test-password --silent >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then echo "Disposable MySQL did not become ready." >&2; exit 1; fi
port=$(docker port "$container" 3306/tcp | awk -F: '{print $NF}')
mvn -B verify "-Daccount.mysql.url=jdbc:mysql://127.0.0.1:$port/account_foundation_test" "$@"
