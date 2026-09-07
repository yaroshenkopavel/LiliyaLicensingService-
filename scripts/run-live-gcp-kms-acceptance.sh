#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 projects/PROJECT/locations/LOCATION/keyRings/RING/cryptoKeys/KEY/cryptoKeyVersions/VERSION [LILIYA_CORE_DIR]" >&2
  exit 2
fi

KEY_VERSION="$1"
CORE_DIR="${2:-}"

if [[ "$KEY_VERSION" =~ ^projects/([^/]+)/locations/([^/]+)/keyRings/([^/]+)/cryptoKeys/([^/]+)/cryptoKeyVersions/([^/]+)$ ]]; then
  PROJECT_ID="${BASH_REMATCH[1]}"
  LOCATION="${BASH_REMATCH[2]}"
  KEY_RING="${BASH_REMATCH[3]}"
  KEY_NAME="${BASH_REMATCH[4]}"
  VERSION_ID="${BASH_REMATCH[5]}"
else
  echo "Invalid Cloud KMS CryptoKeyVersion resource name" >&2
  exit 2
fi

echo "=== LIVE GCP KMS ACCEPTANCE ==="
echo "Key version: $KEY_VERSION"
echo "Backend head: $(git rev-parse HEAD)"
echo "No cloud resources will be created or deleted by this script."

if ! command -v gcloud >/dev/null 2>&1; then
  echo "gcloud is required for the preflight check" >&2
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

echo
echo "=== KMS PREFLIGHT ==="
gcloud kms keys versions describe "$VERSION_ID"   --project="$PROJECT_ID"   --location="$LOCATION"   --keyring="$KEY_RING"   --key="$KEY_NAME"   --format='yaml(name,algorithm,state,protectionLevel)'

ALGORITHM="$(
  gcloud kms keys versions describe "$VERSION_ID"     --project="$PROJECT_ID"     --location="$LOCATION"     --keyring="$KEY_RING"     --key="$KEY_NAME"     --format='value(algorithm)'
)"
STATE="$(
  gcloud kms keys versions describe "$VERSION_ID"     --project="$PROJECT_ID"     --location="$LOCATION"     --keyring="$KEY_RING"     --key="$KEY_NAME"     --format='value(state)'
)"

if [[ "$ALGORITHM" != "EC_SIGN_P256_SHA256" ]]; then
  echo "Unexpected algorithm: $ALGORITHM" >&2
  exit 1
fi

if [[ "$STATE" != "ENABLED" ]]; then
  echo "Key version is not ENABLED: $STATE" >&2
  exit 1
fi

echo
echo "=== APPLICATION DEFAULT CREDENTIALS PREFLIGHT ==="
gcloud auth application-default print-access-token >/dev/null
echo "ADC available"

EVIDENCE_PATH="$(pwd)/build/live-kms-evidence.properties"
rm -f "$EVIDENCE_PATH"

export LIVE_GCP_KMS_KEY_VERSION="$KEY_VERSION"
export LIVE_GCP_KMS_EVIDENCE_PATH="$EVIDENCE_PATH"

echo
echo "=== RUN BACKEND LIVE KMS TEST ==="
"${GRADLE_CMD[@]}" :issuer-gcp-kms:test   --tests "pro.liliya.licensing.gcpkms.GcpKmsLiveAcceptanceTest"   --console=plain

if [[ ! -s "$EVIDENCE_PATH" ]]; then
  echo "Expected live KMS evidence file was not produced" >&2
  exit 1
fi

echo
echo "=== BACKEND EVIDENCE BUNDLE READY ==="
grep -E '^(schemaVersion|algorithm|keyReference)=' "$EVIDENCE_PATH"
echo "payload/signature/public-key bytes are intentionally not printed"

if [[ -n "$CORE_DIR" ]]; then
  EXPECTED_CORE_HEAD="5a4f0c82a64eec11230bdd5afc322d647071f90a"

  if [[ ! -d "$CORE_DIR/.git" ]]; then
    echo "LiliyaCore directory is not a Git checkout: $CORE_DIR" >&2
    exit 2
  fi

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
echo "=== LIVE GCP KMS ACCEPTANCE PASS ==="
