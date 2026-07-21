using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class OptionsParsingTests
{
    [Fact]
    public void ParseAcceptsWindowRecordingArguments()
    {
        var options =
            Options.Parse([
                "--mode",
                "recording",
                "--source",
                "window",
                "--title",
                "Spectre",
                "--owner-pid",
                "123",
                "--fps",
                "60",
                "--cursor",
                "false",
                "--output",
                "out.mp4",
            ]);

        Assert.Equal(CaptureMode.Recording, options.Mode);
        Assert.Equal(CaptureSource.Window, options.Source);
        Assert.Equal("Spectre", options.Title);
        Assert.Equal(123L, options.OwnerPid);
        Assert.Equal(60, options.Fps);
        Assert.False(options.CaptureCursor);
        Assert.Equal("out.mp4", options.Output);
    }

    [Fact]
    public void ParseAcceptsRegionRecordingArguments()
    {
        var options =
            Options.Parse([
                "--mode",
                "recording",
                "--source",
                "region",
                "--x",
                "10",
                "--y",
                "20",
                "--width",
                "300",
                "--height",
                "200",
                "--output",
                "out.mp4",
            ]);

        Assert.Equal(CaptureSource.Region, options.Source);
        Assert.Equal(new CaptureRect(10, 20, 300, 200), options.Region);
        Assert.Equal(30, options.Fps);
        Assert.True(options.CaptureCursor);
    }

    [Fact]
    public void ParseAcceptsWindowCropFlags()
    {
        var options =
            Options.Parse([
                "--mode",
                "recording",
                "--source",
                "window",
                "--title",
                "Spectre",
                "--owner-pid",
                "99",
                "--crop-x",
                "8",
                "--crop-y",
                "40",
                "--crop-width",
                "640",
                "--crop-height",
                "480",
                "--output",
                "out.mp4",
            ]);

        Assert.Equal(new CaptureRect(8, 40, 640, 480), options.Crop);
        Assert.Null(options.Region);
    }

    [Fact]
    public void ParseRejectsCropOnRegionSource()
    {
        var ex = Assert.Throws<ArgumentException>(() =>
            Options.Parse([
                "--mode",
                "recording",
                "--source",
                "region",
                "--x",
                "0",
                "--y",
                "0",
                "--width",
                "100",
                "--height",
                "100",
                "--crop-x",
                "0",
                "--output",
                "out.mp4",
            ]));
        Assert.Contains("--crop-", ex.Message);
    }

    public static IEnumerable<object[]> InvalidArgumentCases()
    {
        yield return new object[] { "--output is required", Array.Empty<string>() };
        yield return new object[] { "Unknown argument", new[] { "--unknown", "value" } };
        yield return
            new object[] { "--cursor must be true or false", new[] { "--cursor", "1", "--output", "out.mp4" } };
        yield return new object[]
        {
            "--width is required",
            new[]
            {
                "--mode", "recording", "--source", "region", "--x", "0", "--y", "0", "--height", "100",
                "--output", "out.mp4",
            },
        };
    }

    [Theory]
    [MemberData(nameof(InvalidArgumentCases))]
    public void ParseRejectsInvalidArguments(string messageFragment, string[] args)
    {
        var ex = Assert.Throws<ArgumentException>(() => Options.Parse(args));

        Assert.Contains(messageFragment, ex.Message);
    }
}
