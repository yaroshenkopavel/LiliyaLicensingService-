# Liliya Licensing Service

Separate private backend repository for the LiliyaCore Licensing Service.

Current program: **Slice 5 / S5.1 — protocol + canonical entitlement composition**.

Security boundary:

- no Android SDK/plugin;
- no ONNX or llama.cpp;
- no Runtime Hardening;
- no Authority/Execution semantics;
- no production signing private keys;
- backend issuance remains distinct from client LicensePolicy and runtime Authority.

Canonical client source of truth for entitlement shape and encoding remains `yaroshenkopavel/LiliyaCore-`.
