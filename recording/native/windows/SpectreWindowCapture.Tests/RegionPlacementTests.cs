using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class RegionPlacementTests
{
    [Fact]
    public void FullyContainedRegionBecomesMonitorRelativeCrop()
    {
        var monitor = new MonitorRect(0, 0, 1920, 1080);
        var region = new CaptureRect(160, 160, 360, 180);

        var chosen = RegionPlacement.Choose(new[] { monitor }, region);

        Assert.NotNull(chosen);
        Assert.Equal(monitor, chosen.Value.Monitor);
        Assert.Equal(new CaptureRect(160, 160, 360, 180), chosen.Value.Crop);
    }

    [Fact]
    public void OnePixelOvershootIsTheOnlyOverflowThatIsTolerated()
    {
        var monitor = new MonitorRect(0, 0, 1920, 1080);
        var region = new CaptureRect(1600, 900, 321, 181);

        var chosen = RegionPlacement.Choose(new[] { monitor }, region);

        Assert.NotNull(chosen);
        Assert.Equal(new CaptureRect(1600, 900, 320, 180), chosen.Value.Crop);

        Assert.Null(RegionPlacement.Choose(
            new[] { monitor },
            new CaptureRect(1600, 900, 322, 182)));
    }

    [Fact]
    public void RegionOnAMonitorWithANegativeOriginStaysInsideThatMonitor()
    {
        var left = new MonitorRect(-1920, 0, 0, 1080);
        var primary = new MonitorRect(0, 0, 1920, 1080);
        var region = new CaptureRect(-400, 80, 360, 180);

        var chosen = RegionPlacement.Choose(new[] { primary, left }, region);

        Assert.NotNull(chosen);
        Assert.Equal(left, chosen.Value.Monitor);
        Assert.Equal(new CaptureRect(1520, 80, 360, 180), chosen.Value.Crop);
    }

    [Fact]
    public void RegionOutsideEveryMonitorIsNotPlaced()
    {
        var monitor = new MonitorRect(0, 0, 1920, 1080);
        var region = new CaptureRect(4000, 4000, 360, 180);

        Assert.Null(RegionPlacement.Choose(new[] { monitor }, region));
    }

    [Fact]
    public void RegionSpanningMonitorsIsNotMovedToTheLargestOverlap()
    {
        var left = new MonitorRect(0, 0, 1920, 1080);
        var right = new MonitorRect(1920, 0, 3840, 1080);
        Assert.Null(RegionPlacement.Choose(
            new[] { left, right },
            new CaptureRect(1800, 100, 400, 300)));
    }
}
