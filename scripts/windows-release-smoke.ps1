#Requires -Version 5.1
<#
.SYNOPSIS
  One-shot Windows pre-tag release smoke (agent UI e2e + WGC + packaged CLI).

.DESCRIPTION
  Run from a logged-in interactive desktop on a physical Windows box (e.g. Mattone).
  Does not need multiple terminals: Gradle e2e spawns fixtures; CLI uses `spectre launch --once`.

  PowerShell note: Gradle -P properties with dots must be quoted (this script does that).

  `spectre launch -- … gradlew …` prints a Gradle-ish warning by design — still a valid smoke.

.EXAMPLE
  .\scripts\windows-release-smoke.ps1

.EXAMPLE
  pwsh -NoProfile -File C:\src\spectre\scripts\windows-release-smoke.ps1

.EXAMPLE
  .\scripts\windows-release-smoke.ps1 -SkipWgc -SkipCli
#>
[CmdletBinding()]
param(
    [switch] $SkipAgentE2e,
    [switch] $SkipWgc,
    [switch] $SkipCli,
    [switch] $SkipPackageCli
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-RepoRoot {
    $here = $PSScriptRoot
    if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }
    $root = (Resolve-Path (Join-Path $here "..")).Path
    if (-not (Test-Path (Join-Path $root "settings.gradle.kts"))) {
        throw "Could not find repo root above $here (missing settings.gradle.kts)"
    }
    return $root
}

function Write-LogLines {
    param([string[]] $Paths)
    foreach ($p in $Paths) {
        if (-not (Test-Path $p)) { continue }
        Get-Content -LiteralPath $p -ErrorAction SilentlyContinue | ForEach-Object {
            # Write-Host does not pollute the success stream (unlike bare pipeline output).
            Write-Host $_
        }
    }
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $ArgumentList,
        [Parameter(Mandatory = $true)][string] $WorkingDirectory,
        [string] $LogName = "smoke"
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        throw "Executable not found: $FilePath"
    }
    $logDir = Join-Path $WorkingDirectory "build\smoke"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $stdout = Join-Path $logDir "$LogName-$stamp.out.log"
    $stderr = Join-Path $logDir "$LogName-$stamp.err.log"

    Write-Host ("> $FilePath $($ArgumentList -join ' ')") -ForegroundColor DarkGray

    # Start-Process keeps native stdout off PowerShell's success stream (avoids
    # polluting function return values under Set-StrictMode).
    $p = Start-Process -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr

    Write-LogLines -Paths @($stdout, $stderr)

    if ($null -eq $p.ExitCode) {
        throw "Process did not report an exit code: $FilePath"
    }
    if ($p.ExitCode -ne 0) {
        throw ("{0} exited with code {1} (logs: {2}, {3})" -f $FilePath, $p.ExitCode, $stdout, $stderr)
    }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string[]] $GradleArgs,
        [string] $LogName = "gradle"
    )
    $gradlew = Join-Path $RepoRoot "gradlew.bat"
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found at $gradlew" }
    # Always append --console=plain for readable redirected logs.
    $args = @($GradleArgs) + @("--console=plain")
    Invoke-Native -FilePath $gradlew -ArgumentList $args -WorkingDirectory $RepoRoot -LogName $LogName
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][scriptblock] $Action
    )
    Write-Host ""
    Write-Host "==== $Name ====" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # Do not use `return` with pipeline-prone actions: capture a single result object only.
    $result = $null
    try {
        # Discard any accidental success-stream output from the action.
        $null = & $Action 6>&1
        $sw.Stop()
        Write-Host ("PASS  {0}  ({1}s)" -f $Name, [int]$sw.Elapsed.TotalSeconds) -ForegroundColor Green
        $result = [pscustomobject]@{
            Name    = $Name
            Result  = "pass"
            Seconds = [int]$sw.Elapsed.TotalSeconds
            Detail  = ""
        }
    }
    catch {
        $sw.Stop()
        $msg = $_.Exception.Message
        Write-Host ("FAIL  {0}  ({1}s): {2}" -f $Name, [int]$sw.Elapsed.TotalSeconds, $msg) -ForegroundColor Red
        $result = [pscustomobject]@{
            Name    = $Name
            Result  = "fail"
            Seconds = [int]$sw.Elapsed.TotalSeconds
            Detail  = $msg
        }
    }
    # Single object only — callers assign to a variable (do not use unary comma; that
    # wraps in Object[] and breaks $r.Result under Set-StrictMode).
    return $result
}

function Get-PackagedSpectre {
    param([string] $RepoRoot)
    $candidates = @(
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\spectre.exe"),
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\Spectre.exe")
    )
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return (Resolve-Path -LiteralPath $c).Path }
    }
    return $null
}

# --- main ---

if ($env:OS -ne "Windows_NT") {
    throw "This script is Windows-only (current OS: $([System.Environment]::OSVersion.VersionString))"
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot
Write-Host "Windows release smoke" -ForegroundColor White
Write-Host "Repo: $repoRoot"
try {
    $sha = git -C $repoRoot rev-parse --short HEAD 2>$null
    Write-Host "SHA:  $sha"
}
catch {
    Write-Host "SHA:  (unknown)"
}
Write-Host "Host: $env:COMPUTERNAME  User: $env:USERNAME"

$results = New-Object System.Collections.Generic.List[object]

if (-not $SkipAgentE2e) {
    $step = Invoke-Step "Agent UI e2e (attach + inject + launch)" {
        Invoke-Gradle -RepoRoot $repoRoot -LogName "agent-e2e" -GradleArgs @(
            ":agent:test",
            "-Pspectre.agent.attachE2e.allowWindows=true",
            "--tests", "*AgentAttachIntegration*",
            "--tests", "*AgentInjectAttachIntegration*",
            "--tests", "*LaunchAndAttachIntegration*"
        )
    }
    [void]$results.Add($step)
}

if (-not $SkipWgc) {
    $step = Invoke-Step "WGC region smoke" {
        Invoke-Gradle -RepoRoot $repoRoot -LogName "wgc-region" -GradleArgs @(
            ":recording:runWindowsGraphicsCaptureRegionSmoke"
        )
        $mp4 = Join-Path $env:TEMP "spectre-wgc-region-smoke.mp4"
        if (-not (Test-Path -LiteralPath $mp4)) { throw "Expected output missing: $mp4" }
        $len = (Get-Item -LiteralPath $mp4).Length
        if ($len -lt 8192) { throw "MP4 too small ($len bytes): $mp4" }
        Write-Host "  mp4: $mp4 ($len bytes)" -ForegroundColor DarkGray
    }
    [void]$results.Add($step)
}

if (-not $SkipCli) {
    if (-not $SkipPackageCli) {
        $step = Invoke-Step "Package Windows CLI" {
            Invoke-Gradle -RepoRoot $repoRoot -LogName "package-cli" -GradleArgs @(
                ":cli:packageWindowsX64"
            )
        }
        [void]$results.Add($step)
    }

    $step = Invoke-Step "Packaged spectre launch --once (fixture)" {
        $spectre = Get-PackagedSpectre -RepoRoot $repoRoot
        if (-not $spectre) {
            throw "spectre.exe not found under cli\build\construo\windowsX64\roast\ — run without -SkipPackageCli"
        }
        Write-Host "  using $spectre" -ForegroundColor DarkGray
        Write-Host "  note: Gradle-ish launch warning is expected for ':agent-test-fixture:run'" -ForegroundColor DarkGray
        $gradlew = Join-Path $repoRoot "gradlew.bat"
        # spectre launch --once --app-name ComposeFixtureMain -- gradlew.bat :agent-test-fixture:run
        Invoke-Native -FilePath $spectre -WorkingDirectory $repoRoot -LogName "spectre-launch" -ArgumentList @(
            "launch",
            "--once",
            "--app-name", "ComposeFixtureMain",
            "--",
            $gradlew,
            ":agent-test-fixture:run"
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
    $props = @($r.PSObject.Properties | ForEach-Object { $_.Name })
    if ($props -notcontains "Result" -or $props -notcontains "Name") {
        Write-Host ("FAIL   <malformed step: {0}>" -f $r) -ForegroundColor Red
        $failCount++
        continue
    }
    if ($r.Result -ne "pass") { $failCount++ }
    $line = "{0,-6} {1,-48} {2,4}s" -f $r.Result.ToUpperInvariant(), $r.Name, $r.Seconds
    if ($r.Detail) { $line += "  $($r.Detail)" }
    Write-Host $line -ForegroundColor $(if ($r.Result -eq "pass") { "Green" } else { "Red" })
}

$outDir = Join-Path $repoRoot "build\smoke"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reportPath = Join-Path $outDir "windows-release-smoke.json"
@($results) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "Report: $reportPath"
Write-Host "Logs:   $outDir\*.log"

if ($failCount -gt 0) {
    Write-Host "FAILED ($failCount step(s))" -ForegroundColor Red
    exit 1
}
Write-Host "ALL PASSED" -ForegroundColor Green
exit 0
