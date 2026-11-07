//! Resolve an X11 window XID from a caller-supplied title, or from the frame of an untitled
//! window.
//!
//! GStreamer's `ximagesrc xname=` only compares `XFetchName` (`WM_NAME`) and **falls back
//! to the root window** when that lookup misses (`gstximagesrc.c` → "Using root window").
//! Compose/AWT titles often live in `_NET_WM_NAME` (UTF-8) and may include Latin-1
//! characters such as `·` that do not match `WM_NAME`. Silent root capture is the #513
//! defect (full Xvfb framebuffer instead of the named window).
//!
//! This module walks the tree, matches `_NET_WM_NAME` and `WM_NAME`, and returns a real
//! XID. Missing titles fail closed — callers must not spawn `ximagesrc` without an XID.
//!
//! Untitled windows (a Swing `JWindow`, a plain AWT `Window`) have no title to match. For
//! those the JVM sends the window frame in root coordinates instead, and
//! [`find_window_by_frame`] matches it against the top-level client windows. Zero or several
//! matches fail closed, like a missing title.

use crate::protocol::{Region, ScreenshotCommand};
use anyhow::{anyhow, bail, Context, Result};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_long, c_uint, c_ulong, c_void};
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
type XTranslateCoordinatesFn = unsafe extern "C" fn(
    *mut c_void,
    c_ulong,
    c_ulong,
    c_int,
    c_int,
    *mut c_int,
    *mut c_int,
    *mut c_ulong,
) -> c_int;

type XCreateSimpleWindowFn = unsafe extern "C" fn(
    *mut c_void,
    c_ulong,
    c_int,
    c_int,
    c_uint,
    c_uint,
    c_uint,
    c_ulong,
    c_ulong,
) -> c_ulong;
type XSelectInputFn = unsafe extern "C" fn(*mut c_void, c_ulong, c_long) -> c_int;
type XChangePropertyFn = unsafe extern "C" fn(
    *mut c_void,
    c_ulong,
    c_ulong,
    c_ulong,
    c_int,
    c_int,
    *const u8,
    c_int,
) -> c_int;
type XCheckTypedWindowEventFn =
    unsafe extern "C" fn(*mut c_void, c_ulong, c_int, *mut c_long) -> c_int;
type XDestroyWindowFn = unsafe extern "C" fn(*mut c_void, c_ulong) -> c_int;
type XSendEventFn = unsafe extern "C" fn(*mut c_void, c_ulong, c_int, c_long, *mut c_long) -> c_int;
type XSyncFn = unsafe extern "C" fn(*mut c_void, c_int) -> c_int;

/// `XEvent` is a union padded to 24 C longs (192 bytes on LP64).
const XEVENT_LONGS: usize = 24;
const PROPERTY_NOTIFY: c_int = 28;
const CLIENT_MESSAGE: c_int = 33;
const PROPERTY_CHANGE_MASK: c_long = 1 << 22;
const SUBSTRUCTURE_NOTIFY_MASK: c_long = 1 << 19;
const SUBSTRUCTURE_REDIRECT_MASK: c_long = 1 << 20;
const XA_CARDINAL: c_ulong = 6;
const PROP_MODE_REPLACE: c_int = 0;
/// EWMH source indication 2: a pager or other tool acting for the user. Mutter honours it with
/// a current timestamp; a plain application request (1) with the same timestamp also works on
/// GNOME 50, but a pager request is what Spectre is.
const ACTIVE_WINDOW_SOURCE_PAGER: c_long = 2;
const SERVER_TIME_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(1_000);

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
    translate_coordinates: XTranslateCoordinatesFn,
    create_simple_window: XCreateSimpleWindowFn,
    select_input: XSelectInputFn,
    change_property: XChangePropertyFn,
    check_typed_window_event: XCheckTypedWindowEventFn,
    destroy_window: XDestroyWindowFn,
    send_event: XSendEventFn,
    sync: XSyncFn,
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

/// How the screenshot command identifies its target window.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum X11WindowSelector {
    Title(String),
    Frame {
        frame: Region,
        owner_pid: Option<u32>,
    },
}

/// Reads the window selector of an X11 window screenshot. Exactly one of `window_title` and
/// `window_frame` must be set.
pub fn x11_window_selector(command: &ScreenshotCommand) -> Result<X11WindowSelector> {
    match (&command.window_title, command.window_frame) {
        (Some(_), Some(_)) => {
            bail!("X11 window screenshot: window_title and window_frame are mutually exclusive")
        }
        (Some(title), None) => Ok(X11WindowSelector::Title(title.clone())),
        (None, Some(frame)) => Ok(X11WindowSelector::Frame {
            frame,
            owner_pid: command.window_owner_pid,
        }),
        (None, None) => bail!("X11 window screenshot requires window_title or window_frame"),
    }
}

/// Rounding slack between the JVM's device-pixel frame and the X server geometry.
pub const FRAME_TOLERANCE_PX: i32 = 1;

/// A top-level client window, with its origin translated to root coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameCandidate {
    pub xid: u64,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    /// `_NET_WM_PID`, when the client sets it.
    pub pid: Option<u32>,
}

/// Picks the one candidate whose geometry equals [frame] within [FRAME_TOLERANCE_PX].
///
/// Candidates whose `_NET_WM_PID` names a process other than [owner_pid] are skipped; a
/// candidate without `_NET_WM_PID` is kept. No match, several matches, XID 0, or the root
/// window all fail closed: `ximagesrc` would otherwise record the whole desktop.
pub fn select_frame_match(
    candidates: &[FrameCandidate],
    frame: Region,
    owner_pid: Option<u32>,
    root: u64,
) -> Result<ResolvedX11Window> {
    let near = |actual: i64, expected: i64| (actual - expected).abs() <= FRAME_TOLERANCE_PX as i64;
    let matches: Vec<&FrameCandidate> = candidates
        .iter()
        .filter(|c| match (owner_pid, c.pid) {
            (Some(expected), Some(actual)) => expected == actual,
            _ => true,
        })
        .filter(|c| {
            near(c.x as i64, frame.x as i64)
                && near(c.y as i64, frame.y as i64)
                && near(c.width as i64, frame.width as i64)
                && near(c.height as i64, frame.height as i64)
        })
        .collect();
    let describe = || {
        format!(
            "frame {},{} {}x{} (device pixels){}",
            frame.x,
            frame.y,
            frame.width,
            frame.height,
            owner_pid
                .map(|pid| format!(" owned by pid {pid}"))
                .unwrap_or_default()
        )
    };
    let best = match matches.as_slice() {
        [] => bail!(
            "X11 window with {} was not found among the top-level windows. Spectre fails \
             closed instead of recording the root/desktop.",
            describe()
        ),
        [only] => **only,
        many => bail!(
            "{} windows match {}; Spectre cannot tell which one to capture",
            many.len(),
            describe()
        ),
    };
    if best.xid == 0 || best.xid == root {
        bail!(
            "X11 window lookup for {} resolved to the root window; refusing silent desktop capture",
            describe()
        );
    }
    Ok(ResolvedX11Window {
        xid: best.xid,
        width: best.width,
        height: best.height,
    })
}

/// Resolves the top-level client window whose root-relative geometry equals [frame].
pub fn find_window_by_frame(
    display_name: Option<&str>,
    frame: Region,
    owner_pid: Option<u32>,
) -> Result<ResolvedX11Window> {
    if frame.width <= 0 || frame.height <= 0 {
        bail!(
            "X11 window capture by frame requires a positive width and height; got {}x{}",
            frame.width,
            frame.height
        );
    }
    let api = X11Api::load().context("loading libX11.so.6 for window-frame lookup")?;
    let what = format!(
        "window frame {},{} {}x{}",
        frame.x, frame.y, frame.width, frame.height
    );
    with_display(&api, display_name, &what, |display| unsafe {
        let root = (api.default_root_window)(display);
        let candidates = collect_frame_candidates(&api, display, root)?;
        select_frame_match(&candidates, frame, owner_pid, root as u64)
    })
}

/// Asks the window manager to activate the top-level client window whose root-relative geometry
/// equals [frame], through an EWMH `_NET_ACTIVE_WINDOW` request with the current X server time.
///
/// On GNOME's Xwayland, Mutter ignores a Java window's own focus requests (`toFront`,
/// `requestFocus`) once another window, or no window, holds focus. A pager-style activation
/// request with a current timestamp is honoured. The window is found the same way as for a
/// frame-selected screenshot, so zero or several matches fail instead of activating another
/// window.
pub fn activate_window_by_frame(
    display_name: Option<&str>,
    frame: Region,
    owner_pid: Option<u32>,
) -> Result<ResolvedX11Window> {
    if frame.width <= 0 || frame.height <= 0 {
        bail!(
            "X11 window activation requires a positive width and height; got {}x{}",
            frame.width,
            frame.height
        );
    }
    let api = X11Api::load().context("loading libX11.so.6 for window activation")?;
    let what = format!(
        "window frame {},{} {}x{} to activate",
        frame.x, frame.y, frame.width, frame.height
    );
    with_display(&api, display_name, &what, |display| unsafe {
        let root = (api.default_root_window)(display);
        let candidates = collect_frame_candidates(&api, display, root)?;
        let window = select_frame_match(&candidates, frame, owner_pid, root as u64)?;
        let message_type = intern_atom(&api, display, "_NET_ACTIVE_WINDOW").ok_or_else(|| {
            anyhow!("the window manager does not support _NET_ACTIVE_WINDOW (EWMH)")
        })?;
        let timestamp = server_time(&api, display, root)?;
        let mut event = active_window_message(window.xid, message_type as u64, timestamp);
        let status = (api.send_event)(
            display,
            root,
            0,
            SUBSTRUCTURE_REDIRECT_MASK | SUBSTRUCTURE_NOTIFY_MASK,
            event.as_mut_ptr(),
        );
        (api.sync)(display, 0);
        if status == 0 {
            bail!(
                "XSendEvent failed to send _NET_ACTIVE_WINDOW for XID {:#x}",
                window.xid
            );
        }
        Ok(window)
    })
}

/// An EWMH `_NET_ACTIVE_WINDOW` client message for [window], as the raw `XEvent` union
/// (`XClientMessageEvent` on LP64: `type` at long 0, `window` at 4, `message_type` at 5,
/// `format` at 6, `data.l` from 7).
fn active_window_message(window: u64, message_type: u64, timestamp: u64) -> [c_long; XEVENT_LONGS] {
    let mut event = [0 as c_long; XEVENT_LONGS];
    event[0] = CLIENT_MESSAGE as c_long;
    event[4] = window as c_long;
    event[5] = message_type as c_long;
    event[6] = 32;
    event[7] = ACTIVE_WINDOW_SOURCE_PAGER;
    event[8] = timestamp as c_long;
    event[9] = 0;
    event
}

/// The `time` of a raw `XPropertyEvent` (long 6 on LP64), or None for any other event.
fn property_notify_time(event: &[c_long; XEVENT_LONGS]) -> Option<u64> {
    if event[0] as c_int == PROPERTY_NOTIFY {
        Some(event[6] as u64)
    } else {
        None
    }
}

/// The current X server time: change a property on a scratch window and read the timestamp of
/// the `PropertyNotify` event the server sends back (the ICCCM way to get a current timestamp).
unsafe fn server_time(api: &X11Api, display: *mut c_void, root: c_ulong) -> Result<u64> {
    let window = (api.create_simple_window)(display, root, -10, -10, 1, 1, 0, 0, 0);
    if window == 0 {
        bail!("XCreateSimpleWindow failed while reading the X server time");
    }
    let result = (|| {
        (api.select_input)(display, window, PROPERTY_CHANGE_MASK);
        let name = CString::new("_SPECTRE_SERVER_TIME").expect("static atom name");
        let atom = (api.intern_atom)(display, name.as_ptr(), 0);
        if atom == 0 {
            bail!("XInternAtom(_SPECTRE_SERVER_TIME) failed");
        }
        let value = 0u8;
        (api.change_property)(
            display,
            window,
            atom,
            XA_CARDINAL,
            8,
            PROP_MODE_REPLACE,
            &value,
            1,
        );
        let deadline = std::time::Instant::now() + SERVER_TIME_TIMEOUT;
        let mut event = [0 as c_long; XEVENT_LONGS];
        loop {
            (api.sync)(display, 0);
            if (api.check_typed_window_event)(display, window, PROPERTY_NOTIFY, event.as_mut_ptr())
                != 0
            {
                if let Some(time) = property_notify_time(&event) {
                    return Ok(time);
                }
            }
            if std::time::Instant::now() >= deadline {
                bail!("the X server sent no PropertyNotify event to read its time from");
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
    })();
    (api.destroy_window)(display, window);
    (api.sync)(display, 0);
    result
}

fn with_display<T>(
    api: &X11Api,
    display_name: Option<&str>,
    what: &str,
    body: impl FnOnce(*mut c_void) -> Result<T>,
) -> Result<T> {
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
            "Could not open X display {requested:?} while resolving {what}. \
             Named-window capture cannot fall back to the root/desktop."
        );
    }
    let result = body(display);
    unsafe {
        (api.close_display)(display);
    }
    result
}

/// Top-level client windows: every window with `WM_STATE` (set by the window manager), plus
/// each child of the root that has no `WM_STATE` window under it (no window manager, or an
/// override-redirect window). Window-manager frames and toolkit child windows are skipped.
unsafe fn collect_frame_candidates(
    api: &X11Api,
    display: *mut c_void,
    root: c_ulong,
) -> Result<Vec<FrameCandidate>> {
    let wm_state = intern_atom(api, display, "WM_STATE");
    let net_wm_pid = intern_atom(api, display, "_NET_WM_PID");
    let mut out = Vec::new();
    for child in query_children(api, display, root) {
        let mut managed = Vec::new();
        if let Some(atom) = wm_state {
            collect_with_property(api, display, child, atom, &mut managed);
        }
        let clients = if managed.is_empty() {
            vec![child]
        } else {
            managed
        };
        for window in clients {
            if let Some(candidate) = frame_candidate(api, display, root, window, net_wm_pid)? {
                out.push(candidate);
            }
        }
    }
    Ok(out)
}

unsafe fn collect_with_property(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
    atom: c_ulong,
    out: &mut Vec<c_ulong>,
) {
    if has_property(api, display, window, atom) {
        out.push(window);
        return;
    }
    for child in query_children(api, display, window) {
        collect_with_property(api, display, child, atom, out);
    }
}

unsafe fn frame_candidate(
    api: &X11Api,
    display: *mut c_void,
    root: c_ulong,
    window: c_ulong,
    net_wm_pid: Option<c_ulong>,
) -> Result<Option<FrameCandidate>> {
    let Some((width, height, map_state)) = window_geometry(api, display, window)? else {
        return Ok(None);
    };
    if map_state != IS_VIEWABLE {
        return Ok(None);
    }
    let mut x = 0;
    let mut y = 0;
    let mut child = 0;
    if (api.translate_coordinates)(display, window, root, 0, 0, &mut x, &mut y, &mut child) == 0 {
        return Ok(None);
    }
    let pid = net_wm_pid.and_then(|atom| read_cardinal(api, display, window, atom));
    Ok(Some(FrameCandidate {
        xid: window as u64,
        x,
        y,
        width,
        height,
        pid,
    }))
}

unsafe fn query_children(api: &X11Api, display: *mut c_void, window: c_ulong) -> Vec<c_ulong> {
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
    let mut out = Vec::new();
    if status != 0 && !children.is_null() && nchildren > 0 {
        out.extend_from_slice(std::slice::from_raw_parts(children, nchildren as usize));
    }
    if !children.is_null() {
        (api.free)(children.cast());
    }
    out
}

unsafe fn has_property(api: &X11Api, display: *mut c_void, window: c_ulong, atom: c_ulong) -> bool {
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
        0,
        0,
        ANY_PROPERTY_TYPE,
        &mut actual_type,
        &mut actual_format,
        &mut nitems,
        &mut bytes_after,
        &mut prop,
    );
    if !prop.is_null() {
        (api.free)(prop.cast());
    }
    status == SUCCESS && actual_type != 0
}

/// Reads the first item of a 32-bit property. Xlib hands 32-bit items back as C longs.
unsafe fn read_cardinal(
    api: &X11Api,
    display: *mut c_void,
    window: c_ulong,
    atom: c_ulong,
) -> Option<u32> {
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
        1,
        0,
        ANY_PROPERTY_TYPE,
        &mut actual_type,
        &mut actual_format,
        &mut nitems,
        &mut bytes_after,
        &mut prop,
    );
    let value = if status == SUCCESS && !prop.is_null() && actual_format == 32 && nitems >= 1 {
        Some(*(prop as *const c_ulong) as u32)
    } else {
        None
    };
    if !prop.is_null() {
        (api.free)(prop.cast());
    }
    value
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
                    translate_coordinates: load_sym(handle, "XTranslateCoordinates")?,
                    create_simple_window: load_sym(handle, "XCreateSimpleWindow")?,
                    select_input: load_sym(handle, "XSelectInput")?,
                    change_property: load_sym(handle, "XChangeProperty")?,
                    check_typed_window_event: load_sym(handle, "XCheckTypedWindowEvent")?,
                    destroy_window: load_sym(handle, "XDestroyWindow")?,
                    send_event: load_sym(handle, "XSendEvent")?,
                    sync: load_sym(handle, "XSync")?,
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
    fn active_window_message_is_an_ewmh_pager_request_with_the_timestamp() {
        let event = active_window_message(0xa0_0007, 301, 123_456);
        assert_eq!(event[0] as i32, CLIENT_MESSAGE);
        assert_eq!(event[4], 0xa0_0007, "window");
        assert_eq!(event[5], 301, "message_type");
        assert_eq!(event[6] as i32, 32, "format");
        assert_eq!(event[7], ACTIVE_WINDOW_SOURCE_PAGER, "source indication");
        assert_eq!(event[8], 123_456, "timestamp");
        assert_eq!(event[9], 0, "no currently active window");
        assert_eq!(event.len() * std::mem::size_of::<c_long>(), 192);
    }

    #[test]
    fn parses_an_activate_window_command() {
        let json = r#"{"command":"activate_window","display_name":":0",
            "frame":{"x":200,"y":187,"width":420,"height":273},"owner_pid":42}"#;
        match serde_json::from_str::<crate::protocol::Command>(json).expect("activate command") {
            crate::protocol::Command::ActivateWindow {
                display_name,
                frame,
                owner_pid,
            } => {
                assert_eq!(display_name.as_deref(), Some(":0"));
                assert_eq!(
                    frame,
                    Region {
                        x: 200,
                        y: 187,
                        width: 420,
                        height: 273
                    }
                );
                assert_eq!(owner_pid, Some(42));
            }
            other => panic!("unexpected command {other:?}"),
        }
    }

    #[test]
    fn reads_the_server_time_only_from_a_property_notify_event() {
        let mut event = [0 as c_long; XEVENT_LONGS];
        event[0] = PROPERTY_NOTIFY as c_long;
        event[6] = 987_654;
        assert_eq!(property_notify_time(&event), Some(987_654));

        event[0] = CLIENT_MESSAGE as c_long;
        assert_eq!(property_notify_time(&event), None);
    }

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

    fn candidate(
        xid: u64,
        x: i32,
        y: i32,
        width: u32,
        height: u32,
        pid: Option<u32>,
    ) -> FrameCandidate {
        FrameCandidate {
            xid,
            x,
            y,
            width,
            height,
            pid,
        }
    }

    fn frame(x: i32, y: i32, width: i32, height: i32) -> Region {
        Region {
            x,
            y,
            width,
            height,
        }
    }

    #[test]
    fn frame_selector_picks_the_only_window_with_that_geometry() {
        let candidates = [
            candidate(0x100, 0, 0, 800, 600, Some(42)),
            candidate(0x200, 100, 120, 300, 200, Some(42)),
        ];
        let resolved =
            select_frame_match(&candidates, frame(100, 120, 300, 200), Some(42), 1).unwrap();
        assert_eq!(
            resolved,
            ResolvedX11Window {
                xid: 0x200,
                width: 300,
                height: 200
            }
        );
    }

    #[test]
    fn frame_selector_tolerates_one_device_pixel_of_rounding() {
        let candidates = [candidate(0x200, 101, 119, 299, 201, None)];
        let resolved =
            select_frame_match(&candidates, frame(100, 120, 300, 200), Some(42), 1).unwrap();
        assert_eq!(resolved.xid, 0x200);
    }

    #[test]
    fn frame_selector_skips_windows_of_another_process() {
        let candidates = [
            candidate(0x200, 100, 120, 300, 200, Some(7)),
            candidate(0x300, 100, 120, 300, 200, Some(42)),
        ];
        let resolved =
            select_frame_match(&candidates, frame(100, 120, 300, 200), Some(42), 1).unwrap();
        assert_eq!(resolved.xid, 0x300);
    }

    #[test]
    fn frame_selector_fails_closed_when_nothing_matches() {
        let candidates = [candidate(0x200, 102, 120, 300, 200, Some(42))];
        let err =
            select_frame_match(&candidates, frame(100, 120, 300, 200), Some(42), 1).unwrap_err();
        let message = format!("{err:#}");
        assert!(
            message.contains("was not found"),
            "unexpected error: {message}"
        );
        assert!(
            message.contains("root") || message.contains("desktop"),
            "unexpected error: {message}"
        );
    }

    #[test]
    fn frame_selector_fails_when_two_windows_share_the_geometry() {
        let candidates = [
            candidate(0x200, 100, 120, 300, 200, Some(42)),
            candidate(0x300, 100, 120, 300, 200, None),
        ];
        let err =
            select_frame_match(&candidates, frame(100, 120, 300, 200), Some(42), 1).unwrap_err();
        let message = format!("{err:#}");
        assert!(message.contains("2 windows"), "unexpected error: {message}");
    }

    #[test]
    fn frame_selector_refuses_the_root_window_and_xid_zero() {
        let root = [candidate(1, 0, 0, 300, 200, None)];
        let err = select_frame_match(&root, frame(0, 0, 300, 200), None, 1).unwrap_err();
        assert!(
            format!("{err:#}").contains("root"),
            "unexpected error: {err:#}"
        );
        let zero = [candidate(0, 0, 0, 300, 200, None)];
        let err = select_frame_match(&zero, frame(0, 0, 300, 200), None, 1).unwrap_err();
        assert!(
            format!("{err:#}").contains("root"),
            "unexpected error: {err:#}"
        );
    }

    #[test]
    fn empty_frame_fails_closed_without_opening_x() {
        let err = find_window_by_frame(None, frame(0, 0, 0, 200), None).unwrap_err();
        assert!(
            format!("{err:#}").contains("positive"),
            "unexpected error: {err:#}"
        );
    }

    fn window_command(json: &str) -> crate::protocol::ScreenshotCommand {
        serde_json::from_str(json).expect("screenshot command")
    }

    #[test]
    fn window_frame_selects_the_frame_selector() {
        let command = window_command(
            r#"{"backend":"x11","target":"window","cursor_mode":"hidden",
                "region":{"x":0,"y":0,"width":300,"height":200},
                "window_frame":{"x":-10,"y":20,"width":600,"height":400},
                "window_owner_pid":4242,"output":"/tmp/out.png"}"#,
        );
        match x11_window_selector(&command).unwrap() {
            X11WindowSelector::Frame { frame, owner_pid } => {
                assert_eq!(
                    (frame.x, frame.y, frame.width, frame.height),
                    (-10, 20, 600, 400)
                );
                assert_eq!(owner_pid, Some(4242));
            }
            other => panic!("expected a frame selector, got {other:?}"),
        }
    }

    #[test]
    fn window_title_keeps_the_title_selector() {
        let command = window_command(
            r#"{"backend":"x11","target":"window","cursor_mode":"hidden",
                "region":{"x":0,"y":0,"width":300,"height":200},
                "window_title":"Spectre","output":"/tmp/out.png"}"#,
        );
        match x11_window_selector(&command).unwrap() {
            X11WindowSelector::Title(title) => assert_eq!(title, "Spectre"),
            other => panic!("expected a title selector, got {other:?}"),
        }
    }

    #[test]
    fn window_title_and_frame_are_mutually_exclusive() {
        let command = window_command(
            r#"{"backend":"x11","target":"window","cursor_mode":"hidden",
                "region":{"x":0,"y":0,"width":300,"height":200},
                "window_title":"Spectre",
                "window_frame":{"x":0,"y":0,"width":300,"height":200},
                "output":"/tmp/out.png"}"#,
        );
        let err = x11_window_selector(&command).unwrap_err();
        assert!(
            format!("{err:#}").contains("mutually exclusive"),
            "unexpected error: {err:#}"
        );
    }

    #[test]
    fn window_target_needs_a_title_or_a_frame() {
        let command = window_command(
            r#"{"backend":"x11","target":"window","cursor_mode":"hidden",
                "region":{"x":0,"y":0,"width":300,"height":200},"output":"/tmp/out.png"}"#,
        );
        let err = x11_window_selector(&command).unwrap_err();
        assert!(
            format!("{err:#}").contains("window_title or window_frame"),
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
