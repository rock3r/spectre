#Requires -Version 5.1
<#
.SYNOPSIS
  One-shot Windows pre-tag release smoke (agent UI e2e + WGC + packaged CLI).

.DESCRIPTION
  Run from a logged-in interactive desktop on a physical Windows box (e.g. Mattone).
  Does not need multiple terminals: Gradle e2e spawns fixtures; CLI uses `spectre launch --once`.

  PowerShell note: Gradle -P properties with dots must be quoted (this script does that).

.EXAMPLE
  # From repo root (recommended)
  .\scripts\windows-release-smoke.ps1

.EXAMPLE
  pwsh -NoProfile -File C:\src\spectre\scripts\windows-release-smoke.ps1

.EXAMPLE
  # Faster iterate: agent e2e only
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

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][scriptblock] $Action
    )
    Write-Host ""
    Write-Host "==== $Name ====" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Action
        $sw.Stop()
        Write-Host "PASS  $Name  ($([int]$sw.Elapsed.TotalSeconds)s)" -ForegroundColor Green
        return [pscustomobject]@{ Name = $Name; Result = "pass"; Seconds = [int]$sw.Elapsed.TotalSeconds; Detail = "" }
    }
    catch {
        $sw.Stop()
        $msg = $_.Exception.Message
        Write-Host "FAIL  $Name  ($([int]$sw.Elapsed.TotalSeconds)s): $msg" -ForegroundColor Red
        return [pscustomobject]@{ Name = $Name; Result = "fail"; Seconds = [int]$sw.Elapsed.TotalSeconds; Detail = $msg }
    }
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)][string] $RepoRoot,
        [Parameter(Mandatory = $true)][string[]] $GradleArgs
    )
    $gradlew = Join-Path $RepoRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found at $gradlew" }
    Push-Location $RepoRoot
    try {
        Write-Host "> gradlew.bat $($GradleArgs -join ' ')" -ForegroundColor DarkGray
        & $gradlew @GradleArgs --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew exited with code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-PackagedSpectre {
    param([string] $RepoRoot)
    $candidates = @(
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\spectre.exe"),
        (Join-Path $RepoRoot "cli\build\construo\windowsX64\roast\Spectre.exe")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return (Resolve-Path $c).Path }
    }
    return $null
}

# --- main ---

# Windows PowerShell 5.1 has no $IsWindows; use $env:OS only.
if ($env:OS -ne "Windows_NT") {
    throw "This script is Windows-only (current OS: $([System.Environment]::OSVersion.VersionString))"
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot
Write-Host "Windows release smoke" -ForegroundColor White
Write-Host "Repo: $repoRoot"
Write-Host "SHA:  $(git -C $repoRoot rev-parse --short HEAD 2>$null)"
Write-Host "Host: $env:COMPUTERNAME  User: $env:USERNAME"

$results = New-Object System.Collections.Generic.List[object]

if (-not $SkipAgentE2e) {
    $results.Add((Invoke-Step "Agent UI e2e (attach + inject + launch)" {
        Invoke-Gradle -RepoRoot $repoRoot -GradleArgs @(
            ":agent:test",
            "-Pspectre.agent.attachE2e.allowWindows=true",
            "--tests", "*AgentAttachIntegration*",
            "--tests", "*AgentInjectAttachIntegration*",
            "--tests", "*LaunchAndAttachIntegration*"
        )
    }))
}

if (-not $SkipWgc) {
    $results.Add((Invoke-Step "WGC region smoke" {
        Invoke-Gradle -RepoRoot $repoRoot -GradleArgs @(
            ":recording:runWindowsGraphicsCaptureRegionSmoke"
        )
        $mp4 = Join-Path $env:TEMP "spectre-wgc-region-smoke.mp4"
        if (-not (Test-Path $mp4)) { throw "Expected output missing: $mp4" }
        $len = (Get-Item $mp4).Length
        if ($len -lt 8192) { throw "MP4 too small ($len bytes): $mp4" }
        Write-Host "  mp4: $mp4 ($len bytes)" -ForegroundColor DarkGray
    }))
}

if (-not $SkipCli) {
    if (-not $SkipPackageCli) {
        $results.Add((Invoke-Step "Package Windows CLI" {
            Invoke-Gradle -RepoRoot $repoRoot -GradleArgs @(":cli:packageWindowsX64")
        }))
    }

    $results.Add((Invoke-Step "Packaged spectre launch --once (fixture)" {
        $spectre = Get-PackagedSpectre -RepoRoot $repoRoot
        if (-not $spectre) {
            throw "spectre.exe not found under cli\build\construo\windowsX64\roast\ — run without -SkipPackageCli"
        }
        Write-Host "  using $spectre" -ForegroundColor DarkGray
        $gradlew = Join-Path $repoRoot "gradlew.bat"
        Push-Location $repoRoot
        try {
            # Single process: CLI launches fixture via Gradle, attaches, prints readiness, tears down.
            & $spectre launch --once --app-name ComposeFixtureMain -- $gradlew :agent-test-fixture:run
            if ($LASTEXITCODE -ne 0) {
                throw "spectre launch exited with code $LASTEXITCODE"
            }
        }
        finally {
            Pop-Location
        }
    }))
}

Write-Host ""
Write-Host "==== Summary ====" -ForegroundColor White
foreach ($r in $results) {
    $line = "{0,-6} {1,-48} {2,4}s" -f $r.Result.ToUpper(), $r.Name, $r.Seconds
    if ($r.Detail) { $line += "  $($r.Detail)" }
    Write-Host $line -ForegroundColor $(if ($r.Result -eq "pass") { "Green" } else { "Red" })
}

$failCount = @($results | Where-Object { $_.Result -ne "pass" }).Count
$outDir = Join-Path $repoRoot "build\smoke"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reportPath = Join-Path $outDir "windows-release-smoke.json"
$results | ConvertTo-Json -Depth 4 | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "Report: $reportPath"

if ($failCount -gt 0) {
    Write-Host "FAILED ($failCount step(s))" -ForegroundColor Red
    exit 1
}
Write-Host "ALL PASSED" -ForegroundColor Green
exit 0
