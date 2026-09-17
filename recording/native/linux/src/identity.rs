//! Host portal identity for unsandboxed Spectre (`org.freedesktop.host.portal.Registry`).
//!
//! `Register` must run on a D-Bus connection **before** any ScreenCast / RemoteDesktop call,
//! and the app id must match a `.desktop` basename on disk.

use anyhow::{Context, Result};
use std::fs;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

/// Freedesktop application id (no `.desktop` suffix) used for portal grants.
pub const SPECTRE_APP_ID: &str = "dev.sebastiano.spectre";

const DESKTOP_FILE_NAME: &str = "dev.sebastiano.spectre.desktop";
const DESKTOP_FILE: &str = include_str!("../resources/dev.sebastiano.spectre.desktop");

/// Directory that should contain `dev.sebastiano.spectre.desktop`.
pub fn applications_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("SPECTRE_WAYLAND_DESKTOP_DIR") {
        if !override_dir.is_empty() {
            return PathBuf::from(override_dir);
        }
    }
    if let Ok(data_home) = std::env::var("XDG_DATA_HOME") {
        if !data_home.is_empty() {
            return PathBuf::from(data_home).join("applications");
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        if !home.is_empty() {
            return PathBuf::from(home).join(".local").join("share").join("applications");
        }
    }
    PathBuf::from("/var/empty/spectre-no-home/applications")
}

pub fn desktop_file_path() -> PathBuf {
    applications_dir().join(DESKTOP_FILE_NAME)
}

/// Write the Spectre `.desktop` if missing or stale. Mode 0644 — desktop files are not secrets.
pub fn install_desktop_file() -> Result<PathBuf> {
    let path = desktop_file_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("creating applications dir {}", parent.display()))?;
    }
    if path.is_file() {
        if let Ok(existing) = fs::read_to_string(&path) {
            if existing == DESKTOP_FILE {
                return Ok(path);
            }
        }
    }
    write_desktop_file(&path, DESKTOP_FILE.as_bytes())
        .with_context(|| format!("writing desktop file {}", path.display()))?;
    Ok(path)
}

fn write_desktop_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let tmp = parent.join(format!(
        ".{}.tmp.{}",
        path.file_name().and_then(|s| s.to_str()).unwrap_or("desktop"),
        std::process::id()
    ));
    {
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o644)
            .open(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644))?;
    fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn app_id_matches_desktop_basename() {
        assert_eq!(SPECTRE_APP_ID, "dev.sebastiano.spectre");
        assert!(DESKTOP_FILE.contains("Name=Spectre"));
        assert!(DESKTOP_FILE.contains("Type=Application"));
        assert!(!DESKTOP_FILE.contains("java/robot"));
    }

    #[test]
    fn install_desktop_file_writes_xdg_applications_entry() {
        let _guard = ENV_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!(
            "spectre-desktop-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        std::env::set_var("SPECTRE_WAYLAND_DESKTOP_DIR", &dir);
        std::env::remove_var("XDG_DATA_HOME");

        let path = install_desktop_file().unwrap();
        assert_eq!(path, dir.join(DESKTOP_FILE_NAME));
        let contents = fs::read_to_string(&path).unwrap();
        assert_eq!(contents, DESKTOP_FILE);
        let mode = fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o644);

        std::env::remove_var("SPECTRE_WAYLAND_DESKTOP_DIR");
        let _ = fs::remove_dir_all(&dir);
    }
}
