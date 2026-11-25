using System.Runtime.InteropServices;
using System.Windows.Automation;

/// <summary>Reads UIA Control View incrementally, checking protocol limits before expanding a node.</summary>
internal sealed class WindowsUiaTreeReader : IAccessibilityTreeReader
{
    internal static readonly AutomationProperty[] CachedPatternProperties =
    [
        ValuePattern.ValueProperty,
        TogglePattern.ToggleStateProperty,
        ExpandCollapsePattern.ExpandCollapseStateProperty,
        SelectionItemPattern.IsSelectedProperty,
    ];
    private int count;
    private AccessibilityReadLimits limits = null!;
    private string? limitCode;
    private string? limitMessage;

    public IReadOnlyList<AccessibilityWindowData> Read(AccessibilityTarget target, AccessibilityReadLimits readLimits)
    {
        limits = readLimits; count = 0; limitCode = null; limitMessage = null;
        try
        {
            var roots = FindRoots(target); if (roots.Count == 0) throw new AccessibilityTargetException("target-not-found", "No matching top-level UI Automation window was found");
            var result = new List<AccessibilityWindowData>();
            foreach (var root in roots)
            {
                if (!CanRead(0)) break;
                var cached = root.GetUpdatedCache(CreateCacheRequest());
                var bounds = Rectangle(cached.Cached.BoundingRectangle);
                var element = ReadElement(cached, 0);
                result.Add(new(cached.Cached.NativeWindowHandle.ToString(System.Globalization.CultureInfo.InvariantCulture), NullIfEmpty(cached.Cached.Name), bounds, element));
                if (limitCode is not null) break;
            }
            if (limitCode is not null) throw new AccessibilityLimitException(limitCode, limitMessage!, result);
            return result;
        }
        catch (UnauthorizedAccessException e) { throw new AccessibilityTargetException("permission-missing", e.Message); }
        catch (ElementNotAvailableException e) { throw new AccessibilityTargetException("no-accessibility-tree", e.Message); }
        catch (COMException e) { throw new AccessibilityTargetException("no-accessibility-tree", $"UI Automation failed: {e.Message}"); }
    }

    private static IReadOnlyList<AutomationElement> FindRoots(AccessibilityTarget target)
    {
        if (target.WindowHandle is { } handle) return [AutomationElement.FromHandle(handle)];

        Condition? owner = null;
        if (target.ProcessId is { } pid)
        {
            if (pid > int.MaxValue) throw new AccessibilityTargetException("target-not-found", $"Process id {pid} is outside the UIA range");
            owner = new PropertyCondition(AutomationElement.ProcessIdProperty, (int)pid);
        }

        Condition filter;
        if (target.WindowTitle is { } title)
        {
            var name = new PropertyCondition(AutomationElement.NameProperty, title);
            filter = owner is null ? name : new AndCondition(owner, name);
        }
        else filter = owner ?? throw new InvalidOperationException("Missing UIA process selector");

        var matches = AutomationElement.RootElement.FindAll(TreeScope.Children, filter).Cast<AutomationElement>().ToArray();
        if (target.WindowTitle is not null && matches.Length > 1)
            throw new AccessibilityTargetException("target-ambiguous", $"More than one top-level window has title '{target.WindowTitle}'");
        return matches;
    }

    internal static CacheRequest CreateCacheRequest()
    {
        var r = new CacheRequest { AutomationElementMode = AutomationElementMode.Full, TreeFilter = Automation.ControlViewCondition, TreeScope = TreeScope.Element | TreeScope.Children };
        foreach (var p in new[] { AutomationElement.AutomationIdProperty, AutomationElement.NameProperty, AutomationElement.ControlTypeProperty, AutomationElement.IsEnabledProperty,
            AutomationElement.HasKeyboardFocusProperty, AutomationElement.IsKeyboardFocusableProperty, AutomationElement.BoundingRectangleProperty, AutomationElement.NativeWindowHandleProperty,
            }.Concat(CachedPatternProperties)) r.Add(p);
        r.Add(ValuePattern.Pattern); r.Add(TogglePattern.Pattern); r.Add(ExpandCollapsePattern.Pattern); r.Add(SelectionItemPattern.Pattern);
        return r;
    }

    private AccessibilityElementData ReadElement(AutomationElement element, int depth)
    {
        count++;
        var role = element.Cached.ControlType?.ProgrammaticName?.Replace("ControlType.", "", StringComparison.Ordinal);
        var value = ReadValue(element, out var check, out var expand, out var select);
        var children = new List<AccessibilityElementData>();
        foreach (AutomationElement child in element.CachedChildren)
        {
            if (!CanRead(depth + 1)) break;
            children.Add(ReadElement(child.GetUpdatedCache(CreateCacheRequest()), depth + 1));
            if (limitCode is not null) break;
        }
        return new(NullIfEmpty(element.Cached.AutomationId), role, NormalizeRole(role), NullIfEmpty(element.Cached.Name), value,
            element.Cached.IsEnabled, element.Cached.HasKeyboardFocus, element.Cached.IsKeyboardFocusable, check, expand, select, Rectangle(element.Cached.BoundingRectangle), children);
    }

    private bool CanRead(int depth)
    {
        if (limits.Stopwatch.ElapsedMilliseconds >= limits.TimeoutMs) { limitCode = "timeout"; limitMessage = $"timeoutMs {limits.TimeoutMs} reached"; return false; }
        if (depth > limits.MaximumDepth) { limitCode = "limit-reached"; limitMessage = $"maximumDepth {limits.MaximumDepth} reached"; return false; }
        if (count >= limits.MaximumNodeCount) { limitCode = "limit-reached"; limitMessage = $"maximumNodeCount {limits.MaximumNodeCount} reached"; return false; }
        return true;
    }

    private static string? ReadValue(AutomationElement e, out bool checkedState, out bool expanded, out bool selected)
    {
        checkedState = expanded = selected = false;
        if (e.TryGetCachedPattern(ValuePattern.Pattern, out var vp)) return NullIfEmpty(((ValuePattern)vp).Cached.Value);
        if (e.TryGetCachedPattern(TogglePattern.Pattern, out var tp)) { var s = ((TogglePattern)tp).Cached.ToggleState; checkedState = s == ToggleState.On; return s == ToggleState.On ? "on" : s == ToggleState.Off ? "off" : "indeterminate"; }
        if (e.TryGetCachedPattern(ExpandCollapsePattern.Pattern, out var ep)) { var s = ((ExpandCollapsePattern)ep).Cached.ExpandCollapseState; expanded = s == ExpandCollapseState.Expanded; return s switch { ExpandCollapseState.Expanded => "expanded", ExpandCollapseState.Collapsed => "collapsed", ExpandCollapseState.PartiallyExpanded => "partially-expanded", _ => "leaf-node" }; }
        if (e.TryGetCachedPattern(SelectionItemPattern.Pattern, out var sp)) { selected = ((SelectionItemPattern)sp).Cached.IsSelected; return selected ? "selected" : "not-selected"; }
        return null;
    }
    private static string? NormalizeRole(string? role) => role switch { "Button" => "button", "CheckBox" => "checkbox", "ComboBox" => "combobox", "Edit" => "textbox", "Group" => "group", "Hyperlink" => "link", "Image" => "image", "List" => "list", "ListItem" => "listitem", "Menu" => "menu", "MenuItem" => "menuitem", "RadioButton" => "radio", "Slider" => "slider", "Tab" => "tablist", "TabItem" => "tab", "Text" => "text", "Window" => "window", null => null, _ => role.ToLowerInvariant() };
    private static AccessibilityRectangle Rectangle(System.Windows.Rect r) => r.IsEmpty || !double.IsFinite(r.X) || !double.IsFinite(r.Y) || !double.IsFinite(r.Width) || !double.IsFinite(r.Height) ? new(0, 0, 0, 0) : new(Round(r.X), Round(r.Y), Math.Max(0, Round(r.Width)), Math.Max(0, Round(r.Height)));
    private static int Round(double v) => (int)Math.Clamp(Math.Round(v, MidpointRounding.AwayFromZero), int.MinValue, int.MaxValue);
    private static string? NullIfEmpty(string? v) => string.IsNullOrEmpty(v) ? null : v;
}
