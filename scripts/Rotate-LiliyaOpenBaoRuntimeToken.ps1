$ErrorActionPreference = "Stop"

$Bao = "C:\LiliyaServer\bin\openbao-2.6.2\bao.exe"
$RootTokenFile = "C:\LiliyaServer\backup\openbao-secrets\initial-root-token.dpapi"
$RuntimeTokenFile = "C:\LiliyaServer\backup\openbao-secrets\licensing-runtime-token.dpapi"
$CaCert = "C:\LiliyaServer\config\openbao\tls\ca.crt"
$PolicyFile = Join-Path $PSScriptRoot "openbao\liliya-licensing-policy.hcl"
$TempTokenFile = "$RuntimeTokenFile.next"

function Read-DpapiSecret {
    param([string] $Path)
    $secure = Get-Content $Path | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
        $secure = $null
    }
}
function Save-DpapiSecret {
    param([string] $Path, [string] $Value)
    $secure = ConvertTo-SecureString $Value -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -Path $Path -Encoding ASCII
    $secure = $null
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $userGrant = "*" + $sid + ":(F)"
    & icacls.exe $Path /inheritance:r /grant:r $userGrant "*S-1-5-18:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Remove-Item $Path -Force -ErrorAction SilentlyContinue
        throw "Failed to secure DPAPI token container."
    }
}

foreach ($required in @($Bao,$RootTokenFile,$RuntimeTokenFile,$CaCert,$PolicyFile)) {
    if (-not (Test-Path $required)) { throw "Required artifact missing: $required" }
}
Remove-Item $TempTokenFile -Force -ErrorAction SilentlyContinue

$rootToken = Read-DpapiSecret $RootTokenFile
$oldToken = Read-DpapiSecret $RuntimeTokenFile
$newToken = $null
try {
    $env:BAO_ADDR = "https://127.0.0.1:8200"
    $env:BAO_CACERT = $CaCert
    $env:BAO_TOKEN = $rootToken

    & $Bao policy write liliya-licensing $PolicyFile *> $null
    if ($LASTEXITCODE -ne 0) { throw "Runtime policy update failed." }

    $createRequest = @{
        policies = @("liliya-licensing")
        period = "168h"
        no_default_policy = $true
        renewable = $true
        display_name = "liliya-licensing-runtime"
    } | ConvertTo-Json -Compress
    $createJson = $createRequest | & $Bao write -format=json auth/token/create-orphan -
    if ($LASTEXITCODE -ne 0) { throw "Periodic runtime token creation failed." }

    $created = ([string]::Join([Environment]::NewLine,$createJson)) | ConvertFrom-Json
    $newToken = [string]$created.auth.client_token
    if ([string]::IsNullOrWhiteSpace($newToken)) { throw "Created token is empty." }
    if (-not [bool]$created.auth.renewable) { throw "Created token is not renewable." }
    if ([int64]$created.auth.lease_duration -le 0) { throw "Created token TTL is invalid." }
    if (@($created.auth.policies) -notcontains "liliya-licensing") {
        throw "Created token policy mismatch."
    }

    $env:BAO_TOKEN = $newToken
    $renewJson = & $Bao token renew -format=json
    if ($LASTEXITCODE -ne 0) { throw "New runtime token self-renew failed." }
    $renewed = ([string]::Join([Environment]::NewLine,$renewJson)) | ConvertFrom-Json
    if (-not [bool]$renewed.auth.renewable) { throw "Renewed token is not renewable." }

    foreach ($key in @("license-signing","license-service-state","activation-signing")) {
        & $Bao read -format=json "transit/keys/$key" *> $null
        if ($LASTEXITCODE -ne 0) { throw "Runtime token cannot read $key." }
    }
    $probe = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes("liliya-runtime-token-rotation-probe-v1")
    )
    foreach ($key in @("license-signing","license-service-state","activation-signing")) {
        & $Bao write "transit/sign/$key/sha2-256" "input=$probe" *> $null
        if ($LASTEXITCODE -ne 0) { throw "Runtime token cannot sign with $key." }
    }

    Save-DpapiSecret -Path $TempTokenFile -Value $newToken

    $env:BAO_TOKEN = $rootToken
    $revokeRequest = @{ token = $oldToken } | ConvertTo-Json -Compress
    $null = $revokeRequest | & $Bao write auth/token/revoke -
    if ($LASTEXITCODE -ne 0) { throw "Old runtime token revocation failed." }

    Move-Item $TempTokenFile $RuntimeTokenFile -Force
    Write-Host "OPENBAO_RUNTIME_TOKEN_ROTATION_READY"
    Write-Host "POLICY=liliya-licensing"
    Write-Host "PERIOD_HOURS=168"
    Write-Host "NEW_TOKEN_RENEWAL=PASS"
    Write-Host "TRANSIT_READ_SIGN=PASS"
    Write-Host "OLD_TOKEN_REVOKED=PASS"
}
finally {
    Remove-Item Env:BAO_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:BAO_ADDR -ErrorAction SilentlyContinue
    Remove-Item Env:BAO_CACERT -ErrorAction SilentlyContinue
    Remove-Item $TempTokenFile -Force -ErrorAction SilentlyContinue
    $rootToken = $null
    $oldToken = $null
    $newToken = $null
    $createRequest = $null
    $createJson = $null
    $created = $null
    $renewJson = $null
    $renewed = $null
    $revokeRequest = $null
    $probe = $null
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
