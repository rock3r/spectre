param([Parameter(Mandatory=$true)][int]$ProcessId, [Parameter(Mandatory=$true)][string]$Golden)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
$g = Get-Content -Raw $Golden | ConvertFrom-Json
$root = [Windows.Automation.AutomationElement]::RootElement
$pidCondition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::ProcessIdProperty, $ProcessId)
$deadline = [DateTime]::UtcNow.AddSeconds(15)
do { $window = $root.FindFirst([Windows.Automation.TreeScope]::Descendants, $pidCondition); Start-Sleep -Milliseconds 100 } while ($null -eq $window -and [DateTime]::UtcNow -lt $deadline)
if ($null -eq $window) { throw "fixture window did not register with UIA" }
function ById([string]$id) {
  $condition = [Windows.Automation.PropertyCondition]::new([Windows.Automation.AutomationElement]::AutomationIdProperty, $id)
  return @($window.FindAll([Windows.Automation.TreeScope]::Descendants, $condition))
}
if ($window.Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.windowRole) { throw "window role mismatch" }
if ((ById "primary-button")[0].Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.buttonRole) { throw "button role mismatch" }
if ((ById "newsletter")[0].Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.checkboxRole) { throw "checkbox role mismatch" }
if ((ById "account-name")[0].Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.textRole) { throw "text role mismatch" }
$toggle = (ById "newsletter")[0].GetCurrentPattern([Windows.Automation.TogglePattern]::Pattern).Current.ToggleState.ToString()
if ($toggle -ne $g.checkedState) { throw "checkbox state mismatch" }
if ((ById "repeated").Count -ne 3) { throw "duplicate ids were collapsed" }
if ((ById "disabled-button")[0].Current.IsEnabled) { throw "disabled button is enabled" }
$value = (ById "account-name")[0].GetCurrentPattern([Windows.Automation.ValuePattern]::Pattern).Current.Value
if ($value -ne $g.textValue) { throw "text value mismatch: $value" }
$invoke = (ById "popover-trigger")[0].GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern)
$invoke.Invoke()
$deadline = [DateTime]::UtcNow.AddSeconds(5)
do { $popover = ById "details-popover"; Start-Sleep -Milliseconds 100 } while ($popover.Count -eq 0 -and [DateTime]::UtcNow -lt $deadline)
if ($popover.Count -eq 0) { throw "popover did not open" }
if ($popover[0].Current.ControlType.ProgrammaticName.Split('.')[-1] -ne $g.popoverRole) { throw "popover role mismatch" }
Write-Output "accesskit-fixture-windows: pass"
