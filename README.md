# Liliya Licensing Service

Separate backend repository for the LiliyaCore Licensing Service.

## Current baseline

The repository has progressed beyond the original Slice 6 transport baseline.

Verified implementation present on canonical `main` includes:

- authenticated backend request handling and transport contracts;
- HTTPS listener and request-auth boundary;
- OpenBao Transit signing integration;
- PostgreSQL-backed authoritative licensing state;
- production PostgreSQL entitlement-source provider;
- deployment composition and runtime wiring;
- environment observability;
- S7.9D authenticated service-state policy acceptance chain;
- fail-closed client-side verification/policy acceptance tests that stop before Authority/Execution.

Canonical backend security boundary:

- no Android SDK/plugin;
- no ONNX or llama.cpp;
- no Runtime Hardening implementation inside the backend;
- no client Authority/Execution semantics inside the backend;
- no production signing private keys in source;
- OpenBao Transit remains behind the signing boundary;
- PostgreSQL remains authoritative for replay/revocation and entitlement state;
- backend issuance remains distinct from client LicensePolicy and runtime Authority.

## Verification

Local baseline verification on Windows uses Gradle 9.6.1:

```powershell
C:\LiliyaServer\bin\gradle-9.6.1\bin\gradle.bat test --no-daemon --console=plain
```

A green unit/integration test run is required before changing deployment or production wiring.

## Repository and trust notes

This repository is intentionally source-visible so standard GitHub-hosted Actions can run without relying on source secrecy. Production credentials, signing private material, database credentials and provider secrets are prohibited from source and normal CI artifacts.

The repository currently has no open-source license file. Public visibility alone should not be treated as an explicit grant of reuse/redistribution rights.

Canonical client source of truth for entitlement shape, frozen verification semantics, LicensePolicy and runtime Authority remains `yaroshenkopavel/LiliyaCore-`.
