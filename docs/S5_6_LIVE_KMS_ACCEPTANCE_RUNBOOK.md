# S5.6 Live Cloud KMS + Frozen Client Acceptance Runbook

Status: **READY TO EXECUTE / COST-BEARING RESOURCE CREATION REQUIRES HUMAN APPROVAL**

The live gate now proves both:

1. real backend Cloud KMS signing;
2. exact backend-issued envelope compatibility with frozen LiliyaCore.

## 1. Preconditions

Required backend branch:

`licensing/s5-6-gcp-kms-profile-v0.1`

Required LiliyaCore main:

`5a4f0c82a64eec11230bdd5afc322d647071f90a`

Required KMS key version algorithm:

`EC_SIGN_P256_SHA256`

The repository runner:

`scripts/run-live-gcp-kms-acceptance.sh`

does not create or delete cloud resources.

It:

- validates the exact KMS CryptoKeyVersion;
- signs one real canonical LicenseEntitlement;
- verifies the KMS signature with the KMS public key;
- proves payload tamper rejection;
- proves a missing exact KMS version fails closed;
- writes a local evidence bundle;
- optionally passes that exact backend evidence bundle into the frozen LiliyaCore live compatibility test.

## 2. Existing-key path

If an eligible non-production KMS CryptoKeyVersion already exists:

```bash
rm -rf ~/liliya-licensing-live ~/liliya-core-live

gh repo clone yaroshenkopavel/LiliyaLicensingService- ~/liliya-licensing-live
git -C ~/liliya-licensing-live checkout licensing/s5-6-gcp-kms-profile-v0.1

gh repo clone yaroshenkopavel/LiliyaCore- ~/liliya-core-live
git -C ~/liliya-core-live checkout 5a4f0c82a64eec11230bdd5afc322d647071f90a

gcloud auth application-default login

cd ~/liliya-licensing-live

./scripts/run-live-gcp-kms-acceptance.sh \
  projects/PROJECT_ID/locations/LOCATION/keyRings/KEY_RING/cryptoKeys/KEY_NAME/cryptoKeyVersions/VERSION \
  ~/liliya-core-live
```

Before execution, record:

`git -C ~/liliya-licensing-live rev-parse HEAD`

That exact SHA is the backend acceptance candidate and must match the canonical project checkpoint.

## 3. Temporary-key path

Creating a Cloud KMS key ring/key/key version can incur cloud charges and therefore requires
explicit human approval.

Recommended test-only naming:

- key ring: `liliya-licensing-dev`;
- key: `license-signing-s5`;
- purpose: asymmetric signing;
- algorithm: `ec-sign-p256-sha256`.

No production key should be used for development acceptance.

## 4. Required backend evidence

The backend live test must emit:

`LICENSING_S5_6_KMS_EVIDENCE={"algorithm":true,"realKmsSignature":true,"publicKeyVerification":true,"tamperRejected":true,"missingExactKeyRejected":true,"noFallback":true}`

It must also produce a local properties evidence bundle containing:

- schemaVersion;
- algorithm;
- logical keyReference;
- canonical entitlement payload;
- KMS signature;
- KMS public key DER.

The runner prints only non-secret structural metadata. It intentionally does not print the raw
payload/signature/public-key byte fields.

## 5. Required cross-repository evidence

When the LiliyaCore checkout is passed as the second argument, the runner must emit:

`LICENSING_S5_7_CROSS_REPO_EVIDENCE={"backendIssuedEnvelope":true,"frozenVerifier":true,"serviceState":true,"policyContext":true,"licensePolicy":true,"stoppedBeforeAuthority":true}`

The frozen client test must consume the exact backend evidence file. It must not reconstruct or
re-sign the entitlement independently.

## 6. Required final marker

The complete live gate ends with:

`=== LIVE GCP KMS ACCEPTANCE PASS ===`

A backend-only KMS PASS without the cross-repository gate is insufficient for final Slice 5
acceptance section K.

## 7. Merge rule

PR #6 must not be merged merely because ordinary CI is GREEN.

Merge only after:

- exact-head Backend CI: GREEN;
- exact-head Secret Guard: GREEN;
- real KMS backend evidence: PASS;
- cross-repository frozen LiliyaCore evidence: PASS;
- physical evidence is recorded in canonical documentation.

## 8. Cleanup / retention

The runner does not destroy KMS resources.

Cloud KMS private material is non-exportable. A temporary test key version may be disabled after
acceptance according to the reviewed test-resource retention policy.

Do not destroy evidence-required key versions until the acceptance record and retention decision are
complete.
