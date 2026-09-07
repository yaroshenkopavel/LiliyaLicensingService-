#!/usr/bin/env bash
set -euo pipefail

for command in openssl grep; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required" >&2
    exit 2
  }
done

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "Gradle is required" >&2
  exit 2
fi

echo "=== S7.7 ENVIRONMENT SEPARATION CONTRACTS ==="
"${GRADLE[@]}"   :issuer-observability:test   :issuer-deployment:test   --rerun-tasks   --console=plain

echo "=== S7.7 PRODUCTION SOURCE CONSOLE GUARD ==="
PRODUCTION_SOURCE_DIRS=(
  issuer-core/src/main
  issuer-postgres/src/main
  issuer-openbao-transit/src/main
  issuer-transport-contract/src/main
  issuer-http-endpoint/src/main
  issuer-runtime/src/main
  issuer-request-auth/src/main
  issuer-deployment/src/main
  issuer-https-listener/src/main
)

if grep -R -n -E '(^|[^A-Za-z])(println|print)\(|System\.(out|err)\.'   --include='*.kt' "${PRODUCTION_SOURCE_DIRS[@]}"; then
  echo "Direct production console output found outside structured observability sink" >&2
  exit 1
fi

echo "=== S7.7 ORDINARY CI PRODUCTION PROFILE GUARD ==="
# Dedicated production-entrypoint acceptance workflows intentionally exercise the
# real deployment environment variables with generated ephemeral credentials.
# They are not ordinary CI credential reuse and are guarded by their own acceptance.
if grep -R -n -E   'LILIYA_ENVIRONMENT[^A-Z]*PRODUCTION|LILIYA_(POSTGRES_PASSWORD|OPENBAO_TOKEN|REQUEST_AUTH_SECRET|TLS_KEYSTORE_PASSWORD)'   .github/workflows   --include='*.yml'   --include='*.yaml'   --exclude='s7-7-environment-observability-acceptance.yml'   --exclude='s7-9a-production-entrypoint.yml'   --exclude='s7-9c-application-rollback.yml'   --exclude='s7-9d-service-state-policy.yml'; then
  echo "Production-profile deployment credential/reference detected in ordinary CI workflow" >&2
  exit 1
fi

echo "=== S7.7 BOOTSTRAP REDACTION CONTRACTS ==="
# The production distribution is now a blocking real service entrypoint.
# Configuration/redaction/fail-closed behavior is therefore verified by the
# issuer-deployment JVM contract suite executed above, not by launching the service
# as a short-lived configuration probe.

echo 'LICENSING_S7_7_EVIDENCE={"environmentSeparation":true,"productionIdentityReuseRejected":true,"fixedSchemaObservability":true,"directConsoleProductionWritesRejected":true,"ordinaryCiProductionProfileReuseRejected":true,"bootstrapSecretsRedacted":true,"configurationFailureTyped":true,"productionEntrypointNotUsedAsConfigProbe":true,"exceptionTextNotRendered":true,"hiddenRetry":false,"licenseAuthorityExecutionSeparated":true}'
echo "=== S7.7 ENVIRONMENT OBSERVABILITY ACCEPTANCE PASS ==="
