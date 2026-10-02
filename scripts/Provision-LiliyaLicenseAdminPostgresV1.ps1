$ErrorActionPreference = "Stop"

$Psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$AdminCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\postgres-admin-credential.dpapi"
$OperatorCredentialFile = "C:\LiliyaServer\backup\licensing-secrets\license-admin-postgres-credential.dpapi"
$OperatorRole = "liliya_license_admin"

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
    foreach ($arg in @(
        "-h","127.0.0.1",
        "-p","5432",
        "-U","postgres",
        "-d","liliya_licensing",
        "-v","ON_ERROR_STOP=1",
        "-t","-A"
    )) {
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
        throw "PostgreSQL license-admin SQL failed."
    }
    return $stdout.Trim()
}

if (-not (Test-Path $Psql)) {
    throw "Required artifact missing: $Psql"
}

if (-not (Test-Path $AdminCredentialFile)) {
    Write-Host "LICENSE_ADMIN_POSTGRES_PROVISIONING_BLOCKED"
    Write-Host "REASON=POSTGRES_ADMIN_CREDENTIAL_MISSING"
    exit 3
}

$adminCredential = Read-DpapiSecret $AdminCredentialFile
$operatorCredential = $null

try {
    if (Test-Path $OperatorCredentialFile) {
        $operatorCredential = Read-DpapiSecret $OperatorCredentialFile
    }
    else {
        $operatorCredential = New-RandomCredential
        Save-DpapiSecret -Path $OperatorCredentialFile -Value $operatorCredential
    }

    $exists = Invoke-AdminSql -Credential $adminCredential -Sql "SELECT 1 FROM pg_roles WHERE rolname = 'liliya_license_admin';"
    $escaped = $operatorCredential.Replace("'","''")

    if ([string]::IsNullOrWhiteSpace($exists)) {
        $roleSql = "CREATE ROLE liliya_license_admin LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    else {
        $roleSql = "ALTER ROLE liliya_license_admin LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + $escaped + "';"
    }
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $roleSql

    $grantSql =
        "GRANT USAGE ON SCHEMA public TO liliya_license_admin; " +
        "REVOKE ALL ON TABLE licensing_entitlement FROM liliya_license_admin; " +
        "REVOKE ALL ON TABLE licensing_activation_redemption FROM liliya_license_admin; " +
        "REVOKE ALL ON TABLE licensing_device_binding FROM liliya_license_admin; " +
        "GRANT UPDATE ON TABLE licensing_entitlement TO liliya_license_admin; " +
        "GRANT UPDATE ON TABLE licensing_device_binding TO liliya_license_admin;"
    $null = Invoke-AdminSql -Credential $adminCredential -Sql $grantSql

    $env:PGPASSWORD = $operatorCredential
    $verifySql =
        "SELECT " +
        "has_table_privilege(current_user,'licensing_entitlement','SELECT')," +
        "has_table_privilege(current_user,'licensing_entitlement','INSERT')," +
        "has_table_privilege(current_user,'licensing_entitlement','UPDATE')," +
        "has_table_privilege(current_user,'licensing_entitlement','DELETE')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','SELECT')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','INSERT')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','UPDATE')," +
        "has_table_privilege(current_user,'licensing_activation_redemption','DELETE')," +
        "has_table_privilege(current_user,'licensing_device_binding','SELECT')," +
        "has_table_privilege(current_user,'licensing_device_binding','INSERT')," +
        "has_table_privilege(current_user,'licensing_device_binding','UPDATE')," +
        "has_table_privilege(current_user,'licensing_device_binding','DELETE');"

    $verify = & $Psql -h 127.0.0.1 -p 5432 -U $OperatorRole -d liliya_licensing -t -A -c $verifySql
    if ($LASTEXITCODE -ne 0) {
        throw "License-admin PostgreSQL privilege verification failed."
    }
    if (([string]$verify).Trim() -ne "f|f|t|f|f|f|f|f|f|f|t|f") {
        throw "License-admin PostgreSQL privileges are not minimal."
    }

    Write-Host "LICENSE_ADMIN_POSTGRES_PROVISIONING_READY"
    Write-Host "OPERATOR_ROLE=liliya_license_admin"
    Write-Host "MINIMAL_GRANTS=PASS"
}
finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $adminCredential = $null
    $operatorCredential = $null
    $escaped = $null
    $roleSql = $null
    $grantSql = $null
    $verify = $null
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
