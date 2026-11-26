# Run on an interactive Windows desktop with the built WGC helper.
param([Parameter(Mandatory)][string]$Helper)
$ErrorActionPreference = 'Stop'
$dir = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid()))
$title = "Spectre owner exit $([guid]::NewGuid())"
$movie = Join-Path $dir 'capture.mp4'
$shown = Join-Path $dir 'shown'
$fixture = $null
$capture = $null
try {
    $form = "Add-Type -AssemblyName System.Windows.Forms; " +
        "`$f = New-Object Windows.Forms.Form; `$f.Text = '$title'; `$f.Add_Shown({ [IO.File]::WriteAllText('$($shown.Replace("'", "''"))', '') }); [void]`$f.ShowDialog()"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($form))
    $fixture = Start-Process powershell.exe -ArgumentList "-NoProfile -STA -EncodedCommand $encoded" -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 100
        if ($fixture.HasExited) { throw 'Fixture exited before opening its window' }
    } until ((Test-Path $shown) -or [DateTime]::UtcNow -gt $deadline)
    if (!(Test-Path $shown)) { throw 'Fixture window did not open' }

    Remove-Item $shown
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = (Resolve-Path $Helper).Path
    $info.Arguments = "--mode recording --source window --owner-pid $($fixture.Id) --title `"$title`" --fps 15 --cursor false --output `"$movie`""
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $capture = [Diagnostics.Process]::Start($info)
    $errors = $capture.StandardError.ReadToEndAsync()
    $ready = $capture.StandardOutput.ReadLineAsync()
    if (!$ready.Wait(20000) -or $ready.Result -ne 'READY') { throw 'Helper did not report READY' }
    $fixture.Kill()
    $fixture.WaitForExit()
    # Keep stdin open. Owner loss must terminate capture independently.
    if (!$capture.WaitForExit(15000)) { throw 'Helper remained alive after owner exit' }
    if ($capture.ExitCode -eq 0 -or $errors.Result -notmatch 'window.*closed') {
        throw "Unexpected exit $($capture.ExitCode): $($errors.Result)"
    }
    if (Get-ChildItem -Force $dir) { throw 'Failed recording left output behind' }
} finally {
    foreach ($process in @($capture, $fixture)) {
        if ($null -ne $process) {
            if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() }
            $process.Dispose()
        }
    }
    Remove-Item -Recurse -Force $dir
}
