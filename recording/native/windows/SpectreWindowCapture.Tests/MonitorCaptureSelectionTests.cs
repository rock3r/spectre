using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class MonitorCaptureSelectionTests
{
    [Fact]
    public void DisplayIdsRejectTokensThatAreHmonitorBits()
    {
        // Mattone tip aab7037 logged
        // displayArea=monitorApi=hmonFromDisplay=enumerated=0x2680A25 and then called
        // TryCreateFromDisplayId(0x2680A25). WinUI DisplayId.Value is that GDI HMONITOR.
        var hmonitor = new IntPtr(0x2680A25);

        Assert.Empty(
            MonitorCaptureSelection.DisplayIds(0x2680A25, 0x2680A25, hmonitor, hmonitor));
        Assert.Empty(MonitorCaptureSelection.DisplayIds(0, 0x2680A25, hmonitor, IntPtr.Zero));
        Assert.Empty(MonitorCaptureSelection.DisplayIds(0x2680A25, 0, hmonitor, IntPtr.Zero));
        Assert.Equal(
            "no Windows.Graphics.DisplayId; DisplayArea 0x2680A25 and " +
            "GetDisplayIdFromMonitor 0x2680A25 match GDI HMONITOR " +
            "enumerated=0x2680A25 hmonFromDisplay=0x2680A25. " +
            "TryCreateFromDisplayId was not called.",
            MonitorCaptureSelection.MissingDisplayIdDiagnostic(
                0x2680A25,
                0x2680A25,
                hmonitor,
                hmonitor));

        Assert.Equal(
            new ulong[] { 0xABC },
            MonitorCaptureSelection.DisplayIds(0xABC, 0x2680A25, hmonitor, hmonitor));
        Assert.Equal(
            new ulong[] { 0xABC, 0xDEF },
            MonitorCaptureSelection.DisplayIds(0xABC, 0xDEF, hmonitor, IntPtr.Zero));
        Assert.Equal(
            new ulong[] { 0xABC },
            MonitorCaptureSelection.DisplayIds(0xABC, 0xABC, hmonitor, IntPtr.Zero));
        Assert.Equal(
            new ulong[] { 0xABC },
            MonitorCaptureSelection.DisplayIds(0xABC, 0, IntPtr.Zero, IntPtr.Zero));
        Assert.Empty(MonitorCaptureSelection.DisplayIds(0, 0, IntPtr.Zero, IntPtr.Zero));
        Assert.Equal(
            "no Windows.Graphics.DisplayId; DisplayArea and GetDisplayIdFromMonitor returned 0. " +
            "TryCreateFromDisplayId was not called.",
            MonitorCaptureSelection.MissingDisplayIdDiagnostic(0, 0, hmonitor, IntPtr.Zero));
        Assert.Null(
            MonitorCaptureSelection.MissingDisplayIdDiagnostic(0xABC, 0, hmonitor, IntPtr.Zero));
    }

    [Fact]
    public void MonitorHandlesPreferTheDisplayIdMapping()
    {
        var fromDisplay = new IntPtr(0x10052);
        var enumerated = new IntPtr(0x2680A25);

        Assert.Equal(
            new[] { fromDisplay, enumerated },
            MonitorCaptureSelection.MonitorHandles(fromDisplay, enumerated));
        Assert.Equal(
            new[] { enumerated },
            MonitorCaptureSelection.MonitorHandles(IntPtr.Zero, enumerated));
        Assert.Equal(
            new[] { fromDisplay },
            MonitorCaptureSelection.MonitorHandles(fromDisplay, fromDisplay));
        Assert.Empty(MonitorCaptureSelection.MonitorHandles(IntPtr.Zero, IntPtr.Zero));
    }
}
