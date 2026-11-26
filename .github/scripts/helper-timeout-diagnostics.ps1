[CmdletBinding()]
param(
    [ValidateRange(12, 20)]
    [int] $Iterations = 16,
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [int] $IterationTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$testPattern = "*AccessibilityHelperClientTest*"
$resultDirectory = Join-Path $PWD "cli/build/test-results/test"
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Get-JavaProcesses {
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @("java.exe", "javaw.exe")
    } | Select-Object ProcessId, ParentProcessId, CreationDate, Name, CommandLine)
}

function Get-OwnedDescendants([int] $RootPid) {
    $all = @(Get-CimInstance Win32_Process)
    $owned = [Collections.Generic.HashSet[int]]::new()
    [void] $owned.Add($RootPid)
    do {
        $changed = $false
        foreach ($process in $all) {
            if ($owned.Contains([int] $process.ParentProcessId) -and
                $owned.Add([int] $process.ProcessId)) {
                $changed = $true
            }
        }
    } while ($changed)
    @($all | Where-Object { $owned.Contains([int] $_.ProcessId) })
}

function Stop-OwnedProcessTree([int] $RootPid) {
    # The watchdog may terminate only descendants of the Start-Process PID created below.
    # Deepest/highest PIDs first is best effort; no global process cleanup is permitted.
    $owned = @(Get-OwnedDescendants $RootPid | Sort-Object ProcessId -Descending)
    foreach ($process in $owned) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }
    $owned
}

$runs = @()
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    $runDirectory = Join-Path $output ("iteration-{0:D2}" -f $iteration)
    New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $resultDirectory

    $stdout = Join-Path $runDirectory "gradle.stdout.txt"
    $stderr = Join-Path $runDirectory "gradle.stderr.txt"
    $arguments = @(
        ":cli:test",
        "--tests", $testPattern,
        # Re-execute the test task itself without needlessly recompiling every dependency.
        "--rerun",
        "--no-build-cache",
        "--no-daemon",
        "--max-workers=4",
        "--stacktrace"
    )
    $before = @(Get-JavaProcesses)
    $started = Get-Date
    $process = Start-Process -FilePath (Join-Path $PWD "gradlew.bat") `
        -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -PassThru -NoNewWindow
    $remainingSamples = [Collections.Generic.List[object]]::new()
    $timedOut = $false
    while (-not $process.HasExited) {
        $remainingMs = [Math]::Max(0, $IterationTimeoutSeconds * 1000 -
            [int] ((Get-Date) - $started).TotalMilliseconds)
        $remainingSamples.Add([ordered]@{
            observedAt = (Get-Date).ToUniversalTime().ToString("o")
            remainingMs = $remainingMs
            rootPid = $process.Id
            rootAlive = -not $process.HasExited
            processes = @(Get-OwnedDescendants $process.Id | Select-Object `
                ProcessId, ParentProcessId, CreationDate, Name, CommandLine)
        })
        if ($remainingMs -eq 0) {
            $timedOut = $true
            $terminated = @(Stop-OwnedProcessTree $process.Id)
            break
        }
        Start-Sleep -Milliseconds 500
        $process.Refresh()
    }
    $process.WaitForExit()
    $finished = Get-Date

    $xmlFiles = @()
    if (Test-Path $resultDirectory) {
        foreach ($xml in Get-ChildItem $resultDirectory -Filter "TEST-*.xml") {
            $destination = Join-Path $runDirectory $xml.Name
            Copy-Item $xml.FullName $destination
            $xmlFiles += $destination
        }
    }
    $messages = @()
    $suppressed = @()
    foreach ($xmlPath in $xmlFiles) {
        [xml] $document = Get-Content -Raw $xmlPath
        foreach ($case in @($document.testsuite.testcase)) {
            foreach ($failure in @($case.failure) + @($case.error)) {
                if ($null -ne $failure) {
                    $messages += [string] $failure.message
                    $body = [string] $failure.InnerText
                    $suppressed += @($body -split "`r?`n" | Where-Object { $_ -match "Suppressed:" })
                }
            }
        }
    }
    $exitCode = if ($timedOut) { 124 } else { $process.ExitCode }
    $record = [ordered]@{
        iteration = $iteration
        startedAt = $started.ToUniversalTime().ToString("o")
        durationMs = [int] ($finished - $started).TotalMilliseconds
        exitCode = $exitCode
        timedOut = $timedOut
        rootPid = $process.Id
        rootAliveAfter = -not $process.HasExited
        lastRemainingTimes = @($remainingSamples | Select-Object -Last 20)
        processesBefore = $before
        processesAfter = @(Get-JavaProcesses)
        xmlFiles = @($xmlFiles | ForEach-Object { Split-Path -Leaf $_ })
        missingXml = $xmlFiles.Count -eq 0
        primaryErrorMessages = @($messages | Where-Object { $_ } | Select-Object -Unique)
        suppressedCauses = @($suppressed | Select-Object -Unique)
    }
    $record | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 `
        (Join-Path $runDirectory "diagnostics.json")
    $runs += $record
}

$failed = @($runs | Where-Object {
    $_.exitCode -ne 0 -or $_.missingXml -or $_.primaryErrorMessages.Count -gt 0
})
$distinct = @($failed.primaryErrorMessages | Where-Object { $_ } | Sort-Object -Unique)
$summary = [ordered]@{
    requestedRuns = $Iterations
    completedRuns = $runs.Count
    failedRuns = $failed.Count
    distinctPrimaryErrorMessages = $distinct
    runs = $runs
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 (Join-Path $output "summary.json")

Add-Content $env:GITHUB_STEP_SUMMARY "## AccessibilityHelperClientTest lane results"
Add-Content $env:GITHUB_STEP_SUMMARY "- Fresh runs: $($runs.Count) / $Iterations"
Add-Content $env:GITHUB_STEP_SUMMARY "- Failed runs: $($failed.Count)"
Add-Content $env:GITHUB_STEP_SUMMARY "- Distinct primary errors: $($distinct.Count)"
foreach ($message in $distinct) {
    Add-Content $env:GITHUB_STEP_SUMMARY "  - ``$message``"
}

if ($runs.Count -ne $Iterations -or $failed.Count -gt 0) { exit 1 }
