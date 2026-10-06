# Operations

## Current local baseline

The working Windows deployment foundation is rooted at:

`C:\LiliyaServer`

Primary source checkout:

`C:\LiliyaServer\src\LiliyaLicensingService`

Local platform components currently provisioned under the foundation include PostgreSQL, OpenBao, TLS material/configuration, startup scripts, logs and the Licensing Service source/build outputs.

## Canonical Windows PostgreSQL topology

Current production topology on the owner laptop:

- canonical production service: `postgresql-x64-17-liliya`;
- canonical PostgreSQL version: 17.11;
- canonical endpoint: `127.0.0.1:5432`;
- database: `liliya_licensing`;
- preserved rollback service: `postgresql-x64-16`;
- rollback service state: Stopped / Manual.

The production startup script must wait for `postgresql-x64-17-liliya`. It must not start, depend on, or silently fall back to the PG16 rollback service.

PG16 remains rollback-only until full Windows reboot/autostart acceptance is GREEN and retirement is explicitly approved.

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


## Canonical PostgreSQL production topology — 2026-10-06

The canonical production database service is:

`postgresql-x64-17-liliya`

on:

`127.0.0.1:5432`

The historical PostgreSQL 16 service is rollback-only and must remain:

- stopped;
- manual start;
- retained until the full Windows reboot/autostart recovery gate and an explicit rollback-retirement decision are complete.

The checked-in `scripts/Start-LiliyaLicensingService.ps1` must gate startup on the PostgreSQL 17 service above. A repository checkout must not silently wait for or promote the PostgreSQL 16 rollback service.

The live production helper remains separately deployed under:

`C:\LiliyaServer\bin\startup\Start-LiliyaLicensingService.ps1`

Repository changes do not replace or restart that healthy deployed helper automatically.
