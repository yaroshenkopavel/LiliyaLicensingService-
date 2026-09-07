# S5.6B OpenBao Transit Signing Profile

Status: **FREE EXTERNAL SIGNING-SERVICE CANDIDATE**

Provider:

- OpenBao Transit;
- OpenBao v2.6.2 baseline for acceptance;
- key type: `ecdsa-p256`;
- client algorithm identity: `ECDSA-P256-SHA256`;
- exact logical key reference → exact Transit key name + exact key version.

## Why this profile exists

The GCP KMS profile remains valid but requires billing to create/use a KMS key.

S5.6B provides a self-hosted open-source provider that preserves the application signing boundary
without requiring a paid cloud KMS account.

OpenBao Transit exposes cryptographic operations through an external service API. The backend never
receives private signing-key bytes through the signing endpoint.

## Exact-version rules

The application binding contains:

- logical key reference;
- Transit key name;
- exact positive key version.

The signer:

1. reads key metadata;
2. requires type `ecdsa-p256`;
3. requires signing support;
4. requires the exact configured version to exist;
5. sends that exact `key_version` to Transit;
6. requires the returned `vault:vN:...` signature version to equal the configured version;
7. never searches for or falls back to latest/older versions.

## Live free acceptance

The repository runner starts OpenBao in an isolated dev container, creates an ECDSA P-256 Transit
key, rotates it to version 2 and signs a real canonical entitlement through version 2.

The live gate proves:

- external Transit service signature;
- exact version selection;
- public-key verification;
- one-byte tamper rejection;
- nonexistent exact version rejection;
- no fallback;
- signing response contains signature only, not private key material;
- the evidence bundle is accepted by frozen LiliyaCore.

OpenBao dev mode is intentionally an acceptance fixture only. It is in-memory and insecure for
production.

## Production deployment boundary

A production OpenBao deployment must not use dev mode.

Production requires a separate reviewed deployment profile covering:

- TLS;
- non-root application token;
- least-privilege Transit policy;
- durable OpenBao storage (integrated Raft or another reviewed supported backend);
- seal/unseal and recovery-key ownership;
- backup/restore;
- audit devices and retention;
- HA/environment separation;
- key rotation and minimum-version policy.

The runtime application token should only receive the minimum paths required for key metadata/read
and exact signing. It must not receive key-management, deletion, backup or private-key export
capabilities.

S5.6B live acceptance proves the provider/API cryptographic boundary; it does not falsely claim that
an in-memory dev server is a production OpenBao deployment.
