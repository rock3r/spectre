//! Exclusive ownership of the long-lived Wayland portal session.

use anyhow::{bail, Context, Result};
use nix::fcntl::{flock, FlockArg};
use std::fs::{File, OpenOptions};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::AsRawFd;
use std::path::{Path, PathBuf};

/// Advisory exclusive lock. Dropping releases the flock so another helper may take over.
pub struct SessionLock {
    _file: File,
    pub path: PathBuf,
}

pub fn session_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("SPECTRE_WAYLAND_SESSION_DIR") {
        if !override_dir.is_empty() {
            return PathBuf::from(override_dir);
        }
    }
    if let Ok(runtime) = std::env::var("XDG_RUNTIME_DIR") {
        if !runtime.is_empty() {
            return PathBuf::from(runtime).join("spectre");
        }
    }
    PathBuf::from("/var/empty/spectre-no-runtime")
}

pub fn lock_path(dir: &Path) -> PathBuf {
    dir.join("wayland-session.lock")
}

pub fn socket_path(dir: &Path) -> PathBuf {
    dir.join("wayland-session.sock")
}

/// Take exclusive ownership of the session directory. Fails closed if another helper holds it.
pub fn acquire_session_lock(dir: &Path) -> Result<SessionLock> {
    std::fs::create_dir_all(dir)
        .with_context(|| format!("creating session dir {}", dir.display()))?;
    let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
    let path = lock_path(dir);
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .open(&path)
        .with_context(|| format!("opening session lock {}", path.display()))?;
    match flock(file.as_raw_fd(), FlockArg::LockExclusiveNonblock) {
        Ok(()) => Ok(SessionLock { _file: file, path }),
        Err(nix::errno::Errno::EWOULDBLOCK) | Err(nix::errno::Errno::EAGAIN) => {
            bail!(
                "Spectre Wayland session is already owned by another process (lock {}). \
                 Parallel JVMs must connect to the existing helper socket rather than \
                 opening a second portal session.",
                path.display()
            )
        }
        Err(e) => Err(e).with_context(|| format!("flock {}", path.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn second_owner_fails_closed_while_lock_is_held() {
        let dir = std::env::temp_dir().join(format!(
            "spectre-session-lock-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let first = acquire_session_lock(&dir).expect("first owner");
        let second = acquire_session_lock(&dir);
        assert!(second.is_err(), "second owner must fail closed");
        let msg = format!("{:#}", second.unwrap_err());
        assert!(
            msg.contains("already owned"),
            "error should name the ownership conflict, got: {msg}"
        );

        drop(first);
        let third = acquire_session_lock(&dir);
        assert!(third.is_ok(), "lock must be reusable after drop");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn socket_and_lock_live_under_spectre_runtime_dir() {
        let dir = PathBuf::from("/run/user/1000/spectre");
        assert_eq!(lock_path(&dir).file_name().unwrap(), "wayland-session.lock");
        assert_eq!(socket_path(&dir).file_name().unwrap(), "wayland-session.sock");
        assert!(!socket_path(&dir)
            .to_string_lossy()
            .contains(".java/robot"));
    }
}
