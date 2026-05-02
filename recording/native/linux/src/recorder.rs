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
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd};
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

    // 4. Spawn gst-launch.
    //
    // FD inheritance is the whole point of running gst-launch as a child of this helper:
    // we already cleared CLOEXEC on the PipeWire FD above, and Command inherits parent FDs
    // by default, so `pipewiresrc fd=N` in the argv finds the granted node.
    //
    // stdio routing is more subtle than "just inherit". gst-launch writes its progress
    // narrative to stdout — `Setting pipeline to PAUSED ...`, `Setting pipeline to PLAYING
    // ...`, `Got EOS from element ...`, etc. If we let that flow on the inherited stdout
    // it lands on _our_ stdout pipe, which is the newline-delimited JSON channel to the
    // JVM. The JVM's deserializer chokes on the first non-`{` line and bails the whole
    // recording — observed empirically against gst-launch 1.20 on Ubuntu 22.04 mutter.
    //
    // Fix: redirect the child's stdout to a duplicate of _our_ stderr fd, so gst's progress
    // output flows to helper stderr instead. Gradle's smoke runner surfaces helper stderr
    // verbatim, so we keep the diagnostic output visible without polluting the protocol
    // channel. Child stderr stays as plain `inherit()` — gst's actual error messages also
    // come through the same way.
    let gst_stdout_target: OwnedFd = unsafe {
        // dup() returns a brand-new fd referring to the same open file description as our
        // stderr. Passing it via `Stdio::from(OwnedFd)` lets std's spawn machinery dup2()
        // it onto fd 1 in the child and then close the original — exactly what we want.
        let raw = nix::unistd::dup(std::io::stderr().as_raw_fd())
            .context("dup(stderr) so gst-launch stdout can redirect to helper stderr")?;
        OwnedFd::from_raw_fd(raw)
    };
    let mut child = Command::new(&argv[0])
        .args(&argv[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::from(gst_stdout_target))
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
    // `Command::Stop`, sends SIGINT to gst-launch. Any other command (or EOF before Stop)
    // is treated as an abort signal — same SIGINT, same finalisation path.
    //
    // Why SIGINT and not SIGTERM: gst-launch's `-e` flag only catches SIGINT (the Ctrl-C
    // path) and routes it through the EOS-finalisation handler. SIGTERM is force-quit and
    // bypasses `-e` entirely — empirically observed on gst-launch 1.20 (Ubuntu 22.04):
    // SIGTERM exits the process with `unix_wait_status(15)` and leaves the mp4 file at
    // 0 bytes because mp4mux never gets to write the moov atom (which `faststart=true`
    // buffers in memory and emits only on EOS). SIGINT is the documented graceful-stop
    // path for `-e`; gst-launch handles it, sends EOS into the pipeline, mp4mux drains
    // and finalises, and we get a valid file.
    let stop_thread = thread::Builder::new()
        .name("stop-watcher".into())
        .spawn(move || {
            let stdin = std::io::stdin();
            let mut line = String::new();
            // Block on stdin. When the JVM writes `{"command":"stop"}\n`, we trigger SIGINT.
            // EOF on stdin is also treated as Stop — the JVM hung up, finalise what we have.
            let _ = stdin.lock().read_line(&mut line);
            let _ = kill(Pid::from_raw(gst_pid as i32), Signal::SIGINT);
        })
        .context("spawning stop-watcher thread")?;

    // 6. Wait for gst-launch. The parent thread blocks here; the stop-watcher takes care of
    // sending SIGINT when the JVM asks for it. Hard deadline: SHUTDOWN_GRACE after the
    // stop-watcher has sent SIGINT. Without a deadline, a stuck mp4mux drain would leave
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

    // 7. Surface the outcome. The expected happy-path is exit code 0: gst-launch caught our
    // SIGINT (because `-e` is set), sent EOS into the pipeline, drained, finalised the mp4,
    // exited cleanly. Any other code means something went wrong (encoder error, plugin
    // missing, PipeWire daemon bounce, the user pulled the rug). We do NOT special-case
    // signal-killed status: with `-e` + SIGINT the EOS path always exits cleanly, and a
    // signal-kill exit means the EOS-finalisation didn't happen (so the mp4 is broken).
    let success = exit.success() || exit.code() == Some(0);
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
