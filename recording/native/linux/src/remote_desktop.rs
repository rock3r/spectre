//! Combined RemoteDesktop + ScreenCast session for capture and input.

use crate::portal::{
    self, extract_restore_token, parse_first_stream, register_host_app, sender_token,
    variant, make_options, call_with_response, StreamMetadata,
    PORTAL_BUS, PORTAL_PATH, REMOTE_DESKTOP_INTERFACE, SCREEN_CAST_INTERFACE,
};
use crate::protocol::CursorMode;
use crate::rd_handshake::{
    remote_desktop_token_key, DEVICE_TYPES_KEYBOARD_POINTER, PERSIST_MODE_PERSISTENT,
    RD_TOKEN_FILE_PREFIX,
};
use anyhow::{anyhow, bail, Context, Result};
use dbus::arg::{OwnedFd, PropMap};
use dbus::blocking::Connection;
use dbus::Path as DBusPath;
use std::collections::HashMap;
use std::os::fd::{FromRawFd, IntoRawFd};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

pub struct RemoteDesktopSession {
    pub session_handle: String,
    pub stream: StreamMetadata,
    pub pipewire_fd: i32,
    connection: Connection,
}

impl RemoteDesktopSession {
    fn session_path(&self) -> DBusPath<'static> {
        DBusPath::from(self.session_handle.clone())
    }

    fn proxy(&self) -> dbus::blocking::Proxy<&Connection> {
        self.connection
            .with_proxy(PORTAL_BUS, PORTAL_PATH, Duration::from_secs(5))
    }

    pub fn notify_pointer_motion_absolute(&self, x: f64, y: f64) -> Result<()> {
        let options: PropMap = HashMap::new();
        self.proxy()
            .method_call::<(), _, _, _>(
                REMOTE_DESKTOP_INTERFACE,
                "NotifyPointerMotionAbsolute",
                (self.session_path(), options, self.stream.node_id, x, y),
            )
            .context("NotifyPointerMotionAbsolute")?;
        Ok(())
    }

    pub fn notify_pointer_button(&self, button: i32, pressed: bool) -> Result<()> {
        let options: PropMap = HashMap::new();
        let state: u32 = if pressed { 1 } else { 0 };
        self.proxy()
            .method_call::<(), _, _, _>(
                REMOTE_DESKTOP_INTERFACE,
                "NotifyPointerButton",
                (self.session_path(), options, button, state),
            )
            .context("NotifyPointerButton")?;
        Ok(())
    }

    pub fn notify_keyboard_keysym(&self, keysym: i32, pressed: bool) -> Result<()> {
        let options: PropMap = HashMap::new();
        let state: u32 = if pressed { 1 } else { 0 };
        self.proxy()
            .method_call::<(), _, _, _>(
                REMOTE_DESKTOP_INTERFACE,
                "NotifyKeyboardKeysym",
                (self.session_path(), options, keysym, state),
            )
            .context("NotifyKeyboardKeysym")?;
        Ok(())
    }

    pub fn notify_pointer_axis_discrete(&self, axis: u32, steps: i32) -> Result<()> {
        let options: PropMap = HashMap::new();
        self.proxy()
            .method_call::<(), _, _, _>(
                REMOTE_DESKTOP_INTERFACE,
                "NotifyPointerAxisDiscrete",
                (self.session_path(), options, axis, steps),
            )
            .context("NotifyPointerAxisDiscrete")?;
        Ok(())
    }

    pub fn open_pipewire_remote(&self) -> Result<std::os::fd::OwnedFd> {
        let open_options: PropMap = HashMap::new();
        let (fd,): (OwnedFd,) = self
            .proxy()
            .method_call(
                SCREEN_CAST_INTERFACE,
                "OpenPipeWireRemote",
                (self.session_path(), open_options),
            )
            .context("OpenPipeWireRemote")?;
        Ok(unsafe { std::os::fd::OwnedFd::from_raw_fd(fd.into_raw_fd()) })
    }
}

pub fn open_remote_desktop_session(
    cursor_mode: CursorMode,
    timeout: Duration,
) -> Result<RemoteDesktopSession> {
    let token_key = remote_desktop_token_key(cursor_mode);
    let stored = portal::load_restore_token_named(RD_TOKEN_FILE_PREFIX, &token_key)?;
    match open_remote_desktop_session_inner(cursor_mode, timeout, stored.as_deref(), &token_key) {
        Ok(session) => Ok(session),
        Err(first) if stored.is_some() && is_rd_token_rejection(&first) => {
            let _ = portal::clear_restore_token_named(RD_TOKEN_FILE_PREFIX, &token_key);
            eprintln!(
                "spectre-wayland-helper: stored RemoteDesktop restore_token was rejected \
                 ({first:#}). Cleared token and retrying interactively — a human must \
                 accept Share + Remember / Allow remote interaction once."
            );
            open_remote_desktop_session_inner(cursor_mode, timeout, None, &token_key).with_context(
                || {
                    format!(
                        "interactive RemoteDesktop retry after invalid restore_token also failed \
                         (original: {first:#})"
                    )
                },
            )
        }
        Err(e) => Err(e),
    }
}

fn is_rd_token_rejection(err: &anyhow::Error) -> bool {
    let msg = format!("{err:#}");
    (msg.contains("SelectSources rejected")
        || msg.contains("SelectDevices rejected")
        || msg.contains("Start rejected"))
        && (msg.contains("restore_token")
            || msg.contains("stored restore_token")
            || msg.contains("no longer valid")
            || msg.contains("response code"))
}

fn open_remote_desktop_session_inner(
    cursor_mode: CursorMode,
    timeout: Duration,
    restore_token: Option<&str>,
    token_key: &str,
) -> Result<RemoteDesktopSession> {
    let conn = Connection::new_session().context("opening session bus")?;
    register_host_app(&conn, timeout)?;
    let sender = sender_token(&conn).context("computing sender token")?;
    let counter = AtomicUsize::new(0);
    let next_token = |kind: &str| {
        format!(
            "spectre_{}_{}",
            kind,
            counter.fetch_add(1, Ordering::SeqCst)
        )
    };

    let create_token = next_token("rd_create");
    let create_options = make_options([
        ("handle_token", variant(create_token.clone())),
        ("session_handle_token", variant(next_token("rd_session"))),
    ]);
    let create_response = call_with_response(
        &conn,
        &sender,
        &create_token,
        REMOTE_DESKTOP_INTERFACE,
        "CreateSession",
        (create_options,),
        timeout,
    )?;
    if create_response.response_code != 0 {
        bail!(
            "RemoteDesktop.CreateSession rejected (response code {}).",
            create_response.response_code
        );
    }
    let session_handle = create_response
        .results
        .get("session_handle")
        .and_then(|v| v.0.as_str())
        .map(str::to_string)
        .ok_or_else(|| anyhow!("RemoteDesktop.CreateSession missing session_handle"))?;
    let session_path = DBusPath::from(session_handle.clone());

    let select_token = next_token("rd_devices");
    let mut device_options = make_options([
        ("handle_token", variant(select_token.clone())),
        ("types", variant(DEVICE_TYPES_KEYBOARD_POINTER)),
        ("persist_mode", variant(PERSIST_MODE_PERSISTENT)),
    ]);
    if let Some(token) = restore_token {
        device_options.insert("restore_token".to_string(), variant(token.to_string()));
    }
    let devices_response = call_with_response(
        &conn,
        &sender,
        &select_token,
        REMOTE_DESKTOP_INTERFACE,
        "SelectDevices",
        (session_path.clone(), device_options),
        timeout,
    )?;
    if devices_response.response_code != 0 {
        bail!(
            "SelectDevices rejected (response code {}){}.",
            devices_response.response_code,
            if restore_token.is_some() {
                " (or the stored restore_token was refused)"
            } else {
                ""
            }
        );
    }

    let sources_token = next_token("rd_sources");
    let source_options = make_options([
        ("handle_token", variant(sources_token.clone())),
        ("types", variant(1u32)), // monitor
        ("multiple", variant(false)),
        ("cursor_mode", variant(cursor_mode_flag(cursor_mode))),
    ]);
    let sources_response = call_with_response(
        &conn,
        &sender,
        &sources_token,
        SCREEN_CAST_INTERFACE,
        "SelectSources",
        (session_path.clone(), source_options),
        timeout,
    )?;
    if sources_response.response_code != 0 {
        bail!(
            "SelectSources rejected (response code {}){}.",
            sources_response.response_code,
            if restore_token.is_some() {
                " (or the stored restore_token was refused)"
            } else {
                ""
            }
        );
    }

    let start_token = next_token("rd_start");
    let start_options = make_options([("handle_token", variant(start_token.clone()))]);
    let start_response = call_with_response(
        &conn,
        &sender,
        &start_token,
        REMOTE_DESKTOP_INTERFACE,
        "Start",
        (session_path.clone(), "".to_string(), start_options),
        timeout,
    )?;
    if start_response.response_code != 0 {
        bail!(
            "Start rejected (response code {}). User likely cancelled the Share / Allow \
             remote interaction dialog{}.",
            start_response.response_code,
            if restore_token.is_some() {
                ", or the stored restore_token is no longer valid"
            } else {
                ""
            }
        );
    }
    if let Some(token) = extract_restore_token(&start_response.results) {
        if let Err(e) = portal::save_restore_token_named(RD_TOKEN_FILE_PREFIX, token_key, &token) {
            eprintln!(
                "spectre-wayland-helper: failed to persist RemoteDesktop restore_token: {e:#} \
                 (next session will prompt again)"
            );
        }
    }
    let stream = parse_first_stream(&start_response.results).with_context(|| {
        format!(
            "parsing RemoteDesktop Start streams. Raw results: {:?}",
            start_response.results
        )
    })?;

    let open_options: PropMap = HashMap::new();
    let proxy = conn.with_proxy(PORTAL_BUS, PORTAL_PATH, timeout);
    let (fd,): (OwnedFd,) = proxy
        .method_call(
            SCREEN_CAST_INTERFACE,
            "OpenPipeWireRemote",
            (session_path, open_options),
        )
        .context("OpenPipeWireRemote method call")?;
    let raw_fd = fd.into_raw_fd();

    Ok(RemoteDesktopSession {
        session_handle,
        stream,
        pipewire_fd: raw_fd,
        connection: conn,
    })
}

fn cursor_mode_flag(mode: CursorMode) -> u32 {
    match mode {
        CursorMode::Hidden => 1,
        CursorMode::Embedded => 2,
        CursorMode::Metadata => 4,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rd_handshake::remote_desktop_handshake_calls;

    #[test]
    fn handshake_plan_starts_with_register() {
        assert_eq!(remote_desktop_handshake_calls()[0].method, "Register");
        assert_eq!(remote_desktop_token_key(CursorMode::Embedded), "rd-monitor-embedded");
    }

    #[test]
    fn rd_token_rejection_matches_select_devices() {
        assert!(is_rd_token_rejection(&anyhow!(
            "SelectDevices rejected (response code 2): restore_token"
        )));
        assert!(!is_rd_token_rejection(&anyhow!("CreateSession rejected")));
    }
}
