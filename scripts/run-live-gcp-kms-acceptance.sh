#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 projects/PROJECT/locations/LOCATION/keyRings/RING/cryptoKeys/KEY/cryptoKeyVersions/VERSION" >&2
  exit 2
fi

KEY_VERSION="$1"

case "$KEY_VERSION" in
  projects/*/locations/*/keyRings/*/cryptoKeys/*/cryptoKeyVersions/*) ;;
  *)
    echo "Invalid Cloud KMS CryptoKeyVersion resource name" >&2
    exit 2
    ;;
esac

echo "=== LIVE GCP KMS ACCEPTANCE ==="
echo "Key version: $KEY_VERSION"
echo "No cloud resources will be created or deleted by this script."

if ! command -v gcloud >/dev/null 2>&1; then
  echo "gcloud is required for the preflight check" >&2
  exit 2
fi

if ! command -v gradle >/dev/null 2>&1 && [[ ! -x ./gradlew ]]; then
  echo "Gradle is required" >&2
  exit 2
fi

echo
echo "=== KMS PREFLIGHT ==="
gcloud kms keys versions describe "$KEY_VERSION"   --format='yaml(name,algorithm,state,protectionLevel)'

ALGORITHM="$(
  gcloud kms keys versions describe "$KEY_VERSION"     --format='value(algorithm)'
)"
STATE="$(
  gcloud kms keys versions describe "$KEY_VERSION"     --format='value(state)'
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

export LIVE_GCP_KMS_KEY_VERSION="$KEY_VERSION"

echo
echo "=== RUN LIVE KMS TEST ==="
if [[ -x ./gradlew ]]; then
  ./gradlew :issuer-gcp-kms:test     --tests "pro.liliya.licensing.gcpkms.GcpKmsLiveAcceptanceTest"     --console=plain
else
  gradle :issuer-gcp-kms:test     --tests "pro.liliya.licensing.gcpkms.GcpKmsLiveAcceptanceTest"     --console=plain
fi

echo
echo "=== LIVE GCP KMS ACCEPTANCE PASS ==="
