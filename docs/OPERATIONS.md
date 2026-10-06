# Operations

## Current local baseline

The working Windows deployment foundation is rooted at:

`C:\LiliyaServer`

Primary source checkout:

`C:\LiliyaServer\src\LiliyaLicensingService`

Local platform components currently provisioned under the foundation include PostgreSQL, OpenBao, TLS material/configuration, startup scripts, logs and the Licensing Service source/build outputs.

## Required pre-change verification

Before changing production wiring:

1. confirm the Git working tree and branch;
2. run the full Gradle test suite;
3. inspect service/OpenBao/PostgreSQL logs for the affected path;
4. make the smallest change that preserves the backend/client trust boundary;
5. rerun the relevant focused test and then the full suite.

Reference command:

```powershell
Set-Location C:\LiliyaServer\src\LiliyaLicensingService
C:\LiliyaServer\bin\gradle-9.6.1\bin\gradle.bat test --no-daemon --console=plain
```

## Production boundaries

Operational deployment must preserve all of the following:

- production secrets stay outside source and normal CI artifacts;
- OpenBao runtime identity remains least privilege;
- PostgreSQL remains authoritative for entitlement/replay/revocation state;
- TLS and request authentication protect transport but do not replace signed licensing evidence;
- backend service-state evidence is verified by frozen client logic before it influences LicensePolicy;
- LicensePolicy decisions do not themselves grant runtime Authority or Execution permission.

## Remaining acceptance work

A green repository test suite proves the checked-in code baseline, not full production readiness. Environment-specific rotation, backup/restore drills, certificate lifecycle, recovery procedures, secret renewal, startup/idempotency and end-to-end deployment acceptance remain operational gates and must be verified on the target environment before declaring production readiness.


## Canonical PostgreSQL production service

Current production topology uses PostgreSQL 17.11 on the canonical endpoint:

`127.0.0.1:5432`

Windows service:

`postgresql-x64-17-liliya`

The checked-in startup helper must wait for this exact service before starting Licensing Service.

The historical PostgreSQL 16 service:

`postgresql-x64-16`

is rollback-only and must remain `Stopped / Manual` until the dedicated rollback-retirement gate is explicitly closed. Do not start PG16 as part of normal production startup and do not delete its retained data merely because PG17 is canonical.

A full Windows reboot/autostart proof remains a separate operational acceptance gate.
