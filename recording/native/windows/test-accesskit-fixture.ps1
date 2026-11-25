param(
  [Parameter(Mandatory=$true)][string]$Helper,
  [Parameter(Mandatory=$true)][string]$Fixture,
  [Parameter(Mandatory=$true)][string]$Golden
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient

function Invoke-Helper([string]$Request, [int]$ExpectedExit = 0) {
  $start = [Diagnostics.ProcessStartInfo]::new($Helper, "accessibility-snapshot")
  $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true; $start.UseShellExecute = $false
  $process = [Diagnostics.Process]::Start($start)
  # Drain both pipes concurrently before waiting. Waiting first deadlocks when a snapshot exceeds
  # the OS pipe buffer (which the large-tree limit tests intentionally exercise).
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Write($Request); $process.StandardInput.Close()
  if (-not $process.WaitForExit(10000)) { $process.Kill(); throw "helper timed out" }
  $stdout = $stdoutTask.GetAwaiter().GetResult(); $stderr = $stderrTask.GetAwaiter().GetResult()
  if ($process.ExitCode -ne $ExpectedExit) { throw "helper exit $($process.ExitCode), expected $ExpectedExit; stderr: $stderr; stdout: $stdout" }
  return @{ Json = if ($stdout) { $stdout | ConvertFrom-Json } else { $null }; Error = $stderr }
}
function Request([string]$Type, $Value, [int]$Depth = 64, [int]$Count = 10000, [Nullable[long]]$Owner = $null) {
  $target = @{ type = $Type; value = $Value }
  if ($null -ne $Owner) { $target.processId = $Owner }
  @{ target = $target; maximumDepth = $Depth; maximumNodeCount = $Count; timeoutMs = 5000 } | ConvertTo-Json -Compress
}
function Wait-ForFixtureWindow([int]$ProcessId, [string]$Title) {
  $root = [Windows.Automation.AutomationElement]::RootElement
  $pidCondition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcessId)
  $titleCondition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::NameProperty, $Title)
  $condition = [Windows.Automation.AndCondition]::new($pidCondition, $titleCondition)
  $deadline = [DateTime]::UtcNow.AddSeconds(15)
  do {
    $window = $root.FindFirst([Windows.Automation.TreeScope]::Children, $condition)
    if ($null -eq $window) { Start-Sleep -Milliseconds 100 }
  } while ($null -eq $window -and [DateTime]::UtcNow -lt $deadline)
  if ($null -eq $window) { throw "fixture window '$Title' for process $ProcessId did not register with UIA" }
  return $window
}

$fixtureProcess = Start-Process $Fixture -PassThru
$duplicateProcess = $null
try {
  $fixtureTitle = "Spectre AccessKit Fixture"
  $window = Wait-ForFixtureWindow $fixtureProcess.Id $fixtureTitle
  $goldenValues = Get-Content -Raw $Golden | ConvertFrom-Json
  $response = (Invoke-Helper (Request "process-id" $fixtureProcess.Id)).Json
  if (-not $response.ok) { throw "snapshot failed: $($response.error.message)" }
  $byId = @{}
  foreach ($node in $response.snapshot.nodes) {
    if ($null -eq $node.id) { continue }
    if (-not $byId.ContainsKey($node.id)) { $byId[$node.id] = @() }
    $byId[$node.id] += $node
  }
  if ($byId["primary-button"][0].platformRole -ne $goldenValues.buttonRole) { throw "button role mismatch" }
  if ($byId["account-name"][0].value -ne $goldenValues.textValue) { throw "value pattern mismatch" }
  if ($byId["newsletter"][0].value -ne "on") { throw "toggle pattern mismatch" }
  if ($byId["disabled-button"][0].states -notcontains $goldenValues.disabledState) { throw "disabled state mismatch" }
  $keys = @($response.snapshot.nodes | ForEach-Object key)
  if (@($keys | Sort-Object -Unique).Count -ne $keys.Count) { throw "node keys are not unique" }
  $partial = (Invoke-Helper (Request "process-id" $fixtureProcess.Id 64 2)).Json
  if ($partial.ok -or $partial.error.partialSnapshot.nodes.Count -ne 2) { throw "node limit mismatch" }
  $depth = (Invoke-Helper (Request "process-id" $fixtureProcess.Id 1 10000)).Json
  if ($depth.ok -or $depth.error.code -ne "limit-reached") { throw "depth limit mismatch" }
  $handle = $window.Current.NativeWindowHandle.ToString()
  if (-not (Invoke-Helper (Request "window-id" $handle)).Json.ok) { throw "window-id target mismatch" }
  $titleResult = (Invoke-Helper (Request "window-title" $fixtureTitle)).Json
  if (-not $titleResult.ok -or $titleResult.snapshot.windows.Count -ne 1) { throw "unique window-title target mismatch" }
  $duplicateProcess = Start-Process $Fixture -PassThru
  $null = Wait-ForFixtureWindow $duplicateProcess.Id $fixtureTitle
  $ambiguous = (Invoke-Helper (Request "window-title" $fixtureTitle)).Json
  if ($ambiguous.ok -or $ambiguous.error.code -ne "target-ambiguous") { throw "ambiguous window-title target did not fail closed" }
  foreach ($ownerId in @($fixtureProcess.Id, $duplicateProcess.Id)) {
    $owned = (Invoke-Helper (Request "window-title" $fixtureTitle -Owner $ownerId)).Json
    if (-not $owned.ok -or $owned.snapshot.windows.Count -ne 1 -or $owned.snapshot.windows[0].title -ne $fixtureTitle) {
      throw "window-title with correct owner $ownerId did not select the fixture"
    }
  }
  $closedWindow = Wait-ForFixtureWindow $duplicateProcess.Id $fixtureTitle
  $closedHandle = $closedWindow.Current.NativeWindowHandle.ToString()
  Stop-Process -Id $duplicateProcess.Id -Force
  $duplicateProcess.WaitForExit()
  $missing = (Invoke-Helper (Request "window-id" $closedHandle)).Json
  if ($missing.ok -or $missing.error.code -ne "target-not-found") {
    throw "closed fixture window-id did not return target-not-found"
  }
  $wrongOwner = (Invoke-Helper (Request "window-title" $fixtureTitle -Owner $PID)).Json
  if ($wrongOwner.ok -or $wrongOwner.error.code -ne "target-not-found") { throw "window-title matched a different owner" }
  $wrongTitle = (Invoke-Helper (Request "window-title" "Absent fixture title" -Owner $fixtureProcess.Id)).Json
  if ($wrongTitle.ok -or $wrongTitle.error.code -ne "target-not-found") { throw "owner filter ignored the title" }
  Write-Output "spectre-accesskit-helper: pass"
}
finally {
  if ($null -ne $duplicateProcess -and -not $duplicateProcess.HasExited) { Stop-Process -Id $duplicateProcess.Id -Force }
  if (-not $fixtureProcess.HasExited) { Stop-Process -Id $fixtureProcess.Id -Force }
}
