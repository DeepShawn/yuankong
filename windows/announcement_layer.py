"""GDI+ painter for the Seewo-style notice band.

The band is a flat, full-bleed, opaque rectangle, so nothing here needs a layered
window: paint into a memory bitmap and ``BitBlt`` it into ``WM_PAINT``.  That is
also why the phone preview can match it exactly — there is no per-pixel alpha to
approximate.

Drawn per frame: the vertical gradient, the stroked speaker glyph, the fixed
blue lead-in, and the white body that is currently taking its turn.  Only the
body moves: it enters at the right edge, crosses the track at a constant pace,
vanishes behind the lead-in, and the next message starts from the right again.
"""
import ctypes as c
from ctypes import wintypes as w
import struct

import announcement_seewo as seewo


class PointF(c.Structure):
    _fields_ = [("x", c.c_float), ("y", c.c_float)]


class RectF(c.Structure):
    _fields_ = [("x", c.c_float), ("y", c.c_float), ("width", c.c_float), ("height", c.c_float)]


def argb(value, alpha=0xff):
    return ((alpha & 0xff) << 24) | int(value[1:], 16)


class SeewoSurface:
    """Everything the band needs, in device pixels."""

    def __init__(self, area, config):
        self.area = area
        self.config = config
        self.spec = seewo.layout(area, config)
        window = self.spec["window"]
        self.width, self.height = window["width"], window["height"]
        self.gdi = c.WinDLL("gdi32", use_last_error=True)
        self.user = c.WinDLL("user32", use_last_error=True)
        self.gp = c.WinDLL("gdiplus")
        self._bind()
        self.token = c.c_size_t()

        class Startup(c.Structure):
            _fields_ = [("version", w.UINT), ("callback", c.c_void_p),
                        ("suppress_thread", w.BOOL), ("suppress_codecs", w.BOOL)]

        if self.gp.GdiplusStartup(c.byref(self.token),
                                  c.byref(Startup(1, None, False, False)), None):
            raise OSError("GDI+ 初始化失败")

        screen = self.user.GetDC(None)
        try:
            self.dc = self.gdi.CreateCompatibleDC(screen)
            self.bitmap = self.gdi.CreateCompatibleBitmap(screen, self.width, self.height)
        finally:
            self.user.ReleaseDC(None, screen)
        if not self.dc or not self.bitmap:
            raise c.WinError(c.get_last_error())
        self.old = self.gdi.SelectObject(self.dc, self.bitmap)
        self.graphics = c.c_void_p()
        self.check(self.gp.GdipCreateFromHDC(self.dc, c.byref(self.graphics)))
        self.gp.GdipSetSmoothingMode(self.graphics, 4)
        self.gp.GdipSetTextRenderingHint(self.graphics, 3)

        self.family = c.c_void_p()
        if self.gp.GdipCreateFontFamilyFromName(config["font"], None, c.byref(self.family)):
            self.check(self.gp.GdipGetGenericFontFamilySansSerif(c.byref(self.family)))
        self.font = c.c_void_p()
        # The reference uses a regular weight: 2 px stems on an 18.7 px glyph.
        self.check(self.gp.GdipCreateFont(self.family, float(self.spec["font"]),
                                          1 if config.get("bold") else 0, 2, c.byref(self.font)))
        self.format = c.c_void_p()
        self.check(self.gp.GdipCreateStringFormat(0, 0, c.byref(self.format)))
        # One line, no wrapping, trailing spaces preserved.
        self.gp.GdipSetStringFormatFlags(self.format, 0x1000 | 0x4000)

        self.prefix = seewo.prefix_text(config)
        self.items = seewo.messages(config)
        # The body alone scrolls, so its own glyph run drives the travel.
        self.runs = [self.measure(item)[0] for item in self.items] or [1.0]
        self.durations = seewo.durations(config, self.spec["font"], self.spec["speed"],
                                         self.spec["viewport"]["width"])
        sample = self.items[0] if self.items else (self.prefix or " ")
        self.text_height = self.measure(sample)[1]
        self.index = -1

    @property
    def text(self):
        """The body being shown (the lead-in is drawn separately and never moves)."""
        return self.items[self.index] if 0 <= self.index < len(self.items) else ""

    # ------------------------------------------------------------- GDI+ setup
    def _bind(self):
        pointer = c.c_void_p
        real = c.c_float
        integer = c.c_int
        specs = {
            "GdiplusStartup": ([c.POINTER(c.c_size_t), pointer, pointer], integer),
            "GdiplusShutdown": ([c.c_size_t], None),
            "GdipCreateFromHDC": ([w.HDC, c.POINTER(pointer)], integer),
            "GdipDeleteGraphics": ([pointer], integer),
            "GdipGraphicsClear": ([pointer, w.DWORD], integer),
            "GdipSetSmoothingMode": ([pointer, integer], integer),
            "GdipSetTextRenderingHint": ([pointer, integer], integer),
            "GdipCreateSolidFill": ([w.DWORD, c.POINTER(pointer)], integer),
            "GdipDeleteBrush": ([pointer], integer),
            "GdipFillRectangle": ([pointer, pointer, real, real, real, real], integer),
            "GdipDrawLines": ([pointer, pointer, c.POINTER(PointF), integer], integer),
            "GdipDrawArc": ([pointer, pointer, real, real, real, real, real, real], integer),
            "GdipCreatePen1": ([w.DWORD, real, integer, c.POINTER(pointer)], integer),
            "GdipDeletePen": ([pointer], integer),
            "GdipCreateLineBrush": ([c.POINTER(PointF), c.POINTER(PointF), w.DWORD, w.DWORD,
                                     integer, c.POINTER(pointer)], integer),
            "GdipCreateFontFamilyFromName": ([w.LPCWSTR, pointer, c.POINTER(pointer)], integer),
            "GdipGetGenericFontFamilySansSerif": ([c.POINTER(pointer)], integer),
            "GdipDeleteFontFamily": ([pointer], integer),
            "GdipCreateFont": ([pointer, real, integer, integer, c.POINTER(pointer)], integer),
            "GdipDeleteFont": ([pointer], integer),
            "GdipCreateStringFormat": ([integer, w.WORD, c.POINTER(pointer)], integer),
            "GdipDeleteStringFormat": ([pointer], integer),
            "GdipSetStringFormatFlags": ([pointer, integer], integer),
            "GdipSetClipRect": ([pointer, real, real, real, real, integer], integer),
            "GdipResetClip": ([pointer], integer),
            "GdipDrawString": ([pointer, w.LPCWSTR, integer, pointer, c.POINTER(RectF), pointer,
                                pointer], integer),
            "GdipMeasureString": ([pointer, w.LPCWSTR, integer, pointer, c.POINTER(RectF), pointer,
                                   c.POINTER(RectF), c.POINTER(integer), c.POINTER(integer)], integer),
            "GdipSetInterpolationMode": ([pointer, integer], integer),
        }
        for name, (args, result) in specs.items():
            function = getattr(self.gp, name)
            function.argtypes = args
            function.restype = result
        self.gdi.CreateCompatibleDC.argtypes = [w.HDC]
        self.gdi.CreateCompatibleDC.restype = w.HDC
        self.gdi.CreateCompatibleBitmap.argtypes = [w.HDC, c.c_int, c.c_int]
        self.gdi.CreateCompatibleBitmap.restype = w.HBITMAP
        self.gdi.SelectObject.argtypes = [w.HDC, w.HGDIOBJ]
        self.gdi.SelectObject.restype = w.HGDIOBJ
        self.gdi.DeleteObject.argtypes = [w.HGDIOBJ]
        self.gdi.DeleteDC.argtypes = [w.HDC]
        self.gdi.BitBlt.argtypes = [w.HDC, c.c_int, c.c_int, c.c_int, c.c_int, w.HDC,
                                   c.c_int, c.c_int, w.DWORD]
        self.user.GetDC.argtypes = [w.HWND]
        self.user.GetDC.restype = w.HDC
        self.user.ReleaseDC.argtypes = [w.HWND, w.HDC]

    @staticmethod
    def check(status):
        if status:
            raise OSError("GDI+ 渲染失败 (%d)" % status)

    def measure(self, text):
        bounds = RectF()
        layout = RectF(0, 0, 1 << 20, 1 << 20)
        fitted = c.c_int()
        lines = c.c_int()
        self.check(self.gp.GdipMeasureString(self.graphics, text, -1, self.font,
                                             c.byref(layout), self.format, c.byref(bounds),
                                             c.byref(fitted), c.byref(lines)))
        return max(0.0, bounds.width), max(1.0, bounds.height)

    # ------------------------------------------------------------------ drawing
    def paint(self, elapsed):
        """Redraw the whole band and return (cycles, alpha)."""
        spec = self.spec
        window = spec["window"]
        self.dib_clear()
        self.paint_background()
        self.paint_glyph()
        cycles = self.paint_text(elapsed)
        return cycles, 1.0

    def dib_clear(self):
        self.gp.GdipGraphicsClear(self.graphics, 0xff000000 | int(seewo.BOTTOM_COLOR[1:], 16))

    def paint_background(self):
        top = PointF(0.0, 0.0)
        bottom = PointF(0.0, float(self.height))
        brush = c.c_void_p()
        self.check(self.gp.GdipCreateLineBrush(c.byref(top), c.byref(bottom),
                                               argb(seewo.TOP_COLOR), argb(seewo.BOTTOM_COLOR),
                                               0, c.byref(brush)))
        try:
            self.gp.GdipFillRectangle(self.graphics, brush, 0.0, 0.0,
                                      float(self.width), float(self.height))
        finally:
            self.gp.GdipDeleteBrush(brush)

    def paint_glyph(self):
        icon = self.spec["icon"]
        body, arcs = seewo.glyph(icon["width"])
        pen = c.c_void_p()
        self.check(self.gp.GdipCreatePen1(argb(seewo.ACCENT), float(self.spec["stroke"]), 2,
                                          c.byref(pen)))
        try:
            points = (PointF * len(body))(*[PointF(float(icon["x"] + x), float(icon["y"] + y))
                                            for x, y in body])
            self.check(self.gp.GdipDrawLines(self.graphics, pen, points, len(body)))
            for centre_x, centre_y, radius, start, sweep in arcs:
                self.check(self.gp.GdipDrawArc(self.graphics, pen,
                                               float(icon["x"] + centre_x - radius),
                                               float(icon["y"] + centre_y - radius),
                                               float(radius * 2), float(radius * 2),
                                               start, sweep))
        finally:
            self.gp.GdipDeletePen(pen)

    def paint_text(self, elapsed):
        spec = self.spec
        view = spec["viewport"]
        index, local, rounds = seewo.locate(elapsed, self.durations)
        self.index = index
        offset = seewo.offset(local, spec["speed"], view["width"])
        top = view["y"] + (view["height"] - self.text_height) / 2.0
        body = self.items[index] if index < len(self.items) else ""

        prefix = spec["prefix"]
        if prefix:
            self.draw(prefix["text"], prefix["x"], top, seewo.ACCENT)

        self.gp.GdipSetClipRect(self.graphics, float(view["x"]), float(view["y"]),
                                float(view["width"]), float(view["height"]), 0)
        try:
            self.draw(body, view["x"] + offset, top, seewo.TEXT)
        finally:
            self.gp.GdipResetClip(self.graphics)
        return rounds

    def draw(self, text, x, y, colour):
        brush = c.c_void_p()
        self.check(self.gp.GdipCreateSolidFill(argb(colour), c.byref(brush)))
        try:
            width = max(1.0, self.measure(text)[0] + 8)
            self.check(self.gp.GdipDrawString(self.graphics, text, -1, self.font,
                                              c.byref(RectF(float(x), float(y), float(width),
                                                            float(self.text_height) + 4)),
                                              self.format, brush))
        finally:
            self.gp.GdipDeleteBrush(brush)

    # -------------------------------------------------------------------- output
    def copy_to(self, dc):
        self.gdi.BitBlt(dc, 0, 0, self.width, self.height, self.dc, 0, 0, 0x00CC0020)

    def save_bmp(self, path):
        header = struct.pack("<IiiHHIIiiII", 40, self.width, -self.height, 1, 32, 0,
                             self.width * self.height * 4, 0, 0, 0, 0)
        info = c.create_string_buffer(header)
        data = c.create_string_buffer(self.width * self.height * 4)
        self.gdi.GetDIBits.argtypes = [w.HDC, w.HBITMAP, w.UINT, w.UINT, c.c_void_p,
                                       c.c_void_p, w.UINT]
        self.gdi.SelectObject(self.dc, self.old)
        try:
            if not self.gdi.GetDIBits(self.dc, self.bitmap, 0, self.height, data, info, 0):
                raise c.WinError(c.get_last_error())
        finally:
            self.gdi.SelectObject(self.dc, self.bitmap)
        with open(path, "wb") as out:
            out.write(struct.pack("<2sIHHI", b"BM", 54 + len(data), 0, 0, 54) + header + data.raw)

    @property
    def close_size(self):
        """The Seewo bar has no close button; Esc and the phone's 撤回 end it."""
        return 0

    def hit_close(self, x, y):
        return False

    def close(self):
        if self.font:
            self.gp.GdipDeleteFont(self.font)
        if self.format:
            self.gp.GdipDeleteStringFormat(self.format)
        if self.family:
            self.gp.GdipDeleteFontFamily(self.family)
        if self.graphics:
            self.gp.GdipDeleteGraphics(self.graphics)
        if self.dc and self.old:
            self.gdi.SelectObject(self.dc, self.old)
        if self.bitmap:
            self.gdi.DeleteObject(self.bitmap)
        if self.dc:
            self.gdi.DeleteDC(self.dc)
        self.gp.GdiplusShutdown(self.token)
