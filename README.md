# Liliya Licensing Service

Separate backend repository for the LiliyaCore Licensing Service.

Current program: **Licensing Service Slice 6 — Transport Integration**.

Canonical backend security boundary:

- no Android SDK/plugin;
- no ONNX or llama.cpp;
- no Runtime Hardening;
- no Authority/Execution semantics;
- no production signing private keys in source;
- OpenBao Transit remains behind the signing boundary;
- PostgreSQL remains authoritative for replay/revocation state;
- backend issuance remains distinct from client LicensePolicy and runtime Authority.

Public-repository note:

This repository is intentionally source-visible so standard GitHub-hosted Actions can run without private-repository minute consumption. Security does not rely on source secrecy. Production credentials, signing private material, database credentials and provider secrets are prohibited from source and normal CI artifacts.

The repository currently has no open-source license file. Public visibility alone should not be treated as an explicit grant of reuse/redistribution rights.

Canonical client source of truth for entitlement shape and verification semantics remains `yaroshenkopavel/LiliyaCore-`.
