//! Long-lived Spectre Wayland session: one RemoteDesktop grant, unix-socket RPC.

use crate::input_map::{awt_button_mask_to_evdev, vk_to_keysym};
use crate::protocol::{Command, Event, ScreenshotCommand, StartCommand};
use crate::remote_desktop::{open_remote_desktop_session, RemoteDesktopSession};
use crate::session_lock::{self, acquire_session_lock, SessionLock};
use anyhow::{Context, Result};
use nix::fcntl::{fcntl, FcntlArg, FdFlag};
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use std::collections::{BTreeSet, HashMap};
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
    let lock = match acquire_session_lock(&dir) {
        Ok(lock) => lock,
        Err(e) if format!("{e:#}").contains("already owned") => {
            std::process::exit(session_lock::SESSION_OWNED_EXIT);
        }
        Err(e) => return Err(e),
    };
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
        input_holds: HashMap::new(),
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
    input_holds: HashMap<u64, ClientInputHold>,
    _lock: SessionLock,
}

struct ActiveRecording {
    child: Child,
    output: PathBuf,
    owner: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct ClientInputHold {
    buttons: BTreeSet<i32>,
    keys: BTreeSet<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HeldInput {
    PointerButton(i32),
    Key(i32),
}

struct DisconnectCleanup {
    reap_recording: bool,
    held_input: ClientInputHold,
    portal_releases: ClientInputHold,
}

fn recording_owner_should_reap(recording_owner: Option<u64>, client_id: u64) -> bool {
    recording_owner == Some(client_id)
}

fn apply_held_input(
    holds: &mut HashMap<u64, ClientInputHold>,
    client_id: u64,
    input: HeldInput,
    pressed: bool,
) {
    let hold = holds.entry(client_id).or_default();
    let set = match input {
        HeldInput::PointerButton(_) => &mut hold.buttons,
        HeldInput::Key(_) => &mut hold.keys,
    };
    let code = match input {
        HeldInput::PointerButton(code) | HeldInput::Key(code) => code,
    };
    if pressed {
        set.insert(code);
    } else {
        set.remove(&code);
    }
    if hold.buttons.is_empty() && hold.keys.is_empty() {
        holds.remove(&client_id);
    }
}

fn take_client_held_input(
    holds: &mut HashMap<u64, ClientInputHold>,
    client_id: u64,
) -> ClientInputHold {
    holds.remove(&client_id).unwrap_or_default()
}

fn hold_contains(hold: &ClientInputHold, input: HeldInput) -> bool {
    match input {
        HeldInput::PointerButton(button) => hold.buttons.contains(&button),
        HeldInput::Key(key) => hold.keys.contains(&key),
    }
}

fn input_still_held(holds: &HashMap<u64, ClientInputHold>, input: HeldInput) -> bool {
    holds.values().any(|hold| hold_contains(hold, input))
}

fn input_held_by_others(
    holds: &HashMap<u64, ClientInputHold>,
    client_id: u64,
    input: HeldInput,
) -> bool {
    holds
        .iter()
        .any(|(id, hold)| *id != client_id && hold_contains(hold, input))
}

fn inputs_to_notify_release(
    remaining: &HashMap<u64, ClientInputHold>,
    dropped: &ClientInputHold,
) -> ClientInputHold {
    ClientInputHold {
        buttons: dropped
            .buttons
            .iter()
            .copied()
            .filter(|button| !input_still_held(remaining, HeldInput::PointerButton(*button)))
            .collect(),
        keys: dropped
            .keys
            .iter()
            .copied()
            .filter(|key| !input_still_held(remaining, HeldInput::Key(*key)))
            .collect(),
    }
}

fn disconnect_cleanup(
    recording_owner: Option<u64>,
    client_id: u64,
    holds: &mut HashMap<u64, ClientInputHold>,
) -> DisconnectCleanup {
    let held_input = take_client_held_input(holds, client_id);
    let portal_releases = inputs_to_notify_release(holds, &held_input);
    DisconnectCleanup {
        reap_recording: recording_owner_should_reap(recording_owner, client_id),
        held_input,
        portal_releases,
    }
}

fn maybe_daemonize() {
    if std::env::var("SPECTRE_WAYLAND_SESSION_FOREGROUND")
        .ok()
        .as_deref()
        == Some("1")
    {
        return;
    }
    // noclose=false redirects stdio to /dev/null so piped parents (Gradle, portal-token-warmup)
    // still observe EOF after this process becomes a session daemon.
    if let Err(e) = nix::unistd::daemon(true, false) {
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
        let reap_recording = release_held_input(self.state, self.client_id);
        if reap_recording {
            let _ = stop_recording(self.state);
        }
    }
}

fn release_held_input(state: &Mutex<SessionState>, client_id: u64) -> bool {
    let mut guard = match state.lock() {
        Ok(guard) => guard,
        Err(_) => return false,
    };
    let owner = guard.recording.as_ref().map(|r| r.owner);
    let cleanup = disconnect_cleanup(owner, client_id, &mut guard.input_holds);
    for button in &cleanup.portal_releases.buttons {
        if let Err(e) = guard.session.notify_pointer_button(*button, false) {
            eprintln!(
                "spectre-wayland-helper: failed to release held button {button} on disconnect: {e:#}"
            );
        }
    }
    for key in &cleanup.portal_releases.keys {
        if let Err(e) = guard.session.notify_keyboard_keysym(*key, false) {
            eprintln!(
                "spectre-wayland-helper: failed to release held key {key} on disconnect: {e:#}"
            );
        }
    }
    cleanup.reap_recording
}

fn dispatch(command: Command, state: &Mutex<SessionState>, client_id: u64) -> Event {
    match command {
        Command::PointerMove { x, y } => with_session(state, |s| {
            let (ox, oy) = s.stream.position;
            s.notify_pointer_motion_absolute((x - ox) as f64, (y - oy) as f64)?;
            Ok(Event::InputAck)
        }),
        Command::PointerButton { button, pressed } => with_state(state, |s| {
            let evdev = awt_button_mask_to_evdev(button)?;
            let input = HeldInput::PointerButton(evdev);
            if pressed || !input_held_by_others(&s.input_holds, client_id, input) {
                s.session.notify_pointer_button(evdev, pressed)?;
            }
            apply_held_input(&mut s.input_holds, client_id, input, pressed);
            Ok(Event::InputAck)
        }),
        Command::Key { key_code, pressed } => with_state(state, |s| {
            let keysym = vk_to_keysym(key_code)?;
            let input = HeldInput::Key(keysym);
            if pressed || !input_held_by_others(&s.input_holds, client_id, input) {
                s.session.notify_keyboard_keysym(keysym, pressed)?;
            }
            apply_held_input(&mut s.input_holds, client_id, input, pressed);
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
    with_state(state, |s| f(&s.session))
}

fn with_state(
    state: &Mutex<SessionState>,
    f: impl FnOnce(&mut SessionState) -> Result<Event>,
) -> Event {
    match state.lock() {
        Ok(mut guard) => match f(&mut guard) {
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
    let (stream, owned) = {
        let guard = state.lock().expect("session mutex");
        let owned = guard.session.open_pipewire_remote()?;
        (guard.session.stream.clone(), owned)
    };
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
    let owned_fd = guard.session.open_pipewire_remote()?;
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
    use super::{
        apply_held_input, disconnect_cleanup, recording_owner_should_reap, ClientInputHold,
        HeldInput,
    };
    use std::collections::{BTreeSet, HashMap};

    #[test]
    fn owner_disconnect_reaps_that_clients_recording() {
        assert!(recording_owner_should_reap(Some(7), 7));
        assert!(!recording_owner_should_reap(Some(7), 8));
        assert!(!recording_owner_should_reap(None, 7));
    }

    #[test]
    fn disconnect_releases_that_clients_held_button_and_modifier() {
        let mut holds = HashMap::new();
        apply_held_input(
            &mut holds,
            3,
            HeldInput::PointerButton(0x110),
            true,
        );
        apply_held_input(&mut holds, 3, HeldInput::Key(0xffe3), true);
        apply_held_input(
            &mut holds,
            9,
            HeldInput::PointerButton(0x111),
            true,
        );

        let cleanup = disconnect_cleanup(None, 3, &mut holds);
        assert!(!cleanup.reap_recording);
        assert_eq!(
            cleanup.held_input,
            ClientInputHold {
                buttons: BTreeSet::from([0x110]),
                keys: BTreeSet::from([0xffe3]),
            }
        );
        assert_eq!(
            cleanup.portal_releases,
            cleanup.held_input,
            "sole owner must notify the portal on disconnect"
        );
        assert_eq!(
            holds.get(&9).map(|hold| hold.buttons.clone()),
            Some(BTreeSet::from([0x111])),
            "another client's held button must survive this disconnect"
        );
        assert!(!holds.contains_key(&3));
    }

    #[test]
    fn disconnect_does_not_release_input_still_held_by_another_client() {
        let mut holds = HashMap::new();
        apply_held_input(
            &mut holds,
            3,
            HeldInput::PointerButton(0x110),
            true,
        );
        apply_held_input(&mut holds, 3, HeldInput::Key(0xffe3), true);
        apply_held_input(
            &mut holds,
            9,
            HeldInput::PointerButton(0x110),
            true,
        );
        apply_held_input(&mut holds, 9, HeldInput::Key(0xffe1), true);

        let cleanup = disconnect_cleanup(None, 3, &mut holds);
        assert_eq!(
            cleanup.portal_releases,
            ClientInputHold {
                buttons: BTreeSet::new(),
                keys: BTreeSet::from([0xffe3]),
            },
            "shared button stays down; this client's exclusive modifier is released"
        );
        assert_eq!(
            holds.get(&9).map(|hold| hold.buttons.clone()),
            Some(BTreeSet::from([0x110])),
            "surviving client must keep the shared button"
        );
    }

    #[test]
    fn matching_release_leaves_nothing_to_unwind_on_disconnect() {
        let mut holds = HashMap::new();
        apply_held_input(
            &mut holds,
            4,
            HeldInput::PointerButton(0x110),
            true,
        );
        apply_held_input(
            &mut holds,
            4,
            HeldInput::PointerButton(0x110),
            false,
        );
        apply_held_input(&mut holds, 4, HeldInput::Key(0xffe3), true);
        apply_held_input(&mut holds, 4, HeldInput::Key(0xffe3), false);

        let cleanup = disconnect_cleanup(Some(4), 4, &mut holds);
        assert!(cleanup.reap_recording);
        assert_eq!(cleanup.held_input, ClientInputHold::default());
    }
}
