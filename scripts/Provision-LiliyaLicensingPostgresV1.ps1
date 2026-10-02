$ErrorActionPreference = "Stop"

$AdminCredentialFile =
    "C:\LiliyaServer\backup\licensing-secrets\postgres-admin-credential.dpapi"

$ScriptRoot =
    Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Test-Path $AdminCredentialFile)) {
    Write-Host "LICENSING_POSTGRES_PROVISIONING_BLOCKED"
    Write-Host "REASON=POSTGRES_ADMIN_CREDENTIAL_MISSING"
    exit 3
}

$stages = @(
    [PSCustomObject]@{
        Name = "ACTIVATION"
        Script = "Provision-LiliyaActivationPostgresV1.ps1"
    },
    [PSCustomObject]@{
        Name = "DEVICE_REBIND"
        Script = "Provision-LiliyaDeviceRebindPostgresV1.ps1"
    },
    [PSCustomObject]@{
        Name = "LICENSE_ADMIN"
        Script = "Provision-LiliyaLicenseAdminPostgresV1.ps1"
    }
)

foreach ($stage in $stages) {
    $path = Join-Path $ScriptRoot $stage.Script
    if (-not (Test-Path $path)) {
        Write-Host "LICENSING_POSTGRES_PROVISIONING_FAILED"
        Write-Host "STAGE=$($stage.Name)"
        Write-Host "REASON=STAGE_SCRIPT_MISSING"
        exit 4
    }

    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $path

    if ($LASTEXITCODE -ne 0) {
        Write-Host "LICENSING_POSTGRES_PROVISIONING_FAILED"
        Write-Host "STAGE=$($stage.Name)"
        Write-Host "CHILD_EXIT=$LASTEXITCODE"
        exit $LASTEXITCODE
    }

    Write-Host "STAGE_$($stage.Name)=READY"
}

Write-Host "LICENSING_POSTGRES_PROVISIONING_READY"
exit 0
