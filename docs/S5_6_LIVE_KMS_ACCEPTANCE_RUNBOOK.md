# S5.6 Live Cloud KMS Acceptance Runbook

Status: **READY TO EXECUTE / COST-BEARING RESOURCE CREATION REQUIRES HUMAN APPROVAL**

Canonical candidate head:

`0b4b3c9a7826839b49cfed7cb7f3cdbd4ea194fa`

## 1. Preconditions

The live acceptance must run against an exact Google Cloud KMS asymmetric signing key version using:

`EC_SIGN_P256_SHA256`

The test runner never creates or deletes cloud resources.

The repository script:

`scripts/run-live-gcp-kms-acceptance.sh`

only:

- describes the supplied exact CryptoKeyVersion;
- verifies algorithm and ENABLED state;
- verifies Application Default Credentials are available;
- runs the opt-in Gradle live integration test.

## 2. Existing-key path

If an eligible non-production KMS CryptoKeyVersion already exists:

```bash
git clone --branch licensing/s5-6-gcp-kms-profile-v0.1 \
  https://github.com/yaroshenkopavel/LiliyaLicensingService-.git
cd LiliyaLicensingService-

git rev-parse HEAD
# MUST equal:
# 0b4b3c9a7826839b49cfed7cb7f3cdbd4ea194fa

gcloud auth application-default login

./scripts/run-live-gcp-kms-acceptance.sh \
  projects/PROJECT_ID/locations/LOCATION/keyRings/KEY_RING/cryptoKeys/KEY_NAME/cryptoKeyVersions/VERSION
```

Do not continue if the checked-out SHA differs.

## 3. Temporary-key path

Creating a Cloud KMS key ring/key/key version can incur cloud charges and therefore must be a human-approved action.

Recommended test-only naming:

- key ring: `liliya-licensing-s5-live`;
- key: `license-signing-test`;
- purpose: asymmetric signing;
- algorithm: `ec-sign-p256-sha256`.

Example commands are intentionally not executed by repository automation:

```bash
PROJECT_ID="$(gcloud config get-value project)"
LOCATION="<reviewed-location>"

gcloud kms keyrings create liliya-licensing-s5-live \
  --project="$PROJECT_ID" \
  --location="$LOCATION"

gcloud kms keys create license-signing-test \
  --project="$PROJECT_ID" \
  --location="$LOCATION" \
  --keyring=liliya-licensing-s5-live \
  --purpose=asymmetric-signing \
  --default-algorithm=ec-sign-p256-sha256

KEY_VERSION="$(
  gcloud kms keys versions list \
    --project="$PROJECT_ID" \
    --location="$LOCATION" \
    --keyring=liliya-licensing-s5-live \
    --key=license-signing-test \
    --filter='state=ENABLED' \
    --sort-by='~name' \
    --limit=1 \
    --format='value(name)'
)"

echo "$KEY_VERSION"
```

Review the printed exact resource name before passing it to the acceptance runner.

## 4. Required PASS evidence

The live test must prove:

1. exact key version reports `EC_SIGN_P256_SHA256`;
2. exact canonical payload is signed by Cloud KMS;
3. KMS public key verifies the returned ECDSA signature;
4. one-byte payload mutation fails verification;
5. a deliberately nonexistent exact CryptoKeyVersion fails as `KEY_UNAVAILABLE`;
6. no older configured key is attempted as fallback.

Expected final runner marker:

`=== LIVE GCP KMS ACCEPTANCE PASS ===`

## 5. Merge rule

PR #6 must not be merged merely because ordinary CI is GREEN.

Merge only after:

- ordinary exact-head Backend CI: GREEN;
- Secret Guard: GREEN;
- live KMS test: PASS;
- evidence is recorded in the canonical project documentation.

## 6. Cleanup

Cloud KMS key material is intentionally not exportable. Key/version destruction has delayed and provider-specific lifecycle semantics. Do not treat cleanup as equivalent to deleting a local file.

For a temporary test resource, disable the test key version after acceptance if the reviewed operational policy requires it. Do not destroy a key version required by recorded acceptance evidence until its retention policy is decided.
