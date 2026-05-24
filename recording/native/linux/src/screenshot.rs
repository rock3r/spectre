//! One-shot screenshot orchestration for Linux capture sources.

use crate::gst::{build_pipewire_png_argv, build_x11_png_argv, X11CaptureTarget};
use crate::portal::{self, DEFAULT_RESPONSE_TIMEOUT, SOURCE_TYPE_WINDOW};
use crate::protocol::{CaptureBackend, CaptureTarget, Event, ScreenshotCommand, SourceType};
use anyhow::{anyhow, Context, Result};
use nix::fcntl::{fcntl, FcntlArg, FdFlag};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::io::RawFd;
use std::path::PathBuf;
use std::process::{Command, ExitStatus, Stdio};
use std::sync::mpsc;
use std::time::Duration;

const SCREENSHOT_TIMEOUT: Duration = Duration::from_secs(30);

pub fn run(command: ScreenshotCommand, events: mpsc::Sender<Event>) -> Result<()> {
    let output = PathBuf::from(&command.output);
    match command.backend {
        CaptureBackend::X11 => {
            let argv = build_x11_screenshot_argv(&command, &output)?;
            run_gst_oneshot(&argv)
                .with_context(|| format!("running screenshot pipeline: {argv:?}"))?;
        }
        CaptureBackend::WaylandPortal => {
            run_wayland_screenshot(&command, &output)?;
        }
    };
    let output_size_bytes = std::fs::metadata(&command.output)
        .with_context(|| format!("{:?} screenshot output was not written", command.backend))?
        .len();
    let _ = events.send(Event::ScreenshotSaved { output_size_bytes });
    Ok(())
}

fn build_x11_screenshot_argv(command: &ScreenshotCommand, output: &PathBuf) -> Result<Vec<String>> {
    let capture_cursor = matches!(command.cursor_mode, crate::protocol::CursorMode::Embedded);
    let target = match command.target {
        CaptureTarget::Region => X11CaptureTarget::Region {
            display_name: command.display_name.clone(),
            region: command.region,
        },
        CaptureTarget::Window => X11CaptureTarget::Window {
            display_name: command.display_name.clone(),
            title: command
                .window_title
                .clone()
                .ok_or_else(|| anyhow!("X11 window screenshot requires window_title"))?,
        },
    };
    build_x11_png_argv(target, capture_cursor, output)
}

fn run_wayland_screenshot(command: &ScreenshotCommand, output: &PathBuf) -> Result<()> {
    let source_types = if command.source_types.is_empty() {
        match command.target {
            CaptureTarget::Region => vec![SourceType::Monitor],
            CaptureTarget::Window => vec![SourceType::Window],
        }
    } else {
        command.source_types.clone()
    };
    let session = portal::open_screen_cast_session(
        &source_types,
        command.cursor_mode,
        DEFAULT_RESPONSE_TIMEOUT,
    )
    .context("portal screenshot handshake")?;
    ensure_window_source_if_requested(command.target, session.stream.source_type)?;
    clear_cloexec(session.pipewire_fd).context("allowing PipeWire FD inheritance")?;
    let stream_relative_region = crate::protocol::Region {
        x: command.region.x - session.stream.position.0,
        y: command.region.y - session.stream.position.1,
        width: command.region.width,
        height: command.region.height,
    };
    let argv = build_pipewire_png_argv(
        session.stream.node_id,
        session.pipewire_fd,
        stream_relative_region,
        session.stream.size,
        output,
    )?;
    run_gst_oneshot(&argv)
        .with_context(|| format!("running Wayland screenshot pipeline: {argv:?}"))?;
    drop(session);
    Ok(())
}

fn ensure_window_source_if_requested(
    target: CaptureTarget,
    source_type: Option<u32>,
) -> Result<()> {
    if target == CaptureTarget::Window && source_type != Some(SOURCE_TYPE_WINDOW) {
        anyhow::bail!(
            "Wayland portal screenshot requested a window source, but the compositor returned \
             source_type={source_type:?}. Spectre cannot window-crop a monitor-source stream \
             safely; use an explicit region screenshot or a compositor/version that supports \
             portal window sources."
        );
    }
    Ok(())
}

fn clear_cloexec(fd: RawFd) -> Result<()> {
    let mut flags =
        FdFlag::from_bits_truncate(fcntl(fd, FcntlArg::F_GETFD).context("fcntl(F_GETFD)")?);
    flags.remove(FdFlag::FD_CLOEXEC);
    fcntl(fd, FcntlArg::F_SETFD(flags)).context("fcntl(F_SETFD, !CLOEXEC)")?;
    Ok(())
}

fn run_gst_oneshot(argv: &[String]) -> Result<()> {
    let gst_stdout_target: OwnedFd = unsafe {
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
    let exit = child
        .wait_timeout_like(SCREENSHOT_TIMEOUT)
        .context("waiting for screenshot gst-launch to exit")?;
    let Some(exit) = exit else {
        let _ = child.kill();
        let _ = child.wait();
        anyhow::bail!(
            "gst-launch did not exit within {:?} while capturing screenshot",
            SCREENSHOT_TIMEOUT
        );
    };
    if !exit.success() {
        anyhow::bail!("gst-launch screenshot pipeline exited with status {exit:?}");
    }
    Ok(())
}

trait ChildWaitTimeout {
    fn wait_timeout_like(&mut self, timeout: Duration) -> Result<Option<ExitStatus>>;
}

impl ChildWaitTimeout for std::process::Child {
    fn wait_timeout_like(&mut self, timeout: Duration) -> Result<Option<ExitStatus>> {
        let start = std::time::Instant::now();
        loop {
            if let Some(exit) = self.try_wait()? {
                return Ok(Some(exit));
            }
            if start.elapsed() > timeout {
                return Ok(None);
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}
