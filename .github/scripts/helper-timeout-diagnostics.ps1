[CmdletBinding()]
param(
    [ValidateSet("FullCheck", "RepeatClass")]
    [string] $Mode,
    [ValidateRange(1, 3)] [int] $Iterations = 3,
    [Parameter(Mandatory = $true)] [string] $OutputDirectory,
    [ValidateRange(300, 2100)] [int] $CommandTimeoutSeconds = 1800
)

$ErrorActionPreference = "Stop"
$className = "GradleLaunchMarkerIntegrationTest"
$testPattern = "*$className"
$resultDirectory = Join-Path $PWD "agent/build/test-results/test"
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Get-ProcessTable {
    @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CreationDate, Name, CommandLine)
}
function Get-Descendants([int] $RootPid) {
    $all = @(Get-ProcessTable)
    $owned = [Collections.Generic.HashSet[int]]::new()
    [void] $owned.Add($RootPid)
    do {
        $changed = $false
        foreach ($item in $all) {
            if ($owned.Contains([int] $item.ParentProcessId) -and $owned.Add([int] $item.ProcessId)) { $changed = $true }
        }
    } while ($changed)
    @($all | Where-Object { $owned.Contains([int] $_.ProcessId) })
}
function Get-FixtureDirectories {
    # JUnit @TempDir uses junit* roots. Restrict observation to this test's exact fixture names.
    $fixtureNames = @("overlap-a", "overlap-b", "no-daemon", "no-daemon-no-perfdata")
    foreach ($root in @(Get-ChildItem $env:TEMP -Directory -Filter "junit*" -ErrorAction SilentlyContinue)) {
        foreach ($name in $fixtureNames) {
            $fixture = Join-Path $root.FullName $name
            if (Test-Path $fixture -PathType Container) { Get-Item $fixture -ErrorAction SilentlyContinue }
            $fixtureGradleUserHome = Join-Path $root.FullName "$name-home"
            if (Test-Path $fixtureGradleUserHome -PathType Container) {
                Get-Item $fixtureGradleUserHome -ErrorAction SilentlyContinue
            }
        }
    }
}
function Copy-FixtureEvidence([string] $Destination) {
    New-Item -ItemType Directory -Force $Destination | Out-Null
    $manifest = [Collections.Generic.List[object]]::new()
    foreach ($fixture in @(Get-FixtureDirectories)) {
        $rootName = Split-Path $fixture.Parent.FullName -Leaf
        $copy = Join-Path $Destination "$rootName-$($fixture.Name)"
        New-Item -ItemType Directory -Force $copy | Out-Null
        foreach ($name in @("gradle.log", "stop.log", "warmup.log", "build.pid", "fixture.ready", "fixture.release")) {
            $source = Join-Path $fixture.FullName $name
            if (Test-Path $source -PathType Leaf) { Copy-Item $source (Join-Path $copy $name) -Force -ErrorAction SilentlyContinue }
        }
        foreach ($file in @(Get-ChildItem $fixture.FullName -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 2000)) {
            $manifest.Add([ordered]@{ path = $file.FullName; length = $file.Length; updated = $file.LastWriteTimeUtc })
        }
    }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $Destination "fixture-files.json")
}
function Copy-TestXml([string] $Destination) {
    New-Item -ItemType Directory -Force $Destination | Out-Null
    if (Test-Path $resultDirectory) {
        Get-ChildItem $resultDirectory -File -Filter "*$className*.xml" | ForEach-Object {
            Copy-Item $_.FullName (Join-Path $Destination $_.Name) -Force
        }
    }
}
function Get-FixtureOwnedProcesses {
    $pids = [Collections.Generic.HashSet[int]]::new()
    foreach ($fixture in @(Get-FixtureDirectories)) {
        foreach ($name in @("build.pid", "fixture.ready")) {
            $path = Join-Path $fixture.FullName $name
            if (Test-Path $path -PathType Leaf) {
                $first = @(Get-Content $path -ErrorAction SilentlyContinue)[0]
                $pidValue = 0
                if ([int]::TryParse($first, [ref] $pidValue)) { [void] $pids.Add($pidValue) }
            }
        }
    }
    $snapshot = @()
    foreach ($pidValue in $pids) { $snapshot += @(Get-Descendants $pidValue) }
    @($snapshot | Sort-Object ProcessId -Unique)
}
function Stop-OwnedProcessTree([int] $RootPid) {
    @(Get-Descendants $RootPid | Sort-Object ProcessId -Descending) | ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
}
function Invoke-DiagnosticGradle([string] $Receipt, [string[]] $Tasks) {
    New-Item -ItemType Directory -Force $Receipt | Out-Null
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $resultDirectory
    $stdout = Join-Path $Receipt "gradle.stdout.txt"
    $stderr = Join-Path $Receipt "gradle.stderr.txt"
    $arguments = @($Tasks) + @("--init-script", (Join-Path $output "test-logging.init.gradle"))
    $started = Get-Date
    $process = Start-Process -FilePath (Join-Path $PWD "gradlew.bat") -ArgumentList $arguments `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -NoNewWindow
    $samples = [Collections.Generic.List[object]]::new()
    $timedOut = $false
    while (-not $process.HasExited) {
        Copy-FixtureEvidence (Join-Path $Receipt "fixtures-live")
        $samples.Add([ordered]@{
            observedAt = (Get-Date).ToUniversalTime().ToString("o")
            gradle = @(Get-Descendants $process.Id)
            fixtures = @(Get-FixtureOwnedProcesses)
        })
        if (((Get-Date) - $started).TotalSeconds -ge $CommandTimeoutSeconds) {
            $timedOut = $true
            Stop-OwnedProcessTree $process.Id
            break
        }
        Start-Sleep -Seconds 5
        $process.Refresh()
    }
    $process.WaitForExit()
    Copy-FixtureEvidence (Join-Path $Receipt "fixtures-final")
    Copy-TestXml (Join-Path $Receipt "junit")
    $exitCode = if ($timedOut) { 124 } else { $process.ExitCode }
    if ($exitCode -ne 0) {
        @(Get-FixtureOwnedProcesses) | ConvertTo-Json -Depth 6 |
            Set-Content -Encoding utf8 (Join-Path $Receipt "failed-owned-fixture-processes.json")
    }
    $xml = @(Get-ChildItem (Join-Path $Receipt "junit") -Filter "TEST-*.xml" -ErrorAction SilentlyContinue)
    $matchingXml = @($xml | Where-Object { (Get-Content -Raw $_.FullName) -match $className })
    $record = [ordered]@{
        startedAt = $started.ToUniversalTime().ToString("o")
        durationMs = [int] ((Get-Date) - $started).TotalMilliseconds
        exitCode = $exitCode
        timedOut = $timedOut
        junitFiles = @($xml.Name)
        targetTestRan = $matchingXml.Count -gt 0
        observations = @($samples | Select-Object -Last 30)
    }
    $record | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $Receipt "receipt.json")
    $record
}

# Logging-only init script retains full exceptions, suppressed causes, and test streams.
@'
allprojects {
    tasks.withType(Test).configureEach {
        testLogging {
            events "failed", "standardOut", "standardError"
            exceptionFormat "full"
            showExceptions true
            showCauses true
            showStackTraces true
            showStandardStreams true
        }
    }
}
'@ | Set-Content -Encoding utf8 (Join-Path $output "test-logging.init.gradle")

$records = @()
if ($Mode -eq "FullCheck") {
    $records += Invoke-DiagnosticGradle (Join-Path $output "full-check") @("check")
} else {
    for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
        $receipt = Join-Path $output ("class-run-{0:D2}" -f $iteration)
        $records += Invoke-DiagnosticGradle $receipt @(
            ":agent:test", "--tests", $testPattern, "--rerun"
        )
    }
}
$records | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $output "summary.json")
$bad = @($records | Where-Object { $_.exitCode -ne 0 -or -not $_.targetTestRan })
Add-Content $env:GITHUB_STEP_SUMMARY "## Gradle launch-marker diagnostics: $Mode"
Add-Content $env:GITHUB_STEP_SUMMARY "- Completed commands: $($records.Count)"
Add-Content $env:GITHUB_STEP_SUMMARY "- Failed or missing target XML: $($bad.Count)"
if ($bad.Count -gt 0) { exit 1 }
