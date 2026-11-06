//! Window streams through GNOME Mutter's own `org.gnome.Mutter.ScreenCast` API.
//!
//! The portal's window source asks the user to pick a window, and its restore token cannot match
//! a new Java window (GNOME gives a window without a `.desktop` match a new `window:<n>` app id),
//! so it shows a dialog for every window. Mutter's API shows no dialog: Mutter only checks that the
//! caller owns the session. `RecordWindow` renders that one window into its own buffer, so a
//! frame is a real window capture: other windows on top of it do not show, and the stream follows
//! the window when it moves.
//!
//! Mutter selects the window by its internal id, and only `xdg-desktop-portal-gnome` may list
//! those ids. Without an id, `RecordWindow` records the window that has keyboard focus. The JVM
//! therefore focuses the window, binds it here, checks the first frame, and restores focus. A
//! binding lasts as long as its Mutter session, so later frames need no focus change.

use crate::portal::{make_options, variant};
use crate::protocol::CursorMode;
use anyhow::{anyhow, bail, Context, Result};
use dbus::arg::{PropMap, RefArg};
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;
use dbus::blocking::Connection;
use dbus::Path as DBusPath;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const MUTTER_SCREEN_CAST_BUS: &str = "org.gnome.Mutter.ScreenCast";
const MUTTER_SCREEN_CAST_PATH: &str = "/org/gnome/Mutter/ScreenCast";
const SESSION_INTERFACE: &str = "org.gnome.Mutter.ScreenCast.Session";
const STREAM_INTERFACE: &str = "org.gnome.Mutter.ScreenCast.Stream";
const CALL_TIMEOUT: Duration = Duration::from_secs(10);
const STREAM_ADDED_TIMEOUT: Duration = Duration::from_secs(10);
/// Mutter's `cursor-mode`: 0 hidden, 1 embedded in the frames, 2 as PipeWire metadata.
fn mutter_cursor_mode(mode: CursorMode) -> u32 {
    match mode {
        CursorMode::Hidden => 0,
        CursorMode::Embedded => 1,
        CursorMode::Metadata => 2,
    }
}

/// Error kind for a desktop without Mutter's ScreenCast API (KDE, wlroots, ...). The JVM turns it
/// into a fail-fast error: Spectre does not fall back to the portal's per-window dialog.
pub const ERROR_KIND_MUTTER_UNAVAILABLE: &str = "MutterUnavailable";
/// Error kind for a key with no binding, for example after the session helper restarted.
pub const ERROR_KIND_WINDOW_NOT_BOUND: &str = "WindowNotBound";

/// A typed failure the session maps to a protocol error kind.
#[derive(Debug)]
pub struct WindowStreamError {
    pub kind: &'static str,
    pub message: String,
}

impl std::fmt::Display for WindowStreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for WindowStreamError {}

/// The protocol error kind for `err`: a [`WindowStreamError`]'s kind, or `Helper`.
pub fn error_kind(err: &anyhow::Error) -> &'static str {
    err.chain()
        .find_map(|cause| cause.downcast_ref::<WindowStreamError>())
        .map(|typed| typed.kind)
        .unwrap_or("Helper")
}

fn typed(kind: &'static str, message: String) -> anyhow::Error {
    anyhow::Error::new(WindowStreamError { kind, message })
}

/// True when `message` (a D-Bus error) says nobody owns Mutter's ScreenCast name.
pub fn is_service_missing(message: &str) -> bool {
    message.contains("ServiceUnknown")
        || message.contains("NameHasNoOwner")
        || message.contains("was not provided by any .service files")
}

/// One bound window: its Mutter session and the PipeWire node of its stream.
#[derive(Debug, Clone)]
pub struct BoundWindow {
    session_path: String,
    pub node_id: u32,
    pub logical_size: (i32, i32),
    pub owner: u64,
}

/// Mutter window streams of this session helper, keyed by the JVM's window key.
pub struct MutterWindowStreams {
    connection: Option<Connection>,
    windows: HashMap<String, BoundWindow>,
}

impl Default for MutterWindowStreams {
    fn default() -> Self {
        Self::new()
    }
}

impl MutterWindowStreams {
    pub fn new() -> Self {
        Self {
            connection: None,
            windows: HashMap::new(),
        }
    }

    /// The D-Bus connection that owns every Mutter session. Mutter stops a session when its
    /// owner's connection closes, so the connection lives as long as the helper.
    fn connection(&mut self) -> Result<&Connection> {
        if self.connection.is_none() {
            self.connection =
                Some(Connection::new_session().context("opening the session bus for Mutter")?);
        }
        Ok(self.connection.as_ref().expect("connection set above"))
    }

    pub fn get(&self, key: &str) -> Result<BoundWindow> {
        self.windows.get(key).cloned().ok_or_else(|| {
            typed(
                ERROR_KIND_WINDOW_NOT_BOUND,
                format!("no Mutter window stream is bound under '{key}'"),
            )
        })
    }

    /// Bind a new Mutter window stream to the window that has keyboard focus now.
    pub fn bind_focused(
        &mut self,
        key: &str,
        owner: u64,
        cursor_mode: CursorMode,
    ) -> Result<BoundWindow> {
        self.unbind(key);
        let conn = self.connection()?;
        let bound = bind_focused_window(conn, owner, cursor_mode)?;
        self.windows.insert(key.to_string(), bound.clone());
        Ok(bound)
    }

    /// Stop the stream bound under `key`. Returns false when nothing was bound.
    pub fn unbind(&mut self, key: &str) -> bool {
        let Some(bound) = self.windows.remove(key) else {
            return false;
        };
        if let Some(conn) = self.connection.as_ref() {
            stop_session(conn, &bound.session_path);
        }
        true
    }

    /// Stop every stream bound by the client `owner`, for example when its JVM disconnected.
    pub fn unbind_owner(&mut self, owner: u64) {
        let keys = keys_owned_by(&self.windows, owner);
        for key in keys {
            self.unbind(&key);
        }
    }
}

fn keys_owned_by(windows: &HashMap<String, BoundWindow>, owner: u64) -> Vec<String> {
    windows
        .iter()
        .filter(|(_, bound)| bound.owner == owner)
        .map(|(key, _)| key.clone())
        .collect()
}

fn stop_session(conn: &Connection, session_path: &str) {
    let proxy = conn.with_proxy(MUTTER_SCREEN_CAST_BUS, session_path, CALL_TIMEOUT);
    if let Err(e) = proxy.method_call::<(), _, _, _>(SESSION_INTERFACE, "Stop", ()) {
        eprintln!("spectre-wayland-helper: stopping Mutter session {session_path}: {e}");
    }
}

fn bind_focused_window(
    conn: &Connection,
    owner: u64,
    cursor_mode: CursorMode,
) -> Result<BoundWindow> {
    let manager = conn.with_proxy(
        MUTTER_SCREEN_CAST_BUS,
        MUTTER_SCREEN_CAST_PATH,
        CALL_TIMEOUT,
    );
    let empty: PropMap = HashMap::new();
    let (session_path,): (DBusPath,) = manager
        .method_call(MUTTER_SCREEN_CAST_BUS, "CreateSession", (empty,))
        .map_err(|e| {
            let message = e.to_string();
            if is_service_missing(&message) {
                typed(
                    ERROR_KIND_MUTTER_UNAVAILABLE,
                    format!(
                        "this Wayland desktop has no GNOME Mutter ScreenCast API ({message}). \
                         Spectre captures windows on Wayland only through it, and does not \
                         fall back to the portal's per-window Share dialog."
                    ),
                )
            } else {
                anyhow!("org.gnome.Mutter.ScreenCast.CreateSession: {message}")
            }
        })?;
    let session_path = session_path.to_string();
    match record_and_start(conn, &session_path, owner, cursor_mode) {
        Ok(bound) => Ok(bound),
        Err(e) => {
            stop_session(conn, &session_path);
            Err(e)
        }
    }
}

fn record_and_start(
    conn: &Connection,
    session_path: &str,
    owner: u64,
    cursor_mode: CursorMode,
) -> Result<BoundWindow> {
    let session = conn.with_proxy(MUTTER_SCREEN_CAST_BUS, session_path, CALL_TIMEOUT);
    // No "window-id": Mutter records the window that has keyboard focus.
    let properties = make_options([("cursor-mode", variant(mutter_cursor_mode(cursor_mode)))]);
    let (stream_path,): (DBusPath,) = session
        .method_call(SESSION_INTERFACE, "RecordWindow", (properties,))
        .map_err(|e| anyhow!("org.gnome.Mutter.ScreenCast.Session.RecordWindow: {e}"))?;
    let stream_path = stream_path.to_string();

    // Subscribe before Start: Mutter announces the PipeWire node once the stream is running.
    let node: Arc<Mutex<Option<u32>>> = Arc::new(Mutex::new(None));
    let node_slot = Arc::clone(&node);
    let mut rule = dbus::message::MatchRule::new_signal(STREAM_INTERFACE, "PipeWireStreamAdded");
    rule.path = Some(stream_path.clone().into());
    let token = conn
        .add_match(rule, move |(node_id,): (u32,), _conn, _msg| {
            *node_slot.lock().unwrap() = Some(node_id);
            true
        })
        .context("subscribing to PipeWireStreamAdded")?;
    let started = session
        .method_call::<(), _, _, _>(SESSION_INTERFACE, "Start", ())
        .map_err(|e| anyhow!("org.gnome.Mutter.ScreenCast.Session.Start: {e}"));
    let node_id = started.and_then(|()| wait_for_node(conn, &node, &stream_path));
    let _ = conn.remove_match(token);
    let node_id = node_id?;

    let stream = conn.with_proxy(MUTTER_SCREEN_CAST_BUS, &stream_path, CALL_TIMEOUT);
    let parameters: PropMap = stream
        .get(STREAM_INTERFACE, "Parameters")
        .unwrap_or_default();
    Ok(BoundWindow {
        session_path: session_path.to_string(),
        node_id,
        logical_size: parse_size(&parameters).unwrap_or((0, 0)),
        owner,
    })
}

fn wait_for_node(conn: &Connection, node: &Mutex<Option<u32>>, stream_path: &str) -> Result<u32> {
    let deadline = Instant::now() + STREAM_ADDED_TIMEOUT;
    loop {
        if let Some(node_id) = *node.lock().unwrap() {
            return Ok(node_id);
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            bail!(
                "Mutter did not announce a PipeWire node for {stream_path} within {:?}",
                STREAM_ADDED_TIMEOUT
            );
        }
        conn.process(remaining.min(Duration::from_millis(250)))
            .context("D-Bus message processing")?;
    }
}

/// The `size` entry of a Mutter stream's `Parameters`, as `(width, height)`.
fn parse_size(parameters: &PropMap) -> Option<(i32, i32)> {
    let value = parameters.get("size")?;
    let mut fields = value.0.as_iter()?;
    let first = fields.next()?;
    // `(ii)` inside a variant: either the struct itself or its two fields.
    if let Some(mut inner) = first.as_iter() {
        let width = inner.next()?.as_i64()?;
        let height = inner.next()?.as_i64()?;
        return Some((width as i32, height as i32));
    }
    let width = first.as_i64()?;
    let height = fields.next()?.as_i64()?;
    Some((width as i32, height as i32))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bound(owner: u64) -> BoundWindow {
        BoundWindow {
            session_path: format!("/org/gnome/Mutter/ScreenCast/Session/u{owner}"),
            node_id: 61,
            logical_size: (2560, 1440),
            owner,
        }
    }

    #[test]
    fn a_missing_mutter_service_is_recognised() {
        assert!(is_service_missing(
            "org.freedesktop.DBus.Error.ServiceUnknown: The name org.gnome.Mutter.ScreenCast was \
             not provided by any .service files"
        ));
        assert!(is_service_missing("org.freedesktop.DBus.Error.NameHasNoOwner"));
        assert!(!is_service_missing("org.freedesktop.DBus.Error.Failed: Window not found"));
    }

    #[test]
    fn typed_errors_keep_their_kind_under_context() {
        let err = typed(ERROR_KIND_MUTTER_UNAVAILABLE, "no Mutter".into())
            .context("binding window 'k'");
        assert_eq!(error_kind(&err), ERROR_KIND_MUTTER_UNAVAILABLE);
        assert_eq!(error_kind(&anyhow!("gst failed")), "Helper");
    }

    #[test]
    fn an_unknown_key_is_reported_as_not_bound() {
        let streams = MutterWindowStreams::new();
        let err = streams.get("missing").unwrap_err();
        assert_eq!(error_kind(&err), ERROR_KIND_WINDOW_NOT_BOUND);
    }

    #[test]
    fn a_disconnecting_client_only_drops_its_own_windows() {
        let mut windows = HashMap::new();
        windows.insert("a".to_string(), bound(3));
        windows.insert("b".to_string(), bound(9));
        windows.insert("c".to_string(), bound(3));
        let mut keys = keys_owned_by(&windows, 3);
        keys.sort();
        assert_eq!(keys, vec!["a".to_string(), "c".to_string()]);
    }

    #[test]
    fn cursor_modes_map_to_mutter_values() {
        assert_eq!(mutter_cursor_mode(CursorMode::Hidden), 0);
        assert_eq!(mutter_cursor_mode(CursorMode::Embedded), 1);
        assert_eq!(mutter_cursor_mode(CursorMode::Metadata), 2);
    }

    #[test]
    fn unbinding_without_a_binding_reports_false() {
        let mut streams = MutterWindowStreams::new();
        assert!(!streams.unbind("missing"));
    }
}
