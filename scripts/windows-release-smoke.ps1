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
    [switch] $SkipAgentE2e,
    [switch] $SkipWgc,
    [switch] $SkipCli,
    [switch] $SkipPackageCli
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

    $p = Start-Process -FilePath $FilePath `
        -ArgumentList $argString `
        -WorkingDirectory $WorkingDirectory `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr

    Write-LogFile -Path $stdout
    Write-LogFile -Path $stderr

    $code = 0
    if ($null -ne $p -and $null -ne $p.ExitCode) {
        $code = [int]$p.ExitCode
    }
    if ($code -ne 0) {
        throw ("{0} exited with code {1} (logs: {2} ; {3})" -f $FilePath, $code, $stdout, $stderr)
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
    $allArgs = [string[]](@($GradleArgs) + @("--console=plain"))
    Invoke-Native -FilePath $gradlew -Arguments $allArgs -WorkingDirectory $RepoRoot -LogName $LogName
}

function New-StepResult {
    param(
        [string] $Name,
        [string] $Result,
        [int] $Seconds,
        [string] $Detail = ""
    )
    $o = New-Object PSObject
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Name" -Value $Name
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Result" -Value $Result
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Seconds" -Value $Seconds
    Add-Member -InputObject $o -MemberType NoteProperty -Name "Detail" -Value $Detail
    return $o
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][scriptblock] $Action
    )
    Write-Host ""
    Write-Host ("==== {0} ====" -f $Name) -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        # Swallow success-stream noise from the action so it cannot become our return value.
        $null = . $Action
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        Write-Host ("PASS  {0}  ({1}s)" -f $Name, $secs) -ForegroundColor Green
        return (New-StepResult -Name $Name -Result "pass" -Seconds $secs)
    }
    catch {
        $sw.Stop()
        $secs = [int]$sw.Elapsed.TotalSeconds
        $msg = [string]$_.Exception.Message
        Write-Host ("FAIL  {0}  ({1}s): {2}" -f $Name, $secs, $msg) -ForegroundColor Red
        return (New-StepResult -Name $Name -Result "fail" -Seconds $secs -Detail $msg)
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

function Save-SmokeReport {
    param(
        [Parameter(Mandatory = $true)][System.Collections.IList] $Results,
        [Parameter(Mandatory = $true)][string] $Path
    )
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    # -InputObject with a plain array avoids pipeline enumeration quirks on WinPS 5.1.
    $payload = @($Results)
    $json = ConvertTo-Json -InputObject $payload -Depth 6
    # .NET write avoids Set-Content -Encoding differences between 5.1 and 7+.
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
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
    Write-Host "Windows release smoke" -ForegroundColor White
    Write-Host ("Repo: {0}" -f $repoRoot)
    $sha = ""
    try { $sha = [string](git -C $repoRoot rev-parse --short HEAD 2>$null) } catch { $sha = "" }
    if ($sha) { Write-Host ("SHA:  {0}" -f $sha) }
    Write-Host ("Host: {0}  User: {1}" -f $env:COMPUTERNAME, $env:USERNAME)
    Write-HostGuidance

    $results = New-Object System.Collections.ArrayList

    if (-not $SkipAgentE2e) {
        $step = Invoke-Step -Name "Agent UI e2e (attach + inject + launch)" -Action {
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
        $step = Invoke-Step -Name "WGC region smoke" -Action {
            Invoke-Gradle -RepoRoot $repoRoot -LogName "wgc-region" -GradleArgs @(
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

    if (-not $SkipCli) {
        if (-not $SkipPackageCli) {
            $step = Invoke-Step -Name "Package Windows CLI" -Action {
                Invoke-Gradle -RepoRoot $repoRoot -LogName "package-cli" -GradleArgs @(
                    ":cli:packageWindowsX64"
                )
            }
            [void]$results.Add($step)
        }

        $step = Invoke-Step -Name "Packaged spectre launch --once (fixture)" -Action {
            $spectre = Get-PackagedSpectre -RepoRoot $repoRoot
            if (-not $spectre) {
                throw "spectre.exe not found under cli\build\construo\windowsX64\roast\ -- run without -SkipPackageCli"
            }
            Write-Host ("  using {0}" -f $spectre) -ForegroundColor DarkGray
            Write-Host "  note: Gradle-ish launch warning is expected for ':agent-test-fixture:run'" -ForegroundColor DarkGray
            $gradlew = Join-Path $repoRoot "gradlew.bat"
            Invoke-Native -FilePath $spectre -WorkingDirectory $repoRoot -LogName "spectre-launch" -Arguments @(
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
        $name = [string]$r.Name
        $result = [string]$r.Result
        $secs = [int]$r.Seconds
        $detail = [string]$r.Detail
        if ($result -ne "pass") { $failCount++ }
        $status = $result.ToUpper()
        $line = "{0,-6} {1,-48} {2,4}s" -f $status, $name, $secs
        if ($detail) { $line = $line + "  " + $detail }
        if ($result -eq "pass") {
            Write-Host $line -ForegroundColor Green
        }
        else {
            Write-Host $line -ForegroundColor Red
        }
    }

    $outDir = Join-Path $repoRoot "build\smoke"
    $reportPath = Join-Path $outDir "windows-release-smoke.json"
    Save-SmokeReport -Results $results -Path $reportPath
    Write-Host ("Report: {0}" -f $reportPath)
    Write-Host ("Logs:   {0}" -f $outDir)

    if ($failCount -gt 0) {
        Write-Host ("FAILED ({0} step(s))" -f $failCount) -ForegroundColor Red
        exit 1
    }
    Write-Host "ALL PASSED" -ForegroundColor Green
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
