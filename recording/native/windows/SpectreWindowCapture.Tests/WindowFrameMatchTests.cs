using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class WindowFrameMatchTests
{
    [Fact]
    public void PicksTheOnlyWindowWithThatFrame()
    {
        var candidates = new[]
        {
            new WindowFrameCandidate(new IntPtr(1), new CaptureRect(0, 0, 1600, 1000)),
            new WindowFrameCandidate(new IntPtr(2), new CaptureRect(250, 300, 750, 500)),
        };

        var matches = WindowFrameMatch.Matching(candidates, new CaptureRect(250, 300, 750, 500));

        Assert.Equal(new IntPtr(2), Assert.Single(matches).Handle);
    }

    [Fact]
    public void ToleratesScaleRoundingOnEachEdge()
    {
        var candidates = new[]
        {
            new WindowFrameCandidate(new IntPtr(2), new CaptureRect(252, 299, 749, 502)),
        };

        var matches = WindowFrameMatch.Matching(candidates, new CaptureRect(250, 300, 750, 500));

        Assert.Single(matches);
    }

    [Fact]
    public void RejectsAFrameOutsideTheTolerance()
    {
        var candidates = new[]
        {
            new WindowFrameCandidate(new IntPtr(2), new CaptureRect(253, 300, 750, 500)),
        };

        var matches = WindowFrameMatch.Matching(candidates, new CaptureRect(250, 300, 750, 500));

        Assert.Empty(matches);
    }

    [Fact]
    public void ReturnsEveryWindowThatSharesTheFrame()
    {
        var candidates = new[]
        {
            new WindowFrameCandidate(new IntPtr(2), new CaptureRect(-1280, 40, 300, 200)),
            new WindowFrameCandidate(new IntPtr(3), new CaptureRect(-1280, 40, 300, 200)),
        };

        var matches = WindowFrameMatch.Matching(candidates, new CaptureRect(-1280, 40, 300, 200));

        Assert.Equal(2, matches.Count);
    }
}
