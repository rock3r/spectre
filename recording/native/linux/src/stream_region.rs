//! Pure geometry for mapping an AWT/portal region onto a PipeWire stream.

use crate::protocol::Region;
use anyhow::{bail, Result};

/// Convert an AWT screen-pixel [region] into a PipeWire stream-relative crop.
///
/// Mixed-space HiDPI (XWayland 2560×1440 vs a 1536×864 portal stream) is scaled
/// when the selected AWT display and the stream share an aspect ratio. The optional
/// [awt_display] is that display's origin + size in AWT pixels — never the primary
/// `Toolkit.screenSize`, which is wrong for a secondary monitor. Multi-monitor
/// virtual desktops that do not match the selected stream are left unscaled and
/// only translated by [stream_position]. A leftover overflow then clamps; a miss
/// is still an error.
pub fn map_awt_region_to_stream(
    region: Region,
    stream_position: (i32, i32),
    stream_size: (u32, u32),
    awt_display: Option<Region>,
) -> Result<Region> {
    if region.width <= 0 || region.height <= 0 {
        bail!(
            "region must have positive dimensions, was {}x{}",
            region.width,
            region.height
        );
    }
    let relative = match awt_display.filter(|d| d.width > 0 && d.height > 0) {
        Some(display) => {
            let local = Region {
                x: region.x - display.x,
                y: region.y - display.y,
                width: region.width,
                height: region.height,
            };
            match scale_from_awt_display(display, stream_size) {
                DisplayScale::Scaled(sx, sy) => scale_region(local, sx, sy),
                DisplayScale::Identity => local,
                DisplayScale::Unrelated => subtract_origin(region, stream_position),
            }
        }
        None => subtract_origin(region, stream_position),
    };
    clamp_region_to_stream(relative, stream_size)
}

enum DisplayScale {
    Scaled(f64, f64),
    Identity,
    Unrelated,
}

fn scale_from_awt_display(display: Region, stream_size: (u32, u32)) -> DisplayScale {
    let (stream_w, stream_h) = stream_size;
    if stream_w == 0 || stream_h == 0 {
        return DisplayScale::Unrelated;
    }
    let sx = stream_w as f64 / display.width as f64;
    let sy = stream_h as f64 / display.height as f64;
    if (sx - sy).abs() > ASPECT_MATCH_EPSILON {
        return DisplayScale::Unrelated;
    }
    if (sx - 1.0).abs() < IDENTITY_SCALE_EPSILON && (sy - 1.0).abs() < IDENTITY_SCALE_EPSILON {
        return DisplayScale::Identity;
    }
    DisplayScale::Scaled(sx, sy)
}

fn subtract_origin(region: Region, origin: (i32, i32)) -> Region {
    Region {
        x: region.x - origin.0,
        y: region.y - origin.1,
        width: region.width,
        height: region.height,
    }
}

fn scale_region(region: Region, sx: f64, sy: f64) -> Region {
    Region {
        x: (region.x as f64 * sx).round() as i32,
        y: (region.y as f64 * sy).round() as i32,
        width: (region.width as f64 * sx).round().max(1.0) as i32,
        height: (region.height as f64 * sy).round().max(1.0) as i32,
    }
}

/// Parse optional `screen_size` wire payload (`[x, y, width, height]`).
pub fn screen_size_to_region(values: [i32; 4]) -> Option<Region> {
    let region = Region {
        x: values[0],
        y: values[1],
        width: values[2],
        height: values[3],
    };
    if region.width <= 0 || region.height <= 0 {
        None
    } else {
        Some(region)
    }
}

const ASPECT_MATCH_EPSILON: f64 = 0.02;
const IDENTITY_SCALE_EPSILON: f64 = 0.02;

/// Intersect [region] with [stream_size] and return the visible crop.
///
/// Genuinely oversized windows (larger than the shared monitor, or leftover
/// overflow after mixed-space conversion) clamp to the stream. A region that
/// misses the stream entirely still fails — that is a placement bug, not
/// something we can invent pixels for.
pub fn clamp_region_to_stream(region: Region, stream_size: (u32, u32)) -> Result<Region> {
    if region.width <= 0 || region.height <= 0 {
        bail!(
            "region must have positive dimensions, was {}x{}",
            region.width,
            region.height
        );
    }
    let (stream_w, stream_h) = stream_size;
    if stream_w == 0 || stream_h == 0 {
        bail!(
            "stream_size must have positive dimensions, was {}x{}",
            stream_w,
            stream_h
        );
    }
    let stream_w = stream_w as i64;
    let stream_h = stream_h as i64;
    let left = region.x as i64;
    let top = region.y as i64;
    let right = left + region.width as i64;
    let bottom = top + region.height as i64;
    let clip_left = left.max(0);
    let clip_top = top.max(0);
    let clip_right = right.min(stream_w);
    let clip_bottom = bottom.min(stream_h);
    if clip_right <= clip_left || clip_bottom <= clip_top {
        bail!(
            "region ({}, {}, {}x{}) does not intersect stream {}x{}",
            region.x,
            region.y,
            region.width,
            region.height,
            stream_w,
            stream_h
        );
    }
    Ok(Region {
        x: clip_left as i32,
        y: clip_top as i32,
        width: (clip_right - clip_left) as i32,
        height: (clip_bottom - clip_top) as i32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn region(x: i32, y: i32, w: i32, h: i32) -> Region {
        Region {
            x,
            y,
            width: w,
            height: h,
        }
    }

    #[test]
    fn clamps_overflowing_hidpi_window_to_stream() {
        let clipped = clamp_region_to_stream(region(1362, 0, 480, 240), (1536, 864)).unwrap();
        assert_eq!(clipped.x, 1362);
        assert_eq!(clipped.y, 0);
        assert_eq!(clipped.width, 174);
        assert_eq!(clipped.height, 240);
    }

    #[test]
    fn clamps_window_larger_than_the_stream() {
        let clipped = clamp_region_to_stream(region(0, 0, 4000, 3000), (1536, 864)).unwrap();
        assert_eq!(clipped.x, 0);
        assert_eq!(clipped.y, 0);
        assert_eq!(clipped.width, 1536);
        assert_eq!(clipped.height, 864);
    }

    #[test]
    fn rejects_region_that_misses_the_stream() {
        let err = clamp_region_to_stream(region(2000, 0, 480, 240), (1536, 864)).unwrap_err();
        assert!(
            err.to_string().contains("does not intersect"),
            "{err:#}"
        );
    }

    #[test]
    fn rejects_non_positive_region_before_scale() {
        let err = map_awt_region_to_stream(
            region(100, 100, 0, 240),
            (0, 0),
            (1536, 864),
            Some(region(0, 0, 2560, 1440)),
        )
        .unwrap_err();
        assert!(
            err.to_string().contains("positive dimensions"),
            "{err:#}"
        );
    }

    #[test]
    fn maps_mixed_awt_physical_region_onto_logical_portal_stream() {
        // jbr-bench: AWT/XWayland 2560x1440, portal stream 1536x864 (1.66x).
        // A 480x240 window whose right edge is 1842 must keep its full frame, not a sliver.
        let mapped = map_awt_region_to_stream(
            region(1362, 100, 480, 240),
            (0, 0),
            (1536, 864),
            Some(region(0, 0, 2560, 1440)),
        )
        .unwrap();
        assert_eq!(mapped.x, 817);
        assert_eq!(mapped.y, 60);
        assert_eq!(mapped.width, 288);
        assert_eq!(mapped.height, 144);
    }

    #[test]
    fn subtracts_stream_position_when_awt_screen_size_is_unknown() {
        let mapped = map_awt_region_to_stream(
            region(2000, 80, 400, 300),
            (1920, 0),
            (1920, 1080),
            None,
        )
        .unwrap();
        assert_eq!(mapped.x, 80);
        assert_eq!(mapped.y, 80);
        assert_eq!(mapped.width, 400);
        assert_eq!(mapped.height, 300);
    }

    #[test]
    fn does_not_scale_when_awt_screen_aspect_does_not_match_stream() {
        // Virtual desktop 3840x1080 vs one 1920x1080 monitor: subtract + clamp only.
        let mapped = map_awt_region_to_stream(
            region(2000, 0, 400, 300),
            (1920, 0),
            (1920, 1080),
            Some(region(0, 0, 3840, 1080)),
        )
        .unwrap();
        assert_eq!(mapped.x, 80);
        assert_eq!(mapped.y, 0);
        assert_eq!(mapped.width, 400);
        assert_eq!(mapped.height, 300);
    }

    #[test]
    fn keeps_selected_display_origin_when_that_display_is_identity_scaled() {
        // Preceding 2560x1440@1.66 (AWT) / 1536x864 (portal), then a 1920x1080@1x
        // secondary. AWT origin 2560 != portal origin 1536; subtracting stream
        // position would shift the crop by 1024.
        let mapped = map_awt_region_to_stream(
            region(2640, 80, 400, 300),
            (1536, 0),
            (1920, 1080),
            Some(region(2560, 0, 1920, 1080)),
        )
        .unwrap();
        assert_eq!(mapped.x, 80);
        assert_eq!(mapped.y, 80);
        assert_eq!(mapped.width, 400);
        assert_eq!(mapped.height, 300);
    }

    #[test]
    fn scales_secondary_display_relative_to_that_display_origin() {
        // Primary 1920x1080 at (0,0); selected secondary 2560x1440 at (1920,0) with
        // a 1536x864 portal stream. Scaling against the primary size would apply a
        // spurious 4/3 transform.
        let mapped = map_awt_region_to_stream(
            region(2000, 100, 480, 240),
            (0, 0),
            (1536, 864),
            Some(region(1920, 0, 2560, 1440)),
        )
        .unwrap();
        assert_eq!(mapped.x, 48);
        assert_eq!(mapped.y, 60);
        assert_eq!(mapped.width, 288);
        assert_eq!(mapped.height, 144);
    }
}
