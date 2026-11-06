using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class StillsProtocolTests
{
    [Fact]
    public void ParsesStillsModeWithoutAnOutputFile()
    {
        var options =
            Options.Parse([
                "--mode",
                "stills",
                "--source",
                "window",
                "--title",
                "Spectre",
                "--owner-pid",
                "42",
            ]);

        Assert.Equal(CaptureMode.Stills, options.Mode);
        Assert.Equal("Spectre", options.Title);
    }

    [Fact]
    public void RejectsStillsModeForARegion()
    {
        var ex = Assert.Throws<ArgumentException>(() =>
            Options.Parse([
                "--mode",
                "stills",
                "--source",
                "region",
                "--x",
                "0",
                "--y",
                "0",
                "--width",
                "10",
                "--height",
                "10",
            ]));

        Assert.Contains("stills", ex.Message);
    }

    [Theory]
    [InlineData("c", "Capture")]
    [InlineData("q", "Quit")]
    [InlineData(null, "Quit")]
    [InlineData("x", "Unknown")]
    public void ParsesCommands(string? line, string expected)
    {
        // StillsCommand is internal, so a public theory takes its name.
        Assert.Equal(expected, StillsProtocol.ParseCommand(line).ToString());
    }

    [Fact]
    public void FormatsAFrameHeader()
    {
        Assert.Equal("FRAME 640 480", StillsProtocol.FrameHeader(640, 480));
    }

    [Fact]
    public void KeepsAStaleReasonOnOneLine()
    {
        Assert.Equal("STALE title changed", StillsProtocol.StaleHeader("title\nchanged"));
    }
}
