#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# != 1 || ( "$1" != check && "$1" != baseline-version-1 ) ]]; then
  echo 'Usage: scripts/schema-adoption.sh check|baseline-version-1; read DATABASE_MIGRATIONS.md first.' >&2
  exit 64
fi
schema_jar=${SCHEMA_APPLICATION_JAR:-target/schedulingApp-0.0.1-SNAPSHOT.jar}
if [[ ! -f "$schema_jar" ]]; then
  echo 'Build/obtain the verified application JAR first. No database action performed.' >&2
  exit 1
fi
schema_java=java
if [[ -n "${JAVA_HOME:-}" ]]; then schema_java="$JAVA_HOME/bin/java"; fi
exec "$schema_java" -Dloader.main=com.mmu.schedulingapp.database.SchemaAdoption \
  -cp "$schema_jar" org.springframework.boot.loader.launch.PropertiesLauncher "$1"
