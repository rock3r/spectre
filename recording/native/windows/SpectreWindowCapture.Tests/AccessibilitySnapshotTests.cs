using System.Diagnostics;
using System.Text.Json;
using Xunit;

public sealed class AccessibilitySnapshotTests
{
    [Fact]
    public void SupportsWindowTitleAndRejectsEmptyTitle()
    {
        var reader = new FakeReader([Window(Node("root"))]);
        var result = AccessibilitySnapshotProtocol.RunForTest(Request("window-title", "Unique fixture"), reader);
        Assert.True(JsonDocument.Parse(result.Output).RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal("Unique fixture", reader.Target!.WindowTitle);
        Assert.Equal(2, AccessibilitySnapshotProtocol.RunForTest(Request("window-title", ""), reader).ExitCode);
    }

    [Theory]
    [InlineData("7", true)]
    [InlineData("0", false)]
    [InlineData("-3", false)]
    [InlineData("1.5", false)]
    [InlineData("\"7\"", false)]
    [InlineData("null", false)]
    public void TitleOwnerMustBeAPositiveInteger(string owner, bool valid)
    {
        var reader = new FakeReader([Window(Node("root"))]);
        using var pid = JsonDocument.Parse(owner);
        var request = JsonSerializer.Serialize(new {
            target = new { type = "window-title", value = "Fixture", processId = pid.RootElement },
            maximumDepth = 8, maximumNodeCount = 50, timeoutMs = 1000,
        });
        var result = AccessibilitySnapshotProtocol.RunForTest(request, reader);
        Assert.Equal(valid ? 0 : 2, result.ExitCode);
        if (valid)
        {
            Assert.Equal(7L, reader.Target!.ProcessId);
            Assert.Equal("Fixture", reader.Target.WindowTitle);
        }
        else Assert.Null(reader.Target);
    }

    [Fact]
    public void NumericIdsAndIndexFallbacksCannotCollide()
    {
        var reader = new FakeReader([Window(Node("root", Node("0"), Node(null), Node("same"), Node("same")))]);
        using var json = JsonDocument.Parse(AccessibilitySnapshotProtocol.RunForTest(Request("process-id", 7), reader).Output);
        var keys = json.RootElement.GetProperty("snapshot").GetProperty("nodes").EnumerateArray().Select(x => x.GetProperty("key").GetString()!).ToArray();
        Assert.Equal(["w0/root", "w0/root/~20", "w0/root/1", "w0/root/2", "w0/root/3"], keys);
        Assert.Equal(keys.Length, keys.Distinct().Count());
    }

    [Fact]
    public void ReaderReceivesLimitsBeforeItWalksTheTree()
    {
        var reader = new FakeReader([Window(Node("root"))]);
        AccessibilitySnapshotProtocol.RunForTest(Request("process-id", 7, 3, 4, 25), reader);
        Assert.Equal(3, reader.Limits!.MaximumDepth); Assert.Equal(4, reader.Limits.MaximumNodeCount); Assert.Equal(25, reader.Limits.TimeoutMs);
    }

    [Fact]
    public void PreservesPartialTreeReturnedByBoundedReader()
    {
        var partial = new[] { Window(Node("root", Node("first"))) };
        var reader = new ThrowingReader(new AccessibilityLimitException("limit-reached", "maximumNodeCount 2 reached", partial));
        using var json = JsonDocument.Parse(AccessibilitySnapshotProtocol.RunForTest(Request("process-id", 7), reader).Output);
        Assert.False(json.RootElement.GetProperty("ok").GetBoolean());
        Assert.Equal(2, json.RootElement.GetProperty("error").GetProperty("partialSnapshot").GetProperty("nodes").GetArrayLength());
    }

    [Fact]
    public void RealReaderCacheRequestContainsPatternsAndTheirReadProperties()
    {
        var request = WindowsUiaTreeReader.CreateCacheRequest();
        Assert.NotNull(request);
        Assert.Contains(System.Windows.Automation.ExpandCollapsePattern.ExpandCollapseStateProperty, WindowsUiaTreeReader.CachedPatternProperties);
        Assert.Contains(System.Windows.Automation.TogglePattern.ToggleStateProperty, WindowsUiaTreeReader.CachedPatternProperties);
        Assert.Contains(System.Windows.Automation.ValuePattern.ValueProperty, WindowsUiaTreeReader.CachedPatternProperties);
        Assert.Contains(System.Windows.Automation.SelectionItemPattern.IsSelectedProperty, WindowsUiaTreeReader.CachedPatternProperties);
    }

    private static string Request(string type, object value, int depth = 8, int count = 50, int timeout = 1000) =>
        JsonSerializer.Serialize(new { target = new { type, value }, maximumDepth = depth, maximumNodeCount = count, timeoutMs = timeout });
    private static AccessibilityElementData Node(string? id, params AccessibilityElementData[] children) => new(id, "Group", "group", id, null, true, false, false, false, false, false, new(0, 0, 10, 10), children);
    private static AccessibilityWindowData Window(AccessibilityElementData root) => new("1", "Fixture", new(0, 0, 100, 100), root);
    private sealed class FakeReader(IReadOnlyList<AccessibilityWindowData> result) : IAccessibilityTreeReader
    {
        public AccessibilityTarget? Target { get; private set; } public AccessibilityReadLimits? Limits { get; private set; }
        public IReadOnlyList<AccessibilityWindowData> Read(AccessibilityTarget target, AccessibilityReadLimits limits) { Target = target; Limits = limits; return result; }
    }
    private sealed class ThrowingReader(Exception error) : IAccessibilityTreeReader
    { public IReadOnlyList<AccessibilityWindowData> Read(AccessibilityTarget target, AccessibilityReadLimits limits) => throw error; }
}
