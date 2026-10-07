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


## Owner activation-code offline policy

Canonical product policy is unlimited offline operation until a later verified online revocation/service-state update.

The owner activation-code CLI therefore treats an omitted `--offline-seconds` as:

`OFFLINE_POLICY=UNLIMITED`

Explicit `--offline-seconds none` has the same result.

A finite offline lease is rejected unless the owner deliberately supplies both:

`--offline-seconds <positive-seconds>`

and:

`--allow-finite-offline-lease`

This is an expert override. It must not be used for the normal LiliyaCore product flow.

The generated CLI output reports the selected offline policy without exposing signing secrets.

This operator policy is distinct from revocation semantics: unlimited offline duration does not prevent a later verified service-state sync from advancing the durable revocation/replay floor.


## Licensing startup scheduled-task policy

Canonical owner-laptop task:

`Liliya Licensing Service Startup`

Canonical action:

`powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "C:\LiliyaServer\bin\startup\Start-LiliyaLicensingService.ps1"`

Required task policy:

- trigger: owner-user logon;
- delay: `PT1M`;
- execution time limit: `PT0S` (unlimited);
- multiple instances: `IgnoreNew`;
- restart on failure: 2 attempts at `PT1M`;
- start when available: enabled;
- start-on-battery allowed;
- do not stop merely because the machine switches to battery.

Why `PT0S` is required:

The production helper starts the long-lived Licensing JVM. A finite task execution limit can terminate the scheduled-task process tree after the startup window even though the backend reached readiness. On the 2026-10-07 reboot acceptance attempt, `ExecutionTimeLimit=PT5M` was followed by Task Scheduler result `0x41306` and the reboot path could not be accepted as GREEN.

Use:

`scripts/Set-LiliyaLicensingStartupTaskPolicy.ps1`

without `-Apply` to audit drift. Use `-Apply` only on the owner laptop to back up the existing task XML and normalize the canonical settings.

After changing task lifecycle policy:

1. verify the healthy existing backend is not restarted by an idempotent task run;
2. require `LastTaskResult=0` for that idempotent validation;
3. perform a fresh Windows reboot/logon;
4. verify PG17 Running/Automatic and PG16 Stopped/Manual;
5. verify OpenBao 8200 and Licensing 8443 listeners;
6. verify `/health/ready` with the Licensing CA;
7. verify activation/rebind routes return expected validation responses;
8. retain reboot evidence before declaring full Windows autostart GREEN.

A manual/idempotent task PASS is not a substitute for the fresh reboot proof.
