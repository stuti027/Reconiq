#!/usr/bin/env bash
# Registers the canonical schema against a Schema Registry using the exact bytes the generated
# CanonicalTransaction class produces (see SchemaPrinter). auto.register.schemas=false in production
# means the schema must already be registered before the service can serialize a single record, and it
# must byte-match what the running class sends, not just be semantically equivalent -- registering from
# the raw .avsc file text is not the same thing (see build-challenges-log.md #21).
#
# Usage: scripts/register-schema.sh [registry-url]   (default: http://localhost:8081)

set -euo pipefail
cd "$(dirname "$0")/.."

REGISTRY_URL="${1:-http://localhost:8081}"
SUBJECT="txn.canonical-value"

mvn -q compile

SCHEMA=$(mvn -q exec:java -Dexec.mainClass=com.reconiq.normalization.schema.SchemaPrinter -Dexec.classpathScope=compile 2>/dev/null)

if [ -z "$SCHEMA" ]; then
  echo "Failed to print the schema (is the exec-maven-plugin configured? see pom.xml)." >&2
  exit 1
fi

echo "Registering against ${REGISTRY_URL}, subject ${SUBJECT} ..."
node -e "console.log(JSON.stringify({schema: process.argv[1]}))" "$SCHEMA" | \
  curl -sf -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
    --data @- "${REGISTRY_URL}/subjects/${SUBJECT}/versions"
echo
echo "Done."
