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
        ("handle_token", variant(create_token.as_str())),
        ("session_handle_token", variant(next_token("session").as_str())),
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
        ("handle_token", variant(select_token.as_str())),
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
    let start_options = make_options([("handle_token", variant(start_token.as_str()))]);
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
    let stream = parse_first_stream(&start_response.results).ok_or_else(|| {
        anyhow!(
            "Start Response did not include a usable stream — portal returned no node id. \
             Results: {:?}",
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
    let unique = conn
        .unique_name()
        .ok_or_else(|| anyhow!("session bus did not assign us a unique name"))?;
    Ok(unique.trim_start_matches(':').replace('.', "_"))
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

/// Pull the first stream out of the `Start.Response.streams` payload. The streams field is
/// `a(ua{sv})` — array of (node_id, properties). Each property dict has `position` (ii) and
/// `size` (ii). Spectre records one stream at a time, so we take the first.
fn parse_first_stream(results: &PropMap) -> Option<StreamMetadata> {
    let streams_variant = results.get("streams")?;
    // Streams field comes as Variant<Vec<(u32, PropMap)>>. dbus-rs exposes this as a generic
    // RefArg; we walk through `as_iter` to extract the structure.
    let mut outer = streams_variant.0.as_iter()?;
    let first = outer.next()?;
    let mut struct_fields = first.as_iter()?;
    let node_id = struct_fields.next()?.as_u64()? as u32;
    let mut props_iter = struct_fields.next()?.as_iter()?;
    // The dict-side `PropMap` for the stream's own a{sv}. dbus-rs serialises this as a
    // sequence of alternating keys and variants when using `as_iter()`.
    let mut position = (0i32, 0i32);
    let mut size = (0u32, 0u32);
    while let (Some(k), Some(v)) = (props_iter.next(), props_iter.next()) {
        let key = k.as_str()?;
        let mut tup = v.as_iter()?;
        match key {
            "position" => {
                let x = tup.next()?.as_i64()? as i32;
                let y = tup.next()?.as_i64()? as i32;
                position = (x, y);
            }
            "size" => {
                let w = tup.next()?.as_u64()? as u32;
                let h = tup.next()?.as_u64()? as u32;
                size = (w, h);
            }
            _ => {}
        }
    }
    Some(StreamMetadata {
        node_id,
        position,
        size,
    })
}
