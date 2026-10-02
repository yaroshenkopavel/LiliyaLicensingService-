param(
    [string] $ManifestPath =
        "C:\LiliyaServer\config\acceptance\production-deployment-profile-nonsecret.template.json"
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param(
        [string] $Name,
        [bool] $Pass,
        [string] $Detail
    )
    $results.Add([PSCustomObject]@{
        name = $Name
        pass = $Pass
        detail = $Detail
    })
}

$ca = "C:\LiliyaServer\config\licensing\tls\licensing-ca.crt"
$publicKey = "C:\LiliyaServer\export\license-verification\license-signing-v1-public.pem"
$expectedPemSha256 = "f090e4f87a037edceb5bbeba25ba6aa3534b741873a0412270ccf56bf5c8059f"
Add-Check "manifest-present" (Test-Path $ManifestPath) $ManifestPath
if (-not (Test-Path $ManifestPath)) {
    $results | ConvertTo-Json -Depth 4
    exit 2
}

$manifest = Get-Content $ManifestPath -Raw | ConvertFrom-Json

$listener = Get-NetTCPConnection -State Listen -LocalPort 8443 -ErrorAction SilentlyContinue
Add-Check "licensing-listener-8443" ([bool]$listener) "TCP 8443 listener required"

$healthOk = $false
if (Test-Path $ca) {
    & curl.exe --silent --show-error --fail --ssl-no-revoke --cacert $ca "https://127.0.0.1:8443/health/ready" *> $null
    $healthOk = ($LASTEXITCODE -eq 0)
}
Add-Check "licensing-health-ca-verified" $healthOk "loopback HTTPS with deployment CA"

Add-Check "public-key-present" (Test-Path $publicKey) "public verification key only"
$keyHashOk = $false
if (Test-Path $publicKey) {
    $actual = (Get-FileHash $publicKey -Algorithm SHA256).Hash.ToLower()
    $keyHashOk = ($actual -eq $expectedPemSha256)
}
Add-Check "public-key-sha256" $keyHashOk "must match retained non-secret evidence"
$secretContainers = @(
    "C:\LiliyaServer\backup\licensing-secrets\postgres-password.dpapi",
    "C:\LiliyaServer\backup\openbao-secrets\licensing-runtime-token.dpapi",
    "C:\LiliyaServer\backup\licensing-secrets\tls-keystore-password.dpapi",
    "C:\LiliyaServer\backup\licensing-secrets\request-auth-secret.dpapi",
    "C:\LiliyaServer\backup\licensing-secrets\openbao-truststore-password.dpapi"
)
$containersOk = ($secretContainers | Where-Object { -not (Test-Path $_) }).Count -eq 0
Add-Check "secret-containers-present" $containersOk "presence only; contents are never read"

$approvalFlags = @(
    $manifest.transport.endpointApproved,
    $manifest.licenseRequest.protocolVersionApproved,
    $manifest.licenseRequest.operationApproved,
    $manifest.licenseRequest.productIdApproved,
    $manifest.licenseRequest.subjectReferencePolicyApproved,
    $manifest.licenseRequest.enrollmentPolicyApproved,
    $manifest.trust.licensePublicKeyApproved,
    $manifest.ownersAndPolicies.productAuthProvisioningOwnerApproved,
    $manifest.ownersAndPolicies.authorityOwnerApproved,
    $manifest.ownersAndPolicies.admissionOwnerApproved,
    $manifest.ownersAndPolicies.keyChoicePolicyApproved,
    $manifest.ownersAndPolicies.protectedModelBudgetsApproved,
    $manifest.ownersAndPolicies.stagingOwnerApproved,
    $manifest.ownersAndPolicies.preparedInputOwnersApproved,
    $manifest.ownersAndPolicies.semanticDirectoryNameApproved,
    $manifest.ownersAndPolicies.cognitiveStorageDirectoryNameApproved,
    $manifest.ownersAndPolicies.observabilityOwnerApproved
)
$approvalsOk = ($approvalFlags | Where-Object { $_ -ne $true }).Count -eq 0
Add-Check "external-production-approvals" $approvalsOk "all required non-secret approvals must be explicit"
$endpointCandidate = [string]$manifest.transport.endpointCandidate
$endpointHttps = $endpointCandidate.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)
Add-Check "candidate-endpoint-https" $endpointHttps "candidate endpoint must be HTTPS"

$insecureDisabled = ($manifest.transport.developmentAllowInsecureHttp -eq $false)
Add-Check "insecure-http-disabled" $insecureDisabled "must remain false for production"

$candidateTrustMatches = ([string]$manifest.trust.licensePublicKeyPemSha256Candidate -eq $expectedPemSha256)
Add-Check "candidate-trust-fingerprint" $candidateTrustMatches "candidate must match retained public-key evidence"

$timeoutPolicyReady = ($null -ne $manifest.transport.connectTimeoutMillis -and
    [int]$manifest.transport.connectTimeoutMillis -gt 0 -and
    $null -ne $manifest.transport.readTimeoutMillis -and
    [int]$manifest.transport.readTimeoutMillis -gt 0)
Add-Check "timeout-policy-approved-values-present" $timeoutPolicyReady "positive finite connect/read timeouts required"

$failed = @($results | Where-Object { -not $_.pass })
$summary = [PSCustomObject]@{
    verdict = if ($failed.Count -eq 0) { "PREFLIGHT_READY" } else { "PREFLIGHT_BLOCKED" }
    failedCount = $failed.Count
    checks = $results
}
$summary | ConvertTo-Json -Depth 6
if ($failed.Count -gt 0) { exit 2 }
exit 0
