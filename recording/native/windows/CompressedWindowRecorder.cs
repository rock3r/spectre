using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;

/// <summary>
/// The frames a recording encodes: tightly packed BGRA rows, top row first.
/// </summary>
internal interface IRecordingFrameSource
{
    int Width { get; }

    int Height { get; }

    /// <summary>Waits for the first frame, or returns null when <paramref name="stopToken" /> fires.</summary>
    byte[]? WaitForFrameBytes(CancellationToken stopToken);

    /// <summary>The most recent frame, or null before the first one arrives.</summary>
    byte[]? LatestFrameBytes();
}

/// <summary>
/// Encodes frames from an <see cref="IRecordingFrameSource" /> to an H.264 MP4 through a
/// <see cref="MediaStreamSource" /> and <see cref="MediaTranscoder" />.
/// </summary>
internal sealed class CompressedWindowRecorder
{
    // MF_MT_DEFAULT_STRIDE (mfapi.h): bytes per row; the sign gives the row order.
    private static readonly Guid MfMtDefaultStride = new("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");

    private readonly IRecordingFrameSource frameSource;
    private readonly CancellationToken stopToken;
    private readonly SampleClock clock;
    private readonly System.Diagnostics.Stopwatch sinceFirstSample = new();
    private byte[]? lastFrame;

    private CompressedWindowRecorder(
        int fps,
        IRecordingFrameSource frameSource,
        CancellationToken stopToken)
    {
        this.frameSource = frameSource;
        this.stopToken = stopToken;
        clock = new SampleClock(fps);
    }

    public static async Task EncodeAsync(
        string output,
        int fps,
        IRecordingFrameSource frameSource,
        CancellationToken stopToken,
        Action onReady)
    {
        var recorder = new CompressedWindowRecorder(fps, frameSource, stopToken);
        var videoProperties =
            VideoEncodingProperties.CreateUncompressed(
                MediaEncodingSubtypes.Bgra8,
                (uint)frameSource.Width,
                (uint)frameSource.Height);
        videoProperties.FrameRate.Numerator = (uint)fps;
        videoProperties.FrameRate.Denominator = 1;
        // Media Foundation reads uncompressed RGB without a default stride as bottom-up, which
        // turns every recording upside down (#638). A positive stride declares the rows top-down.
        videoProperties.Properties[MfMtDefaultStride] = (uint)(frameSource.Width * 4);

        var descriptor = new VideoStreamDescriptor(videoProperties);
        var source = new MediaStreamSource(descriptor)
        {
            BufferTime = TimeSpan.Zero,
            Duration = TimeSpan.FromDays(1),
        };
        source.Starting += recorder.OnStarting;
        source.SampleRequested += recorder.OnSampleRequested;

        var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD720p);
        profile.Video.Subtype = "H264";
        profile.Video.Width = (uint)frameSource.Width;
        profile.Video.Height = (uint)frameSource.Height;
        profile.Video.FrameRate.Numerator = (uint)fps;
        profile.Video.FrameRate.Denominator = 1;
        profile.Video.PixelAspectRatio.Numerator = 1;
        profile.Video.PixelAspectRatio.Denominator = 1;

        await using var outputFile = File.Open(
            output,
            FileMode.Create,
            FileAccess.ReadWrite,
            FileShare.Read);
        using var randomAccessStream = outputFile.AsRandomAccessStream();
        var transcoder = new MediaTranscoder { HardwareAccelerationEnabled = true };
        var prepared =
            await transcoder.PrepareMediaStreamSourceTranscodeAsync(
                source,
                randomAccessStream,
                profile);
        if (!prepared.CanTranscode)
        {
            throw new InvalidOperationException(
                $"MediaTranscoder rejected the MP4 stream: {prepared.FailureReason}.");
        }

        onReady();
        await prepared.TranscodeAsync();
    }

    private void OnStarting(MediaStreamSource sender, MediaStreamSourceStartingEventArgs args)
    {
        args.Request.SetActualStartPosition(TimeSpan.Zero);
    }

    private void OnSampleRequested(MediaStreamSource sender, MediaStreamSourceSampleRequestedEventArgs args)
    {
        if (stopToken.IsCancellationRequested)
        {
            args.Request.Sample = null;
            return;
        }

        byte[]? bytes;
        SampleSlot slot;
        if (lastFrame is null)
        {
            // The timeline starts at the first captured frame.
            bytes = frameSource.WaitForFrameBytes(stopToken);
            sinceFirstSample.Start();
            slot = clock.NextSlot(TimeSpan.Zero);
        }
        else
        {
            // #610: Windows Graphics Capture delivers frames only when the window changes.
            // Wait for this sample's slot on the wall clock, then repeat the latest frame,
            // so idle time stays in the video instead of being dropped.
            slot = clock.NextSlot(sinceFirstSample.Elapsed);
            var delay = SampleClock.DelayUntil(slot, sinceFirstSample.Elapsed);
            if (delay > TimeSpan.Zero && stopToken.WaitHandle.WaitOne(delay))
            {
                args.Request.Sample = null;
                return;
            }

            bytes = frameSource.LatestFrameBytes() ?? lastFrame;
        }

        if (bytes is null || stopToken.IsCancellationRequested)
        {
            args.Request.Sample = null;
            return;
        }

        lastFrame = bytes;
        var sample = MediaStreamSample.CreateFromBuffer(bytes.AsBuffer(), slot.Timestamp);
        sample.Duration = slot.Duration;
        args.Request.Sample = sample;
    }
}
