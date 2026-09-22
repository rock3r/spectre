using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class WindowRegionTargetTests
{
    [Fact]
    public void SmallestWindowThatContainsTheRegionWinsOverTheDesktop()
    {
        var desktop = new WindowRect(new IntPtr(1), 0, 0, 1920, 1080);
        var frame = new WindowRect(new IntPtr(2), 160, 160, 520, 340);
        var region = new CaptureRect(160, 160, 360, 180);

        var chosen = WindowRegionTarget.Choose(new[] { desktop, frame }, region);

        Assert.Equal(frame, chosen);
    }

    [Fact]
    public void PartialOverlapUsesTheLargestIntersection()
    {
        // Neither window contains the region. The right window covers more of it.
        var left = new WindowRect(new IntPtr(1), 0, 0, 160, 80);
        var right = new WindowRect(new IntPtr(2), 140, 0, 280, 80);
        var region = new CaptureRect(100, 0, 150, 40);

        var chosen = WindowRegionTarget.Choose(new[] { left, right }, region);

        Assert.Equal(right, chosen);
    }

    [Fact]
    public void CropIsRelativeToTheChosenWindow()
    {
        var frame = new WindowRect(new IntPtr(2), 160, 160, 520, 340);
        var region = new CaptureRect(200, 200, 100, 40);

        Assert.Equal(new CaptureRect(40, 40, 100, 40), WindowRegionTarget.Crop(frame, region));
    }

    [Fact]
    public void AMissIsNotATarget()
    {
        var frame = new WindowRect(new IntPtr(2), 0, 0, 100, 100);
        var region = new CaptureRect(400, 400, 20, 20);

        Assert.Null(WindowRegionTarget.Choose(new[] { frame }, region));
    }
}
