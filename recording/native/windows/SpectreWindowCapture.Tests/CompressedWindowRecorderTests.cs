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
        composition.Clips.Add(await MediaClip.CreateFromFileAsync(file));
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
        return new DecodedFrame(pixels.DetachPixelData(), (int)decoder.PixelWidth);
    }

    private sealed record DecodedFrame(byte[] Bgra, int Width)
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
