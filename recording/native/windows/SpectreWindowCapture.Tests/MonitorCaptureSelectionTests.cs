using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class MonitorCaptureSelectionTests
{
    [Fact]
    public void DisplayIdsPreferTheDisplayAreaToken()
    {
        // 0x2680A25 is the Mattone GDI HMONITOR. TryCreateFromDisplayId returned S_OK and a
        // null item for that value, so it is only attempted when a WinUI API actually
        // returned it — never synthesized from the handle.
        Assert.Equal(
            new ulong[] { 0xABC, 0x2680A25 },
            MonitorCaptureSelection.DisplayIds(0xABC, 0x2680A25));
        Assert.Equal(new ulong[] { 0xABC }, MonitorCaptureSelection.DisplayIds(0xABC, 0xABC));
        Assert.Equal(new ulong[] { 0xABC }, MonitorCaptureSelection.DisplayIds(0xABC, 0));
        Assert.Equal(new ulong[] { 0x2680A25 }, MonitorCaptureSelection.DisplayIds(0, 0x2680A25));
        Assert.Empty(MonitorCaptureSelection.DisplayIds(0, 0));
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
