internal readonly record struct MonitorRect(int Left, int Top, int Right, int Bottom);

internal readonly record struct PlacedRegion(MonitorRect Monitor, CaptureRect Crop);

/// <summary>
/// Maps a screen-space capture rectangle onto one monitor.
///
/// EnumDisplayMonitors and the JVM can disagree by a pixel at a DPI boundary. A region that is
/// fully inside a monitor keeps its size. A region that only overshoots is clamped to the overlap
/// so the helper does not fail closed on that pixel. A region that misses every monitor is not
/// placed.
/// </summary>
internal static class RegionPlacement
{
    public static PlacedRegion? Choose(IReadOnlyList<MonitorRect> monitors, CaptureRect region)
    {
        foreach (var monitor in monitors)
        {
            if (FullyContains(monitor, region))
            {
                return new PlacedRegion(monitor, CropWithin(monitor, region));
            }
        }

        PlacedRegion? best = null;
        var bestArea = 0;
        foreach (var monitor in monitors)
        {
            var overlap = Intersection(monitor, region);
            if (overlap is null)
            {
                continue;
            }

            var area = overlap.Width * overlap.Height;
            if (area > bestArea)
            {
                bestArea = area;
                best = new PlacedRegion(monitor, CropWithin(monitor, overlap));
            }
        }

        return best;
    }

    private static bool FullyContains(MonitorRect monitor, CaptureRect region) =>
        region.X >= monitor.Left &&
        region.Y >= monitor.Top &&
        region.Right <= monitor.Right &&
        region.Bottom <= monitor.Bottom;

    private static CaptureRect? Intersection(MonitorRect monitor, CaptureRect region)
    {
        var left = Math.Max(region.X, monitor.Left);
        var top = Math.Max(region.Y, monitor.Top);
        var right = Math.Min(region.Right, monitor.Right);
        var bottom = Math.Min(region.Bottom, monitor.Bottom);
        var width = right - left;
        var height = bottom - top;
        if (width <= 0 || height <= 0)
        {
            return null;
        }

        return new CaptureRect(left, top, width, height);
    }

    private static CaptureRect CropWithin(MonitorRect monitor, CaptureRect screenRect) =>
        new(
            screenRect.X - monitor.Left,
            screenRect.Y - monitor.Top,
            screenRect.Width,
            screenRect.Height);
}
