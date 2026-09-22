/// <summary>
/// Picks the HMONITOR and DisplayId values region capture is allowed to hand to Windows Graphics
/// Capture.
///
/// MonitorFromRect on the Mattone Win11 host returned <c>0x2680A25</c>, which is not
/// an enumerated monitor handle. <c>CreateForMonitor</c> then failed with <c>E_INVALIDARG</c>, and
/// <c>TryCreateFromDisplayId</c> returned <c>S_OK</c> with a null item for that same value.
/// </summary>
internal static class MonitorCaptureSelection
{
    public static IntPtr PreferEnumeratedHandle(IntPtr enumerated, IntPtr fromMonitorFromRect)
    {
        if (fromMonitorFromRect != IntPtr.Zero && fromMonitorFromRect == enumerated)
        {
            return enumerated;
        }

        return enumerated;
    }

    public static ulong[] DisplayIdCandidates(ulong winUiDisplayId, IntPtr monitor)
    {
        var bits = unchecked((ulong)monitor.ToInt64());
        if (winUiDisplayId != 0 && bits != 0 && bits != winUiDisplayId)
        {
            return new[] { winUiDisplayId, bits };
        }

        if (winUiDisplayId != 0)
        {
            return new[] { winUiDisplayId };
        }

        if (bits != 0)
        {
            return new[] { bits };
        }

        return Array.Empty<ulong>();
    }
}
