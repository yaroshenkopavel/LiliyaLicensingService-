$ErrorActionPreference = "Stop"

$Java =
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe"

$Dist =
    "C:\LiliyaServer\src\LiliyaLicensingService\issuer-deployment\build\install\issuer-deployment"

$Lib =
    "$Dist\lib"

$PgPassFile =
    "C:\LiliyaServer\backup\licensing-secrets\postgres-password.dpapi"

$ActivationWriterPassFile =
    "C:\LiliyaServer\backup\licensing-secrets\activation-postgres-writer-credential.dpapi"

$DeviceRebindWriterPassFile =
    "C:\LiliyaServer\backup\licensing-secrets\device-rebind-postgres-writer-credential.dpapi"

$OpenBaoTokenFile =
    "C:\LiliyaServer\backup\openbao-secrets\licensing-runtime-token.dpapi"

$TlsPassFile =
    "C:\LiliyaServer\backup\licensing-secrets\tls-keystore-password.dpapi"

$AuthSecretFile =
    "C:\LiliyaServer\backup\licensing-secrets\request-auth-secret.dpapi"

$TrustPassFile =
    "C:\LiliyaServer\backup\licensing-secrets\openbao-truststore-password.dpapi"

$TrustStore =
    "C:\LiliyaServer\config\licensing\trust\openbao-truststore.p12"

$TlsStore =
    "C:\LiliyaServer\config\licensing\tls\liliya-licensing-server.p12"

$LicensingCa =
    "C:\LiliyaServer\config\licensing\tls\licensing-ca.crt"

$LogDir =
    "C:\LiliyaServer\logs\licensing"

$StdoutLog =
    "$LogDir\licensing-service.stdout.log"

$StderrLog =
    "$LogDir\licensing-service.stderr.log"

$ArgFile =
    "C:\LiliyaServer\temp\licensing-jvm-" +
    [Guid]::NewGuid().ToString("N") +
    ".args"

function Read-DpapiSecret {
    param([string] $Path)

    $secure =
        Get-Content $Path |
        ConvertTo-SecureString

    $ptr =
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
            $secure
        )

    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
            $ptr
        )
    }
    finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(
                $ptr
            )
        }

        $secure = $null
    }
}

function Wait-ForPort {
    param(
        [int] $Port,
        [int] $Seconds = 60
    )

    for ($i = 0; $i -lt $Seconds; $i++) {
        if (
            Get-NetTCPConnection `
                -State Listen `
                -LocalPort $Port `
                -ErrorAction SilentlyContinue
        ) {
            return $true
        }

        Start-Sleep -Seconds 1
    }

    return $false
}

function Test-LicensingHealth {
    $health =
        & curl.exe `
            --silent `
            --show-error `
            --fail `
            --ssl-no-revoke `
            --cacert $LicensingCa `
            "https://127.0.0.1:8443/health/ready"

    if ($LASTEXITCODE -ne 0) {
        return $false
    }

    return (
        $health -match '"status"\s*:\s*"ready"'
    )
}

function Test-ActivationAndRebindRoutes {
    foreach ($path in @(
        "/v1/activation/redeem",
        "/v1/activation/rebind"
    )) {
        $status =
            & curl.exe `
                --silent `
                --show-error `
                --ssl-no-revoke `
                --cacert $LicensingCa `
                --output NUL `
                --write-out "%{http_code}" `
                --request POST `
                --header "Content-Type: application/json" `
                --data "{}" `
                ("https://127.0.0.1:8443" + $path)

        if ($LASTEXITCODE -ne 0 -or ([string]$status).Trim() -ne "400") {
            return $false
        }
    }

    return $true
}

foreach ($required in @(
    $Java,
    $Dist,
    $Lib,
    $PgPassFile,
    $OpenBaoTokenFile,
    $TlsPassFile,
    $AuthSecretFile,
    $TrustPassFile,
    $TrustStore,
    $TlsStore,
    $LicensingCa
)) {
    if (-not (Test-Path $required)) {
        throw "Required artifact missing: $required"
    }
}

New-Item -ItemType Directory -Force -Path $LogDir |
    Out-Null

New-Item -ItemType Directory -Force -Path "C:\LiliyaServer\temp" |
    Out-Null


# PostgreSQL gate
$postgresReady = $false

for ($i = 0; $i -lt 60; $i++) {
    $pg =
        Get-Service `
            postgresql-x64-16 `
            -ErrorAction SilentlyContinue

    if ($pg -and $pg.Status -eq "Running") {
        $postgresReady = $true
        break
    }

    Start-Sleep -Seconds 1
}

if (-not $postgresReady) {
    throw "PostgreSQL did not become ready."
}


# OpenBao dependency gate.
# Run the already-tested idempotent OpenBao launcher first so that
# Licensing never races against a still-sealed OpenBao instance.
$OpenBaoLauncher =
    "C:\LiliyaServer\bin\startup\Start-LiliyaOpenBao.ps1"

$openBaoOutput =
    & powershell.exe `
        -NoProfile `
        -NonInteractive `
        -ExecutionPolicy Bypass `
        -File $OpenBaoLauncher

if ($LASTEXITCODE -ne 0) {
    throw "OpenBao startup/unseal launcher failed."
}

if (
    ($openBaoOutput -join "`n") -notmatch
    "OPENBAO_STARTUP_READY"
) {
    throw "OpenBao launcher did not report readiness."
}

if (-not (Wait-ForPort -Port 8200 -Seconds 60)) {
    throw "OpenBao API listener did not become ready."
}


# Idempotent existing-service gate
$existing =
    Get-NetTCPConnection `
        -State Listen `
        -LocalPort 8443 `
        -ErrorAction SilentlyContinue

if ($existing) {

    $existingPid =
        (
            $existing |
            Select-Object -First 1
        ).OwningProcess

    if (-not $existingPid) {
        throw "8443 is occupied unexpectedly."
    }

    $existingProcess =
        Get-CimInstance Win32_Process `
            -Filter "ProcessId=$existingPid"

    if (
        -not $existingProcess -or
        $existingProcess.Name -ne "java.exe" -or
        $existingProcess.CommandLine -notmatch
            "LicensingDeploymentMainKt"
    ) {
        throw "8443 is owned by an unexpected process."
    }

    if (-not (Test-LicensingHealth)) {
        throw "Existing Licensing Service is unhealthy."
    }

    if (
        (Test-Path $ActivationWriterPassFile) -and
        (Test-Path $DeviceRebindWriterPassFile) -and
        -not (Test-ActivationAndRebindRoutes)
    ) {
        throw "Existing Licensing Service lacks activation/rebind routes; controlled restart required."
    }

    Write-Host "LICENSING_SERVICE_ALREADY_RUNNING"
    Write-Host "PID=$existingPid"
    exit 0
}

# A fresh production start must never silently disable activation/rebind.
foreach ($requiredWriterCredential in @(
    $ActivationWriterPassFile,
    $DeviceRebindWriterPassFile
)) {
    if (-not (Test-Path $requiredWriterCredential)) {
        throw "Required production licensing writer credential missing: $requiredWriterCredential"
    }
}

$pgPassword =
    Read-DpapiSecret $PgPassFile

$activationWriterPassword =
    Read-DpapiSecret $ActivationWriterPassFile

$deviceRebindWriterPassword =
    Read-DpapiSecret $DeviceRebindWriterPassFile

$openBaoToken =
    Read-DpapiSecret $OpenBaoTokenFile

$tlsPassword =
    Read-DpapiSecret $TlsPassFile

$authSecret =
    Read-DpapiSecret $AuthSecretFile

$trustPassword =
    Read-DpapiSecret $TrustPassFile

$process = $null
$startupSucceeded = $false

try {
    $env:LILIYA_ENVIRONMENT =
        "PRODUCTION"

    $env:LILIYA_LISTENER_HOST =
        "0.0.0.0"

    $env:LILIYA_LISTENER_PORT =
        "8443"

    $env:LILIYA_POSTGRES_JDBC_URL =
        "jdbc:postgresql://127.0.0.1:5432/liliya_licensing"

    $env:LILIYA_POSTGRES_USERNAME =
        "liliya_licensing"

    $env:LILIYA_POSTGRES_PASSWORD =
        $pgPassword

    $env:LILIYA_OPENBAO_ADDRESS =
        "https://127.0.0.1:8200"

    $env:LILIYA_OPENBAO_KEY_REFERENCE =
        "liliya-prod-license-signing-v1"

    $env:LILIYA_OPENBAO_TOKEN =
        $openBaoToken

    $env:LILIYA_REQUEST_AUTH_IDENTITY_REFERENCE =
        "liliya-prod-request-auth-v1"

    $env:LILIYA_REQUEST_AUTH_SECRET =
        $authSecret

    $env:LILIYA_TLS_IDENTITY_REFERENCE =
        "liliya-prod-tls-v1"

    $env:LILIYA_TLS_KEYSTORE_PATH =
        $TlsStore

    $env:LILIYA_TLS_KEYSTORE_PASSWORD =
        $tlsPassword

    $env:LILIYA_OPENBAO_KEY_NAME =
        "license-signing"

    $env:LILIYA_OPENBAO_KEY_VERSION =
        "1"

    $env:LILIYA_SERVICE_STATE_OPENBAO_KEY_REFERENCE =
        "liliya-prod-service-state-v1"

    $env:LILIYA_SERVICE_STATE_OPENBAO_KEY_NAME =
        "license-service-state"

    $env:LILIYA_SERVICE_STATE_OPENBAO_KEY_VERSION =
        "1"

    $env:LILIYA_ACTIVATION_KEY_ID =
        "activation-key-v1"

    $env:LILIYA_ACTIVATION_OPENBAO_KEY_NAME =
        "activation-signing"

    $env:LILIYA_ACTIVATION_OPENBAO_KEY_VERSION =
        "1"

    $env:LILIYA_ACTIVATION_POSTGRES_USERNAME =
        "liliya_activation_writer"

    $env:LILIYA_ACTIVATION_POSTGRES_CREDENTIAL =
        $activationWriterPassword

    # Zero is interpreted by the production composition as no expiry.
    $env:LILIYA_ACTIVATION_ENTITLEMENT_LIFETIME_SECONDS =
        "0"

    # Zero is interpreted as no offline lease deadline.
    $env:LILIYA_ACTIVATION_OFFLINE_LEASE_SECONDS =
        "0"

    $env:LILIYA_DEVICE_REBIND_POSTGRES_USERNAME =
        "liliya_device_rebind_writer"

    $env:LILIYA_DEVICE_REBIND_POSTGRES_CREDENTIAL =
        $deviceRebindWriterPassword


    # No JAVA_TOOL_OPTIONS.
    # Keep truststore password out of JVM diagnostic output
    # and out of the Java command line.
    @(
        "-Djavax.net.ssl.trustStore=$TrustStore",
        "-Djavax.net.ssl.trustStoreType=PKCS12",
        "-Djavax.net.ssl.trustStorePassword=$trustPassword"
    ) |
        Set-Content `
            -Path $ArgFile `
            -Encoding ASCII


    # Restrict temporary plaintext JVM argument file.
    # Use security identifiers instead of localized Windows account names.
    $CurrentUserSid =
        [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value

    & icacls.exe `
        $ArgFile `
        /inheritance:r `
        /grant:r `
        "*$CurrentUserSid`:(F)" `
        "*S-1-5-18`:(F)" |
        Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to secure temporary JVM argument file."
    }


    Remove-Item $StdoutLog -Force -ErrorAction SilentlyContinue
    Remove-Item $StderrLog -Force -ErrorAction SilentlyContinue


    $process =
        Start-Process `
            -FilePath $Java `
            -ArgumentList @(
                "@$ArgFile",
                "-cp",
                "$Lib\*",
                "pro.liliya.licensing.deployment.LicensingDeploymentMainKt"
            ) `
            -WorkingDirectory $Dist `
            -RedirectStandardOutput $StdoutLog `
            -RedirectStandardError $StderrLog `
            -WindowStyle Hidden `
            -PassThru


    $ready = $false

    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1

        $process.Refresh()

        if ($process.HasExited) {
            throw "Licensing Service exited before RUNTIME_READY."
        }

        $text = ""

        if (Test-Path $StdoutLog) {
            $text += Get-Content $StdoutLog -Raw
        }

        if (Test-Path $StderrLog) {
            $text += Get-Content $StderrLog -Raw
        }

        if ($text -match "RUNTIME_READY") {
            $ready = $true
            break
        }
    }

    if (-not $ready) {
        throw "RUNTIME_READY was not observed."
    }


    if (-not (Test-LicensingHealth)) {
        throw "HTTPS readiness failed."
    }

    if (-not (Test-ActivationAndRebindRoutes)) {
        throw "Activation/rebind route readiness failed."
    }


    $listener =
        Get-NetTCPConnection `
            -State Listen `
            -LocalPort 8443 `
            -ErrorAction Stop

    if (
        $listener.LocalAddress -notcontains "0.0.0.0" -and
        $listener.LocalAddress -notcontains "::"
    ) {
        throw "Licensing Service is not bound to all interfaces."
    }

    if ($listener.OwningProcess -notcontains $process.Id) {
        throw "8443 is not owned by spawned Licensing Service."
    }


    # Secret-leak guard without printing the secret.
    foreach ($log in @(
        $StdoutLog,
        $StderrLog
    )) {
        if (Test-Path $log) {
            $logContent =
                Get-Content `
                    $log `
                    -Raw `
                    -ErrorAction SilentlyContinue

            foreach ($secretValue in @(
                $pgPassword,
                $activationWriterPassword,
                $deviceRebindWriterPassword,
                $openBaoToken,
                $tlsPassword,
                $authSecret,
                $trustPassword
            )) {
                if (
                    $null -ne $logContent -and
                    -not [string]::IsNullOrEmpty($secretValue) -and
                    $logContent.Contains($secretValue)
                ) {
                    throw "SECURITY FAILURE: credential material appeared in service log."
                }
            }
        }
    }

    $logContent = $null


    $startupSucceeded = $true

    Write-Host "RUNTIME_READY"
    Write-Host "HTTPS_READY"
    Write-Host "SECRET_LOG_GUARD=PASS"
    Write-Host "PID=$($process.Id)"
    Write-Host "LICENSING_SERVICE_STARTUP_READY"
}
catch {
    if (
        $process -and
        -not $process.HasExited
    ) {
        Stop-Process `
            -Id $process.Id `
            -Force `
            -ErrorAction SilentlyContinue
    }

    throw
}
finally {
    Remove-Item `
        $ArgFile `
        -Force `
        -ErrorAction SilentlyContinue

    @(
        "LILIYA_ENVIRONMENT",
        "LILIYA_LISTENER_HOST",
        "LILIYA_LISTENER_PORT",
        "LILIYA_POSTGRES_JDBC_URL",
        "LILIYA_POSTGRES_USERNAME",
        "LILIYA_POSTGRES_PASSWORD",
        "LILIYA_OPENBAO_ADDRESS",
        "LILIYA_OPENBAO_KEY_REFERENCE",
        "LILIYA_OPENBAO_TOKEN",
        "LILIYA_REQUEST_AUTH_IDENTITY_REFERENCE",
        "LILIYA_REQUEST_AUTH_SECRET",
        "LILIYA_TLS_IDENTITY_REFERENCE",
        "LILIYA_TLS_KEYSTORE_PATH",
        "LILIYA_TLS_KEYSTORE_PASSWORD",
        "LILIYA_OPENBAO_KEY_NAME",
        "LILIYA_OPENBAO_KEY_VERSION",
        "LILIYA_SERVICE_STATE_OPENBAO_KEY_REFERENCE",
        "LILIYA_SERVICE_STATE_OPENBAO_KEY_NAME",
        "LILIYA_SERVICE_STATE_OPENBAO_KEY_VERSION",
        "LILIYA_ACTIVATION_KEY_ID",
        "LILIYA_ACTIVATION_OPENBAO_KEY_NAME",
        "LILIYA_ACTIVATION_OPENBAO_KEY_VERSION",
        "LILIYA_ACTIVATION_POSTGRES_USERNAME",
        "LILIYA_ACTIVATION_POSTGRES_CREDENTIAL",
        "LILIYA_ACTIVATION_ENTITLEMENT_LIFETIME_SECONDS",
        "LILIYA_ACTIVATION_OFFLINE_LEASE_SECONDS",
        "LILIYA_DEVICE_REBIND_POSTGRES_USERNAME",
        "LILIYA_DEVICE_REBIND_POSTGRES_CREDENTIAL"
    ) | ForEach-Object {
        Remove-Item `
            "Env:$_" `
            -ErrorAction SilentlyContinue
    }

    $pgPassword = $null
    $activationWriterPassword = $null
    $deviceRebindWriterPassword = $null
    $openBaoToken = $null
    $tlsPassword = $null
    $authSecret = $null
    $trustPassword = $null

    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}

if (-not $startupSucceeded) {
    exit 1
}

exit 0





