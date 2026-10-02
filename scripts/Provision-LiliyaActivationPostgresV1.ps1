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

    $grantSql = "GRANT USAGE ON SCHEMA public TO liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_entitlement FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_activation_redemption FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_device_binding FROM liliya_activation_writer; " +
        "REVOKE ALL ON TABLE licensing_device_rebind_redemption FROM liliya_activation_writer; " +
        "GRANT INSERT ON TABLE licensing_entitlement TO liliya_activation_writer; " +
        "GRANT SELECT, INSERT ON TABLE licensing_activation_redemption TO liliya_activation_writer; " +
        "GRANT INSERT ON TABLE licensing_device_binding TO liliya_activation_writer; " +
        "REVOKE INSERT, UPDATE, DELETE ON TABLE licensing_device_binding FROM liliya_licensing; " +
        "GRANT SELECT ON TABLE licensing_device_binding TO liliya_licensing;"
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $grantSql

    $env:PGPASSWORD = $writerCredential
    $verifySql = "SELECT " +
        "has_table_privilege(current_user,'licensing_entitlement','INSERT')," +
        "has_table_privilege(current_user,'licensing_entitlement','SELECT')," +
        "has_table_privilege(current_user,'licensing_entitlement','UPDATE')," +
        "has_table_privilege(current_user,'licensing_entitlement','DELETE')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','SELECT')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','INSERT')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','UPDATE')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','DELETE')," +
        "has_table_privilege(current_user,'licensing_device_binding','INSERT')," +
        "has_table_privilege(current_user,'licensing_device_binding','SELECT')," +
        "has_table_privilege(current_user,'licensing_device_binding','UPDATE')," +
        "has_table_privilege(current_user,'licensing_device_binding','DELETE')," +
        "has_table_privilege(current_user,'licensing_device_rebind_redemption','SELECT')," +
        "has_table_privilege(current_user,'licensing_device_rebind_redemption','INSERT')," +
        "has_table_privilege(current_user,'licensing_device_rebind_redemption','UPDATE')," +
        "has_table_privilege(current_user,'licensing_device_rebind_redemption','DELETE');"
    $verify = & $Psql -h 127.0.0.1 -p 5432 -U $WriterRole -d liliya_licensing -t -A -c $verifySql
    if ($LASTEXITCODE -ne 0) {
        throw "Activation PostgreSQL writer verification failed."
    }
    if (([string]$verify).Trim() -ne "t|f|f|f|t|t|f|f|t|f|f|f|f|f|f|f") {
        throw "Activation PostgreSQL writer privileges are not minimal."
    }

    $runtimeVerifySql = "SELECT " +
        "has_table_privilege(current_user,'licensing_device_binding','SELECT')," +
        "has_table_privilege(current_user,'licensing_device_binding','INSERT')," +
        "has_table_privilege(current_user,'licensing_device_binding','UPDATE')," +
        "has_table_privilege(current_user,'licensing_device_binding','DELETE');"
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
        if (([string]$runtimeVerify).Trim() -ne "t|f|f|f") {
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