param(
    [switch] $Replace
)

$ErrorActionPreference = "Stop"
$Psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$CredentialFile =
    "C:\LiliyaServer\backup\licensing-secrets\postgres-admin-credential.dpapi"

if (-not (Test-Path $Psql)) {
    throw "PostgreSQL psql executable missing."
}

if ((Test-Path $CredentialFile) -and -not $Replace) {
    Write-Host "POSTGRES_ADMIN_CREDENTIAL_SETUP_BLOCKED"
    Write-Host "REASON=CREDENTIAL_CONTAINER_ALREADY_EXISTS"
    exit 4
}

$secure = Read-Host "Enter PostgreSQL postgres password (input remains local)" -AsSecureString

if ($secure.Length -eq 0) {
    Write-Host "POSTGRES_ADMIN_CREDENTIAL_SETUP_BLOCKED"
    Write-Host "REASON=EMPTY_CREDENTIAL"
    exit 5
}
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$plain = $null

try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)

    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Psql
    foreach ($arg in @(
        "-h","127.0.0.1",
        "-p","5432",
        "-U","postgres",
        "-d","liliya_licensing",
        "-v","ON_ERROR_STOP=1",
        "-t","-A",
        "-c",
        "SELECT current_user || '|' || rolsuper || '|' || rolcreaterole || '|' || rolcreatedb FROM pg_roles WHERE rolname = current_user;"
    )) {
        $psi.ArgumentList.Add($arg)
    }

    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.Environment["PGPASSWORD"] = $plain

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $null = $process.Start()
    $stdout = $process.StandardOutput.ReadToEnd().Trim()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $psi.Environment.Remove("PGPASSWORD")

    if ($process.ExitCode -ne 0) {
        Write-Host "POSTGRES_ADMIN_CREDENTIAL_SETUP_BLOCKED"
        Write-Host "REASON=POSTGRES_ADMIN_AUTHENTICATION_FAILED"
        exit 6
    }

    if ($stdout -ne "postgres|t|t|t") {
        Write-Host "POSTGRES_ADMIN_CREDENTIAL_SETUP_BLOCKED"
        Write-Host "REASON=POSTGRES_ADMIN_CAPABILITIES_MISMATCH"
        exit 7
    }

    $directory = Split-Path -Parent $CredentialFile
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $secure | ConvertFrom-SecureString | Set-Content -Path $CredentialFile -Encoding ASCII

    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $CredentialFile /inheritance:r /grant:r "*$currentSid`:(F)" "*S-1-5-18`:(F)" | Out-Null

    if ($LASTEXITCODE -ne 0) {
        Remove-Item $CredentialFile -Force -ErrorAction SilentlyContinue
        throw "Failed to restrict PostgreSQL admin credential container ACL."
    }

    Write-Host "POSTGRES_ADMIN_CREDENTIAL_SETUP_READY"
    Write-Host "ROLE=postgres"
    Write-Host "CAPABILITIES=VERIFIED"
    Write-Host "DPAPI_CONTAINER=READY"
}
finally {
    if ($psi -ne $null) {
        $null = $psi.Environment.Remove("PGPASSWORD")
    }
    if ($process -ne $null) {
        $process.Dispose()
    }
    $plain = $null
    $secure = $null
    $process = $null
    $psi = $null
    if ($ptr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
