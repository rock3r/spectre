//! Resolve an X11 window XID from a caller-supplied title.
//!
//! GStreamer's `ximagesrc xname=` only compares `XFetchName` (`WM_NAME`) and **falls back
//! to the root window** when that lookup misses (`gstximagesrc.c` → "Using root window").
//! Compose/AWT titles often live in `_NET_WM_NAME` (UTF-8) and may include Latin-1
//! characters such as `·` that do not match `WM_NAME`. Silent root capture is the #513
//! defect (full Xvfb framebuffer instead of the named window).
//!
//! This module walks the tree, matches `_NET_WM_NAME` and `WM_NAME`, and returns a real
//! XID. Missing titles fail closed — callers must not spawn `ximagesrc` without an XID.

use anyhow::{anyhow, bail, Context, Result};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_long, c_ulong, c_void};
use std::ptr;

const RTLD_LAZY: c_int = 1;
const SUCCESS: c_int = 0;
const IS_VIEWABLE: i32 = 2;
const ANY_PROPERTY_TYPE: c_ulong = 0;
const XA_WM_NAME: c_ulong = 39;

/// Width/height of `XWindowAttributes` on LP64; `map_state` sits after the aligned
/// `Colormap`. The full struct is larger — we pass a 256-byte buffer so Xlib cannot
/// write past our allocation.
const XWA_WIDTH_OFFSET: usize = 8;
const XWA_HEIGHT_OFFSET: usize = 12;
const XWA_MAP_STATE_OFFSET: usize = 92;
const XWA_BUF_LEN: usize = 256;

#[link(name = "dl")]
extern "C" {
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlclose(handle: *mut c_void) -> c_int;
    fn dlerror() -> *mut c_char;
}

type XOpenDisplayFn = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type XCloseDisplayFn = unsafe extern "C" fn(*mut c_void) -> c_int;
type XDefaultRootWindowFn = unsafe extern "C" fn(*mut c_void) -> c_ulong;
type XInternAtomFn = unsafe extern "C" fn(*mut c_void, *const c_char, c_int) -> c_ulong;
type XQueryTreeFn = unsafe extern "C" fn(
    *mut c_void,
    c_ulong,
    *mut c_ulong,
    *mut c_ulong,
    *mut *mut c_ulong,
    *mut u32,
) -> c_int;
type XGetWindowPropertyFn = unsafe extern "C" fn(
    *mut c_void,
    c_ulong,
    c_ulong,
    c_long,
    c_long,
    c_int,
    c_ulong,
    *mut c_ulong,
    *mut c_int,
    *mut c_ulong,
    *mut c_ulong,
    *mut *mut u8,
) -> c_int;
type XFetchNameFn = unsafe extern "C" fn(*mut c_void, c_ulong, *mut *mut c_char) -> c_int;
type XGetWindowAttributesFn = unsafe extern "C" fn(*mut c_void, c_ulong, *mut u8) -> c_int;
type XFreeFn = unsafe extern "C" fn(*mut c_void) -> c_int;

struct X11Api {
    handle: *mut c_void,
    open_display: XOpenDisplayFn,
    close_display: XCloseDisplayFn,
    default_root_window: XDefaultRootWindowFn,
    intern_atom: XInternAtomFn,
    query_tree: XQueryTreeFn,
    get_window_property: XGetWindowPropertyFn,
    fetch_name: XFetchNameFn,
    get_window_attributes: XGetWindowAttributesFn,
    free: XFreeFn,
}

impl Drop for X11Api {
    fn drop(&mut self) {
        unsafe {
            dlclose(self.handle);
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ResolvedX11Window {
    pub xid: u64,
    pub width: u32,
    pub height: u32,
}

pub fn find_window_by_title(display_name: Option<&str>, title: &str) -> Result<ResolvedX11Window> {
    let title = title.trim();
    if title.is_empty() {
        bail!("X11 window capture requires a non-blank window title");
    }
    let api = X11Api::load().context("loading libX11.so.6 for named-window lookup")?;
    let display_cstr = match display_name.map(str::trim).filter(|s| !s.is_empty()) {
        Some(name) => Some(CString::new(name).context("DISPLAY name contains an interior NUL")?),
        None => None,
    };
    let display = unsafe {
        (api.open_display)(
            display_cstr
                .as_ref()
                .map(|s| s.as_ptr())
                .unwrap_or(ptr::null()),
        )
    };
    if display.is_null() {
        let requested = display_name
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .or_else(|| std::env::var("DISPLAY").ok())
            .unwrap_or_else(|| "<default>".into());
        bail!(
            "Could not open X display {requested:?} while resolving window title {title:?}. \
             Named-window capture cannot fall back to the root/desktop."
        );
    }
    let result = unsafe { find_on_open_display(&api, display, title) };
    unsafe {
        (api.close_display)(display);
    }
    result
}

unsafe fn find_on_open_display(
    api: &X11Api,
    display: *mut c_void,
    title: &str,
) -> Result<ResolvedX11Window> {
    let root = (api.default_root_window)(display);
    let net_wm_name = intern_atom(api, display, "_NET_WM_NAME");
    let mut matches = Vec::new();
    collect_matches(api, display, root, root, title, net_wm_name, &mut matches)?;
    let best = matches
        .into_iter()
        .min_by_key(|m| m.width.saturating_mul(m.height))
        .ok_or_else(|| {
            anyhow!(
                "X11 window titled {title:?} was not found (checked WM_NAME and _NET_WM_NAME). \
                 GStreamer ximagesrc xname= would silently record the root/desktop; Spectre \
                 fails closed instead. Use the exact mapped window title, or startRegion(...) \
                 if framebuffer capture is what you want."
            )
        })?;
    if best.xid == 0 || best.xid == root {
        bail!(
            "X11 window lookup for {title:?} resolved to the root window; refusing silent \
             desktop capture"
        );
    }
    Ok(best)
}

unsafe fn collect_matches(
    api: &X11Api,
    display: *mut c_void,
    root: c_ulong,
    window: c_ulong,
    title: &str,
    net_wm_name: Option<c_ulong>,
    out: &mut Vec<ResolvedX11Window>,
) -> Result<()> {
    if window != root {
        if let Some(resolved) = match_window(api, display, window, title, net_wm_name)? {
            out.push(resolved);
        }
    }
    let mut root_return = 0;
    let mut parent_return = 0;
    let mut children: *mut c_ulong = ptr::null_mut();
    let mut nchildren = 0u32;
    let status = (api.query_tree)(
        display,
        window,
        &mut root_return,
        &mut parent_return,
        &mut children,
        &mut nchildren,
    );
    if status == 0 {
        return Ok(());
    }
    if !children.is_null() && nchildren > 0 {
        let slice = std::slice::from_raw_parts(children, nchildren as usize);
        for child in slice {
            collect_matches(api, display, root, *child, title, net_wm_name, out)?;
        }
    }
    if !children.is_null() {
        (api.free)(children.cast());
    }
    Ok(())
}

unsafe fn match_window(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
    title: &str,
    net_wm_name: Option<c_ulong>,
) -> Result<Option<ResolvedX11Window>> {
    let net_name = net_wm_name
        .map(|atom| read_text_property(api, display, window, atom))
        .transpose()?
        .flatten();
    let wm_name = fetch_name(api, display, window)?;
    let xa_wm_name = read_text_property(api, display, window, XA_WM_NAME)?;
    let names = [net_name, wm_name, xa_wm_name];
    if !names.iter().flatten().any(|name| titles_match(title, name)) {
        return Ok(None);
    }
    let Some((width, height, map_state)) = window_geometry(api, display, window)? else {
        return Ok(None);
    };
    if map_state != IS_VIEWABLE || width == 0 || height == 0 {
        return Ok(None);
    }
    Ok(Some(ResolvedX11Window {
        xid: window,
        width,
        height,
    }))
}

unsafe fn intern_atom(api: &X11Api, display: *mut c_void, name: &str) -> Option<c_ulong> {
    let c_name = CString::new(name).ok()?;
    let atom = (api.intern_atom)(display, c_name.as_ptr(), 1);
    if atom == 0 {
        None
    } else {
        Some(atom)
    }
}

unsafe fn read_text_property(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
    atom: c_ulong,
) -> Result<Option<String>> {
    let mut actual_type = 0;
    let mut actual_format = 0;
    let mut nitems = 0;
    let mut bytes_after = 0;
    let mut prop: *mut u8 = ptr::null_mut();
    let status = (api.get_window_property)(
        display,
        window,
        atom,
        0,
        1024,
        0,
        ANY_PROPERTY_TYPE,
        &mut actual_type,
        &mut actual_format,
        &mut nitems,
        &mut bytes_after,
        &mut prop,
    );
    if status != SUCCESS || prop.is_null() || nitems == 0 {
        if !prop.is_null() {
            (api.free)(prop.cast());
        }
        return Ok(None);
    }
    let byte_len = match actual_format {
        8 => nitems as usize,
        16 => nitems as usize * 2,
        32 => nitems as usize * 4,
        _ => {
            (api.free)(prop.cast());
            return Ok(None);
        }
    };
    let bytes = std::slice::from_raw_parts(prop, byte_len);
    let text = decode_x_text(bytes);
    (api.free)(prop.cast());
    if text.is_empty() {
        Ok(None)
    } else {
        Ok(Some(text))
    }
}

unsafe fn fetch_name(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
) -> Result<Option<String>> {
    let mut name: *mut c_char = ptr::null_mut();
    let status = (api.fetch_name)(display, window, &mut name);
    if status == 0 || name.is_null() {
        if !name.is_null() {
            (api.free)(name.cast());
        }
        return Ok(None);
    }
    let text = CStr::from_ptr(name).to_bytes();
    let decoded = decode_x_text(text);
    (api.free)(name.cast());
    if decoded.is_empty() {
        Ok(None)
    } else {
        Ok(Some(decoded))
    }
}

unsafe fn window_geometry(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
) -> Result<Option<(u32, u32, i32)>> {
    let mut buf = [0u8; XWA_BUF_LEN];
    let status = (api.get_window_attributes)(display, window, buf.as_mut_ptr());
    if status == 0 {
        return Ok(None);
    }
    let width = i32::from_ne_bytes(
        buf[XWA_WIDTH_OFFSET..XWA_WIDTH_OFFSET + 4]
            .try_into()
            .expect("width slice"),
    );
    let height = i32::from_ne_bytes(
        buf[XWA_HEIGHT_OFFSET..XWA_HEIGHT_OFFSET + 4]
            .try_into()
            .expect("height slice"),
    );
    let map_state = i32::from_ne_bytes(
        buf[XWA_MAP_STATE_OFFSET..XWA_MAP_STATE_OFFSET + 4]
            .try_into()
            .expect("map_state slice"),
    );
    if width <= 0 || height <= 0 {
        return Ok(None);
    }
    Ok(Some((width as u32, height as u32, map_state)))
}

fn titles_match(expected: &str, actual: &str) -> bool {
    expected.trim() == actual.trim()
}

fn decode_x_text(bytes: &[u8]) -> String {
    let bytes = bytes.strip_suffix(&[0]).unwrap_or(bytes);
    if let Ok(text) = std::str::from_utf8(bytes) {
        return text.trim().to_string();
    }
    bytes
        .iter()
        .copied()
        .map(char::from)
        .collect::<String>()
        .trim()
        .to_string()
}

impl X11Api {
    fn load() -> Result<Self> {
        unsafe {
            // Clear any leftover dlerror.
            dlerror();
            let name = CString::new("libX11.so.6").expect("static soname");
            let handle = dlopen(name.as_ptr(), RTLD_LAZY);
            if handle.is_null() {
                let err = dlerror_message();
                bail!(
                    "libX11.so.6 is required for named-window X11 capture (ximagesrc already \
                     depends on it): {err}"
                );
            }
            match (|| {
                Ok(Self {
                    handle,
                    open_display: load_sym(handle, "XOpenDisplay")?,
                    close_display: load_sym(handle, "XCloseDisplay")?,
                    default_root_window: load_sym(handle, "XDefaultRootWindow")?,
                    intern_atom: load_sym(handle, "XInternAtom")?,
                    query_tree: load_sym(handle, "XQueryTree")?,
                    get_window_property: load_sym(handle, "XGetWindowProperty")?,
                    fetch_name: load_sym(handle, "XFetchName")?,
                    get_window_attributes: load_sym(handle, "XGetWindowAttributes")?,
                    free: load_sym(handle, "XFree")?,
                })
            })() {
                Ok(api) => Ok(api),
                Err(err) => {
                    dlclose(handle);
                    Err(err)
                }
            }
        }
    }
}

unsafe fn load_sym<T>(handle: *mut c_void, name: &str) -> Result<T> {
    dlerror();
    let c_name = CString::new(name).expect("symbol name");
    let sym = dlsym(handle, c_name.as_ptr());
    if sym.is_null() {
        let err = dlerror_message();
        bail!("dlsym({name}) failed: {err}");
    }
    assert_eq!(
        std::mem::size_of::<T>(),
        std::mem::size_of::<*mut c_void>(),
        "Xlib symbol {name} is not pointer-sized"
    );
    let mut out = std::mem::MaybeUninit::<T>::uninit();
    std::ptr::write(out.as_mut_ptr().cast::<*mut c_void>(), sym);
    Ok(out.assume_init())
}

unsafe fn dlerror_message() -> String {
    let ptr = dlerror();
    if ptr.is_null() {
        "unknown dlopen/dlsym error".into()
    } else {
        CStr::from_ptr(ptr).to_string_lossy().into_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_latin1_middle_dot_wm_name() {
        let bytes = b"bioparco \xB7 grabby stepper";
        assert_eq!(decode_x_text(bytes), "bioparco · grabby stepper");
    }

    #[test]
    fn decodes_utf8_net_wm_name() {
        let title = "bioparco · grabby stepper";
        assert_eq!(decode_x_text(title.as_bytes()), title);
    }

    #[test]
    fn titles_match_after_trim() {
        assert!(titles_match(
            "bioparco · grabby stepper",
            " bioparco · grabby stepper "
        ));
        assert!(!titles_match(
            "bioparco · grabby stepper",
            "bioparco grabby stepper"
        ));
    }

    #[test]
    fn blank_title_fails_closed_without_opening_x() {
        let err = find_window_by_title(None, "   ").unwrap_err();
        assert!(
            err.to_string().contains("non-blank"),
            "unexpected error: {err:#}"
        );
    }

    #[test]
    fn missing_title_fails_closed_when_display_is_available() {
        let display = match std::env::var("DISPLAY") {
            Ok(value) if !value.trim().is_empty() => value,
            _ => return,
        };
        let err = find_window_by_title(Some(&display), "spectre-no-such-window-513").unwrap_err();
        let message = format!("{err:#}");
        assert!(
            message.contains("was not found"),
            "missing title must fail closed, got: {message}"
        );
        assert!(
            message.contains("root") || message.contains("desktop"),
            "error must mention the silent-root hazard, got: {message}"
        );
    }
}
