//! Long-lived Spectre Wayland session: one RemoteDesktop grant, unix-socket RPC.

use crate::input_map::{awt_button_mask_to_evdev, vk_to_keysym};
use crate::protocol::{Command, Event, ScreenshotCommand, StartCommand};
use crate::remote_desktop::{open_remote_desktop_session, RemoteDesktopSession};
use crate::session_lock::{self, acquire_session_lock, SessionLock};
use anyhow::{Context, Result};
use nix::fcntl::{fcntl, FcntlArg, FdFlag};
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use std::io::{BufRead, BufReader, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::process::{Child, Command as ProcessCommand, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

static NEXT_CLIENT_ID: AtomicU64 = AtomicU64::new(1);

pub fn serve() -> Result<()> {
    let dir = session_lock::session_dir();
    let lock = acquire_session_lock(&dir)?;
    let socket = session_lock::socket_path(&dir);
    if socket.exists() {
        let _ = std::fs::remove_file(&socket);
    }
    // Open the portal grant before advertising the socket. Binding first made waitForSocket
    // return while SelectDevices was still showing a dialog, and daemonizing first made the
    // JVM Process exit 0 even when the handshake later failed.
    let session = open_remote_desktop_session(
        crate::protocol::CursorMode::Embedded,
        crate::portal::DEFAULT_RESPONSE_TIMEOUT,
    )?;
    dup_keep_pipewire_fd(session.pipewire_fd)?;
    let listener = UnixListener::bind(&socket)
        .with_context(|| format!("binding Wayland session socket {}", socket.display()))?;
    maybe_daemonize();
    let state = Arc::new(Mutex::new(SessionState {
        session,
        recording: None,
        _lock: lock,
    }));

    eprintln!(
        "spectre-wayland-helper: RemoteDesktop session ready on {}",
        socket.display()
    );

    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                let state = Arc::clone(&state);
                let client_id = NEXT_CLIENT_ID.fetch_add(1, Ordering::Relaxed);
                let _ = std::thread::Builder::new()
                    .name("wayland-session-client".into())
                    .spawn(move || {
                        if let Err(e) = handle_client(stream, &state, client_id) {
                            eprintln!("spectre-wayland-helper: session client error: {e:#}");
                        }
                    });
            }
            Err(e) => eprintln!("spectre-wayland-helper: accept failed: {e}"),
        }
    }
    Ok(())
}

struct SessionState {
    session: RemoteDesktopSession,
    recording: Option<ActiveRecording>,
    _lock: SessionLock,
}

struct ActiveRecording {
    child: Child,
    output: PathBuf,
    owner: u64,
}

fn recording_owner_should_reap(recording_owner: Option<u64>, client_id: u64) -> bool {
    recording_owner == Some(client_id)
}

fn maybe_daemonize() {
    if std::env::var("SPECTRE_WAYLAND_SESSION_FOREGROUND")
        .ok()
        .as_deref()
        == Some("1")
    {
        return;
    }
    if let Err(e) = nix::unistd::daemon(true, true) {
        eprintln!("spectre-wayland-helper: daemonize failed ({e}); staying in foreground");
    }
}

fn dup_keep_pipewire_fd(fd: i32) -> Result<()> {
    let mut flags = FdFlag::from_bits_truncate(fcntl(fd, FcntlArg::F_GETFD).context("F_GETFD")?);
    flags.remove(FdFlag::FD_CLOEXEC);
    fcntl(fd, FcntlArg::F_SETFD(flags)).context("F_SETFD")?;
    Ok(())
}

fn handle_client(stream: UnixStream, state: &Mutex<SessionState>, client_id: u64) -> Result<()> {
    let _reap = DisconnectReap {
        state,
        client_id,
    };
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut writer = stream;
    let mut line = String::new();
    loop {
        line.clear();
        let n = reader.read_line(&mut line)?;
        if n == 0 {
            return Ok(());
        }
        let command: Command = serde_json::from_str(line.trim())
            .with_context(|| format!("parsing session command: {}", line.trim()))?;
        let event = dispatch(command, state, client_id);
        write_event(&mut writer, event)?;
    }
}

struct DisconnectReap<'a> {
    state: &'a Mutex<SessionState>,
    client_id: u64,
}

impl Drop for DisconnectReap<'_> {
    fn drop(&mut self) {
        reap_owned_recording(self.state, self.client_id);
    }
}

fn reap_owned_recording(state: &Mutex<SessionState>, client_id: u64) {
    let owner = {
        let guard = match state.lock() {
            Ok(guard) => guard,
            Err(_) => return,
        };
        guard.recording.as_ref().map(|r| r.owner)
    };
    if recording_owner_should_reap(owner, client_id) {
        let _ = stop_recording(state);
    }
}

fn dispatch(command: Command, state: &Mutex<SessionState>, client_id: u64) -> Event {
    match command {
        Command::PointerMove { x, y } => with_session(state, |s| {
            let (ox, oy) = s.stream.position;
            s.notify_pointer_motion_absolute((x - ox) as f64, (y - oy) as f64)?;
            Ok(Event::InputAck)
        }),
        Command::PointerButton { button, pressed } => with_session(state, |s| {
            let evdev = awt_button_mask_to_evdev(button)?;
            s.notify_pointer_button(evdev, pressed)?;
            Ok(Event::InputAck)
        }),
        Command::Key { key_code, pressed } => with_session(state, |s| {
            let keysym = vk_to_keysym(key_code)?;
            s.notify_keyboard_keysym(keysym, pressed)?;
            Ok(Event::InputAck)
        }),
        Command::PointerAxis { axis, steps } => with_session(state, |s| {
            s.notify_pointer_axis_discrete(axis, steps)?;
            Ok(Event::InputAck)
        }),
        Command::Screenshot(command) => match run_screenshot(state, command) {
            Ok(size) => Event::ScreenshotSaved {
                output_size_bytes: size,
            },
            Err(e) => Event::Error {
                kind: "Helper".into(),
                message: format!("{e:#}"),
            },
        },
        Command::Start(start) => match run_recording_start(state, start, client_id) {
            Ok(event) => event,
            Err(e) => Event::Error {
                kind: "Helper".into(),
                message: format!("{e:#}"),
            },
        },
        Command::Stop => match stop_recording(state) {
            Ok(size) => Event::Stopped {
                output_size_bytes: size,
            },
            Err(e) => Event::Error {
                kind: "Helper".into(),
                message: format!("{e:#}"),
            },
        },
    }
}

fn with_session(
    state: &Mutex<SessionState>,
    f: impl FnOnce(&RemoteDesktopSession) -> Result<Event>,
) -> Event {
    match state.lock() {
        Ok(guard) => match f(&guard.session) {
            Ok(event) => event,
            Err(e) => Event::Error {
                kind: "Input".into(),
                message: format!("{e:#}"),
            },
        },
        Err(e) => Event::Error {
            kind: "Helper".into(),
            message: format!("session mutex poisoned: {e}"),
        },
    }
}

fn run_screenshot(state: &Mutex<SessionState>, command: ScreenshotCommand) -> Result<u64> {
    let output = PathBuf::from(&command.output);
    let (stream, fd) = {
        let guard = state.lock().expect("session mutex");
        (guard.session.stream.clone(), guard.session.pipewire_fd)
    };
    let dup_fd = nix::unistd::dup(fd).context("dup PipeWire FD for screenshot")?;
    let owned = unsafe { std::os::fd::OwnedFd::from_raw_fd(dup_fd) };
    crate::screenshot::capture_from_stream(&stream, owned.as_raw_fd(), &command, &output)?;
    Ok(std::fs::metadata(&output)?.len())
}

fn run_recording_start(
    state: &Mutex<SessionState>,
    start: StartCommand,
    client_id: u64,
) -> Result<Event> {
    let mut guard = state.lock().expect("session mutex");
    if guard.recording.is_some() {
        anyhow::bail!("a recording is already running on the Spectre Wayland session");
    }
    let stream = guard.session.stream.clone();
    let fd = nix::unistd::dup(guard.session.pipewire_fd).context("dup PipeWire FD for recording")?;
    let owned_fd = unsafe { std::os::fd::OwnedFd::from_raw_fd(fd) };
    let raw = owned_fd.as_raw_fd();
    let mut flags = FdFlag::from_bits_truncate(fcntl(raw, FcntlArg::F_GETFD).context("F_GETFD")?);
    flags.remove(FdFlag::FD_CLOEXEC);
    fcntl(raw, FcntlArg::F_SETFD(flags))?;

    let stream_relative_region = crate::stream_region::map_awt_region_to_stream(
        start.region,
        stream.position,
        stream.size,
        start.screen_size.and_then(crate::stream_region::screen_size_to_region),
    )?;
    let argv = crate::gst::build_pipewire_argv(
        stream.node_id,
        raw,
        stream_relative_region,
        stream.size,
        start.frame_rate,
        matches!(start.cursor_mode, crate::protocol::CursorMode::Embedded),
        &PathBuf::from(&start.output),
        &start.codec,
    )?;
    let gst_stdout_target = unsafe {
        let stderr_raw = nix::unistd::dup(std::io::stderr().as_raw_fd())?;
        std::os::fd::OwnedFd::from_raw_fd(stderr_raw)
    };
    let child = ProcessCommand::new(&argv[0])
        .args(&argv[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::from(gst_stdout_target))
        .stderr(Stdio::inherit())
        .spawn()
        .with_context(|| format!("spawning gst-launch: {argv:?}"))?;
    let gst_pid = child.id();
    drop(owned_fd);
    let output = PathBuf::from(&start.output);
    guard.recording = Some(ActiveRecording {
        child,
        output,
        owner: client_id,
    });
    Ok(Event::Started {
        node_id: stream.node_id,
        stream_size: [stream.size.0, stream.size.1],
        stream_position: [stream.position.0, stream.position.1],
        gst_pid,
    })
}

fn stop_recording(state: &Mutex<SessionState>) -> Result<u64> {
    let ActiveRecording {
        mut child, output, ..
    } = {
        let mut guard = state.lock().expect("session mutex");
        guard
            .recording
            .take()
            .context("no recording in progress on the Spectre Wayland session")?
    };
    let pid = child.id();
    let _ = kill(Pid::from_raw(gst_pid_i32(pid)), Signal::SIGINT);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    loop {
        if let Some(status) = child.try_wait()? {
            if !status.success() {
                anyhow::bail!("gst-launch exited with status {status:?}");
            }
            return Ok(std::fs::metadata(&output).map(|m| m.len()).unwrap_or(0));
        }
        if std::time::Instant::now() >= deadline {
            let _ = kill(Pid::from_raw(gst_pid_i32(pid)), Signal::SIGKILL);
            let status = child.wait()?;
            anyhow::bail!("gst-launch did not exit within 30s after SIGINT (status {status:?})");
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

fn gst_pid_i32(pid: u32) -> i32 {
    i32::try_from(pid).unwrap_or(i32::MAX)
}

fn write_event(writer: &mut UnixStream, event: Event) -> Result<()> {
    let line = serde_json::to_string(&event)?;
    writer.write_all(line.as_bytes())?;
    writer.write_all(b"\n")?;
    writer.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::recording_owner_should_reap;

    #[test]
    fn owner_disconnect_reaps_that_clients_recording() {
        assert!(recording_owner_should_reap(Some(7), 7));
        assert!(!recording_owner_should_reap(Some(7), 8));
        assert!(!recording_owner_should_reap(None, 7));
    }
}
