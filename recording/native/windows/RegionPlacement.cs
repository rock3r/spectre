internal readonly record struct MonitorRect(int Left, int Top, int Right, int Bottom);

internal readonly record struct PlacedRegion(MonitorRect Monitor, CaptureRect Crop);

/// <summary>
/// Maps a screen-space capture rectangle onto one monitor.
///
/// EnumDisplayMonitors and the JVM can disagree by a pixel at a DPI boundary. A region that is
/// fully inside a monitor keeps its size. Only a one-pixel DPI-rounding overshoot is clamped;
/// cross-monitor and larger overflow is rejected.
/// </summary>
internal static class RegionPlacement
{
    public static PlacedRegion? Choose(IReadOnlyList<MonitorRect> monitors, CaptureRect region)
    {
        if (region.Width <= 0 || region.Height <= 0)
        {
            return null;
        }
        foreach (var monitor in monitors)
        {
            if (FullyContains(monitor, region))
            {
                return new PlacedRegion(monitor, CropWithin(monitor, region));
            }
        }

        foreach (var monitor in monitors)
        {
            var overlap = Intersection(monitor, region);
            if (overlap is null || Overflow(monitor, region) > 1)
            {
                continue;
            }
            return new PlacedRegion(monitor, CropWithin(monitor, overlap));
        }
        return null;
    }

    private static bool FullyContains(MonitorRect monitor, CaptureRect region) =>
        region.Width > 0 &&
        region.Height > 0 &&
        region.X >= monitor.Left &&
        region.Y >= monitor.Top &&
        (long)region.X + region.Width <= monitor.Right &&
        (long)region.Y + region.Height <= monitor.Bottom;

    private static CaptureRect? Intersection(MonitorRect monitor, CaptureRect region)
    {
        var left = Math.Max(region.X, monitor.Left);
        var top = Math.Max(region.Y, monitor.Top);
        var right = Math.Min((long)region.X + region.Width, monitor.Right);
        var bottom = Math.Min((long)region.Y + region.Height, monitor.Bottom);
        var width = right - left;
        var height = bottom - top;
        if (width <= 0 || height <= 0)
        {
            return null;
        }

        return new CaptureRect((int)left, (int)top, (int)width, (int)height);
    }

    private static long Overflow(MonitorRect monitor, CaptureRect region) =>
        Math.Max(
            Math.Max(Math.Max(monitor.Left - region.X, 0), Math.Max(monitor.Top - region.Y, 0)),
            Math.Max(Math.Max((long)region.X + region.Width - monitor.Right, 0), Math.Max((long)region.Y + region.Height - monitor.Bottom, 0)));

    private static CaptureRect CropWithin(MonitorRect monitor, CaptureRect screenRect) =>
        new(
            screenRect.X - monitor.Left,
            screenRect.Y - monitor.Top,
            screenRect.Width,
            screenRect.Height);
}
