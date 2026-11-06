internal enum CaptureMode
{
    Screenshot,
    Recording,
    // Keeps one capture session open and writes a still to stdout for each request (#612).
    Stills,
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
    // Selects an untitled window (JWindow, plain AWT Window) by its GetWindowRect frame, in
    // device pixels. Window source only; mutually exclusive with Title.
    CaptureRect? Frame,
    int Fps,
    bool CaptureCursor,
    string Output)
{
    public static string[] NormalizeIncomingArgs(string[] args)
    {
        if (args.Length == 1 && args[0].StartsWith('@') && args[0].Length > 1)
        {
            var path = args[0][1..];
            if (!File.Exists(path))
            {
                throw new ArgumentException($"Arguments file not found: {path}");
            }

            args = File.ReadAllLines(path)
                .Where(static line => !string.IsNullOrWhiteSpace(line))
                .ToArray();
        }

        if (args.Length > 0 && IsLeakedProgramPath(args[0]))
        {
            args = args[1..];
        }

        return args;
    }

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
        int? frameX = null;
        int? frameY = null;
        int? frameWidth = null;
        int? frameHeight = null;
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
                case "--frame-x":
                    frameX = ParseInt(ReadValue(args, ref i, "--frame-x"), "--frame-x");
                    break;
                case "--frame-y":
                    frameY = ParseInt(ReadValue(args, ref i, "--frame-y"), "--frame-y");
                    break;
                case "--frame-width":
                    frameWidth = ParsePositiveInt(ReadValue(args, ref i, "--frame-width"), "--frame-width");
                    break;
                case "--frame-height":
                    frameHeight = ParsePositiveInt(ReadValue(args, ref i, "--frame-height"), "--frame-height");
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

        if (mode == CaptureMode.Stills)
        {
            // Stills go to stdout; an --output path has no meaning.
            output ??= "-";
        }

        if (string.IsNullOrWhiteSpace(output))
        {
            throw new ArgumentException("--output is required and must not be blank.");
        }

        var parsedSource = source ?? throw new ArgumentException("--source is required.");
        if (mode == CaptureMode.Stills && parsedSource != CaptureSource.Window)
        {
            throw new ArgumentException("--mode stills only supports --source window.");
        }
        CaptureRect? region = null;
        CaptureRect? crop = null;
        CaptureRect? frame = null;
        var cropPartsProvided =
            cropX is not null || cropY is not null || cropWidth is not null || cropHeight is not null;
        var framePartsProvided =
            frameX is not null || frameY is not null || frameWidth is not null || frameHeight is not null;
        switch (parsedSource)
        {
            case CaptureSource.Window:
                if (framePartsProvided)
                {
                    if (title is not null)
                    {
                        throw new ArgumentException("--title and --frame-* are mutually exclusive.");
                    }

                    frame = new CaptureRect(
                        frameX ?? throw new ArgumentException("--frame-x is required when any --frame-* flag is set."),
                        frameY ?? throw new ArgumentException("--frame-y is required when any --frame-* flag is set."),
                        frameWidth ?? throw new ArgumentException("--frame-width is required when any --frame-* flag is set."),
                        frameHeight ?? throw new ArgumentException("--frame-height is required when any --frame-* flag is set."));
                }
                else if (string.IsNullOrWhiteSpace(title))
                {
                    throw new ArgumentException("--title or --frame-* is required, and --title must not be blank.");
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
                if (framePartsProvided)
                {
                    throw new ArgumentException("--frame-* flags are only valid with --source window.");
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
            frame,
            fps ?? 30,
            captureCursor ?? true,
            output);
    }

    private static bool IsLeakedProgramPath(string token)
    {
        if (token.StartsWith('-'))
        {
            return false;
        }

        return token.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            || token.Contains('\\')
            || token.Contains('/');
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
            "stills" => CaptureMode.Stills,
            _ => throw new ArgumentException("--mode must be screenshot, recording, or stills."),
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
