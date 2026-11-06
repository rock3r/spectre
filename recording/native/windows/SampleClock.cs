internal readonly record struct SampleSlot(long Index, TimeSpan Timestamp, TimeSpan Duration);

/// <summary>
/// Places recording samples on a constant-rate timeline that follows the wall clock.
///
/// Windows Graphics Capture delivers a frame only when the captured content changes. The
/// recorder therefore samples the latest frame once per slot, repeating it while nothing
/// changes, so idle time stays in the video (#610). Slot <c>n</c> starts at <c>n / fps</c>
/// seconds after the first sample. When sampling falls behind the clock, the slots that are
/// already over are skipped, so the timeline never runs slower than real time.
/// </summary>
internal sealed class SampleClock
{
    private long nextIndex;

    public SampleClock(int fps)
    {
        if (fps <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(fps), fps, "Frame rate must be positive.");
        }

        FrameDuration = TimeSpan.FromTicks(TimeSpan.TicksPerSecond / fps);
    }

    public TimeSpan FrameDuration { get; }

    /// <summary>
    /// Returns the slot for the next sample, given the time elapsed since the first sample.
    /// The slot is never earlier than the last slot that has started, and never repeats a slot.
    /// </summary>
    public SampleSlot NextSlot(TimeSpan elapsed)
    {
        var started = elapsed <= TimeSpan.Zero ? 0 : elapsed.Ticks / FrameDuration.Ticks;
        var index = Math.Max(nextIndex, started);
        nextIndex = index + 1;
        return new SampleSlot(index, TimeSpan.FromTicks(FrameDuration.Ticks * index), FrameDuration);
    }

    /// <summary>How long to wait, from <paramref name="elapsed" />, until the slot starts.</summary>
    public static TimeSpan DelayUntil(SampleSlot slot, TimeSpan elapsed)
    {
        var delay = slot.Timestamp - elapsed;
        return delay > TimeSpan.Zero ? delay : TimeSpan.Zero;
    }
}
