using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

internal readonly record struct AccessibilityRectangle(int X, int Y, int Width, int Height);
internal sealed record AccessibilityElementData(string? Id, string? PlatformRole, string? Role, string? Name,
    string? Value, bool IsEnabled, bool HasKeyboardFocus, bool IsKeyboardFocusable, bool IsChecked,
    bool IsExpanded, bool IsSelected, AccessibilityRectangle Bounds, IReadOnlyList<AccessibilityElementData> Children);
internal sealed record AccessibilityWindowData(string Id, string? Title, AccessibilityRectangle Bounds, AccessibilityElementData Root);
internal sealed record AccessibilityTarget(string Type, long? ProcessId, nint? WindowHandle, string? WindowTitle);
internal sealed record AccessibilityReadLimits(int MaximumDepth, int MaximumNodeCount, long TimeoutMs, Stopwatch Stopwatch);
internal interface IAccessibilityTreeReader { IReadOnlyList<AccessibilityWindowData> Read(AccessibilityTarget target, AccessibilityReadLimits limits); }
internal sealed class AccessibilityTargetException(string code, string message) : Exception(message) { public string Code { get; } = code; }
internal sealed class AccessibilityLimitException(string code, string message, IReadOnlyList<AccessibilityWindowData> partial) : Exception(message)
{ public string Code { get; } = code; public IReadOnlyList<AccessibilityWindowData> Partial { get; } = partial; }
internal readonly record struct AccessibilityProtocolResult(int ExitCode, string Output, string Error);

internal static class AccessibilitySnapshotProtocol
{
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, DefaultIgnoreCondition = JsonIgnoreCondition.Never };
    public static int Run(IAccessibilityTreeReader reader) { var r = RunForTest(Console.In.ReadToEnd(), reader); Console.Out.Write(r.Output); if (r.Error.Length > 0) Console.Error.WriteLine(r.Error); return r.ExitCode; }
    internal static AccessibilityProtocolResult RunForTest(string input, IAccessibilityTreeReader reader)
    {
        Request request;
        try { request = Parse(input); } catch (Exception e) when (e is JsonException or RequestException or InvalidOperationException) { return new(2, "", e.Message); }
        var watch = Stopwatch.StartNew();
        try
        {
            var windows = reader.Read(request.Target, new(request.Depth, request.Count, request.Timeout, watch));
            if (windows.Count == 0) return Fail("target-not-found", "No matching top-level window was found", null);
            return Success(new { ok = true, snapshot = Snapshot(windows) });
        }
        catch (AccessibilityLimitException e) { return Fail(e.Code, e.Message, Snapshot(e.Partial)); }
        catch (AccessibilityTargetException e) { return Fail(e.Code, e.Message, null); }
    }
    private static object Snapshot(IReadOnlyList<AccessibilityWindowData> windows)
    {
        var windowJson = new List<object>(); var nodes = new List<object>();
        for (var w = 0; w < windows.Count; w++)
        {
            var window = windows[w]; var wk = $"w{w}";
            windowJson.Add(new { key = wk, title = window.Title, bounds = Rect(window.Bounds), boundsSpace = "screen" });
            Visit(window.Root, null, wk, window.Bounds, nodes);
        }
        return new { schemaVersion = 2, source = "os-accessibility", capturedAt = DateTimeOffset.UtcNow.ToString("O"), windows = windowJson, nodes, notes = Array.Empty<string>() };
    }
    private static void Visit(AccessibilityElementData e, string? parent, string prefix, AccessibilityRectangle window, List<object> nodes, string? segment = null)
    {
        var key = $"{prefix}/{segment ?? Segment(e.Id, 0, false)}";
        nodes.Add(new { key, parentKey = parent, id = e.Id, platformRole = e.PlatformRole, role = e.Role, name = e.Name,
            states = States(e), value = e.Value, bounds = Rect(e.Bounds), onScreen = Intersects(e.Bounds, window) });
        var duplicate = e.Children.Where(x => x.Id is not null).GroupBy(x => x.Id, StringComparer.Ordinal).Where(x => x.Count() > 1).Select(x => x.Key).ToHashSet(StringComparer.Ordinal);
        for (var i = 0; i < e.Children.Count; i++) Visit(e.Children[i], key, key, window, nodes, Segment(e.Children[i].Id, i, e.Children[i].Id is not null && duplicate.Contains(e.Children[i].Id)));
    }
    private static string Segment(string? id, int index, bool duplicate) => id is null || duplicate ? index.ToString(System.Globalization.CultureInfo.InvariantCulture) : IsNumericLiteral(id) ? $"~2{id}" : id.Replace("~", "~0").Replace("/", "~1");
    private static bool IsNumericLiteral(string id) => id.Length > 0 && id.All(char.IsAsciiDigit);
    private static string[] States(AccessibilityElementData e) { var s = new List<string> { e.IsEnabled ? "enabled" : "disabled" }; if (e.IsKeyboardFocusable) s.Add("focusable"); if (e.HasKeyboardFocus) s.Add("focused"); if (e.IsChecked) s.Add("checked"); if (e.IsExpanded) s.Add("expanded"); if (e.IsSelected) s.Add("selected"); return [.. s]; }
    private static object Rect(AccessibilityRectangle r) => new { x = r.X, y = r.Y, width = r.Width, height = r.Height };
    private static bool Intersects(AccessibilityRectangle a, AccessibilityRectangle b) => a.Width > 0 && a.Height > 0 && b.Width > 0 && b.Height > 0 && a.X < b.X + b.Width && a.X + a.Width > b.X && a.Y < b.Y + b.Height && a.Y + a.Height > b.Y;
    private static AccessibilityProtocolResult Success(object value) => new(0, JsonSerializer.Serialize(value, JsonOptions), "");
    private static AccessibilityProtocolResult Fail(string code, string message, object? partial) => Success(new { ok = false, error = new { code, message, partialSnapshot = partial } });
    private static Request Parse(string input)
    {
        using var doc = JsonDocument.Parse(input); var root = doc.RootElement; if (root.ValueKind != JsonValueKind.Object) throw new RequestException("Request must be a JSON object");
        var target = Required(root, "target"); var type = String(target, "type"); AccessibilityTarget parsed;
        if (type == "process-id") { var id = Integer(target, "value"); if (id <= 0) throw new RequestException("process-id value must be positive"); parsed = new(type, id, null, null); }
        else if (type == "window-id") { var value = String(target, "value"); if (!TryHandle(value, out var handle) || handle == 0) throw new RequestException("window-id value must be a non-zero decimal or hexadecimal handle"); parsed = new(type, null, handle, null); }
        else if (type == "window-title")
        {
            long? owner = target.TryGetProperty("processId", out _) ? PositiveLong(target, "processId") : null;
            parsed = new(type, owner, null, String(target, "value"));
        }
        else throw new RequestException("target.type must be process-id, window-id, or window-title");
        return new(parsed, PositiveInt(root, "maximumDepth"), PositiveInt(root, "maximumNodeCount"), PositiveLong(root, "timeoutMs"));
    }
    private static JsonElement Required(JsonElement e, string n) { if (!e.TryGetProperty(n, out var v)) throw new RequestException($"{n} is required"); return v; }
    private static string String(JsonElement e, string n) { var v = Required(e, n); if (v.ValueKind != JsonValueKind.String || string.IsNullOrEmpty(v.GetString())) throw new RequestException($"{n} must be a non-empty string"); return v.GetString()!; }
    private static long Integer(JsonElement e, string n) { var v = Required(e, n); if (v.ValueKind != JsonValueKind.Number || !v.TryGetInt64(out var x)) throw new RequestException($"{n} must be an integer"); return x; }
    private static int PositiveInt(JsonElement e, string n) { var x = Integer(e, n); if (x <= 0 || x > int.MaxValue) throw new RequestException($"{n} must be positive"); return (int)x; }
    private static long PositiveLong(JsonElement e, string n) { var x = Integer(e, n); if (x <= 0) throw new RequestException($"{n} must be positive"); return x; }
    private static bool TryHandle(string value, out nint handle) { var hex = value.StartsWith("0x", StringComparison.OrdinalIgnoreCase); if (hex) value = value[2..]; if (ulong.TryParse(value, hex ? System.Globalization.NumberStyles.AllowHexSpecifier : System.Globalization.NumberStyles.Integer, null, out var x) && (IntPtr.Size == 8 || x <= uint.MaxValue)) { handle = unchecked((nint)x); return true; } handle = 0; return false; }
    private sealed record Request(AccessibilityTarget Target, int Depth, int Count, long Timeout);
    private sealed class RequestException(string message) : Exception(message);
}
