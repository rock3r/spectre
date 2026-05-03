//! `xdg-desktop-portal` ScreenCast client.
//!
//! Drives the four-call portal flow synchronously:
//!  1. `CreateSession` → wait for `Response` signal → extract session_handle
//!  2. `SelectSources` → wait for Response → confirm response_code 0
//!  3. `Start` → wait for Response → extract streams (node_id, position, size)
//!  4. `OpenPipeWireRemote` → returns the PipeWire FD directly (no Request/Response pattern)
//!
//! The first three calls follow the portal's documented async pattern: each method returns a
//! Request object path immediately, and the actual result lands later as a `Response` signal
//! on that path. We pre-compute the Request path from the calling connection's unique name
//! and a per-call `handle_token` (so we can subscribe to the signal _before_ making the call,
//! avoiding the race where the portal could deliver the Response between the method return
//! and our handler registration). `OpenPipeWireRemote` is special: it's a "private fd" call
//! that doesn't go through the Request mechanism — the response carries the FD inline.
//!
//! Why dbus-rs (sync) and not zbus (async): zbus's recent versions pull transitive deps
//! that bumped to edition2024 (Rust 1.85+), but our toolchain floor is 1.75 (Ubuntu 22.04
//! jammy-backports). dbus-rs has been stable since 2018 with a tiny, predictable dep tree.
//! See the Cargo.toml comment for the longer story.

use crate::protocol::{CursorMode, SourceType};
use anyhow::{anyhow, bail, Context, Result};
use dbus::arg::{OwnedFd, PropMap, RefArg, Variant};
use dbus::blocking::Connection;
use dbus::Path as DBusPath;
use std::collections::HashMap;
use std::os::fd::IntoRawFd;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The compositor's portal service: bus name + entry object path. Constant across distros
/// (`xdg-desktop-portal-gnome`, `xdg-desktop-portal-kde`, `xdg-desktop-portal-wlr` all
/// register under the same well-known names per the freedesktop spec).
const PORTAL_BUS: &str = "org.freedesktop.portal.Desktop";
const PORTAL_PATH: &str = "/org/freedesktop/portal/desktop";
const PORTAL_REQUEST_PATH_BASE: &str = "/org/freedesktop/portal/desktop/request";
const REQUEST_RESPONSE_INTERFACE: &str = "org.freedesktop.portal.Request";
const SCREEN_CAST_INTERFACE: &str = "org.freedesktop.portal.ScreenCast";

/// Default per-call timeout for both the synchronous D-Bus method and the asynchronous
/// `Response` signal. 60s covers the worst case where the user is reading a permission dialog
/// before clicking Share. Configurable via [`open_screen_cast_session`].
pub const DEFAULT_RESPONSE_TIMEOUT: Duration = Duration::from_secs(60);

/// One stream returned by `Start.Response.streams[]`. Spectre uses the first (and on
/// `monitor` source type, only) stream.
#[derive(Debug, Clone)]
pub struct StreamMetadata {
    pub node_id: u32,
    pub position: (i32, i32),
    pub size: (u32, u32),
}

/// Active screen-cast session. Owns the D-Bus connection (closing it tears the session down)
/// and the PipeWire FD (a Unix FD authorising a client to read the granted node).
pub struct ScreenCastSession {
    pub session_handle: String,
    pub stream: StreamMetadata,
    /// The PipeWire FD as a raw int. Marked CLOEXEC by the kernel; the caller must clear
    /// the flag with `fcntl(F_SETFD, ...)` before passing it to a subprocess via inheritance.
    pub pipewire_fd: i32,
    /// D-Bus connection that owns the portal session. Held alive by this struct so the
    /// compositor doesn't drop the screen-cast on us mid-recording. Dropping the struct
    /// closes the connection (RAII) which releases the session.
    _connection: Connection,
}

/// Run the full portal handshake and return an active [`ScreenCastSession`]. Blocks until
/// either the four-call flow completes (returning `Ok`) or any step fails / times out.
///
/// First call within a login session pops the user's "share your screen" dialog. Subsequent
/// calls within the same session reuse the granted permission silently if `persist_mode`
/// is `transient` or `persistent` (we use `transient` by default — scoped to the running
/// helper instance, no cross-login storage).
pub fn open_screen_cast_session(
    source_types: &[SourceType],
    cursor_mode: CursorMode,
    timeout: Duration,
) -> Result<ScreenCastSession> {
    let conn = Connection::new_session().context("opening session bus")?;
    let sender = sender_token(&conn).context("computing sender token")?;
    let counter = AtomicUsize::new(0);
    let next_token =
        |kind: &str| format!("spectre_{}_{}", kind, counter.fetch_add(1, Ordering::SeqCst));

    // 1. CreateSession
    let create_token = next_token("create");
    let create_options = make_options([
        ("handle_token", variant(create_token.clone())),
        ("session_handle_token", variant(next_token("session"))),
    ]);
    let create_response = call_with_response(
        &conn,
        &sender,
        &create_token,
        SCREEN_CAST_INTERFACE,
        "CreateSession",
        (create_options,),
        timeout,
    )?;
    if create_response.response_code != 0 {
        bail!(
            "CreateSession rejected (response code {}). Common causes: PipeWire daemon not \
             reachable, xdg-desktop-portal-gnome service crashed, user disabled screen-cast \
             permission globally.",
            create_response.response_code
        );
    }
    let session_handle = create_response
        .results
        .get("session_handle")
        .and_then(|v| v.0.as_str())
        .map(str::to_string)
        .ok_or_else(|| {
            anyhow!(
                "CreateSession Response code 0 but did not include session_handle: {:?}",
                create_response.results
            )
        })?;
    let session_path = DBusPath::from(session_handle.clone());

    // 2. SelectSources
    let select_token = next_token("select");
    let select_options = make_options([
        ("handle_token", variant(select_token.clone())),
        ("types", variant(source_types_to_bitmask(source_types))),
        ("multiple", variant(false)),
        ("cursor_mode", variant(cursor_mode_flag(cursor_mode))),
        ("persist_mode", variant(1u32)), // TRANSIENT
    ]);
    let select_response = call_with_response(
        &conn,
        &sender,
        &select_token,
        SCREEN_CAST_INTERFACE,
        "SelectSources",
        (session_path.clone(), select_options),
        timeout,
    )?;
    if select_response.response_code != 0 {
        bail!(
            "SelectSources rejected (response code {}). Sources requested: {:?}; the portal \
             might lack support for those.",
            select_response.response_code,
            source_types
        );
    }

    // 3. Start
    let start_token = next_token("start");
    let start_options = make_options([("handle_token", variant(start_token.clone()))]);
    let start_response = call_with_response(
        &conn,
        &sender,
        &start_token,
        SCREEN_CAST_INTERFACE,
        "Start",
        (session_path.clone(), "".to_string(), start_options),
        timeout,
    )?;
    if start_response.response_code != 0 {
        bail!(
            "Start rejected (response code {}). User likely cancelled the screen-cast \
             permission dialog.",
            start_response.response_code
        );
    }
    let stream = parse_first_stream(&start_response.results).with_context(|| {
        format!(
            "parsing Start Response streams field. Raw results: {:?}",
            start_response.results
        )
    })?;

    // 4. OpenPipeWireRemote — synchronous, no Request/Response pattern. Returns the FD inline.
    let open_options: PropMap = HashMap::new();
    let proxy = conn.with_proxy(PORTAL_BUS, PORTAL_PATH, timeout);
    let (fd,): (OwnedFd,) = proxy
        .method_call(
            SCREEN_CAST_INTERFACE,
            "OpenPipeWireRemote",
            (session_path.clone(), open_options),
        )
        .context("OpenPipeWireRemote method call")?;
    let raw_fd = fd.into_raw_fd();

    Ok(ScreenCastSession {
        session_handle,
        stream,
        pipewire_fd: raw_fd,
        _connection: conn,
    })
}

/// One Response payload, captured by [`call_with_response`] from the matching `Request.Response`
/// signal.
struct ResponsePayload {
    response_code: u32,
    results: PropMap,
}

/// Make a portal method call that follows the Request/Response pattern: subscribe to the
/// `Response` signal on the predicted Request object path, then make the call, then drain
/// signals on the connection until the Response arrives or [`timeout`] elapses.
fn call_with_response<A: dbus::arg::AppendAll>(
    conn: &Connection,
    sender: &str,
    handle_token: &str,
    interface: &str,
    method: &str,
    args: A,
    timeout: Duration,
) -> Result<ResponsePayload> {
    let request_path = format!("{PORTAL_REQUEST_PATH_BASE}/{sender}/{handle_token}");

    // Subscribe BEFORE calling — the portal can deliver the Response before our method
    // call returns the request path, and we'd miss the signal otherwise.
    let captured: Arc<Mutex<Option<ResponsePayload>>> = Arc::new(Mutex::new(None));
    let captured_clone = Arc::clone(&captured);
    let mut match_rule = dbus::message::MatchRule::new_signal(
        REQUEST_RESPONSE_INTERFACE,
        "Response",
    );
    match_rule.path = Some(request_path.clone().into());
    let token = conn
        .add_match(match_rule, move |(): (), _conn, msg| {
            // Response signal carries (u response, a{sv} results).
            let mut iter = msg.iter_init();
            let code: u32 = match iter.read() {
                Ok(c) => c,
                Err(e) => {
                    eprintln!("[portal] Response signal: failed to read response code: {e}");
                    return true; // keep handler registered
                }
            };
            let results: PropMap = match iter.read() {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("[portal] Response signal: failed to read results dict: {e}");
                    return true;
                }
            };
            *captured_clone.lock().unwrap() = Some(ResponsePayload {
                response_code: code,
                results,
            });
            true
        })
        .context("registering Response signal handler")?;

    // Make the call. Return value is the Request path the portal would dispatch the
    // Response on; we sanity-check it matches what we predicted.
    let proxy = conn.with_proxy(PORTAL_BUS, PORTAL_PATH, timeout);
    let (returned_path,): (DBusPath,) = proxy
        .method_call(interface, method, args)
        .with_context(|| format!("{interface}.{method} method call"))?;
    if returned_path.as_ref() != request_path {
        let _ = conn.remove_match(token);
        bail!(
            "Portal returned a Request path ({}) that doesn't match the path computed from \
             sender={} / handle_token={} ({}). The handle_token contract is broken.",
            returned_path,
            sender,
            handle_token,
            request_path,
        );
    }

    // Drain the connection until the Response arrives or we time out.
    let deadline = std::time::Instant::now() + timeout;
    let result = loop {
        if let Some(payload) = captured.lock().unwrap().take() {
            break Ok(payload);
        }
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            break Err(anyhow!(
                "Timed out after {timeout:?} waiting for Response signal on {request_path}. \
                 Common causes: user dismissed the dialog without responding; portal service \
                 crashed; D-Bus message bus stalled."
            ));
        }
        // process() drains pending messages, dispatches signals, returns when the bus is
        // quiet. We give it a slice of the remaining budget so the loop check happens
        // periodically — doesn't perfectly match the deadline but bounds the wait at
        // 250ms granularity.
        let slice = remaining.min(Duration::from_millis(250));
        if let Err(e) = conn.process(slice) {
            let _ = conn.remove_match(token);
            return Err(anyhow::Error::from(e).context("D-Bus message processing"));
        }
    };

    let _ = conn.remove_match(token);
    result
}

/// Compute the sender token used in the Request object path. dbus-rs exposes the
/// connection's unique bus name in `:X.Y` form; the portal computes the path from that name
/// with `:` and `.` replaced by `_`.
fn sender_token(conn: &Connection) -> Result<String> {
    let unique = conn.unique_name();
    let s: &str = unique.as_ref();
    if s.is_empty() {
        return Err(anyhow!("session bus did not assign us a unique name"));
    }
    Ok(s.trim_start_matches(':').replace('.', "_"))
}

/// Build a `PropMap` (a{sv}) from a fixed list of (key, variant) pairs. Saves repeating the
/// `Variant(Box::new(...))` boilerplate at every call site.
fn make_options<const N: usize>(
    entries: [(&str, Variant<Box<dyn RefArg>>); N],
) -> PropMap {
    entries
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect()
}

/// Box a value into a [`Variant`] for use in [`make_options`]. dbus-rs's `Variant<Box<dyn
/// RefArg>>` is the standard a{sv} value-side wrapper; this function gets the boxing right
/// in one place.
fn variant<T: RefArg + 'static>(value: T) -> Variant<Box<dyn RefArg>> {
    Variant(Box::new(value))
}

fn source_types_to_bitmask(types: &[SourceType]) -> u32 {
    types
        .iter()
        .map(|t| match t {
            SourceType::Monitor => 1,
            SourceType::Window => 2,
            SourceType::Virtual => 4,
        })
        .fold(0u32, |acc, b| acc | b)
}

fn cursor_mode_flag(mode: CursorMode) -> u32 {
    match mode {
        CursorMode::Hidden => 1,
        CursorMode::Embedded => 2,
        CursorMode::Metadata => 4,
    }
}

/// Pull the first stream out of the `Start.Response.streams` payload.
///
/// The portal returns this field with signature `a(ua{sv})` — an array of `(node_id,
/// properties)` structs. The `node_id` is the PipeWire node id (NOT the same as the inner
/// dict's `id` string, which is the persistent restore key). The properties dict has:
///
/// - `position` (variant, signature `(ii)`) — top-left of the captured region in compositor
///   coordinates.
/// - `size` (variant, signature `(ii)`) — pixel dimensions of the captured region.
/// - `source_type` (variant `u`) — bitmask: 1=monitor, 2=window, 4=virtual.
/// - `id` (variant `s`) — restore-id (the restore_token-paired identifier), NOT the node id.
///
/// dbus-rs exposes the whole tree as untyped `&dyn RefArg`. The non-obvious traversal rule:
/// **variants need an extra unwrap.** `Variant::as_iter()` yields exactly ONE inner item; you
/// have to call `as_iter()` again on that item to walk into a struct/array. Values inside an
/// `a{sv}` are all variants, so anything past a dict value needs this extra step.
///
/// `position` is required for `source_type = Monitor` — the AWT-region → stream-relative
/// translation in [`crate::recorder::run`] subtracts it from the JVM-supplied rectangle, and
/// defaulting to `(0,0)` when the granted monitor is at e.g. `(1920, 0)` produces silently
/// wrong coordinates and a recording of the wrong area on multi-monitor setups. For
/// `source_type = Window` (#85) the portal genuinely omits `position` because the stream IS
/// the window's surface — there is nothing to translate against. Mutter on Ubuntu 24.04 /
/// GNOME 46 was observed returning `{size, id, source_type}` with no `position` key for
/// window streams; xdg-desktop-portal-gnome's source confirms this is intentional. The parser
/// therefore defaults `position` to `(0, 0)` only when `source_type = Window`; monitor
/// streams still bail loudly on missing position. `size` is required regardless of source
/// type because the encoder's argv builder ([`crate::gst::build_pipewire_argv`]) needs it for
/// bounds-checking and pipeline construction; defaulting it would surface as a confusing
/// `"stream_size must have positive dimensions"` instead of a clear "portal misbehaved" error.
fn parse_first_stream(results: &PropMap) -> Result<StreamMetadata> {
    let streams_variant = results
        .get("streams")
        .context("Start Response missing 'streams' key")?;
    let mut outer = streams_variant
        .0
        .as_iter()
        .context("'streams' value is not iterable (expected a(ua{sv}))")?;
    let first = outer
        .next()
        .context("'streams' array is empty — portal returned 0 streams")?;
    let mut struct_fields = first
        .as_iter()
        .context("first stream entry not iterable (expected (ua{sv}) struct)")?;
    let node_id_arg = struct_fields
        .next()
        .context("first stream struct has no fields (expected u32 node_id then a{sv})")?;
    let node_id = node_id_arg
        .as_u64()
        .with_context(|| {
            format!(
                "stream node_id field is not a u32 — got signature '{}', value {:?}",
                node_id_arg.signature(),
                node_id_arg
            )
        })? as u32;

    // Properties dict. Both `position` and `size` are required for correct downstream
    // behaviour — see the function-level doc-comment for the bail-don't-default reasoning.
    let props_arg = struct_fields
        .next()
        .context("stream struct missing properties dict (expected (ua{sv}), got just u)")?;
    let mut props_iter = props_arg
        .as_iter()
        .context("stream properties value not iterable (expected a{sv})")?;
    let mut position: Option<(i32, i32)> = None;
    let mut size: Option<(u32, u32)> = None;
    let mut source_type: Option<u32> = None;
    while let (Some(k), Some(v)) = (props_iter.next(), props_iter.next()) {
        let Some(key) = k.as_str() else { continue };
        match key {
            "position" => {
                let pair = parse_int_pair(v).with_context(|| {
                    format!(
                        "stream property 'position' had unexpected shape (signature '{}'): {:?}",
                        v.signature(),
                        v
                    )
                })?;
                position = Some((pair.0 as i32, pair.1 as i32));
            }
            "size" => {
                let pair = parse_int_pair(v).with_context(|| {
                    format!(
                        "stream property 'size' had unexpected shape (signature '{}'): {:?}",
                        v.signature(),
                        v
                    )
                })?;
                // Size is signed in the wire format (`(ii)`) but the portal spec guarantees
                // non-negative values. Reject negatives explicitly rather than silently
                // clamping — a negative size would be a portal-spec violation worth seeing.
                if pair.0 < 0 || pair.1 < 0 {
                    bail!(
                        "stream 'size' contained negative dimension(s): ({}, {}). Portal spec \
                         requires non-negative.",
                        pair.0,
                        pair.1,
                    );
                }
                size = Some((pair.0 as u32, pair.1 as u32));
            }
            "source_type" => {
                // Variants need the same one-level unwrap as positon/size; reuse parse_int_pair's
                // inner machinery via a tiny ad-hoc walk because this is a scalar, not a pair.
                if let Some(mut variant_iter) = v.as_iter() {
                    if let Some(inner) = variant_iter.next() {
                        if let Some(n) = inner.as_u64() {
                            source_type = Some(n as u32);
                        }
                    }
                }
                if source_type.is_none() {
                    if let Some(n) = v.as_u64() {
                        source_type = Some(n as u32);
                    }
                }
            }
            _ => {}
        }
    }

    // Window source-type streams (bit 2) genuinely omit position — the portal spec lets the
    // compositor leave it out because the stream IS the picked window's surface and there is
    // no monitor-relative origin to report. Monitor streams (bit 1) still require position
    // because the AWT-region translation depends on it on multi-monitor setups.
    const SOURCE_TYPE_WINDOW: u32 = 2;
    let position = match position {
        Some(p) => p,
        None if matches!(source_type, Some(SOURCE_TYPE_WINDOW)) => (0, 0),
        None => {
            return Err(anyhow!(
                "stream properties dict did not include 'position' — required for AWT-region → \
                 stream-relative coordinate translation on monitor streams (source_type={:?}).",
                source_type
            ));
        }
    };
    let size = size.ok_or_else(|| {
        anyhow!(
            "stream properties dict did not include 'size' — required for the encoder's \
             videocrop pass and bounds-checking."
        )
    })?;
    if size.0 == 0 || size.1 == 0 {
        bail!(
            "stream 'size' was ({}, {}); the encoder's videocrop pass requires positive \
             dimensions and will reject zero. The compositor returned an empty stream.",
            size.0,
            size.1,
        );
    }

    Ok(StreamMetadata {
        node_id,
        position,
        size,
    })
}

/// Read a `(ii)` pair from a variant-wrapped value. Handles the two-level unwrap required
/// for values inside `a{sv}`: first `as_iter()` peels the variant (yielding one inner item),
/// then a second `as_iter()` walks the struct fields.
///
/// Falls back to a one-level walk for the (theoretical) case where the value isn't variant-
/// wrapped. Returns `None` only if neither shape produces two integers.
fn parse_int_pair(arg: &dyn RefArg) -> Option<(i64, i64)> {
    // Two-level unwrap: variant → inner struct → fields.
    if let Some(mut variant_iter) = arg.as_iter() {
        if let Some(inner) = variant_iter.next() {
            if let Some(mut field_iter) = inner.as_iter() {
                if let (Some(a), Some(b)) = (field_iter.next(), field_iter.next()) {
                    if let (Some(av), Some(bv)) = (a.as_i64(), b.as_i64()) {
                        return Some((av, bv));
                    }
                }
            }
            // Inner already looks like an int — maybe arg wasn't variant-wrapped after all
            // and `variant_iter` is actually iterating fields directly.
            if let Some(av) = inner.as_i64() {
                if let Some(b) = variant_iter.next() {
                    if let Some(bv) = b.as_i64() {
                        return Some((av, bv));
                    }
                }
            }
        }
    }
    None
}
