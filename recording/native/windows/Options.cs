internal enum CaptureMode
{
    Screenshot,
    Recording,
}

internal enum CaptureSource
{
    Window,
    Region,
}

internal sealed record CaptureRect(int X, int Y, int Width, int Height)
{
    public int Right => X + Width;

    public int Bottom => Y + Height;
}

internal sealed record Options(
    CaptureMode Mode,
    CaptureSource Source,
    string? Title,
    long? OwnerPid,
    CaptureRect? Region,
    // Optional crop relative to the captured window top-left, in device pixels (WGC item space).
    // Only valid for window source. Fixed for the recording lifetime (issue #186).
    CaptureRect? Crop,
    int Fps,
    bool CaptureCursor,
    string Output)
{
    public static Options Parse(string[] args)
    {
        CaptureMode? mode = null;
        CaptureSource? source = null;
        string? title = null;
        long? ownerPid = null;
        int? x = null;
        int? y = null;
        int? width = null;
        int? height = null;
        int? cropX = null;
        int? cropY = null;
        int? cropWidth = null;
        int? cropHeight = null;
        int? fps = null;
        bool? captureCursor = null;
        string? output = null;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--mode":
                    mode = ParseMode(ReadValue(args, ref i, "--mode"));
                    break;
                case "--source":
                    source = ParseSource(ReadValue(args, ref i, "--source"));
                    break;
                case "--title":
                    title = ReadValue(args, ref i, "--title");
                    break;
                case "--owner-pid":
                    ownerPid = ParsePositiveLong(ReadValue(args, ref i, "--owner-pid"), "--owner-pid");
                    break;
                case "--x":
                    x = ParseInt(ReadValue(args, ref i, "--x"), "--x");
                    break;
                case "--y":
                    y = ParseInt(ReadValue(args, ref i, "--y"), "--y");
                    break;
                case "--width":
                    width = ParsePositiveInt(ReadValue(args, ref i, "--width"), "--width");
                    break;
                case "--height":
                    height = ParsePositiveInt(ReadValue(args, ref i, "--height"), "--height");
                    break;
                case "--crop-x":
                    cropX = ParseNonNegativeInt(ReadValue(args, ref i, "--crop-x"), "--crop-x");
                    break;
                case "--crop-y":
                    cropY = ParseNonNegativeInt(ReadValue(args, ref i, "--crop-y"), "--crop-y");
                    break;
                case "--crop-width":
                    cropWidth = ParsePositiveInt(ReadValue(args, ref i, "--crop-width"), "--crop-width");
                    break;
                case "--crop-height":
                    cropHeight = ParsePositiveInt(ReadValue(args, ref i, "--crop-height"), "--crop-height");
                    break;
                case "--fps":
                    fps = ParsePositiveInt(ReadValue(args, ref i, "--fps"), "--fps");
                    break;
                case "--cursor":
                    captureCursor = ParseBool(ReadValue(args, ref i, "--cursor"), "--cursor");
                    break;
                case "--output":
                    output = ReadValue(args, ref i, "--output");
                    break;
                default:
                    throw new ArgumentException($"Unknown argument: {args[i]}");
            }
        }

        if (string.IsNullOrWhiteSpace(output))
        {
            throw new ArgumentException("--output is required and must not be blank.");
        }

        var parsedSource = source ?? throw new ArgumentException("--source is required.");
        CaptureRect? region = null;
        CaptureRect? crop = null;
        var cropPartsProvided =
            cropX is not null || cropY is not null || cropWidth is not null || cropHeight is not null;
        switch (parsedSource)
        {
            case CaptureSource.Window:
                if (string.IsNullOrWhiteSpace(title))
                {
                    throw new ArgumentException("--title is required and must not be blank.");
                }
                if (ownerPid is null)
                {
                    throw new ArgumentException("--owner-pid is required.");
                }
                if (cropPartsProvided)
                {
                    crop = new CaptureRect(
                        cropX ?? throw new ArgumentException("--crop-x is required when any --crop-* flag is set."),
                        cropY ?? throw new ArgumentException("--crop-y is required when any --crop-* flag is set."),
                        cropWidth ?? throw new ArgumentException("--crop-width is required when any --crop-* flag is set."),
                        cropHeight ?? throw new ArgumentException("--crop-height is required when any --crop-* flag is set."));
                }
                break;
            case CaptureSource.Region:
                if (cropPartsProvided)
                {
                    throw new ArgumentException("--crop-* flags are only valid with --source window.");
                }
                region = new CaptureRect(
                    x ?? throw new ArgumentException("--x is required."),
                    y ?? throw new ArgumentException("--y is required."),
                    width ?? throw new ArgumentException("--width is required."),
                    height ?? throw new ArgumentException("--height is required."));
                break;
        }

        return new Options(
            mode ?? throw new ArgumentException("--mode is required."),
            parsedSource,
            title,
            ownerPid,
            region,
            crop,
            fps ?? 30,
            captureCursor ?? true,
            output);
    }

    private static string ReadValue(string[] args, ref int index, string name)
    {
        if (index + 1 >= args.Length)
        {
            throw new ArgumentException($"{name} requires a value.");
        }

        index += 1;
        return args[index];
    }

    private static CaptureMode ParseMode(string value) =>
        value switch
        {
            "screenshot" => CaptureMode.Screenshot,
            "recording" => CaptureMode.Recording,
            _ => throw new ArgumentException("--mode must be screenshot or recording."),
        };

    private static CaptureSource ParseSource(string value) =>
        value switch
        {
            "window" => CaptureSource.Window,
            "region" => CaptureSource.Region,
            _ => throw new ArgumentException("--source must be window or region."),
        };

    private static bool ParseBool(string value, string name) =>
        value switch
        {
            "true" => true,
            "false" => false,
            _ => throw new ArgumentException($"{name} must be true or false."),
        };

    private static int ParsePositiveInt(string value, string name)
    {
        var parsed = ParseInt(value, name);
        if (parsed <= 0)
        {
            throw new ArgumentException($"{name} must be a positive integer.");
        }

        return parsed;
    }

    private static int ParseNonNegativeInt(string value, string name)
    {
        var parsed = ParseInt(value, name);
        if (parsed < 0)
        {
            throw new ArgumentException($"{name} must be a non-negative integer.");
        }

        return parsed;
    }

    private static long ParsePositiveLong(string value, string name)
    {
        if (!long.TryParse(value, out var parsed) || parsed <= 0 || parsed > uint.MaxValue)
        {
            throw new ArgumentException($"{name} must be a positive DWORD integer.");
        }

        return parsed;
    }

    private static int ParseInt(string value, string name)
    {
        if (!int.TryParse(value, out var parsed))
        {
            throw new ArgumentException($"{name} must be an integer.");
        }

        return parsed;
    }
}
