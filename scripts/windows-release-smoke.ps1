#Requires -Version 5.1
<#
.SYNOPSIS
  One-shot Windows pre-tag release smoke (agent UI e2e + WGC + packaged CLI).

.DESCRIPTION
  Run from a logged-in interactive desktop on a physical Windows box (e.g. Mattone).
  Does not need multiple terminals: Gradle e2e spawns fixtures; CLI uses `spectre launch --once`.

  PowerShell note: Gradle -P properties with dots must be quoted (this script does that).

  `spectre launch -- ... gradlew ...` prints a Gradle-ish warning by design -- still a valid smoke.

  Encoding: this file is ASCII-only so Windows PowerShell 5.1 can parse it without a UTF-8 BOM
  (WinPS 5.1 defaults to the system ANSI code page for BOM-less sources).

  Invocation: prefer one of the .EXAMPLE one-liners below. Direct `.\scripts\...` often fails under
  the default LocalMachine Restricted ExecutionPolicy. Always pass -ExecutionPolicy Bypass on the
  process (does not change machine policy) for both pwsh and Windows PowerShell 5.1.

.EXAMPLE
  # Preferred when PowerShell 7+ is installed (Bypass is process-scoped only):
  pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1

.EXAMPLE
  # Windows PowerShell 5.1 / stock powershell.exe:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1

.EXAMPLE
  pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1 -SkipWgc -SkipCli
#>
[CmdletBinding()]
param(
    [string] $Version = "0.5.0",
    [string] $Base = "",
    [switch] $SkipAgentE2e,
    [switch] $SkipWgc,
    [switch] $SkipCli,
    [switch] $SkipPackageCli,
    [switch] $SkipCheck,
    [switch] $SkipMavenLocal,
    # Operator attestation that headed two-JVM Robot contention was run on this desktop.
    # Without it the hard input-coord-headed-robot cell stays n/a with a blocking reason; the
    # automated coordinator cells never satisfy it (they pass headless and under SSH).
    [string] $HeadedRobotEvidence = "",
    # Schema/preflight self-check only: remaining required IDs are hard n/a with reason.
    # Not a release GO.
    [switch] $PreflightOnly,
    [ValidateRange(1, 86400)][int] $AgentE2eTimeoutSeconds = 900,
    [ValidateRange(1, 86400)][int] $WgcTimeoutSeconds = 300,
    [ValidateRange(1, 86400)][int] $PackageCliTimeoutSeconds = 900,
    [ValidateRange(1, 86400)][int] $CliLaunchTimeoutSeconds = 300,
    [ValidateRange(1, 86400)][int] $CheckTimeoutSeconds = 1200,
    [ValidateRange(1, 86400)][int] $MavenLocalTimeoutSeconds = 1200
)

# Must match scripts/smoke_lib.py REQUIRED_SCENARIO_IDS (fail-closed matrix completeness).
$script:RequiredScenarioIds = @(
    "preflight",
    "check",
    "junit-live",
    "agent-attach-core",
    "agent-contract-corpus",
    "agent-inject",
    "agent-launch-and-attach",
    "cli-packaged",
    "cli-native-helper-layout",
    "cli-user-flow",
    "mcp-sdk-flow",
    "host-native-recording",
    "maven-local-consumer",
    "portal-token-warmup",
    "pointer-move",
    # Experimental desktop input coordination (#459) delta hard cells -- hard on Windows too
    # (coordinator protocol tests need no display; run under SSH as well as an interactive console).
    "input-coord-contention",
    "input-coord-cancellation",
    "input-coord-quarantine",
    "input-coord-revoke",
    "input-coord-forced-recovery",
    "input-coord-junit-pertest",
    "input-coord-headed-robot"
)
$ErrorActionPreference = "Stop"
# Avoid StrictMode edge cases with dynamic PSCustomObject / native interop on WinPS 5.1.
Set-StrictMode -Version 1

function Get-RepoRoot {
    $here = $PSScriptRoot
    if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }
    $root = (Resolve-Path (Join-Path $here "..")).Path
    if (-not (Test-Path (Join-Path $root "settings.gradle.kts"))) {
        throw "Could not find repo root above $here (missing settings.gradle.kts)"
    }
    return $root
}

# Single source of truth: scripts/smoke_lib.py SCHEMA_VERSION (do not hardcode a parallel constant).
function Get-SmokeSchemaVersion {
    param([Parameter(Mandatory = $true)][string] $RepoRoot)
    $smokeLib = Join-Path $RepoRoot "scripts\smoke_lib.py"
    if (-not (Test-Path -LiteralPath $smokeLib)) {
        throw "smoke_lib.py missing at $smokeLib (required for schemaVersion)"
    }
    $text = [System.IO.File]::ReadAllText($smokeLib)
    $m = [regex]::Match($text, '(?m)^SCHEMA_VERSION\s*=\s*(\d+)\s*$')
    if (-not $m.Success) {
        throw "Could not parse SCHEMA_VERSION from $smokeLib"
    }
    return [int]$m.Groups[1].Value
}

function ConvertTo-ArgString {
    # Start-Process on Windows PowerShell 5.1 is unreliable with string[] ArgumentList
    # ("argument types do not match"). Build one escaped argument string instead.
    param([Parameter(Mandatory = $true)][string[]] $Args)
    $parts = foreach ($a in $Args) {
        if ($null -eq $a) { '""'; continue }
        $s = [string]$a
        if ($s -match '[\s"]') {
            '"' + ($s.Replace('"', '\"')) + '"'
        }
        else {
            $s
        }
    }
    return ($parts -join ' ')
}

function Write-LogFile {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-Content -LiteralPath $Path -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host $_
    }
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $WorkingDirectory,
        [ValidateRange(1, 86400)][int] $TimeoutSeconds = 900,
        [string] $LogName = "smoke"
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        throw "Executable not found: $FilePath"
    }
    $logDir = Join-Path $WorkingDirectory "build\smoke"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmssfff"
    $stdout = Join-Path $logDir ("{0}-{1}.out.log" -f $LogName, $stamp)
    $stderr = Join-Path $logDir ("{0}-{1}.err.log" -f $LogName, $stamp)

    $argString = ConvertTo-ArgString -Args $Arguments
    Write-Host ("> {0} {1}" -f $FilePath, $argString) -ForegroundColor DarkGray

    # Start-Process on WinPS 5.1 can lose the native exit code after asynchronous
    # waiting. ProcessStartInfo keeps one process handle for timeout and ExitCode.
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $argString
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::new()
    $p.StartInfo = $startInfo
    if (-not $p.Start()) { throw "Failed to start: $FilePath" }

    $stdoutTask = $p.StandardOutput.ReadToEndAsync()
    $stderrTask = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        Write-Host ("TIMEOUT after {0}s; terminating process tree rooted at PID {1}" -f $TimeoutSeconds, $p.Id) -ForegroundColor Red
        & taskkill.exe /PID $p.Id /T /F 2>$null | Out-Null
        try { $p.WaitForExit(10000) | Out-Null } catch { }
        try { [System.IO.File]::WriteAllText($stdout, $stdoutTask.Result) } catch { }
        try { [System.IO.File]::WriteAllText($stderr, $stderrTask.Result) } catch { }
        Write-LogFile -Path $stdout
        Write-LogFile -Path $stderr
        throw ("{0} timed out after {1}s (logs: {2} ; {3})" -f $FilePath, $TimeoutSeconds, $stdout, $stderr)
    }

    $p.WaitForExit()
    [System.IO.File]::WriteAllText($stdout, $stdoutTask.Result)
    [System.IO.File]::WriteAllText($stderr, $stderrTask.Result)
    Write-LogFile -Path $stdout
    Write-LogFile -Path $stderr
    $code = [int]$p.ExitCode
    if ($code -ne 0) {
        throw ("{0} exited with code {1} (logs: {2} ; {3})" -f $FilePath, $code, $stdout, $stderr)
    }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string[]] $GradleArgs,
        [ValidateRange(1, 86400)][int] $TimeoutSeconds = 900,
        [string] $LogName = "gradle"
    )
    $gradlew = Join-Path $RepoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found at $gradlew" }
    $allArgs = [string[]](@($GradleArgs) + @("--console=plain"))
    Invoke-Native -FilePath $gradlew -Arguments $allArgs -WorkingDirectory $RepoRoot -TimeoutSeconds $TimeoutSeconds -LogName $LogName
}

function New-StepResult {
    param(
        [Parameter(Mandatory = $true)][string] $Id,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Result,
        [int] $Seconds = 0,
        [string] $Detail = "",
        [string] $Reason = "",
        [string] $Log = "",
        [bool] $Hard = $true
    )
    # Fail-closed: hard n/a without reason becomes fail (matches scripts/smoke_lib.py).
    $finalResult = $Result
    $finalDetail = $Detail
    if ($Result -eq "n/a" -and $Hard -and [string]::IsNullOrWhiteSpace($Reason)) {
        $finalResult = "fail"
        $finalDetail = "hard skip without N/A reason"
    }
    # Use a single case form only: PowerShell member names are case-insensitive, so
    # camelCase + TitleCase aliases on the same PSObject collide and overwrite.
    $o = New-Object PSObject
    Add-Member -InputObject $o -MemberType NoteProperty -Name "id" -Value $Id
    Add-Member -InputObject $o -MemberType NoteProperty -Name "name" -Value $Name
    Add-Member -InputObject $o -MemberType NoteProperty -Name "result" -Value $finalResult
    Add-Member -InputObject $o -MemberType NoteProperty -Name "seconds" -Value $Seconds
    Add-Member -InputObject $o -MemberType NoteProperty -Name "detail" -Value $finalDetail
    Add-Member -InputObject $o -MemberType NoteProperty -Name "reason" -Value $Reason
    Add-Member -InputObject $o -MemberType NoteProperty -Name "log" -Value $Log
    Add-Member -InputObject $o -MemberType NoteProperty -Name "hard" -Value $Hard
    return $o
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string] $Id,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][scriptblock] $Action
    )
    Write-Host ""
    Write-Host ("==== {0} ({1}) ====" -f $Id, $Name) -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        # Swallow success-stream noise from the action so it cannot become our return value.
        $null = . $Action
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        Write-Host ("PASS  {0}  ({1}s)" -f $Id, $secs) -ForegroundColor Green
        return (New-StepResult -Id $Id -Name $Name -Result "pass" -Seconds $secs)
    }
    catch {
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        $msg = [string]$_.Exception.Message
        Write-Host ("FAIL  {0}  ({1}s): {2}" -f $Id, $secs, $msg) -ForegroundColor Red
        return (New-StepResult -Id $Id -Name $Name -Result "fail" -Seconds $secs -Detail $msg)
    }
}

function Get-PackagedSpectre {
    param([string] $RepoRoot)
    $candidates = @(
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\spectre.exe"),
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\Spectre.exe")
    )
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) {
            return [string]((Resolve-Path -LiteralPath $c).Path)
        }
    }
    return $null
}

function Get-PythonForSmoke {
    # Prefer a real python.exe (Mattone: Local\Programs\Python\Python313). The `py` launcher
    # is resolved to sys.executable so Invoke-Native can run the smoke script without -3.
    $candidates = @("python", "python3", "py")
    foreach ($name in $candidates) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -eq $cmd) { continue }
        $path = [string]$cmd.Source
        if ([string]::IsNullOrWhiteSpace($path)) { continue }
        try {
            if ($name -eq "py") {
                $exe = & $path -3 -c "import sys; print(sys.executable)" 2>$null
            }
            else {
                $exe = & $path -c "import sys; print(sys.executable)" 2>$null
            }
            if ($LASTEXITCODE -ne 0) { continue }
            $resolved = ([string]$exe).Trim()
            if (-not [string]::IsNullOrWhiteSpace($resolved) -and (Test-Path -LiteralPath $resolved)) {
                return $resolved
            }
        }
        catch { }
    }
    return $null
}

function Assert-McpFixtureE2eExecuted {
    param([Parameter(Mandatory = $true)][string] $RepoRoot)
    # JUnit XML under cli/build/test-results/test - require the MCP fixture testcase ran
    # (not skipped). tools/list-only is insufficient for hard pass after #414.
    $resultsDir = Join-Path $RepoRoot "cli\build\test-results\test"
    if (-not (Test-Path -LiteralPath $resultsDir)) {
        throw "MCP e2e test results missing under $resultsDir (Gradle did not write JUnit XML)"
    }
    $xmlFiles = @(Get-ChildItem -LiteralPath $resultsDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue)
    if ($xmlFiles.Count -eq 0) {
        throw "MCP e2e produced no TEST-*.xml under $resultsDir"
    }
    $foundMcp = $false
    foreach ($f in $xmlFiles) {
        $raw = [string](Get-Content -LiteralPath $f.FullName -Raw -ErrorAction SilentlyContinue)
        if ([string]::IsNullOrWhiteSpace($raw)) { continue }
        if ($raw -notmatch "MCP stdio drives") { continue }
        $foundMcp = $true
        if ($raw -match "<skipped") {
            throw "MCP fixture e2e was skipped (assumption); hard pass requires attach/op/detach on a headed desktop with -Pspectre.agent.attachE2e.allowWindows=true"
        }
        if ($raw -match 'failures="[1-9]' -or $raw -match 'errors="[1-9]') {
            throw "MCP fixture e2e reported failures/errors in $($f.Name)"
        }
    }
    if (-not $foundMcp) {
        throw "MCP fixture e2e testcase not found in JUnit XML under $resultsDir"
    }
}

function Get-PointerMoveSkipReason {
    param([Parameter(Mandatory = $true)][string] $RepoRoot)
    $path = Join-Path $RepoRoot "core\src\main\kotlin\dev\sebastiano\spectre\core\ComposeAutomator.kt"
    if (-not (Test-Path -LiteralPath $path)) {
        return "ComposeAutomator.kt missing; cannot prove #433 pointer-move verbs"
    }
    $text = [string](Get-Content -LiteralPath $path -Raw -ErrorAction SilentlyContinue)
    $hasMoveTo = $text -match '(?m)^\s*(public\s+)?(suspend\s+)?fun\s+moveTo\s*\('
    $hasMoveBy = $text -match '(?m)^\s*(public\s+)?(suspend\s+)?fun\s+moveBy\s*\('
    if ($hasMoveTo -and $hasMoveBy) { return $null }
    $missing = @()
    if (-not $hasMoveTo) { $missing += "moveTo" }
    if (-not $hasMoveBy) { $missing += "moveBy" }
    return ("ComposeAutomator.{0} not shipped (#433)" -f ($missing -join "/"))
}

function Assert-PointerMoveLiveExecuted {
    param([Parameter(Mandatory = $true)][string] $RepoRoot)
    $resultsDir = Join-Path $RepoRoot "sample-desktop\build\test-results\validationTest"
    if (-not (Test-Path -LiteralPath $resultsDir)) {
        throw "pointer-move test results missing under $resultsDir (Gradle did not write validationTest JUnit XML)"
    }
    $xmlFiles = @(Get-ChildItem -LiteralPath $resultsDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue)
    if ($xmlFiles.Count -eq 0) {
        throw "pointer-move produced no TEST-*.xml under $resultsDir"
    }
    $found = $false
    foreach ($f in $xmlFiles) {
        $raw = [string](Get-Content -LiteralPath $f.FullName -Raw -ErrorAction SilentlyContinue)
        if ([string]::IsNullOrWhiteSpace($raw)) { continue }
        if ($raw -notmatch "PointerMoveLive") { continue }
        $found = $true
        if ($raw -match "<skipped") {
            throw "PointerMoveLive validation was skipped (assumption); hard pass requires a headed display and shipped moveTo/moveBy (#433)"
        }
        if ($raw -match 'failures="[1-9]' -or $raw -match 'errors="[1-9]') {
            throw "PointerMoveLive reported failures/errors in $($f.Name)"
        }
    }
    if (-not $found) {
        throw "PointerMoveLive testcase not found in JUnit XML under $resultsDir"
    }
}

function Get-InputCoordinationSkipReason {
    param([Parameter(Mandatory = $true)][string] $RepoRoot)
    # Hard N/A only when the experimental coordination test surface is gone (regression signal).
    # These are hard cells on every OS per the spectre-release skill; Experimental does not make
    # them soft. Mirrors smoke_lib.input_coordination_smoke_skip_reason.
    $sources = @(
        "input-coordinator-server\src\test\kotlin\dev\sebastiano\spectre\input\server\LocalCoordinatorServerTest.kt",
        "testing\src\test\kotlin\dev\sebastiano\spectre\testing\InputIsolationLifecycleTest.kt"
    )
    foreach ($rel in $sources) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $rel))) {
            $name = Split-Path -Leaf $rel
            return ("experimental input coordination test surface missing ({0}); re-scope the release gate before smoking coordination" -f $name)
        }
    }
    return $null
}

function Assert-JUnitTestcasesPassed {
    param(
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string] $ResultsSubPath,
        [Parameter(Mandatory = $true)][string[]] $Needles,
        [Parameter(Mandatory = $true)][string] $Cell
    )
    # Fail closed: Gradle --tests exits 0 when a filter matches nothing or every method
    # assumption-skips, so a hard coordination pass must prove each named testcase actually ran
    # (present, not <skipped/>, no suite failures/errors). Mirrors Assert-PointerMoveLiveExecuted.
    $resultsDir = Join-Path $RepoRoot $ResultsSubPath
    if (-not (Test-Path -LiteralPath $resultsDir)) {
        throw "$Cell test results missing under $resultsDir (Gradle did not write JUnit XML)"
    }
    $xmlFiles = @(Get-ChildItem -LiteralPath $resultsDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue)
    if ($xmlFiles.Count -eq 0) {
        throw "$Cell produced no TEST-*.xml under $resultsDir"
    }
    foreach ($needle in $Needles) {
        $found = $false
        foreach ($f in $xmlFiles) {
            $raw = [string](Get-Content -LiteralPath $f.FullName -Raw -ErrorAction SilentlyContinue)
            if ([string]::IsNullOrWhiteSpace($raw)) { continue }
            if ($raw -notmatch [regex]::Escape($needle)) { continue }
            $found = $true
            if ($raw -match "<skipped") {
                throw "$Cell testcase '$needle' was skipped (assumption); hard pass requires the coordination test to execute"
            }
            if ($raw -match 'failures="[1-9]' -or $raw -match 'errors="[1-9]') {
                throw "$Cell reported failures/errors for '$needle' in $($f.Name)"
            }
            break
        }
        if (-not $found) {
            throw "$Cell testcase '$needle' not found in JUnit XML under $resultsDir"
        }
    }
}

function Invoke-CoordinationCell {
    # Explicit-parameter cell runner (no closure over loop variables -- avoids the WinPS
    # dot-sourced-scriptblock scope gotcha). Returns a New-StepResult row.
    param(
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string] $Id,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Task,
        [Parameter(Mandatory = $true)][string[]] $Filters,
        [Parameter(Mandatory = $true)][string] $ResultsSubPath,
        [Parameter(Mandatory = $true)][string[]] $Needles,
        [string] $SkipReason = "",
        [ValidateRange(1, 86400)][int] $TimeoutSeconds = 600
    )
    if (-not [string]::IsNullOrWhiteSpace($SkipReason)) {
        return (New-StepResult -Id $Id -Name $Name -Result "n/a" -Reason $SkipReason)
    }
    Write-Host ""
    Write-Host ("==== {0} ({1}) ====" -f $Id, $Name) -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $gradleArgs = New-Object System.Collections.Generic.List[string]
        [void]$gradleArgs.Add($Task)
        foreach ($f in $Filters) {
            [void]$gradleArgs.Add("--tests")
            [void]$gradleArgs.Add($f)
        }
        [void]$gradleArgs.Add("--rerun-tasks")
        [void]$gradleArgs.Add("--no-build-cache")
        Invoke-Gradle -RepoRoot $RepoRoot -TimeoutSeconds $TimeoutSeconds -LogName $Id -GradleArgs ([string[]]$gradleArgs)
        Assert-JUnitTestcasesPassed -RepoRoot $RepoRoot -ResultsSubPath $ResultsSubPath -Needles $Needles -Cell $Id
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        Write-Host ("PASS  {0}  ({1}s)" -f $Id, $secs) -ForegroundColor Green
        return (New-StepResult -Id $Id -Name $Name -Result "pass" -Seconds $secs)
    }
    catch {
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        $msg = [string]$_.Exception.Message
        Write-Host ("FAIL  {0}  ({1}s): {2}" -f $Id, $secs, $msg) -ForegroundColor Red
        return (New-StepResult -Id $Id -Name $Name -Result "fail" -Seconds $secs -Detail $msg)
    }
}

function Get-GitText {
    param([string] $RepoRoot, [string[]] $GitArgs)
    try {
        $out = & git -C $RepoRoot @GitArgs 2>$null
        if ($LASTEXITCODE -ne 0) { return "" }
        return ([string]$out).Trim()
    }
    catch {
        return ""
    }
}

function Get-DisplayModeWindows {
    if ($env:SSH_CONNECTION) { return "windows-ssh" }
    $session = [string]$env:SESSIONNAME
    if ($session -and $session.ToUpper().StartsWith("RDP")) { return "windows-rdp" }
    return "windows-interactive"
}

function Save-VersionedSmokeReport {
    param(
        [Parameter(Mandatory = $true)][System.Collections.IList] $Results,
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string] $Version,
        [string] $Base = "",
        [Parameter(Mandatory = $true)][string] $StartedAt,
        [Parameter(Mandatory = $true)][string] $FinishedAt,
        [int] $OverallSeconds = 0
    )
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }

    $sha = Get-GitText -RepoRoot $RepoRoot -GitArgs @("rev-parse", "HEAD")
    $shaShort = Get-GitText -RepoRoot $RepoRoot -GitArgs @("rev-parse", "--short", "HEAD")
    $status = Get-GitText -RepoRoot $RepoRoot -GitArgs @("status", "--porcelain")
    $dirty = -not [string]::IsNullOrWhiteSpace($status)
    $dirtySummary = ""
    if ($dirty) {
        $lines = @($status -split "`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $dirtySummary = ("{0} path(s) dirty" -f $lines.Count)
    }
    if ([string]::IsNullOrWhiteSpace($Base)) {
        $Base = Get-GitText -RepoRoot $RepoRoot -GitArgs @("describe", "--tags", "--abbrev=0")
    }

    $envObj = New-Object PSObject
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "os" -Value "Windows"
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "osVersion" -Value ([string][System.Environment]::OSVersion.VersionString)
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "arch" -Value ([string]$env:PROCESSOR_ARCHITECTURE)
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "hostname" -Value ([string]$env:COMPUTERNAME)
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "user" -Value ([string]$env:USERNAME)
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "python" -Value ""
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "displayMode" -Value (Get-DisplayModeWindows)
    Add-Member -InputObject $envObj -MemberType NoteProperty -Name "java" -Value ""

    $scenarioList = New-Object System.Collections.ArrayList
    foreach ($r in @($Results)) {
        if ($null -eq $r) { continue }
        $row = New-Object PSObject
        Add-Member -InputObject $row -MemberType NoteProperty -Name "id" -Value ([string]$r.id)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "name" -Value ([string]$r.name)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "result" -Value ([string]$r.result)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "seconds" -Value ([int]$r.seconds)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "detail" -Value ([string]$r.detail)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "reason" -Value ([string]$r.reason)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "log" -Value ([string]$r.log)
        Add-Member -InputObject $row -MemberType NoteProperty -Name "hard" -Value ([bool]$r.hard)
        [void]$scenarioList.Add($row)
    }

    $schemaVersion = Get-SmokeSchemaVersion -RepoRoot $RepoRoot

    $report = New-Object PSObject
    Add-Member -InputObject $report -MemberType NoteProperty -Name "schemaVersion" -Value $schemaVersion
    Add-Member -InputObject $report -MemberType NoteProperty -Name "version" -Value $Version
    Add-Member -InputObject $report -MemberType NoteProperty -Name "base" -Value $Base
    Add-Member -InputObject $report -MemberType NoteProperty -Name "sha" -Value $sha
    Add-Member -InputObject $report -MemberType NoteProperty -Name "shaShort" -Value $shaShort
    Add-Member -InputObject $report -MemberType NoteProperty -Name "dirty" -Value $dirty
    Add-Member -InputObject $report -MemberType NoteProperty -Name "dirtySummary" -Value $dirtySummary
    Add-Member -InputObject $report -MemberType NoteProperty -Name "repoRoot" -Value $RepoRoot
    Add-Member -InputObject $report -MemberType NoteProperty -Name "startedAt" -Value $StartedAt
    Add-Member -InputObject $report -MemberType NoteProperty -Name "finishedAt" -Value $FinishedAt
    Add-Member -InputObject $report -MemberType NoteProperty -Name "overallSeconds" -Value $OverallSeconds
    Add-Member -InputObject $report -MemberType NoteProperty -Name "environment" -Value $envObj
    Add-Member -InputObject $report -MemberType NoteProperty -Name "scenarios" -Value @($scenarioList)

    $json = ConvertTo-Json -InputObject $report -Depth 8
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)

    # Markdown table for the release record (ASCII only).
    $mdPath = [System.IO.Path]::ChangeExtension($Path, ".md")
    $md = New-Object System.Text.StringBuilder
    [void]$md.AppendLine("# Release smoke report (Windows)")
    [void]$md.AppendLine("")
    [void]$md.AppendLine(("- **schemaVersion**: {0}" -f $schemaVersion))
    [void]$md.AppendLine(("- **version**: {0}" -f $Version))
    [void]$md.AppendLine(("- **base**: {0}" -f $Base))
    [void]$md.AppendLine(("- **sha**: ``{0}``" -f $sha))
    [void]$md.AppendLine(("- **dirty**: {0}" -f $dirty))
    [void]$md.AppendLine(("- **displayMode**: {0}" -f $envObj.displayMode))
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| ID | Name | Result | Seconds | Note |")
    [void]$md.AppendLine("| --- | --- | --- | ---: | --- |")
    foreach ($row in @($scenarioList)) {
        $note = $row.reason
        if ([string]::IsNullOrWhiteSpace($note)) { $note = $row.detail }
        $note = ([string]$note).Replace("|", "/").Replace("`n", " ")
        [void]$md.AppendLine(("| {0} | {1} | {2} | {3} | {4} |" -f $row.id, $row.name, $row.result, $row.seconds, $note))
    }
    [System.IO.File]::WriteAllText($mdPath, $md.ToString(), $utf8NoBom)
    return $mdPath
}

# --- main ---

function Write-HostGuidance {
    # Operator-facing reminder. Encoding/parse failures happen before any of this runs
    # (keep the file ASCII-only). Process-scoped Bypass makes effective policy Bypass, so
    # inspect LocalMachine/CurrentUser scopes for the stock Restricted case operators hit
    # with bare .\script.ps1.
    $ver = $PSVersionTable.PSVersion
    $edition = if ($PSVersionTable.ContainsKey("PSEdition")) { [string]$PSVersionTable.PSEdition } else { "Desktop" }
    Write-Host ("PowerShell: {0} ({1})" -f $ver, $edition) -ForegroundColor DarkGray
    # Stringify policy enums: Unrestricted is enum value 0 and is falsy under PowerShell
    # boolean coercion, so never use bare `$policy` in truthiness checks -- always [string].
    $processRaw = $null
    try { $processRaw = Get-ExecutionPolicy -Scope Process -ErrorAction SilentlyContinue } catch { $processRaw = $null }
    $processPolicy = if ($null -eq $processRaw) { "Undefined" } else { [string]$processRaw }
    if ($processPolicy -ne "Undefined") {
        Write-Host ("ExecutionPolicy (Process): {0}" -f $processPolicy) -ForegroundColor DarkGray
    }
    # Bare .\script.ps1 uses effective policy without Process override. Scope order:
    # CurrentUser outranks LocalMachine when CurrentUser is not Undefined.
    $userRaw = $null
    $machineRaw = $null
    try { $userRaw = Get-ExecutionPolicy -Scope CurrentUser -ErrorAction SilentlyContinue } catch { $userRaw = $null }
    try { $machineRaw = Get-ExecutionPolicy -Scope LocalMachine -ErrorAction SilentlyContinue } catch { $machineRaw = $null }
    $userPolicy = if ($null -eq $userRaw) { "Undefined" } else { [string]$userRaw }
    $machinePolicy = if ($null -eq $machineRaw) { "Undefined" } else { [string]$machineRaw }
    $bareScope = $null
    $barePolicy = $null
    if ($userPolicy -ne "Undefined") {
        $bareScope = "CurrentUser"
        $barePolicy = $userPolicy
    }
    elseif ($machinePolicy -ne "Undefined") {
        $bareScope = "LocalMachine"
        $barePolicy = $machinePolicy
    }
    if ($barePolicy -eq "Restricted" -or $barePolicy -eq "AllSigned") {
        Write-Host ("ExecutionPolicy ({0}): {1} -- bare .\script.ps1 may fail. Prefer:" -f $bareScope, $barePolicy) -ForegroundColor Yellow
        Write-Host "  pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1" -ForegroundColor Yellow
        Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows-release-smoke.ps1" -ForegroundColor Yellow
    }
}

try {
    if ($env:OS -ne "Windows_NT") {
        throw "This script is Windows-only (current OS: $([System.Environment]::OSVersion.VersionString))"
    }

    $repoRoot = Get-RepoRoot
    Set-Location $repoRoot
    $startedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $wall = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host "Windows release smoke" -ForegroundColor White
    Write-Host ("Repo: {0}" -f $repoRoot)
    Write-Host ("Version: {0}" -f $Version)
    $sha = Get-GitText -RepoRoot $repoRoot -GitArgs @("rev-parse", "--short", "HEAD")
    if ($sha) { Write-Host ("SHA:  {0}" -f $sha) }
    Write-Host ("Host: {0}  User: {1}" -f $env:COMPUTERNAME, $env:USERNAME)
    Write-Host ("displayMode: {0}" -f (Get-DisplayModeWindows))
    Write-HostGuidance

    $results = New-Object System.Collections.ArrayList

    # preflight
    $pre = Invoke-Step -Id "preflight" -Name "Environment / SHA / clean-tree preflight" -Action {
        $full = Get-GitText -RepoRoot $repoRoot -GitArgs @("rev-parse", "HEAD")
        if ([string]::IsNullOrWhiteSpace($full) -or $full.Length -lt 7) {
            throw ("invalid SHA: {0}" -f $full)
        }
        if ([string]::IsNullOrWhiteSpace($Version)) { throw "Version is required" }
    }
    [void]$results.Add($pre)

    if ($PreflightOnly) {
        # Schema/orchestration self-check: every required ID present; none silently omitted.
        foreach ($sid in $script:RequiredScenarioIds) {
            if ($sid -eq "preflight") { continue }
            [void]$results.Add((New-StepResult -Id $sid -Name ("{0} (not executed)" -f $sid) -Result "n/a" -Reason "preflight-only mode; scenario not executed"))
        }
        Write-Host "PREFLIGHT-ONLY: remaining scenarios recorded as hard n/a (not a full release smoke GO)" -ForegroundColor Yellow
    }
    elseif ($SkipCheck) {
        [void]$results.Add((New-StepResult -Id "check" -Name "./gradlew check" -Result "n/a" -Reason "skipped via -SkipCheck"))
    }
    else {
        $step = Invoke-Step -Id "check" -Name "./gradlew check" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $CheckTimeoutSeconds -LogName "check" -GradleArgs @("check")
        }
        [void]$results.Add($step)
    }

    if (-not $PreflightOnly) {
    # Live JUnit is Unix-primary in release-smoke.py; on Windows record explicit N/A unless expanded.
    [void]$results.Add((New-StepResult -Id "junit-live" -Name "Live JUnit failure artifacts/video and atomic capture" -Result "n/a" -Reason "Windows entrypoint prioritizes agent/WGC/CLI; run sample-desktop validationTest on macOS/Linux baseline"))

    $pointerSkip = Get-PointerMoveSkipReason -RepoRoot $repoRoot
    if ($pointerSkip) {
        [void]$results.Add((New-StepResult -Id "pointer-move" -Name "In-process moveTo/moveBy hover without click" -Result "n/a" -Reason $pointerSkip))
    }
    else {
        $step = Invoke-Step -Id "pointer-move" -Name "In-process moveTo/moveBy hover without click" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "pointer-move" -GradleArgs @(
                ":sample-desktop:validationTest",
                "--tests", "*PointerMoveLive*",
                "--rerun-tasks",
                "--no-build-cache"
            )
            Assert-PointerMoveLiveExecuted -RepoRoot $repoRoot
        }
        [void]$results.Add($step)
    }

    # Experimental desktop input coordination (#459 delta hard cells). Deterministic coordinator
    # protocol + forked-process + JUnit-isolation proofs; no display is needed, so these stay hard
    # on Windows including SSH (unlike the WGC / attach-screenshot cells). See docs/RELEASE-SMOKE.md
    # "Experimental input coordination release gate".
    $coordinationSkip = Get-InputCoordinationSkipReason -RepoRoot $repoRoot
    $serverResults = "input-coordinator-server\build\test-results\test"
    $testingResults = "testing\build\test-results\test"
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-contention" -Name "Two independent client JVMs take one desktop lease without interleaving" `
                -Task ":input-coordinator-server:test" `
                -Filters @("*TwoClientJvmContentionTest", "*LocalCoordinatorServerTest.two independent clients receive one desktop lease in FIFO order", "*CoordinatorProcessLauncherTest.forked coordinator accepts a real client lease") `
                -ResultsSubPath $serverResults `
                -Needles @("two independent client JVMs never hold the desktop lease at the same time", "two independent clients receive one desktop lease in FIFO order", "forked coordinator accepts a real client lease")))
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-cancellation" -Name "Cancelled queued waiter does not strand the next waiter" `
                -Task ":input-coordinator-server:test" `
                -Filters @("*LocalCoordinatorServerTest.interrupting a queued acquisition removes it without disturbing FIFO") `
                -ResultsSubPath $serverResults `
                -Needles @("interrupting a queued acquisition removes it without disturbing FIFO")))
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-quarantine" -Name "Crashed holder stays fenced/quarantined without stale ownership" `
                -Task ":input-coordinator-server:test" `
                -Filters @("*LocalCoordinatorServerTest.successor quarantines a crashed holder until exact-id unsafe recovery") `
                -ResultsSubPath $serverResults `
                -Needles @("successor quarantines a crashed holder until exact-id unsafe recovery")))
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-revoke" -Name "Exact-ID normal revoke cannot affect a newer lease" `
                -Task ":input-coordinator-server:test" `
                -Filters @("*LocalCoordinatorServerTest.exact-id revoke rejects stale observation and fences the actual holder") `
                -ResultsSubPath $serverResults `
                -Needles @("exact-id revoke rejects stale observation and fences the actual holder")))
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-forced-recovery" -Name "Explicit forced recovery reports unsafeTakeover and advances the queue" `
                -Task ":input-coordinator-server:test" `
                -Filters @("*LocalCoordinatorServerTest.explicit force advances FIFO and reports unsafe takeover") `
                -ResultsSubPath $serverResults `
                -Needles @("explicit force advances FIFO and reports unsafe takeover")))
    [void]$results.Add((Invoke-CoordinationCell -RepoRoot $repoRoot -SkipReason $coordinationSkip -TimeoutSeconds $AgentE2eTimeoutSeconds `
                -Id "input-coord-junit-pertest" -Name "Parallel JUnit PerTest serialises factory/body/evidence/teardown" `
                -Task ":testing:test" `
                -Filters @("*InputIsolationLifecycleTest", "*ParallelPerTestInputIsolationTest") `
                -ResultsSubPath $testingResults `
                -Needles @("InputIsolationLifecycleTest", "concurrent per-test invocations never hold the desktop lease at the same time", "the per-test lease is still held while failure evidence is captured")))

    # Headed two-JVM Robot contention: operator-run hard cell. SKILL.md requires *headed*
    # contention, and every automated cell above passes headless/SSH because none builds a
    # RobotDriver. Absent evidence this is a hard FAIL, not a reasoned n/a: the summary's failure
    # count ignores a reasoned n/a, so an n/a here would report success without the headed proof.
    $headedName = "Headed two-JVM Robot contention (operator-recorded)"
    if (-not [string]::IsNullOrWhiteSpace($HeadedRobotEvidence)) {
        [void]$results.Add((New-StepResult -Id "input-coord-headed-robot" -Name $headedName -Result "pass" -Detail ("operator evidence: {0}" -f $HeadedRobotEvidence)))
    }
    else {
        [void]$results.Add((New-StepResult -Id "input-coord-headed-robot" -Name $headedName -Result "fail" -Detail "headed two-JVM Robot contention is operator-run on a real desktop and was NOT recorded; the coordinator-protocol cells do not prove real-input non-interleaving, so this blocks the tag on any OS whose release notes claim headed coordination"))
    }

    if (-not $SkipAgentE2e) {
        # AgentAttachIntegration e2e includes WGC node screenshots (#362). Under SSH that is the
        # same environment-impossible class as host-native-recording -- hard n/a, never fake PASS.
        $attachDisplayMode = Get-DisplayModeWindows
        if ($attachDisplayMode -eq "windows-ssh") {
            [void]$results.Add((New-StepResult -Id "agent-attach-core" -Name "Agent attach with preinstalled core" -Result "n/a" -Reason "AgentAttachIntegration e2e includes WGC node screenshots; SSH cannot provide honest visual evidence (exit 5 / 0x80070424). Re-run from interactive console for hard PASS"))
        }
        else {
            $step = Invoke-Step -Id "agent-attach-core" -Name "Agent attach with preinstalled core" -Action {
                Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "agent-attach" -GradleArgs @(
                    ":agent:test",
                    "-Pspectre.agent.attachE2e.allowWindows=true",
                    # Interactive smoke desktop: keep the Robot keyboard paths, which are
                    # opt-in off CI so `./gradlew check` stays runnable on a machine in use
                    # (#444, #449). Only typeText is reachable here -- the contract corpus is
                    # n/a on Windows (see agent-contract-corpus below).
                    "-Pspectre.agent.realKeyboard=true",
                    "--tests", "*AgentAttachIntegration*",
                    "--rerun-tasks",
                    "--no-build-cache"
                )
            }
            [void]$results.Add($step)
        }

        [void]$results.Add((New-StepResult -Id "agent-contract-corpus" -Name "Agent contract corpus" -Result "n/a" -Reason "Windows agent corpus is covered by attach/inject/launch cells; full AgentContractCorpus stays on Linux/macOS Xvfb matrix"))

        $step = Invoke-Step -Id "agent-inject" -Name "Injected attach without preinstalled core" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "agent-inject" -GradleArgs @(
                ":agent:test",
                "-Pspectre.agent.attachE2e.allowWindows=true",
                "--tests", "*AgentInjectAttachIntegration*",
                "--rerun-tasks",
                "--no-build-cache"
            )
        }
        [void]$results.Add($step)

        $step = Invoke-Step -Id "agent-launch-and-attach" -Name "Launch-and-attach" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "agent-launch" -GradleArgs @(
                ":agent:test",
                "-Pspectre.agent.attachE2e.allowWindows=true",
                "--tests", "*LaunchAndAttachIntegration*",
                "--rerun-tasks",
                "--no-build-cache"
            )
        }
        [void]$results.Add($step)
    }
    else {
        $reason = "skipped via -SkipAgentE2e"
        [void]$results.Add((New-StepResult -Id "agent-attach-core" -Name "Agent attach with preinstalled core" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "agent-contract-corpus" -Name "Agent contract corpus" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "agent-inject" -Name "Injected attach without preinstalled core" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "agent-launch-and-attach" -Name "Launch-and-attach" -Result "n/a" -Reason $reason))
    }

    if (-not $SkipWgc) {
        $displayMode = Get-DisplayModeWindows
        if ($displayMode -eq "windows-ssh") {
            [void]$results.Add((New-StepResult -Id "host-native-recording" -Name "Host native recording (WGC)" -Result "n/a" -Reason "WGC requires native interactive console; SSH session cannot provide honest visual evidence (0x80070424 / black frames)"))
        }
        else {
            $step = Invoke-Step -Id "host-native-recording" -Name "Host native recording (WGC region)" -Action {
                Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $WgcTimeoutSeconds -LogName "wgc-region" -GradleArgs @(
                    ":recording:runWindowsGraphicsCaptureRegionSmoke"
                )
                $mp4 = Join-Path $env:TEMP "spectre-wgc-region-smoke.mp4"
                if (-not (Test-Path -LiteralPath $mp4)) { throw "Expected output missing: $mp4" }
                $len = [int64](Get-Item -LiteralPath $mp4).Length
                if ($len -lt 8192) { throw ("MP4 too small ({0} bytes): {1}" -f $len, $mp4) }
                Write-Host ("  mp4: {0} ({1} bytes)" -f $mp4, $len) -ForegroundColor DarkGray
            }
            [void]$results.Add($step)
        }
    }
    else {
        [void]$results.Add((New-StepResult -Id "host-native-recording" -Name "Host native recording (WGC)" -Result "n/a" -Reason "skipped via -SkipWgc"))
    }

    if (-not $SkipCli) {
        if (-not $SkipPackageCli) {
            $step = Invoke-Step -Id "cli-packaged" -Name "Release-shaped host CLI package (packageWindowsX64)" -Action {
                # Bake -Version into the package so MCP serverInfo.version matches strict stdio
                # (default gradle.properties VERSION_NAME is 0.1.0-SNAPSHOT on main).
                Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $PackageCliTimeoutSeconds -LogName "package-cli" -GradleArgs @(
                    ":cli:packageWindowsX64",
                    ("-PVERSION_NAME={0}" -f $Version)
                )
            }
            [void]$results.Add($step)
        }
        else {
            [void]$results.Add((New-StepResult -Id "cli-packaged" -Name "Release-shaped host CLI package (packageWindowsX64)" -Result "n/a" -Reason "skipped via -SkipPackageCli (reusing existing spectre.exe)"))
        }

        $spectrePath = Get-PackagedSpectre -RepoRoot $repoRoot
        if ($spectrePath) {
            [void]$results.Add((New-StepResult -Id "cli-native-helper-layout" -Name "Native-helper layout in packaged CLI" -Result "pass" -Detail ("found {0}" -f $spectrePath)))
        }
        else {
            [void]$results.Add((New-StepResult -Id "cli-native-helper-layout" -Name "Native-helper layout in packaged CLI" -Result "fail" -Detail "spectre.exe not found under cli\build\construo\windowsX64\roast\"))
        }

        $step = Invoke-Step -Id "cli-user-flow" -Name "Packaged spectre launch --once (fixture)" -Action {
            # Agent e2e attaches to Gradle-spawned fixtures. A reused daemon can retain the
            # extracted runtime path and make the next launch hand that path to a fresh JVM while
            # Windows still has transient file state around it. Use a fresh daemon for this
            # independent packaged-CLI cell.
            #
            # Product (#386): Gradle-ish launch expands default JVM_ATTACHABLE to 120s so cold
            # daemon start after --stop can finish before the app JVM is listed. Outer
            # CliLaunchTimeoutSeconds (default 300) must stay above that product budget.
            # Prefer UP-TO-DATE :agent-test-fixture classes (agent e2e above usually ensures this).
            $gradlew = Join-Path $repoRoot "gradlew.bat"
            Invoke-Native -FilePath $gradlew -WorkingDirectory $repoRoot -TimeoutSeconds 60 -LogName "gradle-stop-before-cli" -Arguments @(
                "--stop"
            )
            $spectre = Get-PackagedSpectre -RepoRoot $repoRoot
            if (-not $spectre) {
                throw "spectre.exe not found under cli\build\construo\windowsX64\roast\ -- run without -SkipPackageCli"
            }
            Write-Host ("  using {0}" -f $spectre) -ForegroundColor DarkGray
            Write-Host "  note: Gradle-ish launch warning is expected for ':agent-test-fixture:run' (product JVM_ATTACHABLE budget 120s for Gradle)" -ForegroundColor DarkGray
            Invoke-Native -FilePath $spectre -WorkingDirectory $repoRoot -TimeoutSeconds $CliLaunchTimeoutSeconds -LogName "spectre-launch" -Arguments @(
                "launch",
                "--once",
                "--app-name", "ComposeFixtureMain",
                "--",
                $gradlew,
                ":agent-test-fixture:run"
            )
        }
        [void]$results.Add($step)

        # #414: hard mcp-sdk-flow when packaging is claimed - attach/op/detach lifecycle via
        # DaemonFixture MCP e2e (Windows opt-in attach gate) + strict mcp-stdio-smoke.py.
        $mcpName = "Packaged MCP attach/op/detach lifecycle + strict stdio"
        if (-not $spectrePath) {
            [void]$results.Add((New-StepResult -Id "mcp-sdk-flow" -Name $mcpName -Result "fail" -Detail "packaged spectre.exe missing; cannot run MCP lifecycle"))
        }
        else {
            $mcpStep = Invoke-Step -Id "mcp-sdk-flow" -Name $mcpName -Action {
                # Fixture attach e2e (same opt-in as agent UI cells). Forces re-run so a prior
                # assumption-skip cannot UP-TO-DATE into a false hard pass.
                Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "mcp-sdk-e2e" -GradleArgs @(
                    ":cli:test",
                    # Same VERSION_NAME as package so SpectreMcpStdioIntegrationTest
                    # expectedMcpVersion() matches packaged serverInfo.version.
                    ("-PVERSION_NAME={0}" -f $Version),
                    "-Pspectre.agent.attachE2e.allowWindows=true",
                    "--tests", "*DaemonFixtureIntegrationTest.MCP stdio drives*",
                    "--tests", "*SpectreMcpStdioIntegrationTest*",
                    ("-Dspectre.cli.distributionExecutable={0}" -f $spectrePath),
                    "--rerun-tasks",
                    "--no-build-cache"
                )
                # Fail closed if the MCP fixture test was assumption-skipped (no fake PASS).
                Assert-McpFixtureE2eExecuted -RepoRoot $repoRoot
                $python = Get-PythonForSmoke
                if (-not $python) {
                    throw "Python 3 not found (python/py/python3); required for mcp-stdio-smoke.py strict leg"
                }
                $smokeScript = Join-Path $repoRoot "scripts\mcp-stdio-smoke.py"
                if (-not (Test-Path -LiteralPath $smokeScript)) {
                    throw "mcp-stdio-smoke.py missing at $smokeScript"
                }
                Invoke-Native -FilePath $python -WorkingDirectory $repoRoot -TimeoutSeconds 60 -LogName "mcp-stdio-smoke" -Arguments @(
                    $smokeScript,
                    "--expected-version", $Version,
                    "--",
                    $spectrePath
                )
            }
            if ($mcpStep.result -eq "pass") {
                $mcpStep.detail = "SDK e2e attach/op/detach session-gone + strict stdio (tools + unknown detach isError)"
            }
            [void]$results.Add($mcpStep)
        }
    }
    else {
        $reason = "skipped via -SkipCli"
        [void]$results.Add((New-StepResult -Id "cli-packaged" -Name "Release-shaped host CLI package" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "cli-native-helper-layout" -Name "Native-helper layout in packaged CLI" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "cli-user-flow" -Name "Packaged CLI user flow" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "mcp-sdk-flow" -Name "Packaged MCP attach/op/detach lifecycle + strict stdio" -Result "n/a" -Reason $reason))
    }

    [void]$results.Add((New-StepResult -Id "portal-token-warmup" -Name "Capture persistent ScreenCast restore token" -Result "n/a" -Reason "Windows does not use xdg-desktop-portal ScreenCast restore tokens"))

    if ($SkipMavenLocal) {
        [void]$results.Add((New-StepResult -Id "maven-local-consumer" -Name "Maven Local publication + fresh consumer" -Result "n/a" -Reason "skipped via -SkipMavenLocal"))
    }
    else {
        $smokeVersion = ("{0}-rc.smoke" -f $Version)
        $step = Invoke-Step -Id "maven-local-consumer" -Name "Maven Local publication + fresh consumer" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $MavenLocalTimeoutSeconds -LogName "maven-local" -GradleArgs @(
                "verifyMavenLocalPublication",
                ("-PVERSION_NAME={0}" -f $smokeVersion),
                "-PstubMacHelperForTesting"
            )
            # Fresh consumer: resolve published core jar from Maven Local (parity with Unix).
            $jar = Join-Path $env:USERPROFILE (".m2\repository\dev\sebastiano\spectre\spectre-core\{0}\spectre-core-{0}.jar" -f $smokeVersion)
            if (-not (Test-Path -LiteralPath $jar)) {
                throw ("Maven Local core jar missing after publish: {0}" -f $jar)
            }
            $len = [int64](Get-Item -LiteralPath $jar).Length
            if ($len -lt 1024) {
                throw ("Maven Local core jar unexpectedly tiny ({0} bytes): {1}" -f $len, $jar)
            }
            Write-Host ("  maven local jar: {0} ({1} bytes)" -f $jar, $len) -ForegroundColor DarkGray
        }
        [void]$results.Add($step)
    }

    } # end -not $PreflightOnly

    Write-Host ""
    Write-Host "==== Summary ====" -ForegroundColor White
    $failCount = 0
    $seenIds = @{}
    foreach ($r in $results) {
        if ($null -eq $r) {
            Write-Host "FAIL   <null step result>" -ForegroundColor Red
            $failCount++
            continue
        }
        $id = [string]$r.id
        if (-not [string]::IsNullOrWhiteSpace($id)) { $seenIds[$id] = $true }
        $result = [string]$r.result
        $secs = [int]$r.seconds
        $detail = [string]$r.detail
        $reason = [string]$r.reason
        $hard = [bool]$r.hard
        if ($hard -and $result -eq "fail") { $failCount++ }
        if ($hard -and $result -eq "n/a" -and [string]::IsNullOrWhiteSpace($reason)) { $failCount++ }
        $status = $result.ToUpper()
        $line = "{0,-6} {1,-28} {2,4}s" -f $status, $id, $secs
        $note = $reason
        if ([string]::IsNullOrWhiteSpace($note)) { $note = $detail }
        if ($note) { $line = $line + "  " + $note }
        if ($result -eq "pass") {
            Write-Host $line -ForegroundColor Green
        }
        elseif ($result -eq "n/a") {
            Write-Host $line -ForegroundColor Yellow
        }
        else {
            Write-Host $line -ForegroundColor Red
        }
    }

    # Fail-closed: every required stable ID must appear (no silent omit). Matches Unix validate_report.
    $missingIds = @()
    foreach ($req in $script:RequiredScenarioIds) {
        if (-not $seenIds.ContainsKey($req)) {
            $missingIds += $req
            Write-Host ("FAIL   missing required scenario id: {0}" -f $req) -ForegroundColor Red
            $failCount++
        }
    }

    $wall.Stop()
    $finishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $outDir = Join-Path $repoRoot "build\smoke"
    $reportPath = Join-Path $outDir "windows-release-smoke.json"
    $mdPath = Save-VersionedSmokeReport -Results $results -Path $reportPath -RepoRoot $repoRoot -Version $Version -Base $Base -StartedAt $startedAt -FinishedAt $finishedAt -OverallSeconds ([int]$wall.Elapsed.TotalSeconds)
    Write-Host ("Report JSON: {0}" -f $reportPath)
    Write-Host ("Report MD:   {0}" -f $mdPath)
    Write-Host ("Logs:        {0}" -f $outDir)

    if ($failCount -gt 0) {
        if ($missingIds.Count -gt 0) {
            Write-Host ("FAILED (missing required scenario id(s): {0})" -f ($missingIds -join ", ")) -ForegroundColor Red
        }
        else {
            Write-Host ("FAILED ({0} hard step(s))" -f $failCount) -ForegroundColor Red
        }
        exit 1
    }
    if ($PreflightOnly) {
        Write-Host "PREFLIGHT-ONLY OK (not a full release smoke GO)" -ForegroundColor Yellow
        exit 0
    }
    Write-Host "ALL HARD SCENARIOS PASSED (or explicit N/A with reason)" -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ""
    Write-Host ("SMOKE SCRIPT ERROR: {0}" -f $_.Exception.Message) -ForegroundColor Red
    if ($_.Exception.InnerException) {
        Write-Host ("  inner: {0}" -f $_.Exception.InnerException.Message) -ForegroundColor Red
    }
    Write-Host ("  type: {0}" -f $_.Exception.GetType().FullName) -ForegroundColor DarkGray
    exit 2
}
