/// <summary>
/// Chooses the DisplayId and HMONITOR values region capture may hand to Windows Graphics Capture.
///
/// On the Mattone Win11 host, <c>TryCreateFromDisplayId(0x2680A25)</c> returned <c>S_OK</c> with a
/// null item and <c>CreateForMonitor</c> returned <c>E_INVALIDARG</c> for that same value. That
/// value is the GDI HMONITOR from <c>EnumDisplayMonitors</c> / <c>MonitorFromRect</c>, not a
/// <c>Windows.Graphics.DisplayId</c>. A display id is only attempted when WinUI returns it.
/// </summary>
internal static class MonitorCaptureSelection
{
    public static ulong[] DisplayIds(ulong displayAreaId, ulong fromMonitorApi)
    {
        if (displayAreaId != 0 && fromMonitorApi != 0 && displayAreaId != fromMonitorApi)
        {
            return new[] { displayAreaId, fromMonitorApi };
        }

        if (displayAreaId != 0)
        {
            return new[] { displayAreaId };
        }

        if (fromMonitorApi != 0)
        {
            return new[] { fromMonitorApi };
        }

        return Array.Empty<ulong>();
    }

    public static IntPtr[] MonitorHandles(IntPtr fromDisplayId, IntPtr enumerated)
    {
        if (fromDisplayId != IntPtr.Zero && enumerated != IntPtr.Zero && fromDisplayId != enumerated)
        {
            return new[] { fromDisplayId, enumerated };
        }

        if (fromDisplayId != IntPtr.Zero)
        {
            return new[] { fromDisplayId };
        }

        if (enumerated != IntPtr.Zero)
        {
            return new[] { enumerated };
        }

        return Array.Empty<IntPtr>();
    }
}
