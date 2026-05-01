//! End-to-end orchestration for the Wayland recording flow.
//!
//! Sequence:
//!  1. Open a portal screen-cast session ([`portal::open_screen_cast_session`]).
//!  2. Clear `O_CLOEXEC` on the PipeWire FD so it survives `execve(2)`.
//!  3. Build the `gst-launch-1.0` argv (region cropping, encoder choice, output path) and
//!     spawn it with the FD inherited.
//!  4. Spawn a stdin-watcher thread that converts the JVM's `Stop` command into a SIGTERM
//!     to gst-launch (the `-e` flag in the argv turns SIGTERM into End-Of-Stream — clean
//!     mux finalisation, valid mp4).
//!  5. Wait for gst-launch to exit (either via the SIGTERM-EOS path or because it crashed).
//!  6. Emit `Stopped` (with output file size) or `Error` (with cause), then return.

use crate::gst::build_pipewire_argv;
use crate::portal::{self, ScreenCastSession, DEFAULT_RESPONSE_TIMEOUT};
use crate::protocol::{Event, StartCommand};
use anyhow::{anyhow, Context, Result};
use nix::fcntl::{fcntl, FcntlArg, FdFlag};
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use std::io::BufRead;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

/// Hard upper bound on time we'll wait for gst-launch to drain its EOS and exit cleanly
/// after we send SIGTERM. mp4mux's faststart-rewrite pass can take a beat on long
/// recordings, but 30s is comfortably above any realistic capture finalisation time.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(30);

pub fn run(start: StartCommand, events: mpsc::Sender<Event>) -> Result<()> {
    // 1. Portal handshake — first call within a session pops the user's "share your screen"
    // dialog. Subsequent calls reuse the grant (transient persist mode).
    let session = portal::open_screen_cast_session(
        &start.source_types,
        start.cursor_mode,
        DEFAULT_RESPONSE_TIMEOUT,
    )
    .context("portal handshake")?;

    // 2. Clear O_CLOEXEC on the PipeWire FD. Default-CLOEXEC FDs (the kernel sets it on
    // SCM_RIGHTS-delivered FDs for safety) wouldn't survive ProcessBuilder's exec call,
    // and gst-launch would silently fall back to fd=-1 / default user socket (the failure
    // mode we observed during the JNR-POSIX bake-off, #80 comments).
    let mut flags = FdFlag::from_bits_truncate(
        fcntl(session.pipewire_fd, FcntlArg::F_GETFD).context("fcntl(F_GETFD) on PipeWire FD")?,
    );
    flags.remove(FdFlag::FD_CLOEXEC);
    fcntl(session.pipewire_fd, FcntlArg::F_SETFD(flags))
        .context("fcntl(F_SETFD, !CLOEXEC) on PipeWire FD")?;

    // 3. Translate the AWT-screen region into stream-relative coordinates. The portal hands
    // us a stream covering one monitor (or the user-selected window); its top-left is in
    // monitor coords, which can be non-(0,0) on multi-monitor setups. Our AWT-side region is
    // already relative to the screen origin, so subtract the stream position.
    let stream_relative_region = crate::protocol::Region {
        x: start.region.x - session.stream.position.0,
        y: start.region.y - session.stream.position.1,
        width: start.region.width,
        height: start.region.height,
    };

    let argv = build_pipewire_argv(
        session.stream.node_id,
        session.pipewire_fd,
        stream_relative_region,
        session.stream.size,
        start.frame_rate,
        matches!(start.cursor_mode, crate::protocol::CursorMode::Embedded),
        &PathBuf::from(&start.output),
        &start.codec,
    )
    .context("building gst-launch argv")?;

    eprintln!(
        "[helper] portal handshake OK: node={} fd={} stream={:?} position={:?} \
         region-relative={:?}",
        session.stream.node_id,
        session.pipewire_fd,
        session.stream.size,
        session.stream.position,
        (
            stream_relative_region.x,
            stream_relative_region.y,
            stream_relative_region.width,
            stream_relative_region.height,
        )
    );
    eprintln!("[helper] spawning: {argv:?}");

    // 4. Spawn gst-launch. We don't need to do anything FD-special here — Command inherits
    // all parent FDs by default, and we already cleared CLOEXEC on the PipeWire FD above.
    // stdout/stderr go to the parent's stderr (forwarded to the JVM via the smoke runner)
    // so encoder warnings + errors are visible without an extra log-file dance.
    let mut child = Command::new(&argv[0])
        .args(&argv[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .spawn()
        .with_context(|| format!("spawning gst-launch from argv: {argv:?}"))?;
    let gst_pid = child.id();
    let started_at = Instant::now();

    // Emit the `started` event so the JVM's start() can return its RecordingHandle.
    let _ = events.send(Event::Started {
        node_id: session.stream.node_id,
        stream_size: [session.stream.size.0, session.stream.size.1],
        stream_position: [session.stream.position.0, session.stream.position.1],
        gst_pid,
    });

    // 5. Stop watcher: spawn a thread that reads the next JSON line from stdin and, on
    // `Command::Stop`, sends SIGTERM to gst-launch. Any other command (or EOF before Stop)
    // is treated as an abort signal — same SIGTERM, same finalisation path.
    let stop_thread = thread::Builder::new()
        .name("stop-watcher".into())
        .spawn(move || {
            let stdin = std::io::stdin();
            let mut line = String::new();
            // Block on stdin. When the JVM writes `{"command":"stop"}\n`, we trigger SIGTERM.
            // EOF on stdin is also treated as Stop — the JVM hung up, finalise what we have.
            let _ = stdin.lock().read_line(&mut line);
            // gst-launch with `-e` interprets SIGTERM as End-Of-Stream; the pipeline drains,
            // mp4mux finalises, exit code 0. Without `-e`, SIGTERM kills it dead and the
            // mp4 is unfinalised. The argv builder always sets `-e` (see gst.rs).
            let _ = kill(Pid::from_raw(gst_pid as i32), Signal::SIGTERM);
        })
        .context("spawning stop-watcher thread")?;

    // 6. Wait for gst-launch. The parent thread blocks here; the stop-watcher takes care of
    // sending SIGTERM when the JVM asks for it. Hard deadline: SHUTDOWN_GRACE after the
    // stop-watcher has sent SIGTERM. Without a deadline, a stuck mp4mux drain would leave
    // us hung forever; with one, we escalate to SIGKILL.
    let mut exit_status: Option<std::process::ExitStatus> = None;
    let escalate_at = Instant::now() + SHUTDOWN_GRACE;
    loop {
        match child.try_wait().context("polling gst-launch exit")? {
            Some(status) => {
                exit_status = Some(status);
                break;
            }
            None => {
                if Instant::now() > escalate_at {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(anyhow!(
                        "gst-launch did not exit within {:?} of shutdown — output at {} is in \
                         an undefined state.",
                        SHUTDOWN_GRACE,
                        start.output,
                    ));
                }
                thread::sleep(Duration::from_millis(50));
            }
        }
    }
    let _ = stop_thread.join();
    let exit = exit_status.unwrap();
    let elapsed = started_at.elapsed();

    // 7. Surface the outcome. Exit 0 = clean EOS via SIGTERM-with-`-e`. Other exit codes
    // mean gst-launch crashed (encoder error, plugin missing, PipeWire daemon bounce). We
    // accept SIGTERM exit codes 143 (POSIX 128+15) and -15 (Rust's signal-encoded exit) as
    // expected since we sent the signal ourselves.
    let success = exit.success()
        || exit.code() == Some(0)
        || exit.code() == Some(143)
        || exit.code() == Some(-15);
    if !success {
        let _ = events.send(Event::Error {
            kind: "EncoderCrashed".into(),
            message: format!(
                "gst-launch exited with status {exit:?} after {elapsed:?}. Common causes: \
                 PipeWire daemon disconnect, encoder error (out of memory, codec missing), \
                 disk full, output path on a read-only filesystem. Check the gst-launch log \
                 for details."
            ),
        });
        return Ok(()); // Don't double-report — the Error event is the canonical signal.
    }

    let output_size_bytes = std::fs::metadata(&start.output).map(|m| m.len()).unwrap_or(0);
    let _ = events.send(Event::Stopped { output_size_bytes });

    // session is dropped here — drops the D-Bus connection, releases the portal grant.
    drop(session);
    Ok(())
}
