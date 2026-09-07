#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [LILIYA_CORE_DIR]" >&2
  exit 2
fi

CORE_DIR="${1:-}"
OPENBAO_IMAGE="${OPENBAO_IMAGE:-openbao/openbao:2.6.2}"
CONTAINER_NAME="${OPENBAO_CONTAINER_NAME:-liliya-openbao-s5-live}"
OPENBAO_ADDR="${LIVE_OPENBAO_ADDR:-http://127.0.0.1:18200}"
OPENBAO_ROOT_TOKEN="${OPENBAO_ROOT_TOKEN:-liliya-s5-live-root-only-token}"
OPENBAO_KEY="${LIVE_OPENBAO_KEY:-license-signing-s5}"
OPENBAO_PORT="${OPENBAO_ADDR##*:}"
OPENBAO_PORT="${OPENBAO_PORT%%/*}"

echo "=== LIVE OPENBAO TRANSIT ACCEPTANCE ==="
echo "Backend head: $(git rev-parse HEAD)"
echo "OpenBao image: $OPENBAO_IMAGE"
echo "No paid cloud resource is required."

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the free OpenBao acceptance runner" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required for the free OpenBao acceptance runner" >&2
  exit 2
fi

GRADLE_CMD=()
if [[ -x ./gradlew ]]; then
  GRADLE_CMD=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=(gradle)
else
  echo "Gradle is required" >&2
  exit 2
fi

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d   --name "$CONTAINER_NAME"   -p "$OPENBAO_PORT:8200"   "$OPENBAO_IMAGE"   server -dev   -dev-listen-address=0.0.0.0:8200   -dev-root-token-id="$OPENBAO_ROOT_TOKEN"   >/dev/null

echo
echo "=== WAIT OPENBAO ==="
for _ in $(seq 1 60); do
  if curl -fsS "$OPENBAO_ADDR/v1/sys/health" >/dev/null 2>&1; then
    echo "OpenBao ready"
    break
  fi
  sleep 1
done

curl -fsS "$OPENBAO_ADDR/v1/sys/health" >/dev/null

echo
echo "=== ENABLE TRANSIT ==="
HTTP_CODE="$(
  curl -sS -o /tmp/liliya-openbao-mount.out -w '%{http_code}'     -H "X-Vault-Token: $OPENBAO_ROOT_TOKEN"     -H "Content-Type: application/json"     -X POST     -d '{"type":"transit"}'     "$OPENBAO_ADDR/v1/sys/mounts/transit"
)"
if [[ "$HTTP_CODE" != "204" && "$HTTP_CODE" != "200" && "$HTTP_CODE" != "400" ]]; then
  cat /tmp/liliya-openbao-mount.out >&2
  exit 1
fi

echo
echo "=== CREATE ECDSA P-256 KEY ==="
curl -fsS   -H "X-Vault-Token: $OPENBAO_ROOT_TOKEN"   -H "Content-Type: application/json"   -X POST   -d '{"type":"ecdsa-p256","exportable":false,"allow_plaintext_backup":false}'   "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"   >/dev/null

echo
echo "=== CREATE LEAST-PRIVILEGE APPLICATION POLICY ==="

POLICY_CONTENT='path "transit/keys/'"$OPENBAO_KEY"'" { capabilities = ["read"] }
path "transit/sign/'"$OPENBAO_KEY"'/sha2-256" { capabilities = ["update"] }'

POLICY_JSON="$(jq -n --arg policy "$POLICY_CONTENT" '{policy: $policy}')"

curl -fsS   -H "X-Vault-Token: $OPENBAO_ROOT_TOKEN"   -H "Content-Type: application/json"   -X PUT   -d "$POLICY_JSON"   "$OPENBAO_ADDR/v1/sys/policies/acl/liliya-license-signer"   >/dev/null

APP_TOKEN_RESPONSE="$(
  curl -fsS     -H "X-Vault-Token: $OPENBAO_ROOT_TOKEN"     -H "Content-Type: application/json"     -X POST     -d '{"policies":["liliya-license-signer"],"ttl":"1h","renewable":false,"no_default_policy":true}'     "$OPENBAO_ADDR/v1/auth/token/create"
)"

OPENBAO_APP_TOKEN="$(echo "$APP_TOKEN_RESPONSE" | jq -r '.auth.client_token // empty')"
if [[ -z "$OPENBAO_APP_TOKEN" ]]; then
  echo "Failed to create least-privilege OpenBao application token" >&2
  exit 1
fi

echo "Least-privilege application token created; token value is intentionally not printed"

echo
echo "=== PROVE APPLICATION TOKEN CANNOT MANAGE KEY ==="

ROTATE_STATUS="$(
  curl -sS -o /tmp/liliya-openbao-denied.out -w '%{http_code}'     -H "X-Vault-Token: $OPENBAO_APP_TOKEN"     -H "Content-Type: application/json"     -X POST     -d '{}'     "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY/rotate"
)"

if [[ "$ROTATE_STATUS" != "403" ]]; then
  echo "Least-privilege token unexpectedly reached key rotation: HTTP $ROTATE_STATUS" >&2
  cat /tmp/liliya-openbao-denied.out >&2 || true
  exit 1
fi

echo "Key-management denial confirmed: HTTP 403"

echo
echo "=== ROOT ROTATE TO VERSION 2 ==="
curl -fsS   -H "X-Vault-Token: $OPENBAO_ROOT_TOKEN"   -H "Content-Type: application/json"   -X POST   -d '{}'   "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY/rotate"   >/dev/null

KEY_META="$(
  curl -fsS     -H "X-Vault-Token: $OPENBAO_APP_TOKEN"     "$OPENBAO_ADDR/v1/transit/keys/$OPENBAO_KEY"
)"

echo "$KEY_META" | grep -q '"ecdsa-p256"'
echo "$KEY_META" | grep -q '"2"'

EVIDENCE_PATH="$(pwd)/build/live-openbao-evidence.properties"
rm -f "$EVIDENCE_PATH"

export LIVE_OPENBAO_ADDR="$OPENBAO_ADDR"
export LIVE_OPENBAO_TOKEN="$OPENBAO_APP_TOKEN"
export LIVE_OPENBAO_KEY="$OPENBAO_KEY"
export LIVE_OPENBAO_KEY_VERSION=2
export LIVE_OPENBAO_EVIDENCE_PATH="$EVIDENCE_PATH"

echo
echo "=== RUN BACKEND OPENBAO LIVE TEST ==="
"${GRADLE_CMD[@]}" :issuer-openbao-transit:test   --tests "pro.liliya.licensing.openbao.OpenBaoTransitLiveAcceptanceTest"   --console=plain

if [[ ! -s "$EVIDENCE_PATH" ]]; then
  echo "Expected OpenBao evidence file was not produced" >&2
  exit 1
fi

echo
echo "=== OPENBAO EVIDENCE BUNDLE READY ==="
grep -E '^(schemaVersion|algorithm|keyReference)=' "$EVIDENCE_PATH"
echo "payload/signature/public-key bytes are intentionally not printed"
echo 'LICENSING_S5_6B_OPENBAO_EVIDENCE={"externalTransitSignature":true,"exactKeyVersion":true,"publicKeyVerification":true,"tamperRejected":true,"missingExactVersionRejected":true,"noFallback":true,"privateKeyNotReturnedBySigningApi":true,"leastPrivilegeToken":true,"keyManagementDenied":true}'

if [[ -n "$CORE_DIR" ]]; then
  EXPECTED_CORE_HEAD="5a4f0c82a64eec11230bdd5afc322d647071f90a"
  ACTUAL_CORE_HEAD="$(git -C "$CORE_DIR" rev-parse HEAD)"

  echo
  echo "=== LILIYACORE CROSS-REPOSITORY PREFLIGHT ==="
  echo "Expected core head: $EXPECTED_CORE_HEAD"
  echo "Actual core head:   $ACTUAL_CORE_HEAD"

  if [[ "$ACTUAL_CORE_HEAD" != "$EXPECTED_CORE_HEAD" ]]; then
    echo "LiliyaCore exact-head mismatch" >&2
    exit 1
  fi

  if [[ -x "$CORE_DIR/gradlew" ]]; then
    CORE_GRADLE=("$CORE_DIR/gradlew" "-p" "$CORE_DIR")
  elif command -v gradle >/dev/null 2>&1; then
    CORE_GRADLE=(gradle "-p" "$CORE_DIR")
  else
    echo "Gradle is required for LiliyaCore compatibility test" >&2
    exit 2
  fi

  echo
  echo "=== RUN FROZEN LILIYACORE COMPATIBILITY ==="
  LIVE_S5_KMS_EVIDENCE_PATH="$EVIDENCE_PATH"     "${CORE_GRADLE[@]}" :core:test       --tests "pro.liliya.core.license.LicenseServiceS5LiveKmsCompatibilityTest"       --console=plain

  echo
  echo 'LICENSING_S5_7_CROSS_REPO_EVIDENCE={"backendIssuedEnvelope":true,"frozenVerifier":true,"serviceState":true,"policyContext":true,"licensePolicy":true,"stoppedBeforeAuthority":true}'
fi

echo
echo "=== LIVE OPENBAO TRANSIT ACCEPTANCE PASS ==="
