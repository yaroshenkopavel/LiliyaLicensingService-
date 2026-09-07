# Slice 5 Production Boundary Review

Status: **REVIEWED PROFILE / LIVE KMS ACCEPTANCE STILL PENDING**

This document closes the Slice 5 acceptance requirement to explicitly document the production
deployment boundary before calling the slice complete.

## 1. Deployment platform

Selected v0.1 platform:

- compute: Google Cloud Run;
- durable authority: Cloud SQL for PostgreSQL;
- asymmetric signing: Google Cloud KMS;
- non-key secrets, only where unavoidable: Google Secret Manager;
- source/CI: private GitHub backend repository.

No Android/client component belongs to this deployment boundary.

## 2. Signing / secret boundary

Production signing profile:

- envelope schema: `1`;
- client algorithm identity: `ECDSA-P256-SHA256`;
- Cloud KMS algorithm: `EC_SIGN_P256_SHA256`;
- one reviewed logical signing-key identity maps to one exact KMS CryptoKeyVersion;
- private signing key material is non-exported and never enters process memory as key bytes;
- Cloud Run runtime identity receives only the minimum use-to-sign/read-public-key permissions;
- key administration/creation/disable/destroy permissions belong to a distinct administrative principal.

No alias search, fallback to an older key version, or implicit downgrade is permitted.

## 3. Durable database / transaction primitive

Production durable authority remains the S5.4 PostgreSQL design:

- exact subject/product decision scope;
- transaction-scoped PostgreSQL advisory lock;
- `READ COMMITTED` after lock acquisition;
- strict replay advancement;
- non-decreasing revocation epoch;
- exact signed-envelope lineage committed atomically with state;
- no hidden retry.

Cloud SQL is the deployment host; Cloud SQL itself does not replace these application transaction
rules.

## 4. Schema evolution

S5.4.1 defines the production migration safety rule:

- fresh current schema: accepted;
- empty legacy S5.4 schema: may be upgraded transactionally;
- populated legacy schema missing envelope profile fields: startup fails closed;
- existing authoritative rows are never assigned guessed schema/algorithm values.

Any later incompatible schema change requires another explicit migration gate.

## 5. Environment separation

Production and non-production environments must not share signing or database authority.

Preferred v0.1 layout:

- separate GCP project for production;
- separate non-production project for dev/staging;
- distinct Cloud Run service identities;
- distinct Cloud SQL instances/databases;
- distinct KMS key rings/keys/versions;
- distinct Secret Manager secrets;
- no production KMS resource name in test fixtures.

A test/staging key is never promoted into a production key by renaming configuration.

## 6. Key rotation

Rotation is explicit and ordered:

1. create/enable a distinct new KMS CryptoKeyVersion;
2. assign a distinct logical License signing key ID;
3. distribute/review the corresponding client trust material before issuance selects that ID;
4. switch issuer configuration to the new logical ID;
5. never fall back to the older key if the new exact key is unavailable;
6. retain/retire the old trust path according to reviewed compatibility policy;
7. signing-key retirement remains separate from entitlement revocation.

Automatic client trust-anchor rotation is not claimed by Slice 5.

## 7. Revocation administration boundary

Revocation administration is not a public ISSUE/REFRESH capability.

Rules:

- ordinary service requests cannot authoritatively lower or administratively set revocation state;
- runtime Cloud Run signing identity must not receive broad administrative database/KMS rights;
- administrative revocation mutations require a separate reviewed operator/admin principal or
  controlled administrative workflow;
- the issuer consumes the resulting authoritative server-side state and enforces monotonicity;
- no client-supplied revocation minimum becomes server authority;
- a revocation rollback is rejected by the transactional boundary.

A production admin UI/API is not claimed by Slice 5.

## 8. Backup / restore assumptions

Production Cloud SQL must have automated backups and point-in-time recovery enabled before service
activation.

Restore is a security-sensitive administrative event because restoring an old snapshot can also
restore older replay/revocation values.

Therefore:

- a restored instance does not automatically become serving authority;
- restore occurs into an explicitly reviewed recovery workflow;
- issuance/refresh remains disabled until replay/revocation state is reconciled and the recovered
  database is accepted as the new authority;
- recovery must never silently lower a known revocation/replay minimum;
- KMS key versions are not recreated from database backups;
- database backup/restore does not imply signing-key rollback.

The service makes no zero-RPO claim.

## 9. Operational audit / log retention

Normal application logs remain structural and privacy-bounded per S5.8.

V0.1 retention profile:

- application/service logs: use the project `_Default` Cloud Logging bucket with 30-day retention
  unless a later reviewed policy changes it;
- Admin Activity/System Event audit logs: rely on the provider-managed `_Required` bucket;
- Cloud KMS Data Access audit logging for asymmetric signing/public-key access must be enabled for
  production and retained under the reviewed project logging policy;
- logs must not include private License subjects, raw canonical payloads/signatures, bearer
  credentials, private key material, provider/payment secrets or arbitrary exception text.

Longer paid retention is not required by Slice 5 v0.1.

## 10. Cloud SQL recovery profile

Before production activation:

- automated backups: enabled;
- point-in-time recovery: enabled;
- retention values: selected explicitly for the production edition/budget;
- accidental deletion protection: enabled where supported by the deployment workflow;
- at least one documented restore rehearsal is required by the later Slice 7 readiness audit.

Slice 5 does not claim that backup existence alone proves safe monotonic rollback recovery.

## 11. Non-claims

This production profile still does not claim:

- production HTTP transport;
- exactly-once network delivery;
- idempotency;
- enrollment/device binding;
- automatic trust-anchor rotation;
- Authority grant;
- runtime execution permission;
- zero-RPO disaster recovery;
- production readiness before the later red-team/readiness slice.

## 12. Remaining live gate

This review does not complete S5.6 by itself.

The exact KMS candidate must still physically prove:

- `EC_SIGN_P256_SHA256` exact key version;
- real Cloud KMS signature;
- public-key verification;
- payload tamper rejection;
- missing exact key typed fail-closed behavior;
- no fallback;
- backend-issued evidence accepted by the frozen LiliyaCore verification/policy path.

Only after that evidence and final acceptance audit may Slice 5 be called complete.
