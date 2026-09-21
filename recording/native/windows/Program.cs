using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using Microsoft.Graphics.Canvas;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using WinRT;

internal static class Program
{
    private const int ExitArgumentsRejected = 2;
    private const int ExitWindowNotFound = 3;
    private const int ExitCaptureUnsupported = 4;
    private const int ExitCaptureFailed = 5;
    private static readonly TimeSpan FrameTimeout = TimeSpan.FromSeconds(5);

    [STAThread]
    private static async Task<int> Main(string[] args)
    {
        Options options;
        try
        {
            options = Options.Parse(Options.NormalizeIncomingArgs(args));
        }
        catch (ArgumentException e)
        {
            // CLI rejection only. WinRT E_INVALIDARG is also an ArgumentException
            // ("Value does not fall within the expected range.") and must not be reported as argv.
            Console.Error.WriteLine(e.Message);
            return ExitArgumentsRejected;
        }

        try
        {
            if (!GraphicsCaptureSession.IsSupported())
            {
                Console.Error.WriteLine("Windows Graphics Capture is not supported on this system.");
                return ExitCaptureUnsupported;
            }

            return options.Source switch
            {
                CaptureSource.Window => await RunWindowCaptureAsync(options),
                CaptureSource.Region => await RunRegionCaptureAsync(options),
                _ => ExitArgumentsRejected,
            };
        }
        catch (Exception e)
        {
            Console.Error.WriteLine(e);
            return ExitCaptureFailed;
        }
    }

    private static async Task<int> RunWindowCaptureAsync(Options options)
    {
        var title = options.Title ?? throw new ArgumentException("--title is required for window capture.");
        var ownerPid =
            options.OwnerPid ?? throw new ArgumentException("--owner-pid is required for window capture.");
        var hwnd = FindWindowByTitleAndOwnerPid(title, ownerPid);
        if (hwnd == IntPtr.Zero)
        {
            Console.Error.WriteLine(
                $"Could not find a top-level window titled \"{title}\" owned by pid {ownerPid}.");
            return ExitWindowNotFound;
        }

        return options.Mode switch
        {
            CaptureMode.Screenshot =>
                await CaptureScreenshotAsync(hwnd, options.Output, options.CaptureCursor, options.Crop),
            CaptureMode.Recording => await RecordWindowAsync(options, hwnd),
            _ => ExitArgumentsRejected,
        };
    }

    private static async Task<int> RunRegionCaptureAsync(Options options)
    {
        if (options.Mode != CaptureMode.Recording)
        {
            Console.Error.WriteLine("Region capture only supports recording mode.");
            return ExitArgumentsRejected;
        }
        await RecordRegionAsync(options);
        return 0;
    }

    private static async Task<int> CaptureScreenshotAsync(
        IntPtr hwnd,
        string output,
        bool captureCursor,
        CaptureRect? crop)
    {
        // Same crop clamp as recording, but padToEven:false so PNGs keep true pixel size
        // (H.264 even-padding is recording-only).
        using var frameSource = WgcFrameSource.StartWindow(hwnd, captureCursor, crop, padToEven: false);
        if (frameSource.Width <= 0 || frameSource.Height <= 0)
        {
            throw new InvalidOperationException(
                $"Window has invalid dimensions: {frameSource.Width}x{frameSource.Height}.");
        }

        using var stop = new CancellationTokenSource(FrameTimeout);
        var bytes = frameSource.WaitForFrameBytes(stop.Token);
        if (bytes is null)
        {
            throw new TimeoutException("Timed out waiting for a Windows Graphics Capture frame.");
        }

        using var canvasDevice = new CanvasDevice();
        using var bitmap =
            CanvasBitmap.CreateFromBytes(
                canvasDevice,
                bytes,
                frameSource.Width,
                frameSource.Height,
                DirectXPixelFormat.B8G8R8A8UIntNormalized);
        await bitmap.SaveAsync(Path.GetFullPath(output), CanvasBitmapFileFormat.Png);
        return 0;
    }

    private static async Task<int> RecordWindowAsync(Options options, IntPtr hwnd)
    {
        using var frameSource = WgcFrameSource.StartWindow(hwnd, options.CaptureCursor, options.Crop);
        if (frameSource.Width <= 0 || frameSource.Height <= 0)
        {
            throw new InvalidOperationException(
                $"Window has invalid dimensions: {frameSource.Width}x{frameSource.Height}.");
        }

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(options.Output)) ?? ".");
        await RecordAsync(options, frameSource);
        return 0;
    }

    private static async Task RecordRegionAsync(Options options)
    {
        var region = options.Region ?? throw new ArgumentException("Region capture requires --x, --y, --width, and --height.");
        using var frameSource = WgcFrameSource.StartRegion(region, options.CaptureCursor);
        if (frameSource.Width <= 0 || frameSource.Height <= 0)
        {
            throw new InvalidOperationException(
                $"Region has invalid dimensions: {frameSource.Width}x{frameSource.Height}.");
        }

        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(options.Output)) ?? ".");
        await RecordAsync(options, frameSource);
    }

    private static async Task RecordAsync(Options options, WgcFrameSource frameSource)
    {
        var stopSignal = new CancellationTokenSource();
        _ = Task.Run(() => WaitForStopCommand(stopSignal));
        try
        {
            await CompressedWindowRecorder.EncodeAsync(
                options,
                frameSource,
                stopSignal.Token,
                () =>
                {
                    Console.Out.WriteLine("READY");
                    Console.Out.Flush();
                });
        }
        finally
        {
            CancelStopSignal(stopSignal);
            stopSignal.Dispose();
        }
    }

    private static void WaitForStopCommand(CancellationTokenSource stopSignal)
    {
        try
        {
            while (Console.In.Read() is var read && read >= 0)
            {
                if (read == 'q')
                {
                    CancelStopSignal(stopSignal);
                    return;
                }
            }

            CancelStopSignal(stopSignal);
        }
        catch (IOException)
        {
            CancelStopSignal(stopSignal);
        }
        catch (ObjectDisposedException)
        {
            CancelStopSignal(stopSignal);
        }
    }

    private static void CancelStopSignal(CancellationTokenSource stopSignal)
    {
        try
        {
            stopSignal.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // The helper is already shutting down; there is nothing left to signal.
        }
    }

    private static int Even(int value) => value % 2 == 0 ? value : value + 1;

    private static IntPtr FindWindowByTitleAndOwnerPid(string title, long ownerPid)
    {
        var matchedWindow = IntPtr.Zero;
        bool Callback(IntPtr hwnd, IntPtr data)
        {
            if (TryGetWindowThreadProcessId(hwnd, out var actualPid) &&
                actualPid == ownerPid &&
                GetWindowTitle(hwnd) == title)
            {
                matchedWindow = hwnd;
                return false;
            }

            return true;
        }

        if (!EnumWindows(Callback, IntPtr.Zero))
        {
            if (matchedWindow != IntPtr.Zero)
            {
                return matchedWindow;
            }

            throw new Win32Exception(Marshal.GetLastWin32Error(), "EnumWindows failed.");
        }

        return matchedWindow;
    }

    private static string GetWindowTitle(IntPtr hwnd)
    {
        var titleLength = GetWindowTextLength(hwnd);
        if (titleLength <= 0)
        {
            return string.Empty;
        }

        var title = new StringBuilder(titleLength + 1);
        var copied = GetWindowText(hwnd, title, title.Capacity);
        return copied <= 0 ? string.Empty : title.ToString();
    }

    private static bool TryGetWindowThreadProcessId(IntPtr hwnd, out long pid)
    {
        _ = GetWindowThreadProcessId(hwnd, out var processId);
        pid = processId;
        return processId != 0;
    }

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int GetWindowTextLength(IntPtr hwnd);

    private delegate bool WindowEnumProc(IntPtr hwnd, IntPtr data);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool EnumWindows(WindowEnumProc callback, IntPtr data);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct Rect
    {
        public Rect(int left, int top, int right, int bottom)
        {
            Left = left;
            Top = top;
            Right = right;
            Bottom = bottom;
        }

        public readonly int Left;
        public readonly int Top;
        public readonly int Right;
        public readonly int Bottom;
    }

    private readonly record struct EnumeratedMonitor(IntPtr Handle, MonitorRect Bounds);

    private readonly record struct PlacedMonitor(IntPtr Monitor, CaptureRect Crop);

    private delegate bool MonitorEnumProc(
        IntPtr monitor,
        IntPtr hdcMonitor,
        ref Rect monitorBounds,
        IntPtr data);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool EnumDisplayMonitors(
        IntPtr hdc,
        IntPtr clipRect,
        MonitorEnumProc callback,
        IntPtr data);

    private const uint MonitorDefaultToNull = 0;

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromRect(in Rect rect, uint flags);

    private sealed class CompressedWindowRecorder
    {
        private readonly Options options;
        private readonly WgcFrameSource frameSource;
        private readonly CancellationToken stopToken;
        private readonly TimeSpan frameDuration;
        private int frameIndex;

        private CompressedWindowRecorder(
            Options options,
            WgcFrameSource frameSource,
            CancellationToken stopToken)
        {
            this.options = options;
            this.frameSource = frameSource;
            this.stopToken = stopToken;
            frameDuration = TimeSpan.FromSeconds(1.0 / options.Fps);
        }

        public static async Task EncodeAsync(
            Options options,
            WgcFrameSource frameSource,
            CancellationToken stopToken,
            Action onReady)
        {
            var recorder = new CompressedWindowRecorder(options, frameSource, stopToken);
            var videoProperties =
                VideoEncodingProperties.CreateUncompressed(
                    MediaEncodingSubtypes.Bgra8,
                    (uint)frameSource.Width,
                    (uint)frameSource.Height);
            videoProperties.FrameRate.Numerator = (uint)options.Fps;
            videoProperties.FrameRate.Denominator = 1;

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
            profile.Video.FrameRate.Numerator = (uint)options.Fps;
            profile.Video.FrameRate.Denominator = 1;
            profile.Video.PixelAspectRatio.Numerator = 1;
            profile.Video.PixelAspectRatio.Denominator = 1;

            await using var output = File.Open(
                options.Output,
                FileMode.Create,
                FileAccess.ReadWrite,
                FileShare.Read);
            using var randomAccessStream = output.AsRandomAccessStream();
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

            var bytes = frameSource.WaitForFrameBytes(stopToken);
            if (bytes is null)
            {
                args.Request.Sample = null;
                return;
            }

            var timestamp = TimeSpan.FromTicks(frameDuration.Ticks * frameIndex);
            var sample = MediaStreamSample.CreateFromBuffer(bytes.AsBuffer(), timestamp);
            sample.Duration = frameDuration;
            args.Request.Sample = sample;
            frameIndex += 1;
        }
    }

    private sealed class WgcFrameSource : IDisposable
    {
        private readonly CanvasDevice canvasDevice;
        private readonly GraphicsCaptureItem item;
        private readonly Direct3D11CaptureFramePool framePool;
        private readonly GraphicsCaptureSession session;
        private readonly CaptureRect crop;
        private readonly AutoResetEvent frameAvailable = new(false);
        private readonly object frameLock = new();
        private byte[]? latestFrame;
        private bool disposed;

        private WgcFrameSource(
            CanvasDevice canvasDevice,
            GraphicsCaptureItem item,
            Direct3D11CaptureFramePool framePool,
            GraphicsCaptureSession session,
            CaptureRect crop,
            (int Width, int Height) outputSize)
        {
            this.canvasDevice = canvasDevice;
            this.item = item;
            this.framePool = framePool;
            this.session = session;
            this.crop = crop;
            Width = outputSize.Width;
            Height = outputSize.Height;
        }

        public int Width { get; }

        public int Height { get; }

        public static WgcFrameSource StartWindow(
            IntPtr hwnd,
            bool captureCursor,
            CaptureRect? cropInWindow = null,
            bool padToEven = true)
        {
            var canvasDevice = new CanvasDevice();
            var item = GraphicsCaptureItemInterop.CreateForWindow(hwnd);
            var full = new CaptureRect(0, 0, item.Size.Width, item.Size.Height);
            var crop = cropInWindow is null ? full : ClampCropToItem(cropInWindow, full);
            // Recording needs even dimensions for H.264; screenshots keep exact crop size.
            var outputSize =
                padToEven
                    ? (Even(crop.Width), Even(crop.Height))
                    : (crop.Width, crop.Height);
            return Start(canvasDevice, item, captureCursor, crop, outputSize);
        }

        private static CaptureRect ClampCropToItem(CaptureRect crop, CaptureRect itemBounds)
        {
            if (crop.X < 0 || crop.Y < 0 || crop.Width <= 0 || crop.Height <= 0)
            {
                throw new ArgumentException(
                    $"Window crop must be non-negative with positive size; got {crop.X},{crop.Y} {crop.Width}x{crop.Height}.");
            }

            if (crop.Right > itemBounds.Width || crop.Bottom > itemBounds.Height)
            {
                // Soft clamp rather than fail hard: HiDPI rounding can overshoot by a pixel.
                var width = Math.Min(crop.Width, itemBounds.Width - crop.X);
                var height = Math.Min(crop.Height, itemBounds.Height - crop.Y);
                if (width <= 0 || height <= 0)
                {
                    throw new ArgumentException(
                        $"Window crop {crop.X},{crop.Y} {crop.Width}x{crop.Height} is outside the capture item " +
                        $"{itemBounds.Width}x{itemBounds.Height}.");
                }

                return new CaptureRect(crop.X, crop.Y, width, height);
            }

            return crop;
        }

        public static WgcFrameSource StartRegion(CaptureRect region, bool captureCursor)
        {
            var placed = FindPlacedRegion(region);
            if (placed is null)
            {
                throw new InvalidOperationException(
                    $"Region {region.X},{region.Y} {region.Width}x{region.Height} does not intersect a monitor.");
            }

            var canvasDevice = new CanvasDevice();
            var item = GraphicsCaptureItemInterop.CreateForMonitor(placed.Value.Monitor);
            var crop = ClampCropToItemSize(placed.Value.Crop, item.Size.Width, item.Size.Height);
            Console.Error.WriteLine(
                $"region {region.X},{region.Y} {region.Width}x{region.Height} " +
                $"monitor=0x{placed.Value.Monitor.ToInt64():X} " +
                $"crop={crop.X},{crop.Y} {crop.Width}x{crop.Height} " +
                $"item={item.Size.Width}x{item.Size.Height} cursor={captureCursor}");
            return Start(
                canvasDevice,
                item,
                captureCursor,
                crop,
                (Even(crop.Width), Even(crop.Height)));
        }

        private static WgcFrameSource Start(
            CanvasDevice canvasDevice,
            GraphicsCaptureItem item,
            bool captureCursor,
            CaptureRect crop,
            (int Width, int Height) outputSize)
        {
            if (item.Size.Width <= 0 || item.Size.Height <= 0)
            {
                throw new InvalidOperationException(
                    $"Capture item has invalid size {item.Size.Width}x{item.Size.Height}.");
            }

            var framePool =
                Direct3D11CaptureFramePool.CreateFreeThreaded(
                    canvasDevice,
                    DirectXPixelFormat.B8G8R8A8UIntNormalized,
                    numberOfBuffers: 2,
                    item.Size);
            var session = framePool.CreateCaptureSession(item);
            TrySetCursorCapture(session, captureCursor);
            try
            {
                TryDisableCaptureBorder(session);
            }
            catch (Exception e) when (e is ArgumentException or COMException or Win32Exception)
            {
                Console.Error.WriteLine($"Could not disable the capture border: {e.Message}");
            }
            var source = new WgcFrameSource(canvasDevice, item, framePool, session, crop, outputSize);
            framePool.FrameArrived += source.OnFrameArrived;
            session.StartCapture();
            return source;
        }

        public byte[]? WaitForFrameBytes(CancellationToken stopToken)
        {
            while (!stopToken.IsCancellationRequested)
            {
                bool hasFrame;
                try
                {
                    hasFrame = frameAvailable.WaitOne(TimeSpan.FromMilliseconds(500));
                }
                catch (ObjectDisposedException)
                {
                    return null;
                }

                if (hasFrame)
                {
                    lock (frameLock)
                    {
                        return latestFrame?.ToArray();
                    }
                }
            }

            return null;
        }

        public void Dispose()
        {
            if (disposed)
            {
                return;
            }

            disposed = true;
            lock (frameLock)
            {
                framePool.FrameArrived -= OnFrameArrived;
                session.Dispose();
                framePool.Dispose();
                canvasDevice.Dispose();
            }

            frameAvailable.Dispose();
        }

        private void OnFrameArrived(Direct3D11CaptureFramePool sender, object args)
        {
            lock (frameLock)
            {
                if (disposed)
                {
                    return;
                }

                using var frame = sender.TryGetNextFrame();
                if (frame is null)
                {
                    return;
                }

                using var bitmap =
                    CanvasBitmap.CreateFromDirect3D11Surface(canvasDevice, frame.Surface);
                var bytes = CopyFrameBytes(bitmap, frame.ContentSize, crop, Width, Height);
                latestFrame = bytes;

                frameAvailable.Set();
            }
        }

        private static byte[] CopyFrameBytes(
            CanvasBitmap bitmap,
            SizeInt32 contentSize,
            CaptureRect crop,
            int outputWidth,
            int outputHeight)
        {
            var sourceBytes = bitmap.GetPixelBytes();
            var bitmapWidth = (int)bitmap.SizeInPixels.Width;
            var bitmapHeight = (int)bitmap.SizeInPixels.Height;
            var sourceWidth =
                Math.Min(crop.Width, Math.Min(contentSize.Width, bitmapWidth) - crop.X);
            var sourceHeight =
                Math.Min(crop.Height, Math.Min(contentSize.Height, bitmapHeight) - crop.Y);
            if (sourceWidth <= 0 || sourceHeight <= 0)
            {
                return new byte[outputWidth * outputHeight * 4];
            }

            var output = new byte[outputWidth * outputHeight * 4];
            var sourceStride = bitmapWidth * 4;
            var outputStride = outputWidth * 4;
            var copyBytesPerRow = sourceWidth * 4;
            for (var y = 0; y < sourceHeight; y++)
            {
                var destinationRow = y * outputStride;
                Buffer.BlockCopy(
                    sourceBytes,
                    (crop.Y + y) * sourceStride + crop.X * 4,
                    output,
                    destinationRow,
                    copyBytesPerRow);
                for (var x = sourceWidth; x < outputWidth; x++)
                {
                    Buffer.BlockCopy(
                        output,
                        destinationRow + (sourceWidth - 1) * 4,
                        output,
                        destinationRow + x * 4,
                        4);
                }
            }

            for (var y = sourceHeight; y < outputHeight; y++)
            {
                Buffer.BlockCopy(
                    output,
                    (sourceHeight - 1) * outputStride,
                    output,
                    y * outputStride,
                    outputStride);
            }

            return output;
        }

        public static void TryDisableCaptureBorder(GraphicsCaptureSession session)
        {
            if (!Windows.Foundation.Metadata.ApiInformation.IsPropertyPresent(
                    "Windows.Graphics.Capture.GraphicsCaptureSession",
                    "IsBorderRequired"))
            {
                return;
            }

            var sessionAbi = MarshalInspectable<GraphicsCaptureSession>.FromManaged(
                session,
                unwrapObject: true);
            try
            {
                var session3Guid = IGraphicsCaptureSession3Guid;
                var hr = Marshal.QueryInterface(
                    sessionAbi,
                    ref session3Guid,
                    out var session3);
                if (hr < 0)
                {
                    Marshal.ThrowExceptionForHR(hr);
                }

                try
                {
                    hr = SetIsBorderRequired(session3, required: false);
                    if (hr < 0)
                    {
                        Marshal.ThrowExceptionForHR(hr);
                    }
                }
                finally
                {
                    Marshal.Release(session3);
                }
            }
            finally
            {
                MarshalInspectable<GraphicsCaptureSession>.DisposeAbi(sessionAbi);
            }
        }

        private static PlacedMonitor? FindPlacedRegion(CaptureRect region)
        {
            var monitors = new List<EnumeratedMonitor>();
            bool Callback(IntPtr monitor, IntPtr hdcMonitor, ref Rect bounds, IntPtr data)
            {
                monitors.Add(
                    new EnumeratedMonitor(
                        monitor,
                        new MonitorRect(bounds.Left, bounds.Top, bounds.Right, bounds.Bottom)));
                return true;
            }

            if (!EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, Callback, IntPtr.Zero) && monitors.Count == 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "EnumDisplayMonitors failed.");
            }

            var placed = RegionPlacement.Choose(monitors.ConvertAll(static monitor => monitor.Bounds), region);
            if (placed is null)
            {
                return null;
            }

            var match = monitors.Find(monitor => monitor.Bounds == placed.Value.Monitor);
            var handle = FreshMonitorHandle(placed.Value, match.Handle);
            return new PlacedMonitor(handle, placed.Value.Crop);
        }

        private static IntPtr FreshMonitorHandle(PlacedRegion placed, IntPtr enumerated)
        {
            var screen = new Rect(
                placed.Monitor.Left + placed.Crop.X,
                placed.Monitor.Top + placed.Crop.Y,
                placed.Monitor.Left + placed.Crop.X + placed.Crop.Width,
                placed.Monitor.Top + placed.Crop.Y + placed.Crop.Height);
            var fresh = MonitorFromRect(in screen, MonitorDefaultToNull);
            return fresh == IntPtr.Zero ? enumerated : fresh;
        }

        private static CaptureRect ClampCropToItemSize(CaptureRect crop, int itemWidth, int itemHeight)
        {
            if (itemWidth <= 0 || itemHeight <= 0)
            {
                throw new InvalidOperationException(
                    $"Capture item has invalid size {itemWidth}x{itemHeight}.");
            }

            var x = Math.Clamp(crop.X, 0, Math.Max(itemWidth - 1, 0));
            var y = Math.Clamp(crop.Y, 0, Math.Max(itemHeight - 1, 0));
            var width = Math.Min(crop.Width, itemWidth - x);
            var height = Math.Min(crop.Height, itemHeight - y);
            if (width <= 0 || height <= 0)
            {
                throw new InvalidOperationException(
                    $"Region crop {crop.X},{crop.Y} {crop.Width}x{crop.Height} is outside the capture item " +
                    $"{itemWidth}x{itemHeight}.");
            }

            if (x != crop.X || y != crop.Y || width != crop.Width || height != crop.Height)
            {
                Console.Error.WriteLine(
                    $"clamped crop {crop.X},{crop.Y} {crop.Width}x{crop.Height} to {x},{y} {width}x{height} " +
                    $"for item {itemWidth}x{itemHeight}");
            }

            return new CaptureRect(x, y, width, height);
        }

        private static void TrySetCursorCapture(GraphicsCaptureSession session, bool captureCursor)
        {
            if (!Windows.Foundation.Metadata.ApiInformation.IsPropertyPresent(
                    "Windows.Graphics.Capture.GraphicsCaptureSession",
                    "IsCursorCaptureEnabled"))
            {
                Console.Error.WriteLine(
                    "IsCursorCaptureEnabled is not present; leaving the default cursor capture.");
                return;
            }

            try
            {
                session.IsCursorCaptureEnabled = captureCursor;
            }
            catch (ArgumentException e)
            {
                Console.Error.WriteLine(
                    $"IsCursorCaptureEnabled={captureCursor} was rejected ({e.Message}); " +
                    "continuing with the default.");
            }
        }
    }

    private static int SetIsBorderRequired(IntPtr session3, bool required)
    {
        var vtable = Marshal.ReadIntPtr(session3);
        var setIsBorderRequiredPointer = Marshal.ReadIntPtr(vtable, IntPtr.Size * 7);
        var setIsBorderRequired =
            Marshal.GetDelegateForFunctionPointer<SetIsBorderRequiredDelegate>(
                setIsBorderRequiredPointer);
        return setIsBorderRequired(session3, required);
    }

    private static class GraphicsCaptureItemInterop
    {
        private static readonly Guid GraphicsCaptureItemGuid =
            new("79C3F95B-31F7-4EC2-A464-632EF5D30760");

        public static GraphicsCaptureItem CreateForWindow(IntPtr hwnd)
        {
            var interop = GraphicsCaptureItem.As<IGraphicsCaptureItemInterop>();
            var item = interop.CreateForWindow(hwnd, GraphicsCaptureItemGuid);
            return MarshalInterface<GraphicsCaptureItem>.FromAbi(item);
        }

        public static GraphicsCaptureItem CreateForMonitor(IntPtr monitor)
        {
            var viaDisplayId = TryCreateForDisplayId(monitor);
            if (viaDisplayId is not null)
            {
                return viaDisplayId;
            }

            try
            {
                var interop = GraphicsCaptureItem.As<IGraphicsCaptureItemInterop>();
                var item = interop.CreateForMonitor(monitor, GraphicsCaptureItemGuid);
                return MarshalInterface<GraphicsCaptureItem>.FromAbi(item);
            }
            catch (ArgumentException e)
            {
                throw new InvalidOperationException(
                    $"Windows Graphics Capture rejected monitor 0x{monitor.ToInt64():X} " +
                    $"({e.GetType().Name}: {e.Message}). CreateForMonitor returned E_INVALIDARG and " +
                    "DisplayId capture was unavailable.",
                    e);
            }
        }

        private static GraphicsCaptureItem? TryCreateForDisplayId(IntPtr monitor)
        {
            if (!Windows.Foundation.Metadata.ApiInformation.IsApiContractPresent(
                    "Windows.Foundation.UniversalApiContract",
                    12))
            {
                Console.Error.WriteLine("DisplayId capture is unavailable; using CreateForMonitor.");
                return null;
            }

            Microsoft.UI.DisplayId uiId;
            try
            {
                uiId = Microsoft.UI.Win32Interop.GetDisplayIdFromMonitor(monitor);
            }
            catch (Exception e) when (
                e is ArgumentException or COMException or EntryPointNotFoundException or TypeLoadException)
            {
                Console.Error.WriteLine(
                    $"GetDisplayIdFromMonitor failed: {e.GetType().Name}: {e.Message}");
                return null;
            }

            if (uiId.Value == 0)
            {
                Console.Error.WriteLine("GetDisplayIdFromMonitor returned 0; using CreateForMonitor.");
                return null;
            }

            try
            {
                var statics = GraphicsCaptureItem.As<IGraphicsCaptureItemStatics2>();
                var hr = statics.TryCreateFromDisplayId(uiId.Value, out var abi);
                if (hr < 0 || abi == IntPtr.Zero)
                {
                    Console.Error.WriteLine($"TryCreateFromDisplayId failed hr=0x{hr:X8}.");
                    if (abi != IntPtr.Zero)
                    {
                        Marshal.Release(abi);
                    }

                    return null;
                }

                Console.Error.WriteLine($"region capture item from DisplayId 0x{uiId.Value:X}.");
                return MarshalInterface<GraphicsCaptureItem>.FromAbi(abi);
            }
            catch (Exception e) when (
                e is ArgumentException
                    or COMException
                    or InvalidCastException
                    or EntryPointNotFoundException
                    or MissingMethodException
                    or TypeLoadException)
            {
                Console.Error.WriteLine(
                    $"TryCreateFromDisplayId failed: {e.GetType().Name}: {e.Message}");
                return null;
            }
        }
    }

    [ComImport]
    [Guid("3b92acc9-e584-5862-bf5c-9c316c6d2dbb")]
    [InterfaceType(ComInterfaceType.InterfaceIsIInspectable)]
    private interface IGraphicsCaptureItemStatics2
    {
        [PreserveSig]
        int TryCreateFromWindowId(ulong windowId, out IntPtr item);

        [PreserveSig]
        int TryCreateFromDisplayId(ulong displayId, out IntPtr item);
    }

    [ComImport]
    [Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    [ComVisible(true)]
    private interface IGraphicsCaptureItemInterop
    {
        IntPtr CreateForWindow(IntPtr window, ref Guid iid);

        IntPtr CreateForMonitor(IntPtr monitor, ref Guid iid);
    }

    private static readonly Guid IGraphicsCaptureSession3Guid =
        new("F2CDD966-22AE-5EA1-9596-3A289344C3BE");

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int SetIsBorderRequiredDelegate(
        IntPtr session,
        [MarshalAs(UnmanagedType.I1)] bool required);
}

internal static class WindowsAppSdkBootstrap
{
    [ModuleInitializer]
    public static void Initialize()
    {
        Environment.SetEnvironmentVariable(
            "MICROSOFT_WINDOWSAPPRUNTIME_BASE_DIRECTORY",
            AppContext.BaseDirectory);
    }
}
