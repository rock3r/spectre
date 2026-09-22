using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class MonitorCaptureSelectionTests
{
    [Fact]
    public void UnlistedMonitorFromRectHandleIsNotUsed()
    {
        var enumerated = new IntPtr(0x10052);
        var garbage = new IntPtr(0x2680A25);

        Assert.Equal(
            enumerated,
            MonitorCaptureSelection.PreferEnumeratedHandle(enumerated, garbage));
        Assert.Equal(
            enumerated,
            MonitorCaptureSelection.PreferEnumeratedHandle(enumerated, IntPtr.Zero));
        Assert.Equal(
            enumerated,
            MonitorCaptureSelection.PreferEnumeratedHandle(enumerated, enumerated));
    }

    [Fact]
    public void DisplayIdCandidatesIncludeTheWinUiTokenAndTheHmonitorBits()
    {
        var monitor = new IntPtr(0x10052);

        Assert.Equal(
            new ulong[] { 0xABC, 0x10052 },
            MonitorCaptureSelection.DisplayIdCandidates(0xABC, monitor));
        Assert.Equal(
            new ulong[] { 0x10052 },
            MonitorCaptureSelection.DisplayIdCandidates(0x10052, monitor));
        Assert.Equal(
            new ulong[] { 0x10052 },
            MonitorCaptureSelection.DisplayIdCandidates(0, monitor));
    }
}
