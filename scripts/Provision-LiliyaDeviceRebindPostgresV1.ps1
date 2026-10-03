$ErrorActionPreference = "Stop"

$Gradle = "C:\LiliyaServer\bin\gradle-9.6.1\bin\gradle.bat"
$Repo = "C:\LiliyaServer\src\LiliyaLicensingService"
$Psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$AdminCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\postgres-admin-credential.dpapi"
$WriterCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\device-rebind-postgres-writer-credential.dpapi"
$WriterRole = "liliya_device_rebind_writer"
$JdbcUrl = "jdbc:postgresql://127.0.0.1:5432/liliya_licensing"

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

function New-RandomCredential {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    try { return [Convert]::ToBase64String($bytes) }
    finally { [Array]::Clear($bytes, 0, $bytes.Length) }
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
    $psi.Arguments = "-h 127.0.0.1 -p 5432 -U postgres -d liliya_licensing -v ON_ERROR_STOP=1 -t -A"
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
        throw "PostgreSQL device-rebind admin SQL failed."
    }
    return $stdout.Trim()
}

foreach ($required in @($Gradle, $Repo, $Psql)) {
    if (-not (Test-Path $required)) {
        throw "Required artifact missing: $required"
    }
}

if (-not (Test-Path $AdminCredentialFile)) {
    Write-Host "DEVICE_REBIND_POSTGRES_PROVISIONING_BLOCKED"
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
            throw "Device rebind PostgreSQL schema migration failed."
        }
    }
    finally {
        Pop-Location
    }

    $exists = Invoke-AdminSql -Credential $adminCredential -Sql "SELECT 1 FROM pg_roles WHERE rolname = 'liliya_device_rebind_writer';"
    $escaped = $writerCredential.Replace("'","''")
    if ([string]::IsNullOrWhiteSpace($exists)) {
        $roleSql = "CREATE ROLE liliya_device_rebind_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    else {
        $roleSql = "ALTER ROLE liliya_device_rebind_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $roleSql

    $grantSql =
        "GRANT CONNECT ON DATABASE liliya_licensing TO liliya_device_rebind_writer; " +
        "GRANT USAGE ON SCHEMA public TO liliya_device_rebind_writer; " +
        "REVOKE ALL ON TABLE licensing_entitlement FROM liliya_device_rebind_writer; " +
        "REVOKE ALL ON TABLE licensing_device_binding FROM liliya_device_rebind_writer; " +
        "REVOKE ALL ON TABLE licensing_device_rebind_redemption FROM liliya_device_rebind_writer; " +
        "GRANT SELECT (subject, product_id, revoked_at, device_binding_required, device_binding_epoch) " +
        "ON TABLE licensing_entitlement TO liliya_device_rebind_writer; " +
        "GRANT SELECT (subject, installation_id, device_key_fingerprint, status) " +
        "ON TABLE licensing_device_binding TO liliya_device_rebind_writer; " +
        "GRANT INSERT (binding_id, subject, installation_id, device_key_fingerprint, status, bound_at, revoked_at) " +
        "ON TABLE licensing_device_binding TO liliya_device_rebind_writer; " +
        "GRANT SELECT (code_id, attempt_id, subject, product_id, device_binding_epoch, installation_id, device_key_fingerprint, redeemed_at) " +
        "ON TABLE licensing_device_rebind_redemption TO liliya_device_rebind_writer; " +
        "GRANT INSERT (code_id, attempt_id, subject, product_id, device_binding_epoch, installation_id, device_key_fingerprint, redeemed_at) " +
        "ON TABLE licensing_device_rebind_redemption TO liliya_device_rebind_writer;"
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $grantSql

    $env:PGPASSWORD = $writerCredential
    $verifySql =
        "SELECT " +
        "has_database_privilege(current_user,'liliya_licensing','CONNECT') AND " +
        "NOT has_database_privilege(current_user,'liliya_licensing','CREATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_entitlement','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','subject','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','product_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','revoked_at','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','device_binding_required','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_entitlement','device_binding_epoch','SELECT') AND " +
        "NOT has_column_privilege(current_user,'licensing_entitlement','features','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','SELECT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','INSERT') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','UPDATE') AND " +
        "NOT has_table_privilege(current_user,'licensing_device_binding','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','subject','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','installation_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','device_key_fingerprint','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_binding','status','SELECT') AND " +
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
        "NOT has_table_privilege(current_user,'licensing_device_rebind_redemption','DELETE') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','code_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','attempt_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','subject','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','product_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','device_binding_epoch','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','installation_id','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','device_key_fingerprint','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','redeemed_at','SELECT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','code_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','attempt_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','subject','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','product_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','device_binding_epoch','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','installation_id','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','device_key_fingerprint','INSERT') AND " +
        "has_column_privilege(current_user,'licensing_device_rebind_redemption','redeemed_at','INSERT') AND " +
        "NOT has_column_privilege(current_user,'licensing_device_rebind_redemption','attempt_id','UPDATE');"
    $verify = & $Psql -h 127.0.0.1 -p 5432 -U $WriterRole -d liliya_licensing -t -A -c $verifySql
    if ($LASTEXITCODE -ne 0) {
        throw "Device rebind writer verification failed."
    }
    if (([string]$verify).Trim() -ne "t") {
        throw "Device rebind writer privileges are not minimal."
    }

    Write-Host "DEVICE_REBIND_POSTGRES_PROVISIONING_READY"
    Write-Host "WRITER_ROLE=liliya_device_rebind_writer"
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
