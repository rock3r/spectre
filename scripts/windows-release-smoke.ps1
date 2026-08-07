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
    [ValidateRange(1, 86400)][int] $AgentE2eTimeoutSeconds = 900,
    [ValidateRange(1, 86400)][int] $WgcTimeoutSeconds = 300,
    [ValidateRange(1, 86400)][int] $PackageCliTimeoutSeconds = 900,
    [ValidateRange(1, 86400)][int] $CliLaunchTimeoutSeconds = 300,
    [ValidateRange(1, 86400)][int] $CheckTimeoutSeconds = 1200,
    [ValidateRange(1, 86400)][int] $MavenLocalTimeoutSeconds = 1200
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
    $o = New-Object PSObject
    Add-Member -InputObject $o -MemberType NoteProperty -Name "id" -Value $Id
    Add-Member -InputObject $o -MemberType NoteProperty -Name "name" -Value $Name
    Add-Member -InputObject $o -MemberType NoteProperty -Name "result" -Value $finalResult
    Add-Member -InputObject $o -MemberType NoteProperty -Name "seconds" -Value $Seconds
    Add-Member -InputObject $o -MemberType NoteProperty -Name "detail" -Value $finalDetail
    Add-Member -InputObject $o -MemberType NoteProperty -Name "reason" -Value $Reason
    Add-Member -InputObject $o -MemberType NoteProperty -Name "log" -Value $Log
    Add-Member -InputObject $o -MemberType NoteProperty -Name "hard" -Value $Hard
    # Legacy TitleCase fields kept for older operators grepping Name/Result.
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Name" -Value $Name
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Result" -Value $finalResult
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Seconds" -Value $Seconds
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Detail" -Value $finalDetail
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

    $report = New-Object PSObject
    Add-Member -InputObject $report -MemberType NoteProperty -Name "schemaVersion" -Value 1
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
    [void]$md.AppendLine(("- **schemaVersion**: {0}" -f 1))
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

    if ($SkipCheck) {
        [void]$results.Add((New-StepResult -Id "check" -Name "./gradlew check" -Result "n/a" -Reason "skipped via -SkipCheck"))
    }
    else {
        $step = Invoke-Step -Id "check" -Name "./gradlew check" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $CheckTimeoutSeconds -LogName "check" -GradleArgs @("check")
        }
        [void]$results.Add($step)
    }

    # Live JUnit is Unix-primary in release-smoke.py; on Windows record explicit N/A unless expanded.
    [void]$results.Add((New-StepResult -Id "junit-live" -Name "Live JUnit failure artifacts/video and atomic capture" -Result "n/a" -Reason "Windows entrypoint prioritizes agent/WGC/CLI; run sample-desktop validationTest on macOS/Linux baseline"))

    if (-not $SkipAgentE2e) {
        $step = Invoke-Step -Id "agent-attach-core" -Name "Agent attach with preinstalled core" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $AgentE2eTimeoutSeconds -LogName "agent-attach" -GradleArgs @(
                ":agent:test",
                "-Pspectre.agent.attachE2e.allowWindows=true",
                "--tests", "*AgentAttachIntegration*",
                "--rerun-tasks",
                "--no-build-cache"
            )
        }
        [void]$results.Add($step)

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
                Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $PackageCliTimeoutSeconds -LogName "package-cli" -GradleArgs @(
                    ":cli:packageWindowsX64"
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
            $gradlew = Join-Path $repoRoot "gradlew.bat"
            Invoke-Native -FilePath $gradlew -WorkingDirectory $repoRoot -TimeoutSeconds 60 -LogName "gradle-stop-before-cli" -Arguments @(
                "--stop"
            )
            $spectre = Get-PackagedSpectre -RepoRoot $repoRoot
            if (-not $spectre) {
                throw "spectre.exe not found under cli\build\construo\windowsX64\roast\ -- run without -SkipPackageCli"
            }
            Write-Host ("  using {0}" -f $spectre) -ForegroundColor DarkGray
            Write-Host "  note: Gradle-ish launch warning is expected for ':agent-test-fixture:run'" -ForegroundColor DarkGray
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

        [void]$results.Add((New-StepResult -Id "mcp-sdk-flow" -Name "Packaged MCP via official SDK" -Result "n/a" -Reason "MCP packaged e2e is automated on macOS/Linux release-smoke.py; re-run there or extend Windows when DaemonFixtureIntegration is EnabledOnOs Windows"))
    }
    else {
        $reason = "skipped via -SkipCli"
        [void]$results.Add((New-StepResult -Id "cli-packaged" -Name "Release-shaped host CLI package" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "cli-native-helper-layout" -Name "Native-helper layout in packaged CLI" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "cli-user-flow" -Name "Packaged CLI user flow" -Result "n/a" -Reason $reason))
        [void]$results.Add((New-StepResult -Id "mcp-sdk-flow" -Name "Packaged MCP via official SDK" -Result "n/a" -Reason $reason))
    }

    if ($SkipMavenLocal) {
        [void]$results.Add((New-StepResult -Id "maven-local-consumer" -Name "Maven Local publication + fresh consumer" -Result "n/a" -Reason "skipped via -SkipMavenLocal"))
    }
    else {
        $smokeVersion = ("{0}-rc.smoke" -f $Version)
        $step = Invoke-Step -Id "maven-local-consumer" -Name "Maven Local publication + shape verify" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -TimeoutSeconds $MavenLocalTimeoutSeconds -LogName "maven-local" -GradleArgs @(
                "verifyMavenLocalPublication",
                ("-PVERSION_NAME={0}" -f $smokeVersion),
                "-PstubMacHelperForTesting"
            )
        }
        [void]$results.Add($step)
    }

    Write-Host ""
    Write-Host "==== Summary ====" -ForegroundColor White
    $failCount = 0
    foreach ($r in $results) {
        if ($null -eq $r) {
            Write-Host "FAIL   <null step result>" -ForegroundColor Red
            $failCount++
            continue
        }
        $id = [string]$r.id
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

    $wall.Stop()
    $finishedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $outDir = Join-Path $repoRoot "build\smoke"
    $reportPath = Join-Path $outDir "windows-release-smoke.json"
    $mdPath = Save-VersionedSmokeReport -Results $results -Path $reportPath -RepoRoot $repoRoot -Version $Version -Base $Base -StartedAt $startedAt -FinishedAt $finishedAt -OverallSeconds ([int]$wall.Elapsed.TotalSeconds)
    Write-Host ("Report JSON: {0}" -f $reportPath)
    Write-Host ("Report MD:   {0}" -f $mdPath)
    Write-Host ("Logs:        {0}" -f $outDir)

    if ($failCount -gt 0) {
        Write-Host ("FAILED ({0} hard step(s))" -f $failCount) -ForegroundColor Red
        exit 1
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
