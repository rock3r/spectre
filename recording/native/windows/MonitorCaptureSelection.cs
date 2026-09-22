/// <summary>
/// Chooses the DisplayId and HMONITOR values region capture may hand to Windows Graphics Capture.
///
/// On the Mattone Win11 host, <c>DisplayArea.DisplayId</c> and <c>GetDisplayIdFromMonitor</c> both
/// returned <c>0x2680A25</c>, the same value as the enumerated GDI <c>HMONITOR</c>.
/// <c>TryCreateFromDisplayId</c> then returned <c>S_OK</c> with a null item.
/// <c>Microsoft.UI.DisplayId.Value</c> is that monitor handle, not a
/// <c>Windows.Graphics.DisplayId</c>, so a token that matches a known <c>HMONITOR</c> is not a
/// capture candidate.
/// </summary>
internal static class MonitorCaptureSelection
{
    public static ulong[] DisplayIds(
        ulong displayAreaId,
        ulong fromMonitorApi,
        IntPtr enumeratedMonitor,
        IntPtr monitorFromDisplay)
    {
        var ids = new List<ulong>(2);
        Consider(ids, displayAreaId, enumeratedMonitor, monitorFromDisplay);
        Consider(ids, fromMonitorApi, enumeratedMonitor, monitorFromDisplay);
        return ids.ToArray();
    }

    public static string? MissingDisplayIdDiagnostic(
        ulong displayAreaId,
        ulong fromMonitorApi,
        IntPtr enumeratedMonitor,
        IntPtr monitorFromDisplay)
    {
        if (DisplayIds(displayAreaId, fromMonitorApi, enumeratedMonitor, monitorFromDisplay).Length > 0)
        {
            return null;
        }

        if (displayAreaId == 0 && fromMonitorApi == 0)
        {
            return "no Windows.Graphics.DisplayId; DisplayArea and GetDisplayIdFromMonitor returned 0. " +
                "TryCreateFromDisplayId was not called.";
        }

        return "no Windows.Graphics.DisplayId; " +
            $"DisplayArea 0x{displayAreaId:X} and GetDisplayIdFromMonitor 0x{fromMonitorApi:X} " +
            $"match GDI HMONITOR enumerated=0x{enumeratedMonitor.ToInt64():X} " +
            $"hmonFromDisplay=0x{monitorFromDisplay.ToInt64():X}. " +
            "TryCreateFromDisplayId was not called.";
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

    private static void Consider(
        List<ulong> ids,
        ulong candidate,
        IntPtr enumeratedMonitor,
        IntPtr monitorFromDisplay)
    {
        if (candidate == 0 || ids.Contains(candidate))
        {
            return;
        }

        if (MatchesHandle(candidate, enumeratedMonitor) || MatchesHandle(candidate, monitorFromDisplay))
        {
            return;
        }

        ids.Add(candidate);
    }

    private static bool MatchesHandle(ulong candidate, IntPtr handle) =>
        handle != IntPtr.Zero && candidate == unchecked((ulong)handle.ToInt64());
}
