#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Clear only generated dependency evidence, never application data/configuration.
rm -rf target/dependency-security .ci-artifacts/dependencies
mkdir -p target/dependency-security .ci-artifacts/dependencies
printf '%s\n' '{"complete":false,"error":"Dependency inventory or advisory checking did not finish."}' > .ci-artifacts/dependencies/summary.json
./mvnw -B org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree \
  -DoutputType=json -DoutputFile=target/dependency-security/maven-tree.json \
  org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
  -DincludeTestScope=true -Dcyclonedx.skipAttach=true
cp target/bom.json target/bom.xml .ci-artifacts/dependencies/
python3 scripts/check-dependencies.py
