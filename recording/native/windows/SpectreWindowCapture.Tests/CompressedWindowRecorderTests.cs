using Windows.Graphics.Imaging;
using Windows.Media.Editing;
using Windows.Storage;
using Xunit;

namespace SpectreWindowCapture.Tests;

public sealed class CompressedWindowRecorderTests
{
    private const int Width = 160;
    private const int Height = 120;

    [Fact]
    public void BitrateScalesWithResolutionAndFrameRate()
    {
        var hd = CompressedWindowRecorder.BitrateFor(1280, 720, 30);
        var fourK = CompressedWindowRecorder.BitrateFor(3840, 2160, 30);
        var fourKSixty = CompressedWindowRecorder.BitrateFor(3840, 2160, 60);

        Assert.True(fourK > hd);
        Assert.True(fourKSixty > fourK);
    }

    [Fact]
    public async Task RecordsFramesTopRowFirst()
    {
        // Top half red, bottom half blue: a vertical flip swaps the two halves.
        var frame = TwoBandFrame(Width, Height);
        var output = Path.Combine(Path.GetTempPath(), $"spectre-orientation-{Guid.NewGuid():N}.mp4");
        try
        {
            using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            var source = new FixedFrameSource(frame, Width, Height, stop, samplesBeforeStop: 10);

            await CompressedWindowRecorder.EncodeAsync(output, fps: 30, source, stop.Token, onReady: () => { })
                .WaitAsync(TimeSpan.FromSeconds(60));

            var decoded = await DecodeFirstFrameAsync(output);
            var top = decoded.PixelAt(Width / 2, Height / 4);
            var bottom = decoded.PixelAt(Width / 2, Height * 3 / 4);

            Assert.True(top.IsRed, $"Expected red at the top of the first frame; got {top}.");
            Assert.True(bottom.IsBlue, $"Expected blue at the bottom of the first frame; got {bottom}.");
        }
        finally
        {
            File.Delete(output);
        }
    }

    [Fact]
    public async Task ImmediateStopAfterReadyStillWritesFirstFrame()
    {
        var output = Path.Combine(Path.GetTempPath(), $"{Guid.NewGuid()}.mp4");
        try
        {
            using var stop = new CancellationTokenSource();
            var source = new FixedFrameSource(TwoBandFrame(Width, Height), Width, Height, stop, 2);
            await CompressedWindowRecorder.EncodeAsync(output, 30, source, stop.Token, stop.Cancel)
                .WaitAsync(TimeSpan.FromSeconds(30));
            var decoded = await DecodeFirstFrameAsync(output);
            Assert.True(decoded.PixelAt(Width / 2, Height / 4).IsRed);
            // Media Foundation reports 66.6333 ms for two 30 fps samples on Windows.
            // Allow 50 microseconds of mux rounding, well below a single 33.3 ms sample.
            var twoSamples = TimeSpan.FromTicks(2 * TimeSpan.TicksPerSecond / 30);
            Assert.True(
                decoded.Duration >= twoSamples - TimeSpan.FromMicroseconds(50),
                $"Expected two complete sample durations before end-of-stream; got {decoded.Duration}.");
        }
        finally
        {
            File.Delete(output);
        }
    }

    [Fact]
    public async Task MissingFirstFramePreservesExistingFileAndDoesNotAnnounceReady()
    {
        var output = Path.GetTempFileName();
        try
        {
            await File.WriteAllTextAsync(output, "previous movie");
            bool ready = false;
            await Assert.ThrowsAsync<TimeoutException>(() => CompressedWindowRecorder.EncodeAsync(
                output, 30, new NoFrameSource(), CancellationToken.None, () => ready = true));
            Assert.False(ready);
            Assert.Equal("previous movie", await File.ReadAllTextAsync(output));
        }
        finally
        {
            File.Delete(output);
        }
    }

    private sealed class NoFrameSource : IRecordingFrameSource
    {
        public int Width => CompressedWindowRecorderTests.Width;
        public int Height => CompressedWindowRecorderTests.Height;
        public byte[]? WaitForFrameBytes(CancellationToken token) => null;
        public byte[]? LatestFrameBytes() => null;
    }

    private static byte[] TwoBandFrame(int width, int height)
    {
        var bytes = new byte[width * height * 4];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var offset = (y * width + x) * 4;
                var red = y < height / 2;
                bytes[offset] = red ? (byte)0 : (byte)255; // B
                bytes[offset + 1] = 0; // G
                bytes[offset + 2] = red ? (byte)255 : (byte)0; // R
                bytes[offset + 3] = 255; // A
            }
        }

        return bytes;
    }

    private static async Task<DecodedFrame> DecodeFirstFrameAsync(string path)
    {
        var file = await StorageFile.GetFileFromPathAsync(path);
        var composition = new MediaComposition();
        var clip = await MediaClip.CreateFromFileAsync(file);
        composition.Clips.Add(clip);
        using var image =
            await composition.GetThumbnailAsync(
                TimeSpan.Zero,
                Width,
                Height,
                VideoFramePrecision.NearestFrame);
        var decoder = await BitmapDecoder.CreateAsync(image);
        var pixels =
            await decoder.GetPixelDataAsync(
                BitmapPixelFormat.Bgra8,
                BitmapAlphaMode.Ignore,
                new BitmapTransform(),
                ExifOrientationMode.IgnoreExifOrientation,
                ColorManagementMode.DoNotColorManage);
        return new DecodedFrame(pixels.DetachPixelData(), (int)decoder.PixelWidth, clip.OriginalDuration);
    }

    private sealed record DecodedFrame(byte[] Bgra, int Width, TimeSpan Duration)
    {
        public Pixel PixelAt(int x, int y)
        {
            var offset = (y * Width + x) * 4;
            return new Pixel(Bgra[offset + 2], Bgra[offset + 1], Bgra[offset]);
        }
    }

    private readonly record struct Pixel(byte R, byte G, byte B)
    {
        public bool IsRed => R > 160 && B < 96;

        public bool IsBlue => B > 160 && R < 96;

        public override string ToString() => $"rgb({R}, {G}, {B})";
    }

    private sealed class FixedFrameSource(
        byte[] frame,
        int width,
        int height,
        CancellationTokenSource stop,
        int samplesBeforeStop) : IRecordingFrameSource
    {
        private int samples;

        public int Width => width;

        public int Height => height;

        public byte[]? WaitForFrameBytes(CancellationToken stopToken) => frame;

        public byte[]? LatestFrameBytes()
        {
            if (Interlocked.Increment(ref samples) >= samplesBeforeStop)
            {
                stop.Cancel();
            }

            return frame;
        }
    }
}
