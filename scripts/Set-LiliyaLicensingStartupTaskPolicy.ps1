param(
    [switch] $Apply,
    [string] $TaskName = "Liliya Licensing Service Startup",
    [string] $ExpectedStartupScript = "C:\\LiliyaServer\\bin\\startup\\Start-LiliyaLicensingService.ps1",
    [string] $BackupDirectory = "C:\\LiliyaServer\\backup\\startup-task-hardening"
)

$ErrorActionPreference = "Stop"

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
$exported = Export-ScheduledTask -TaskName $TaskName
[xml] $document = $exported
$namespace = New-Object System.Xml.XmlNamespaceManager($document.NameTable)
$namespace.AddNamespace("t", "http://schemas.microsoft.com/windows/2004/02/mit/task")

function Require-Node {
    param([string] $XPath)
    $node = $document.SelectSingleNode($XPath, $namespace)
    if ($null -eq $node) { throw "Required scheduled-task XML node missing: $XPath" }
    return $node
}

$actionCommand = Require-Node "/t:Task/t:Actions/t:Exec/t:Command"
$actionArguments = Require-Node "/t:Task/t:Actions/t:Exec/t:Arguments"
$delay = Require-Node "/t:Task/t:Triggers/t:LogonTrigger/t:Delay"
$executionTimeLimit = Require-Node "/t:Task/t:Settings/t:ExecutionTimeLimit"
$multipleInstances = Require-Node "/t:Task/t:Settings/t:MultipleInstancesPolicy"
$disallowStartOnBattery = Require-Node "/t:Task/t:Settings/t:DisallowStartIfOnBatteries"
$stopOnBattery = Require-Node "/t:Task/t:Settings/t:StopIfGoingOnBatteries"
$startWhenAvailable = Require-Node "/t:Task/t:Settings/t:StartWhenAvailable"
$restartCount = Require-Node "/t:Task/t:Settings/t:RestartOnFailure/t:Count"
$restartInterval = Require-Node "/t:Task/t:Settings/t:RestartOnFailure/t:Interval"

if ($actionCommand.InnerText -ne "powershell.exe" -or $actionArguments.InnerText -notmatch [regex]::Escape($ExpectedStartupScript)) {
    throw "Scheduled task action does not target the canonical production helper."
}

$expected = [ordered]@{
    Delay = "PT1M"
    ExecutionTimeLimit = "PT0S"
    MultipleInstancesPolicy = "IgnoreNew"
    DisallowStartIfOnBatteries = "false"
    StopIfGoingOnBatteries = "false"
    StartWhenAvailable = "true"
    RestartCount = "2"
    RestartInterval = "PT1M"
}

$observed = [ordered]@{
    Delay = $delay.InnerText
    ExecutionTimeLimit = $executionTimeLimit.InnerText
    MultipleInstancesPolicy = $multipleInstances.InnerText
    DisallowStartIfOnBatteries = $disallowStartOnBattery.InnerText
    StopIfGoingOnBatteries = $stopOnBattery.InnerText
    StartWhenAvailable = $startWhenAvailable.InnerText
    RestartCount = $restartCount.InnerText
    RestartInterval = $restartInterval.InnerText
}

$drift = @(
    foreach ($key in $expected.Keys) {
        if ([string] $observed[$key] -ne [string] $expected[$key]) {
            [pscustomobject]@{ Setting = $key; Expected = $expected[$key]; Observed = $observed[$key] }
        }
    }
)

if (-not $Apply) {
    foreach ($row in $drift) { Write-Host ("STARTUP_TASK_DRIFT=" + $row.Setting + "|" + $row.Observed + "->" + $row.Expected) }
    if ($drift.Count -gt 0) { Write-Host "LICENSING_STARTUP_TASK_POLICY=DRIFT"; exit 2 }
    Write-Host "LICENSING_STARTUP_TASK_POLICY=PASS"
    exit 0
}

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $BackupDirectory ("Liliya-Licensing-Service-Startup-pre-policy-" + $stamp + ".xml")
[System.IO.File]::WriteAllText($backupPath, $exported, [System.Text.Encoding]::Unicode)

$delay.InnerText = $expected.Delay
$executionTimeLimit.InnerText = $expected.ExecutionTimeLimit
$multipleInstances.InnerText = $expected.MultipleInstancesPolicy
$disallowStartOnBattery.InnerText = $expected.DisallowStartIfOnBatteries
$stopOnBattery.InnerText = $expected.StopIfGoingOnBatteries
$startWhenAvailable.InnerText = $expected.StartWhenAvailable
$restartCount.InnerText = $expected.RestartCount
$restartInterval.InnerText = $expected.RestartInterval

Register-ScheduledTask -TaskName $TaskName -Xml $document.OuterXml -Force | Out-Null

$verification = & $PSCommandPath -TaskName $TaskName -ExpectedStartupScript $ExpectedStartupScript -BackupDirectory $BackupDirectory
if ($LASTEXITCODE -ne 0) { throw "Scheduled task policy verification failed after apply." }
$verification
Write-Host ("STARTUP_TASK_BACKUP=" + $backupPath)
Write-Host "LICENSING_STARTUP_TASK_POLICY_APPLY=PASS"
