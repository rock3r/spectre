internal readonly record struct WindowFrameCandidate(IntPtr Handle, CaptureRect Frame);

/// <summary>
/// Selects top-level windows by their frame, for untitled windows (a Swing JWindow or a plain
/// AWT Window) that have no title to match.
///
/// The JVM converts the window's AWT bounds to device pixels with its own rounding, so each edge
/// may differ from GetWindowRect by up to <see cref="TolerancePixels"/>. The caller must treat
/// zero matches as "not found" and several matches as ambiguous: capturing one of them would be a
/// guess.
/// </summary>
internal static class WindowFrameMatch
{
    public const int TolerancePixels = 2;

    public static IReadOnlyList<WindowFrameCandidate> Matching(
        IEnumerable<WindowFrameCandidate> candidates,
        CaptureRect frame) =>
        candidates
            .Where(candidate =>
                Near(candidate.Frame.X, frame.X) &&
                Near(candidate.Frame.Y, frame.Y) &&
                Near(candidate.Frame.Width, frame.Width) &&
                Near(candidate.Frame.Height, frame.Height))
            .ToList();

    private static bool Near(int actual, int expected) => Math.Abs(actual - expected) <= TolerancePixels;
}
