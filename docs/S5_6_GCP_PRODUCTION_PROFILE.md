# S5.6 GCP Production Signing / Deployment Profile

Status: **IMPLEMENTATION CANDIDATE / LIVE CLOUD ACCEPTANCE PENDING**

## Deployment boundary

Selected v0.1 production profile:

- compute: Google Cloud Run;
- durable authority: Cloud SQL for PostgreSQL;
- signing: Google Cloud KMS asymmetric signing;
- secrets that cannot use service identity directly: Google Secret Manager;
- source/CI: private GitHub backend repository.

No production private signing key is exportable to application code, GitHub or CI.

## Signing profile

Backend envelope profile:

- schema version: `1`;
- algorithm identity exposed to frozen client verifier: `ECDSA-P256-SHA256`;
- Cloud KMS key version algorithm: `EC_SIGN_P256_SHA256`;
- key ownership: exact logical signing key ID maps to one exact KMS CryptoKeyVersion resource;
- no alias search;
- no fallback to older/other key version;
- unavailable exact key fails closed.

The backend hashes the exact canonical entitlement bytes with SHA-256 and submits that digest to
Cloud KMS for ECDSA P-256 signing.

## Key rotation

Rotation is explicit:

1. create/enable a distinct new KMS CryptoKeyVersion;
2. assign a distinct logical signing key ID in reviewed service configuration;
3. configure client trust material for the new public key before selecting it for issuance;
4. switch service entitlement decisions to the new logical signing key ID;
5. never fall back to the old key when the selected new key is unavailable;
6. retire/disable old signing only under an explicit compatibility/revocation procedure.

Entitlement revocation and signing-key retirement remain separate domains.

## IAM boundary

The Cloud Run service identity should receive only the permissions required to invoke asymmetric
signing on the selected KMS key versions and to connect to the selected Cloud SQL instance.

Human/admin key-management permissions remain separate from runtime signing permissions.

## Database boundary

S5.4 PostgreSQL semantics remain authoritative.

Production Cloud SQL deployment must preserve:

- transaction behavior required by S5.4;
- backups and point-in-time recovery according to the reviewed production plan;
- environment separation between dev/staging/prod;
- no production DB credential in GitHub.

## Live acceptance still required

This file and the compiled KMS adapter do NOT by themselves complete S5.6.

Before S5.6 is COMPLETE, execute a live cloud gate that proves:

- exact KMS key version is `EC_SIGN_P256_SHA256`;
- service identity can sign but cannot export private key material;
- returned signature verifies using the retrieved public key;
- payload-byte mutation fails verification;
- unavailable/disabled exact key produces typed failure and no fallback;
- rotation uses distinct logical and physical key-version identity.

No paid cloud resource is created automatically by repository CI.
