param([Parameter(Mandatory=$true)][int]$ProcessId, [Parameter(Mandatory=$true)][string]$Golden)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
$g = Get-Content -Raw $Golden | ConvertFrom-Json
$root = [Windows.Automation.AutomationElement]::RootElement
$pidCondition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcessId)
$deadline = [DateTime]::UtcNow.AddSeconds(15)
$window = $null
do {
  $window = $root.FindFirst([Windows.Automation.TreeScope]::Children, $pidCondition)
  if ($null -eq $window) { Start-Sleep -Milliseconds 100 }
} while ($null -eq $window -and [DateTime]::UtcNow -lt $deadline)
if ($null -eq $window) { throw "fixture window did not register with UIA" }
function ById([string]$id) {
  $condition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
  $items = @($window.FindAll([Windows.Automation.TreeScope]::Descendants, $condition) | ForEach-Object { $_ })
  return $items
}
function RequireOne([string]$id) {
  $items = @(ById $id)
  if ($items.Count -ne 1) { throw "expected one node with id '$id', got $($items.Count)" }
  return $items[0]
}
$required = @("primary-button", "newsletter", "account-name", "nested-group", "nested-label", "disabled-button", "offscreen-button", "popover-trigger")
do {
  $ready = $true
  foreach ($id in $required) { if (@(ById $id).Count -ne 1) { $ready = $false; break } }
  if (@(ById "repeated").Count -ne 3) { $ready = $false }
  if (-not $ready) { Start-Sleep -Milliseconds 100 }
} while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
if (-not $ready) { throw "fixture accessible children did not register before timeout" }
if ($window.Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.windowRole) { throw "window role mismatch" }
$button = RequireOne "primary-button"
$checkbox = RequireOne "newsletter"
$text = RequireOne "account-name"
$disabled = RequireOne "disabled-button"
$trigger = RequireOne "popover-trigger"
if ($button.Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.buttonRole) { throw "button role mismatch" }
if ($checkbox.Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.checkboxRole) { throw "checkbox role mismatch" }
if ($text.Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.textRole) { throw "text role mismatch" }
$toggle = $checkbox.GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Current.ToggleState.ToString()
if ($toggle -ne $g.checkedState) { throw "checkbox state mismatch" }
$repeated = @(ById "repeated")
if ($repeated.Count -ne 3) { throw "duplicate ids were collapsed" }
$walker = [Windows.Automation.TreeWalker]::ControlViewWalker
$parentIds = @($repeated | ForEach-Object { $walker.GetParent($_).Current.AutomationId } | Sort-Object -Unique)
if ($parentIds.Count -ne 2) { throw "duplicate parent relationships mismatch" }
if ($walker.GetParent((RequireOne "nested-label")).Current.AutomationId -ne "nested-group") { throw "nested relationship mismatch" }
if ($disabled.Current.IsEnabled -or $g.disabledState -ne "disabled") { throw "disabled state mismatch" }
$value = $text.GetCurrentPattern([Windows.Automation.ValuePattern]::Pattern).Current.Value
if ($value -ne $g.textValue) { throw "text value mismatch: $value" }
$trigger.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern).Invoke()
$popoverDeadline = [DateTime]::UtcNow.AddSeconds(5)
do {
  $popover = @(ById "details-popover")
  if ($popover.Count -eq 0) { Start-Sleep -Milliseconds 100 }
} while ($popover.Count -eq 0 -and [DateTime]::UtcNow -lt $popoverDeadline)
if ($popover.Count -ne 1) { throw "popover did not register before timeout" }
if ($popover[0].Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.popoverRole) { throw "popover role mismatch" }
Write-Output "accesskit-fixture-windows: pass"
