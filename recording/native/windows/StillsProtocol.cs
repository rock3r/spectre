internal enum StillsCommand
{
    Capture,
    Quit,
    Unknown,
}

/// <summary>
/// The line protocol of <c>--mode stills</c> (#612).
///
/// The helper keeps one Windows Graphics Capture session open. It writes <see cref="Ready" /> once
/// the first frame has arrived. Each <c>c</c> line on stdin asks for a still. The helper answers
/// with a <see cref="FrameHeader" /> line followed by <c>width * height * 4</c> bytes of
/// premultiplied BGRA pixels, or with a <see cref="StaleHeader" /> line when the session no longer
/// shows the requested window. <c>q</c> or the end of stdin stops the helper.
/// </summary>
internal static class StillsProtocol
{
    public const string Ready = "READY";

    public static StillsCommand ParseCommand(string? line) =>
        line?.Trim() switch
        {
            null => StillsCommand.Quit,
            "q" => StillsCommand.Quit,
            "c" => StillsCommand.Capture,
            _ => StillsCommand.Unknown,
        };

    public static string FrameHeader(int width, int height) => $"FRAME {width} {height}";

    public static string StaleHeader(string reason) =>
        "STALE " + reason.Replace('\r', ' ').Replace('\n', ' ');
}
