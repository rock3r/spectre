$ErrorActionPreference = "Stop"

. "$PSScriptRoot/windows-helper-preflight.ps1"

$validSamples = @(
  "target is required`n",
  "target is required`r`n",
  "Per-monitor DPI awareness was already set; setting the thread.`ntarget is required`n",
  "Per-monitor DPI awareness was already set; setting the thread.`r`ntarget is required`r`n"
)
foreach ($sample in $validSamples) {
  if (-not (Test-WindowsHelperPreflightStderr -Stderr $sample)) {
    throw "Expected representative helper stderr sample to be accepted: $sample"
  }
}

$invalidSamples = @(
  "",
  "target is required",
  "unrelated error`ntarget is required`n",
  "Per-monitor DPI awareness was already set; setting the thread.`nother error`ntarget is required`n",
  "target is required`nmore output`n"
)
foreach ($sample in $invalidSamples) {
  if (Test-WindowsHelperPreflightStderr -Stderr $sample) {
    throw "Expected unrelated or malformed helper stderr sample to be rejected: $sample"
  }
}

Write-Output "OK Windows helper preflight stderr samples"
