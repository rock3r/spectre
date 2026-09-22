/// <summary>
/// Picks a top-level window that covers a capture rectangle, and the crop inside that window.
///
/// Used when monitor <c>CreateForMonitor</c> / DisplayId capture cannot build an item. The window
/// path is the same <c>CreateForWindow</c> call window capture already uses.
/// </summary>
internal static class WindowRegionTarget
{
    public static WindowRect? Choose(IReadOnlyList<WindowRect> windows, CaptureRect region)
    {
        WindowRect? smallestContainer = null;
        var smallestArea = 0;
        WindowRect? bestOverlap = null;
        var bestOverlapArea = 0;
        foreach (var window in windows)
        {
            if (window.Hwnd == IntPtr.Zero)
            {
                continue;
            }

            var width = window.Right - window.Left;
            var height = window.Bottom - window.Top;
            if (width <= 0 || height <= 0)
            {
                continue;
            }

            var overlap = OverlapArea(window, region);
            if (overlap <= 0)
            {
                continue;
            }

            if (Contains(window, region))
            {
                var area = width * height;
                if (smallestContainer is null || area < smallestArea)
                {
                    smallestContainer = window;
                    smallestArea = area;
                }
            }

            if (overlap > bestOverlapArea)
            {
                bestOverlap = window;
                bestOverlapArea = overlap;
            }
        }

        return smallestContainer ?? bestOverlap;
    }

    public static CaptureRect Crop(WindowRect window, CaptureRect region)
    {
        var left = Math.Max(region.X, window.Left);
        var top = Math.Max(region.Y, window.Top);
        var right = Math.Min(region.Right, window.Right);
        var bottom = Math.Min(region.Bottom, window.Bottom);
        return new CaptureRect(
            left - window.Left,
            top - window.Top,
            Math.Max(0, right - left),
            Math.Max(0, bottom - top));
    }

    private static bool Contains(WindowRect window, CaptureRect region) =>
        region.X >= window.Left &&
        region.Y >= window.Top &&
        region.Right <= window.Right &&
        region.Bottom <= window.Bottom;

    private static int OverlapArea(WindowRect window, CaptureRect region)
    {
        var left = Math.Max(region.X, window.Left);
        var top = Math.Max(region.Y, window.Top);
        var right = Math.Min(region.Right, window.Right);
        var bottom = Math.Min(region.Bottom, window.Bottom);
        var width = right - left;
        var height = bottom - top;
        if (width <= 0 || height <= 0)
        {
            return 0;
        }

        return width * height;
    }
}

internal readonly record struct WindowRect(IntPtr Hwnd, int Left, int Top, int Right, int Bottom);
