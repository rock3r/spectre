using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class SampleClockTests
{
    private static readonly TimeSpan FrameDuration = TimeSpan.FromTicks(TimeSpan.TicksPerSecond / 30);

    [Fact]
    public void TheFirstSampleStartsTheTimeline()
    {
        var clock = new SampleClock(fps: 30);

        var slot = clock.NextSlot(TimeSpan.Zero);

        Assert.Equal(0, slot.Index);
        Assert.Equal(TimeSpan.Zero, slot.Timestamp);
        Assert.Equal(FrameDuration, slot.Duration);
    }

    [Fact]
    public void SamplesRequestedEarlyWaitForTheirSlot()
    {
        var clock = new SampleClock(fps: 30);
        clock.NextSlot(TimeSpan.Zero);

        var elapsed = TimeSpan.FromMilliseconds(1);
        var slot = clock.NextSlot(elapsed);

        Assert.Equal(1, slot.Index);
        Assert.Equal(FrameDuration, slot.Timestamp);
        Assert.Equal(FrameDuration - elapsed, SampleClock.DelayUntil(slot, elapsed));
    }

    [Fact]
    public void TheTimelineFollowsTheWallClockAcrossAnIdlePeriod()
    {
        // #610: Windows Graphics Capture delivers no frame while the window does not change.
        // The sample after 2.6 s without a new frame must be stamped at 2.6 s, not at the
        // next 1/fps slot after the previous sample.
        var clock = new SampleClock(fps: 30);
        clock.NextSlot(TimeSpan.Zero);

        var slot = clock.NextSlot(TimeSpan.FromSeconds(2.6));

        Assert.Equal(78, slot.Index);
        Assert.Equal(FrameDuration * 78, slot.Timestamp);
        Assert.Equal(TimeSpan.Zero, SampleClock.DelayUntil(slot, TimeSpan.FromSeconds(2.6)));
    }

    [Fact]
    public void NeverReusesASlot()
    {
        var clock = new SampleClock(fps: 30);
        var elapsed = TimeSpan.FromSeconds(1);

        var first = clock.NextSlot(elapsed);
        var second = clock.NextSlot(elapsed);

        Assert.Equal(first.Index + 1, second.Index);
        Assert.True(second.Timestamp > first.Timestamp);
    }

    [Fact]
    public void RejectsANonPositiveFrameRate()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SampleClock(fps: 0));
    }
}
