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
  issuer-observability/src/main
)

if grep -R -n -E '(^|[^A-Za-z])(println|print)\(|System\.(out|err)\.'   --include='*.kt' "${PRODUCTION_SOURCE_DIRS[@]}"; then
  echo "Direct production console output found outside structured observability sink" >&2
  exit 1
fi

echo "=== S7.7 ORDINARY CI PRODUCTION PROFILE GUARD ==="
if grep -R -n -E   'LILIYA_ENVIRONMENT[^A-Z]*PRODUCTION|LILIYA_(POSTGRES_PASSWORD|OPENBAO_TOKEN|REQUEST_AUTH_SECRET|TLS_KEYSTORE_PASSWORD)'   .github/workflows   --include='*.yml'   --include='*.yaml'   --exclude='s7-7-environment-observability-acceptance.yml'; then
  echo "Production-profile deployment credential/reference detected in ordinary CI workflow" >&2
  exit 1
fi

echo "=== S7.7 BUILD DEPLOYMENT BOOTSTRAP ==="
"${GRADLE[@]}" :issuer-deployment:installDist --console=plain >/dev/null

BOOTSTRAP="issuer-deployment/build/install/issuer-deployment/bin/issuer-deployment"
[[ -x "$BOOTSTRAP" ]] || {
  echo "Deployment bootstrap distribution is missing" >&2
  exit 1
}

DB_PASSWORD="$(openssl rand -hex 24)"
OPENBAO_TOKEN="$(openssl rand -hex 24)"
REQUEST_AUTH_SECRET="$(openssl rand -hex 24)"

export LILIYA_ENVIRONMENT="PRODUCTION"
export LILIYA_LISTENER_HOST="0.0.0.0"
export LILIYA_LISTENER_PORT="8443"
export LILIYA_POSTGRES_JDBC_URL="jdbc:postgresql://prod-db.s7-7.invalid:5432/licensing"
export LILIYA_POSTGRES_USERNAME="s7-7-prod-runtime"
export LILIYA_POSTGRES_PASSWORD="$DB_PASSWORD"
export LILIYA_OPENBAO_ADDRESS="https://prod-openbao.s7-7.invalid:8200"
export LILIYA_OPENBAO_KEY_REFERENCE="s7-7-prod-license-key"
export LILIYA_OPENBAO_TOKEN="$OPENBAO_TOKEN"
export LILIYA_REQUEST_AUTH_IDENTITY_REFERENCE="s7-7-prod-request-auth"
export LILIYA_REQUEST_AUTH_SECRET="$REQUEST_AUTH_SECRET"
export LILIYA_TLS_IDENTITY_REFERENCE="s7-7-prod-tls"

VALID_OUTPUT="$("$BOOTSTRAP" 2>&1)"
printf '%s\n' "$VALID_OUTPUT" | grep -F 'LICENSING_OPERATIONAL_EVENT=' >/dev/null
printf '%s\n' "$VALID_OUTPUT" | grep -F '"environment":"PRODUCTION"' >/dev/null
printf '%s\n' "$VALID_OUTPUT" | grep -F '"code":"BOOTSTRAP_READY"' >/dev/null

for forbidden in   "$DB_PASSWORD"   "$OPENBAO_TOKEN"   "$REQUEST_AUTH_SECRET"   "jdbc:postgresql://prod-db.s7-7.invalid:5432/licensing"   "s7-7-prod-runtime"   "s7-7-prod-license-key"   "s7-7-prod-request-auth"   "s7-7-prod-tls"; do
  if printf '%s\n' "$VALID_OUTPUT" | grep -F "$forbidden" >/dev/null; then
    echo "Sensitive or environment identity material leaked from bootstrap output" >&2
    exit 1
  fi
done

echo "=== S7.7 FAIL-CLOSED CONFIGURATION TELEMETRY ==="
unset LILIYA_OPENBAO_TOKEN
set +e
REJECTED_OUTPUT="$("$BOOTSTRAP" 2>&1)"
REJECTED_STATUS=$?
set -e

[[ "$REJECTED_STATUS" -eq 2 ]] || {
  echo "Missing required secret did not fail closed with exit code 2" >&2
  exit 1
}

printf '%s\n' "$REJECTED_OUTPUT" | grep -F '"code":"BOOTSTRAP_REJECTED"' >/dev/null
printf '%s\n' "$REJECTED_OUTPUT" | grep -F '"reason":"MISSING_REQUIRED_CONFIGURATION"' >/dev/null
printf '%s\n' "$REJECTED_OUTPUT" | grep -F '"detail":"OPENBAO_TOKEN"' >/dev/null

for forbidden in   "$DB_PASSWORD"   "$REQUEST_AUTH_SECRET"   "jdbc:postgresql://prod-db.s7-7.invalid:5432/licensing"   "s7-7-prod-runtime"   "s7-7-prod-license-key"   "s7-7-prod-request-auth"   "s7-7-prod-tls"   "Exception"; do
  if printf '%s\n' "$REJECTED_OUTPUT" | grep -F "$forbidden" >/dev/null; then
    echo "Rejected bootstrap leaked private or exception material" >&2
    exit 1
  fi
done

echo 'LICENSING_S7_7_EVIDENCE={"environmentSeparation":true,"productionIdentityReuseRejected":true,"fixedSchemaObservability":true,"directConsoleProductionWritesRejected":true,"ordinaryCiProductionProfileReuseRejected":true,"bootstrapSecretsRedacted":true,"configurationFailureTyped":true,"exceptionTextNotRendered":true,"hiddenRetry":false,"licenseAuthorityExecutionSeparated":true}'
echo "=== S7.7 ENVIRONMENT OBSERVABILITY ACCEPTANCE PASS ==="
