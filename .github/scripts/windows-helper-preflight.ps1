function Test-WindowsHelperPreflightStderr {
  param(
    [Parameter(Mandatory=$true)][AllowEmptyString()][string]$Stderr
  )

  $platformInformation = 'Per-monitor DPI awareness was already set; setting the thread\.'
  return $Stderr -cmatch "\A(?:$platformInformation\r?\n)?target is required\r?\n\z"
}
