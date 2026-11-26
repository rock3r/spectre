"""Run under Xvfb with the built spectre-wayland-helper, Tk, xprop and GStreamer."""
import json
import os
from pathlib import Path
import ctypes as ct
import subprocess
import sys
import tempfile
import tkinter as tk
from uuid import uuid4


# A simple Xlib client owns each colored drawable directly (no decoration child).
x = ct.CDLL("libX11.so.6")
x.XOpenDisplay.restype = ct.c_void_p
display = ct.c_void_p(x.XOpenDisplay(None))


def window(title, left, color):
    xid = x.XCreateSimpleWindow(display, x.XDefaultRootWindow(display), left, 42,
                                120, 90, 0, 0, color)
    x.XStoreName(display, xid, title.encode())
    x.XMapWindow(display, xid)
    x.XSync(display, 0)
    subprocess.run(["xprop", "-id", hex(xid), "-f", "_NET_WM_PID", "32c", "-set",
                    "_NET_WM_PID", str(os.getpid())], check=True)
    return xid


def main():
    assert display.value, "An X11 display is required"
    if sys.argv[1] == "--fixture":
        window(sys.argv[2], 240, 0x0000ff)
        print("READY", flush=True)
        import signal
        signal.pause()
        return
    title = f"Spectre {uuid4()}"
    xid = window(title, 42, 0xff0000)
    root = tk.Tk()
    root.withdraw()
    frame = dict(x=42, y=42, width=120, height=90)
    peer = subprocess.Popen([sys.executable, __file__, "--fixture", title],
                            stdout=subprocess.PIPE, text=True)
    try:
        assert peer.stdout.readline().strip() == "READY"
        with tempfile.TemporaryDirectory() as directory:
            def capture(selector, pid, expected, target="window", region=frame):
                path = Path(directory) / "still.png"
                path.unlink(missing_ok=True)
                command = dict(command="screenshot", backend="x11", target=target,
                               cursor_mode="hidden", region=region, output=str(path), **selector)
                if pid is not None:
                    command["window_owner_pid"] = pid
                result = subprocess.run([sys.argv[1]], input=json.dumps(command) + "\n",
                                        text=True, capture_output=True, check=True, timeout=15)
                event = json.loads(result.stdout)
                if isinstance(expected, str):
                    assert event["event"] == "error" and expected in event["message"], event
                    assert not path.exists()
                else:
                    assert event["event"] == "screenshot_saved", event
                    image = tk.PhotoImage(file=str(path))
                    assert (image.width(), image.height()) == (region["width"], region["height"])
                    assert image.get(image.width() // 2, image.height() // 2) == expected, (image.get(image.width() // 2, image.height() // 2), expected, result.stderr)

            by_title, by_frame = dict(window_title=title), dict(window_frame=frame)
            red, blue = (255, 0, 0), (0, 0, 255)
            capture(by_title, os.getpid(), red)
            capture(by_title, peer.pid, blue)
            capture(dict(window_title=" " + title), os.getpid(), "not found")
            capture({}, None, red, "region", dict(x=52, y=52, width=64, height=44))
            capture({}, None, "fully inside", "region",
                    dict(x=root.winfo_screenwidth() - 2, y=0, width=3, height=2))
            twin = window(title, 420, 0x00ff00)
            capture(by_title, os.getpid(), "cannot select")
            capture(by_frame, os.getpid(), red)
            x.XMoveWindow(display, twin, 42, 42)
            x.XSync(display, 0)
            capture(by_frame, os.getpid(), "cannot tell")
            x.XUnmapWindow(display, twin)
            x.XSync(display, 0)
            capture(by_title, os.getpid(), red)
            capture(by_frame, os.getpid(), red)
            for client in (xid, twin):
                name = subprocess.check_output(["xprop", "-id", hex(client), "WM_NAME"], text=True)
                assert title in name, name
            capture(by_title, None, "owner_pid")
            capture(by_frame, None, "owner_pid")
            subprocess.run(["xprop", "-id", hex(xid), "-remove", "_NET_WM_PID"], check=True)
            capture(by_title, os.getpid(), "not found")
            capture(by_frame, os.getpid(), "not found")
    finally:
        peer.terminate()
        peer.wait(timeout=5)
        root.destroy()


if __name__ == "__main__":
    main()
