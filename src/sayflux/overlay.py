"""Recording indicator: a small pill at the bottom center of the screen.

The window runs in its own process (`python -m sayflux.overlay`) so a GUI problem
can never break dictation. The daemon drives it with one command per line on stdin:

    show            show the pill, bars follow the mic
    level <0..1>    current mic loudness
    processing      switch to the "working" animation
    hide            hide the pill

It exits when stdin closes, i.e. when the daemon stops.
"""

import logging
import math
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

log = logging.getLogger(__name__)

# Sizes in logical pixels; multiplied by the screen scale factor.
WIDTH, HEIGHT, BOTTOM_MARGIN = 132, 36, 64
BARS = 15
BAR_WIDTH, BAR_GAP = 3, 3
BG = "#18181b"
BAR = "#f4f4f5"
DOT = "#ef4444"
WORKING = "#818cf8"
ALPHA = 0.94
FRAME_MS = 16


# --- daemon side -------------------------------------------------------------

class OverlayClient:
    """Starts the overlay process and sends it commands. Never raises."""

    MAX_STARTS = 3

    def __init__(self):
        self._proc: subprocess.Popen | None = None
        self._starts = 0
        self._lock = threading.Lock()

    def _spawn(self) -> None:
        exe = sys.executable
        kwargs: dict = {}
        if sys.platform == "win32":
            pythonw = Path(exe).with_name("pythonw.exe")
            if pythonw.exists():
                exe = str(pythonw)
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        self._starts += 1
        self._proc = subprocess.Popen(
            [exe, "-m", "sayflux.overlay"],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            text=True, bufsize=1, **kwargs,
        )

    def send(self, cmd: str) -> None:
        with self._lock:
            if self._proc is not None and self._proc.poll() is not None:
                err = self._proc.stderr.read().strip() if self._proc.stderr else ""
                log.warning("Overlay exited (code %s): %s", self._proc.returncode, err[-500:])
                self._proc = None
            if self._proc is None:
                if self._starts >= self.MAX_STARTS:
                    return
                try:
                    self._spawn()
                except OSError as e:
                    log.warning("Overlay failed to start: %s", e)
                    self._starts = self.MAX_STARTS
                    return
            try:
                self._proc.stdin.write(cmd + "\n")
            except (BrokenPipeError, OSError, ValueError):
                pass  # picked up as an exit on the next send

    def close(self) -> None:
        with self._lock:
            if self._proc is not None:
                try:
                    self._proc.stdin.close()
                    self._proc.wait(timeout=2)
                except (OSError, subprocess.TimeoutExpired):
                    self._proc.kill()
                self._proc = None


# --- overlay process ---------------------------------------------------------

def _round_corners_x11(window_id: int, w: int, h: int, r: int) -> None:
    """Clip the window to a rounded rectangle with the X Shape extension."""
    import ctypes
    import ctypes.util

    x11 = ctypes.CDLL(ctypes.util.find_library("X11"))
    xext = ctypes.CDLL(ctypes.util.find_library("Xext"))

    class XRectangle(ctypes.Structure):
        _fields_ = [("x", ctypes.c_short), ("y", ctypes.c_short),
                    ("width", ctypes.c_ushort), ("height", ctypes.c_ushort)]

    rows = []
    for y in range(h):
        dy = max(r - y - 0.5, y + 0.5 - (h - r), 0.0)
        inset = round(r - math.sqrt(max(r * r - dy * dy, 0.0))) if dy > 0 else 0
        rows.append(XRectangle(inset, y, max(0, w - 2 * inset), 1))
    rects = (XRectangle * len(rows))(*rows)

    V, W = ctypes.c_void_p, ctypes.c_ulong
    x11.XOpenDisplay.restype = V
    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XQueryTree.argtypes = [V, W, ctypes.POINTER(W), ctypes.POINTER(W),
                               ctypes.POINTER(ctypes.POINTER(W)), ctypes.POINTER(ctypes.c_uint)]
    x11.XFree.argtypes = [V]
    x11.XFlush.argtypes = [V]
    x11.XCloseDisplay.argtypes = [V]
    xext.XShapeCombineRectangles.argtypes = [
        V, W, ctypes.c_int, ctypes.c_int, ctypes.c_int,
        ctypes.POINTER(XRectangle), ctypes.c_int, ctypes.c_int, ctypes.c_int,
    ]
    dpy = x11.XOpenDisplay(None)
    if not dpy:
        raise OSError("can't open X display")
    try:
        # Tk wraps each toplevel in a parent window; the one directly under the root
        # window is what the compositor draws, so that's the one to clip.
        top = window_id
        while True:
            root, parent = W(), W()
            children, n = ctypes.POINTER(W)(), ctypes.c_uint()
            if not x11.XQueryTree(dpy, top, ctypes.byref(root), ctypes.byref(parent),
                                  ctypes.byref(children), ctypes.byref(n)):
                raise OSError("XQueryTree failed")
            if children:
                x11.XFree(children)
            if parent.value in (0, root.value):
                break
            top = parent.value
        SHAPE_BOUNDING, SHAPE_SET, UNSORTED = 0, 0, 0
        for win in {top, window_id}:
            xext.XShapeCombineRectangles(dpy, win, SHAPE_BOUNDING, 0, 0, rects, len(rows),
                                         SHAPE_SET, UNSORTED)
        x11.XFlush(dpy)
    finally:
        x11.XCloseDisplay(dpy)


def _no_activate_windows(window_id: int) -> None:
    """Windows: never take focus from the app being typed into."""
    import ctypes

    GWL_EXSTYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW = -20, 0x08000000, 0x00000080
    user32 = ctypes.windll.user32
    style = user32.GetWindowLongW(window_id, GWL_EXSTYLE)
    user32.SetWindowLongW(window_id, GWL_EXSTYLE, style | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW)


class Overlay:
    def __init__(self):
        import tkinter as tk

        self.root = root = tk.Tk()
        root.withdraw()
        root.overrideredirect(True)
        root.attributes("-topmost", True)

        # tk scaling is pixels per point; 96 dpi = 1.333. Use it to size for HiDPI.
        self.s = s = max(1.0, float(root.tk.call("tk", "scaling")) / (96 / 72))
        self.w, self.h = round(WIDTH * s), round(HEIGHT * s)

        # Corners: Linux clips the window itself; Windows and macOS use a transparent
        # background around a drawn pill.
        canvas_bg = BG
        if sys.platform == "win32":
            canvas_bg = "#010203"
            root.attributes("-transparentcolor", canvas_bg)
        elif sys.platform == "darwin":
            canvas_bg = "systemTransparent"
            root.attributes("-transparent", True)
            root.config(bg="systemTransparent")
        self.canvas = c = tk.Canvas(
            root, width=self.w, height=self.h, bg=canvas_bg, highlightthickness=0, bd=0
        )
        c.pack()
        d = self.h
        c.create_oval(0, 0, d, d, fill=BG, outline=BG)
        c.create_oval(self.w - d, 0, self.w, d, fill=BG, outline=BG)
        c.create_rectangle(d / 2, 0, self.w - d / 2, d, fill=BG, outline=BG)

        # Red recording dot on the left, bars centered in the remaining space.
        dot_r = 4 * s
        self.dot_x = d / 2 + 2 * s
        self.dot = c.create_oval(self.dot_x - dot_r, d / 2 - dot_r, self.dot_x + dot_r, d / 2 + dot_r,
                                 fill=DOT, outline="")
        span = BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP
        left = self.dot_x + dot_r + (self.w - d / 2 - (self.dot_x + dot_r) - span * s) / 2
        self.bar_x = [left + (i * (BAR_WIDTH + BAR_GAP) + BAR_WIDTH / 2) * s for i in range(BARS)]
        self.bars = [
            c.create_line(x, d / 2, x, d / 2, width=BAR_WIDTH * s, fill=BAR, capstyle="round")
            for x in self.bar_x
        ]

        # New levels enter at the center and ripple outward.
        self.history = [0.0] * (BARS // 2 + 1)
        self.heights = [0.0] * BARS
        self.mode = "hidden"
        self.shaped = False
        self.started = time.monotonic()
        self.commands: queue.Queue[str] = queue.Queue()
        threading.Thread(target=self._read_stdin, daemon=True).start()

    def _read_stdin(self) -> None:
        for line in sys.stdin:
            self.commands.put(line.strip())
        self.commands.put("quit")

    def _show(self) -> None:
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        x, y = (sw - self.w) // 2, sh - self.h - round(BOTTOM_MARGIN * self.s)
        self.root.geometry(f"{self.w}x{self.h}+{x}+{y}")
        self.history = [0.0] * len(self.history)
        if not self.shaped:
            # Shape before the first map so the window never flashes as a rectangle.
            self.shaped = True
            self.root.update_idletasks()
            try:
                if sys.platform.startswith("linux"):
                    _round_corners_x11(self.root.winfo_id(), self.w, self.h, self.h // 2)
                elif sys.platform == "win32":
                    _no_activate_windows(int(self.root.wm_frame(), 16))
            except Exception as e:  # cosmetic; keep going with a square window
                print(f"overlay: window shaping failed: {e}", file=sys.stderr)
        self.root.deiconify()
        self.root.lift()
        self.root.update_idletasks()
        self.root.attributes("-alpha", ALPHA)  # only sticks once the window is mapped

    def _handle(self, cmd: str) -> bool:
        if cmd == "quit":
            return False
        if cmd == "show":
            self.mode = "recording"
            self.canvas.itemconfigure(self.dot, state="normal")
            for b in self.bars:
                self.canvas.itemconfigure(b, fill=BAR)
            self._show()
        elif cmd == "processing":
            self.mode = "processing"
            self.started = time.monotonic()
            self.canvas.itemconfigure(self.dot, state="hidden")
            for b in self.bars:
                self.canvas.itemconfigure(b, fill=WORKING)
        elif cmd == "hide":
            self.mode = "hidden"
            self.root.withdraw()
        elif cmd.startswith("level ") and self.mode == "recording":
            try:
                level = min(1.0, max(0.0, float(cmd.split()[1])))
            except ValueError:
                return True
            self.history = [level] + self.history[:-1]
        return True

    def _tick(self) -> None:
        try:
            while True:
                if not self._handle(self.commands.get_nowait()):
                    self.root.destroy()
                    return
        except queue.Empty:
            pass

        if self.mode != "hidden":
            mid = BARS // 2
            t = time.monotonic()
            for i in range(BARS):
                dist = abs(i - mid)
                if self.mode == "recording":
                    # Taper toward the edges so the shape reads as a voice wave.
                    target = self.history[dist] * (1 - 0.45 * dist / mid)
                    self.heights[i] += (target - self.heights[i]) * 0.45
                else:
                    phase = t * 5 - dist * 0.7
                    self.heights[i] = 0.18 + 0.22 * (0.5 + 0.5 * math.sin(phase))
            if self.mode == "recording":
                pulse = 0.5 + 0.5 * math.sin(t * 4)
                r = (3.2 + 1.2 * pulse) * self.s
                cy = self.h / 2
                self.canvas.coords(self.dot, self.dot_x - r, cy - r, self.dot_x + r, cy + r)
            usable = (self.h - 14 * self.s) / 2
            for i, bar in enumerate(self.bars):
                half = max(0.5 * self.s, usable * self.heights[i])
                x = self.bar_x[i]
                self.canvas.coords(bar, x, self.h / 2 - half, x, self.h / 2 + half)
        self.root.after(FRAME_MS, self._tick)

    def run(self) -> None:
        self.root.after(FRAME_MS, self._tick)
        self.root.mainloop()


def main() -> None:
    Overlay().run()


if __name__ == "__main__":
    main()
