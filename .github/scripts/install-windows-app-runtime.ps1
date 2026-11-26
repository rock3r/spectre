param(
  [Parameter(Mandatory=$true)][string]$Helper
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/windows-helper-preflight.ps1"
$runtimeVersion = "1.8.260209005"
$runtimeFamily = "Microsoft.WindowsAppRuntime.1.8_8wekyb3d8bbwe"
$installer = Join-Path $env:RUNNER_TEMP "WindowsAppRuntimeInstall-x64.exe"
$installerUri = "https://aka.ms/windowsappsdk/1.8/$runtimeVersion/windowsappruntimeinstall-x64.exe"

Write-Output "Installing Windows App Runtime $runtimeVersion (x64) from the Microsoft archive"
Invoke-WebRequest -Uri $installerUri -OutFile $installer
$signature = Get-AuthenticodeSignature -FilePath $installer
if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
  throw "Windows App Runtime installer has invalid Authenticode status: $($signature.Status)"
}
if ($signature.SignerCertificate.Subject -notmatch '(^|, )O=Microsoft Corporation(,|$)') {
  throw "Windows App Runtime installer is not signed by Microsoft Corporation: $($signature.SignerCertificate.Subject)"
}

& $installer --quiet
if ($LASTEXITCODE -ne 0) {
  throw "Windows App Runtime installer exited with code $LASTEXITCODE"
}

$runtime = Get-AppxPackage -Name "Microsoft.WindowsAppRuntime.1.8" |
  Where-Object { $_.PackageFamilyName -ceq $runtimeFamily -and $_.Architecture -eq "X64" }
if ($null -eq $runtime) {
  $registered = Get-AppxPackage -Name "Microsoft.WindowsAppRuntime*" |
    ForEach-Object { "$($_.PackageFamilyName) [$($_.Architecture)] $($_.Version)" }
  throw "Required public Windows App Runtime family $runtimeFamily (x64) is not registered. Registered runtimes: $($registered -join '; ')"
}
Write-Output "Verified $($runtime.PackageFullName)"

# A missing framework fails before Main and may show an app-launch dialog instead of writing
# stderr. Exercise the real framework-dependent executable without a fixture, bounded and hidden.
$start = [Diagnostics.ProcessStartInfo]::new($Helper, "accessibility-snapshot")
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$process = [Diagnostics.Process]::Start($start)
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.StandardInput.Write('{}')
$process.StandardInput.Close()
if (-not $process.WaitForExit(10000)) {
  $process.Kill($true)
  throw "Windows helper preflight timed out before fixture startup"
}
$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()
if ($process.ExitCode -ne 2 -or -not [string]::IsNullOrEmpty($stdout) -or
    -not (Test-WindowsHelperPreflightStderr -Stderr $stderr)) {
  throw "Windows helper preflight returned an unexpected malformed-request response (exit $($process.ExitCode)); stderr: $stderr; stdout: $stdout"
}
Write-Output "Windows helper no-UI preflight reached Main"
