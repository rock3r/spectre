#Requires -Version 7
<#
Launches a command onto a NEWLY CREATED desktop inside the current window station, so the child
process has a real desktop that is never the *input* desktop. Its windows exist and are genuine,
nothing presents them, and it cannot inject input -- the same condition a locked workstation
imposes, but reachable with no credentials, no elevation, and without disturbing the user.

Std handles are passed through to the child (STARTF_USESTDHANDLES + bInheritHandles), so a parent
that spawned THIS script with pipes talks to the child over those same pipes and any existing
stdin/stdout protocol keeps working unchanged.

Blocks until the child exits, and propagates its exit code.
#>
param([Parameter(Mandatory = $true)][string]$DesktopName)

# The executable and command line arrive in the environment rather than as arguments. Both can
# contain spaces, and the JDK's legacy Windows argument quoting does not escape embedded quotes,
# so a JDK installed under Program Files would be split apart before pwsh ever parsed it.
$Exe = $env:SPECTRE_LAUNCH_EXE
$CommandLine = $env:SPECTRE_LAUNCH_CMDLINE
if ([string]::IsNullOrWhiteSpace($Exe) -or [string]::IsNullOrWhiteSpace($CommandLine)) {
    [Console]::Error.WriteLine(
        '[hidden-desktop] SPECTRE_LAUNCH_EXE / SPECTRE_LAUNCH_CMDLINE must both be set')
    exit 92
}

Add-Type -Namespace SpectreHidden -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
public static extern IntPtr CreateDesktop(string lpszDesktop, IntPtr lpszDevice,
    IntPtr pDevmode, int dwFlags, uint dwDesiredAccess, IntPtr lpsa);

[DllImport("kernel32.dll", SetLastError=true)]
public static extern IntPtr GetStdHandle(int nStdHandle);

[DllImport("kernel32.dll", SetLastError=true)]
public static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

[DllImport("kernel32.dll", SetLastError=true)]
public static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

[StructLayout(LayoutKind.Sequential)]
public struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread;
    public uint dwProcessId; public uint dwThreadId; }

[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
public struct STARTUPINFO { public int cb; public string lpReserved; public string lpDesktop;
    public string lpTitle; public int dwX; public int dwY; public int dwXSize; public int dwYSize;
    public int dwXCountChars; public int dwYCountChars; public int dwFillAttribute;
    public int dwFlags; public short wShowWindow; public short cbReserved2; public IntPtr lpReserved2;
    public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError; }

[DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
public static extern bool CreateProcess(string lpApplicationName,
    System.Text.StringBuilder lpCommandLine, IntPtr lpProcessAttributes,
    IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags,
    IntPtr lpEnvironment, string lpCurrentDirectory,
    ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);
'@

$GENERIC_ALL = 0x10000000
$STARTF_USESTDHANDLES = 0x00000100
$CREATE_NO_WINDOW = 0x08000000

# Idempotent: CreateDesktop returns a handle to the existing desktop if the name is taken.
$hDesk = [SpectreHidden.Native]::CreateDesktop($DesktopName, [IntPtr]::Zero, [IntPtr]::Zero, 0,
    $GENERIC_ALL, [IntPtr]::Zero)
if ($hDesk -eq [IntPtr]::Zero) {
    $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    [Console]::Error.WriteLine("[hidden-desktop] CreateDesktop failed err=$err")
    exit 90
}
[Console]::Error.WriteLine("[hidden-desktop] desktop ready: WinSta0\$DesktopName")

$si = New-Object SpectreHidden.Native+STARTUPINFO
$si.cb = [System.Runtime.InteropServices.Marshal]::SizeOf([type][SpectreHidden.Native+STARTUPINFO])
$si.lpDesktop = "WinSta0\$DesktopName"
$si.dwFlags = $STARTF_USESTDHANDLES
# -10/-11/-12 = STD_INPUT/STD_OUTPUT/STD_ERROR. Handing our own pipes to the child keeps the
# parent's existing line protocol intact across the desktop boundary.
$si.hStdInput = [SpectreHidden.Native]::GetStdHandle(-10)
$si.hStdOutput = [SpectreHidden.Native]::GetStdHandle(-11)
$si.hStdError = [SpectreHidden.Native]::GetStdHandle(-12)

$pi = New-Object SpectreHidden.Native+PROCESS_INFORMATION
$cmd = New-Object System.Text.StringBuilder 32768
[void]$cmd.Append($CommandLine)

$ok = [SpectreHidden.Native]::CreateProcess($Exe, $cmd, [IntPtr]::Zero, [IntPtr]::Zero,
    $true, $CREATE_NO_WINDOW, [IntPtr]::Zero, [NullString]::Value, [ref]$si, [ref]$pi)
if (-not $ok) {
    $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
    [Console]::Error.WriteLine("[hidden-desktop] CreateProcess failed err=$err")
    exit 91
}
[Console]::Error.WriteLine("[hidden-desktop] launched pid=$($pi.dwProcessId) on WinSta0\$DesktopName")

# INFINITE. Must be [uint32]::MaxValue -- PowerShell binds the 0xFFFFFFFF literal as signed -1.
[void][SpectreHidden.Native]::WaitForSingleObject($pi.hProcess, [uint32]::MaxValue)
$code = 0
[void][SpectreHidden.Native]::GetExitCodeProcess($pi.hProcess, [ref]$code)
exit $code
