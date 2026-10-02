$ErrorActionPreference = "Stop"

$Gradle = "C:\LiliyaServer\bin\gradle-9.6.1\bin\gradle.bat"
$Repo = "C:\LiliyaServer\src\LiliyaLicensingService"
$Psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$AdminCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\postgres-admin-credential.dpapi"
$WriterCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\activation-postgres-writer-credential.dpapi"
$WriterRole = "liliya_activation_writer"
$JdbcUrl = "jdbc:postgresql://127.0.0.1:5432/liliya_licensing"

function Read-DpapiSecret {
    param([string] $Path)
    $secure = Get-Content $Path | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
        $secure = $null
    }
}

function New-RandomCredential {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    try {
        return [Convert]::ToBase64String($bytes)
    }
    finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Save-DpapiSecret {
    param([string] $Path, [string] $Value)
    $secure = ConvertTo-SecureString $Value -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -Path $Path -Encoding ASCII
    $secure = $null
}

function Invoke-AdminSql {
    param([string] $Sql, [string] $Credential)
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Psql
    foreach ($arg in @("-h","127.0.0.1","-p","5432","-U","postgres","-d","liliya_licensing","-v","ON_ERROR_STOP=1","-t","-A")) {
        $psi.ArgumentList.Add($arg)
    }
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.Environment["PGPASSWORD"] = $Credential

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $null = $process.Start()
    $process.StandardInput.WriteLine($Sql)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "PostgreSQL admin SQL failed."
    }
    return $stdout.Trim()
}

foreach ($required in @($Gradle, $Repo, $Psql)) {
    if (-not (Test-Path $required)) {
        throw "Required artifact missing: $required"
    }
}

if (-not (Test-Path $AdminCredentialFile)) {
    Write-Host "ACTIVATION_POSTGRES_PROVISIONING_BLOCKED"
    Write-Host "REASON=POSTGRES_ADMIN_CREDENTIAL_MISSING"
    exit 3
}

$adminCredential = Read-DpapiSecret $AdminCredentialFile
$writerCredential = $null

try {
    if (Test-Path $WriterCredentialFile) {
        $writerCredential = Read-DpapiSecret $WriterCredentialFile
    }
    else {
        $writerCredential = New-RandomCredential
        Save-DpapiSecret -Path $WriterCredentialFile -Value $writerCredential
    }

    $env:S7_9A_POSTGRES_URL = $JdbcUrl
    $env:S7_9A_POSTGRES_ADMIN_USER = "postgres"
    $env:S7_9A_POSTGRES_ADMIN_PASSWORD = $adminCredential

    Push-Location $Repo
    try {
        & $Gradle ":issuer-deployment:runS79aSchemaMigration" "--offline" "--no-daemon" "--console=plain"
        if ($LASTEXITCODE -ne 0) {
            throw "Activation PostgreSQL schema migration failed."
        }
    }
    finally {
        Pop-Location
    }

    $exists = Invoke-AdminSql -Credential $adminCredential -Sql "SELECT 1 FROM pg_roles WHERE rolname = 'liliya_activation_writer';"
    $escaped = $writerCredential.Replace("'","''")

    if ([string]::IsNullOrWhiteSpace($exists)) {
        $roleSql = "CREATE ROLE liliya_activation_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    else {
        $roleSql = "ALTER ROLE liliya_activation_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $roleSql

    $grantSql = "REVOKE CREATE ON SCHEMA public FROM PUBLIC; " +
        "REVOKE CREATE ON SCHEMA public FROM liliya_licensing; " +
        "GRANT USAGE ON SCHEMA public TO liliya_licensing; " +
        "GRANT USAGE ON SCHEMA public TO liliya_activation_writer; " +
        "REVOKE CREATE ON SCHEMA public FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_entitlement FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_activation_redemption FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_device_binding FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_device_rebind_redemption FROM liliya_activation_writer; " +
        "GRANT INSERT (license_id, subject, product_id, features, version, signing_key_id, issued_at, not_before, expires_at, offline_lease_until, revocation_epoch, device_binding_required) " +
        "ON TABLE licensing_entitlement TO liliya_activation_writer; " +
        "GRANT SELECT (code_id, attempt_id, subject, product_id, installation_id, device_key_fingerprint, redeemed_at) " +
        "ON TABLE licensing_activation_redemption TO liliya_activation_writer; " +
        "GRANT INSERT (code_id, attempt_id, subject, product_id, installation_id, device_key_fingerprint, redeemed_at) " +
        "ON TABLE licensing_activation_redemption TO liliya_activation_writer; " +
        "GRANT INSERT (binding_id, subject, installation_id, device_key_fingerprint, status, bound_at, revoked_at) " +
        "ON TABLE licensing_device_binding TO liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_device_binding FROM liliya_licensing; " +
        "GRANT SELECT (subject, installation_id, device_key_fingerprint, status) " +
        "ON TABLE licensing_device_binding TO liliya_licensing;"
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $grantSql

    $env:PGPASSWORD = $writerCredential
    $verifySql = "SELECT " +
        "has_schema_privilege(current_user,'public','USAGE') AND " +
        "NOT has_schema_privilege(current_user,'public','CREATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','license_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','subject','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','product_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','features','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','version','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','signing_key_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','issued_at','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','not_before','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','expires_at','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','offline_lease_until','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','revocation_epoch','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','device_binding_required','INSERT') AND " +
        "NOT has_column_privilege(current_user,'licensing_entitlement','device_binding_epoch','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_activation_redemption','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_activation_redemption','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_activation_redemption','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_activation_redemption','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','code_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','attempt_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','subject','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','product_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','installation_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','device_key_fingerprint','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','redeemed_at','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','code_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','attempt_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','subject','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','product_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','installation_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','device_key_fingerprint','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_activation_redemption','redeemed_at','INSERT') AND " +
        "NOT has_column_privilege(current_user,'licensing_activation_redemption','attempt_id','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','binding_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','subject','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','installation_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','device_key_fingerprint','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','status','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','bound_at','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','revoked_at','INSERT') AND " +
        "NOT has_column_privilege(current_user,'licensing_device_binding','status','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_rebind_redemption','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_rebind_redemption','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_rebind_redemption','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_rebind_redemption','DELETE');"
    $verify = & $Psql -h 127.0.0.1 -p 5432 -U $WriterRole -d liliya_licensing -t -A -c $verifySql
    if ($LASTEXITCODE -ne 0) {
        throw "Activation PostgreSQL writer verification failed."
    }
    if (([string]$verify).Trim() -ne "t") {
        throw "Activation PostgreSQL writer privileges are not minimal."
    }

    $runtimeVerifySql = "SELECT " +
        "has_schema_privilege(current_user,'public','USAGE') AND " +
        "NOT has_schema_privilege(current_user,'public','CREATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','subject','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','installation_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','device_key_fingerprint','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','status','SELECT') AND " +
        "NOT has_column_privilege(current_user,'licensing_device_binding','revoked_at','SELECT');"
    $runtimeCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\postgres-password.dpapi"
    if (-not (Test-Path $runtimeCredentialFile)) {
        throw "Runtime PostgreSQL credential container missing."
    }
    $runtimeCredential = Read-DpapiSecret $runtimeCredentialFile
    try {
        $env:PGPASSWORD = $runtimeCredential
        $runtimeVerify = & $Psql -h 127.0.0.1 -p 5432 -U "liliya_licensing" -d liliya_licensing -t -A -c $runtimeVerifySql
        if ($LASTEXITCODE -ne 0) {
            throw "Runtime PostgreSQL device-binding verification failed."
        }
        if (([string]$runtimeVerify).Trim() -ne "t") {
            throw "Runtime PostgreSQL device-binding privileges are not read-only."
        }
    }
    finally {
        $runtimeCredential = $null
        $runtimeVerify = $null
    }

    Write-Host "ACTIVATION_POSTGRES_PROVISIONING_READY"
    Write-Host "SCHEMA=READY"
    Write-Host "WRITER_ROLE=liliya_activation_writer"
    Write-Host "MINIMAL_GRANTS=PASS"
}
finally {
    foreach ($name in @("S7_9A_POSTGRES_URL","S7_9A_POSTGRES_ADMIN_USER","S7_9A_POSTGRES_ADMIN_PASSWORD","PGPASSWORD")) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    $adminCredential = $null
    $writerCredential = $null
    $escaped = $null
    $roleSql = $null
    $grantSql = $null
    $verify = $null
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}