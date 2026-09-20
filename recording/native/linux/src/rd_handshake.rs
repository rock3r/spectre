//! RemoteDesktop + ScreenCast handshake plan for the long-lived Spectre Wayland session.
//!
//! Persistence lives on RemoteDesktop.SelectDevices (`persist_mode=2`). ScreenCast.SelectSources
//! on a RemoteDesktop session must **not** set persist_mode / restore_token.

use crate::identity::SPECTRE_APP_ID;
use crate::protocol::{CursorMode, SourceType};

/// Keyboard (1) | pointer (2). Touchscreen is not requested.
pub const DEVICE_TYPE_KEYBOARD: u32 = 1;
pub const DEVICE_TYPE_POINTER: u32 = 2;
pub const DEVICE_TYPES_KEYBOARD_POINTER: u32 = DEVICE_TYPE_KEYBOARD | DEVICE_TYPE_POINTER;

pub const PERSIST_MODE_PERSISTENT: u32 = 2;

/// Token-file key for the combined RemoteDesktop + monitor ScreenCast grant.
pub fn remote_desktop_token_key(cursor_mode: CursorMode) -> String {
    let cursor = match cursor_mode {
        CursorMode::Hidden => "hidden",
        CursorMode::Embedded => "embedded",
        CursorMode::Metadata => "metadata",
    };
    format!("rd-monitor-{cursor}")
}

/// Filename prefix — distinct from ScreenCast-only `wayland-screencast-restore-token-*`.
pub const RD_TOKEN_FILE_PREFIX: &str = "wayland-rd-restore-token";

pub fn remote_desktop_token_file_name(token_key: &str) -> String {
    format!("{RD_TOKEN_FILE_PREFIX}-{token_key}")
}

/// Ordered D-Bus method names the handshake must issue. Tests pin this so Register cannot
/// drift after a ScreenCast/RemoteDesktop call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HandshakeCall {
    pub interface: &'static str,
    pub method: &'static str,
}

pub const HOST_REGISTRY_INTERFACE: &str = "org.freedesktop.host.portal.Registry";
pub const REMOTE_DESKTOP_INTERFACE: &str = "org.freedesktop.portal.RemoteDesktop";
pub const SCREEN_CAST_INTERFACE: &str = "org.freedesktop.portal.ScreenCast";

/// The exact call order for a combined capture+input session.
pub fn remote_desktop_handshake_calls() -> Vec<HandshakeCall> {
    vec![
        HandshakeCall {
            interface: HOST_REGISTRY_INTERFACE,
            method: "Register",
        },
        HandshakeCall {
            interface: REMOTE_DESKTOP_INTERFACE,
            method: "CreateSession",
        },
        HandshakeCall {
            interface: REMOTE_DESKTOP_INTERFACE,
            method: "SelectDevices",
        },
        HandshakeCall {
            interface: SCREEN_CAST_INTERFACE,
            method: "SelectSources",
        },
        HandshakeCall {
            interface: REMOTE_DESKTOP_INTERFACE,
            method: "Start",
        },
        HandshakeCall {
            interface: SCREEN_CAST_INTERFACE,
            method: "OpenPipeWireRemote",
        },
    ]
}

pub fn select_devices_persist_mode() -> u32 {
    PERSIST_MODE_PERSISTENT
}

pub fn select_devices_types() -> u32 {
    DEVICE_TYPES_KEYBOARD_POINTER
}

pub fn monitor_source_bitmask() -> u32 {
    source_types_to_bitmask(&[SourceType::Monitor])
}

pub fn source_types_to_bitmask(types: &[SourceType]) -> u32 {
    types
        .iter()
        .map(|t| match t {
            SourceType::Monitor => 1,
            SourceType::Window => 2,
            SourceType::Virtual => 4,
        })
        .fold(0u32, |acc, b| acc | b)
}

pub fn register_app_id() -> &'static str {
    SPECTRE_APP_ID
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn register_is_the_first_handshake_call() {
        let calls = remote_desktop_handshake_calls();
        assert_eq!(calls[0].interface, HOST_REGISTRY_INTERFACE);
        assert_eq!(calls[0].method, "Register");
        assert_eq!(register_app_id(), "dev.sebastiano.spectre");
        let portal_before_register = calls.iter().take_while(|c| c.method != "Register").any(|c| {
            c.interface.contains("ScreenCast") || c.interface.contains("RemoteDesktop")
        });
        assert!(!portal_before_register);
    }

    #[test]
    fn persist_mode_is_on_select_devices_not_select_sources() {
        assert_eq!(select_devices_persist_mode(), 2);
        assert_eq!(select_devices_types(), 3);
        let calls = remote_desktop_handshake_calls();
        let select_sources = calls
            .iter()
            .find(|c| c.method == "SelectSources")
            .expect("SelectSources");
        assert_eq!(select_sources.interface, SCREEN_CAST_INTERFACE);
        let select_devices = calls
            .iter()
            .find(|c| c.method == "SelectDevices")
            .expect("SelectDevices");
        assert_eq!(select_devices.interface, REMOTE_DESKTOP_INTERFACE);
    }

    #[test]
    fn remote_desktop_token_key_is_not_screencast_filename() {
        let key = remote_desktop_token_key(CursorMode::Embedded);
        assert_eq!(key, "rd-monitor-embedded");
        let name = remote_desktop_token_file_name(&key);
        assert_eq!(name, "wayland-rd-restore-token-rd-monitor-embedded");
        assert!(!name.contains("screencast"));
        assert!(!name.contains(".java"));
        assert!(!name.contains("robot"));
    }

    #[test]
    fn monitor_only_source_mask() {
        assert_eq!(monitor_source_bitmask(), 1);
        assert_eq!(source_types_to_bitmask(&[SourceType::Window]), 2);
    }
}
