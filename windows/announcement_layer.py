"""Per-pixel-alpha surface for the Seewo-style bar.

The classic surface paints an opaque bitmap and lets Windows apply one constant
alpha to the whole window.  That cannot express a soft shadow or anti-aliased
rounded corners, so the Seewo style renders into a 32-bpp top-down DIB and
presents it with ``UpdateLayeredWindow``:

* the static layer (shadow, gradient fill, icon badge, prefix, divider, close
  glyph) is built once as premultiplied BGRA plus an alpha mask;
* each frame copies that layer, lets GDI+ draw only the scrolling text, then
  restores the alpha channel from the mask — text is never drawn outside the
  bar, so the mask stays valid and the copy is a single slice assignment.

Nothing here downloads or embeds third-party artwork: the speaker glyph and the
close cross are drawn from the coordinates in ``announcement_seewo``.
"""
import ctypes as c
from ctypes import wintypes as w
import struct

import announcement_seewo as seewo


class PointF(c.Structure):
    _fields_ = [("x", c.c_float), ("y", c.c_float)]


class RectF(c.Structure):
    _fields_ = [("x", c.c_float), ("y", c.c_float), ("width", c.c_float), ("height", c.c_float)]


class BITMAPINFOHEADER(c.Structure):
    _fields_ = [("biSize", w.DWORD), ("biWidth", c.c_long), ("biHeight", c.c_long),
                ("biPlanes", w.WORD), ("biBitCount", w.WORD), ("biCompression", w.DWORD),
                ("biSizeImage", w.DWORD), ("biXPelsPerMeter", c.c_long),
                ("biYPelsPerMeter", c.c_long), ("biClrUsed", w.DWORD), ("biClrImportant", w.DWORD)]


class BITMAPINFO(c.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", w.DWORD * 3)]


class POINT(c.Structure):
    _fields_ = [("x", c.c_long), ("y", c.c_long)]


class SIZE(c.Structure):
    _fields_ = [("cx", c.c_long), ("cy", c.c_long)]


class BLENDFUNCTION(c.Structure):
    _fields_ = [("BlendOp", c.c_ubyte), ("BlendFlags", c.c_ubyte),
                ("SourceConstantAlpha", c.c_ubyte), ("AlphaFormat", c.c_ubyte)]


def argb(value, alpha=0xff):
    """#RRGGBB + alpha -> GDI+ ARGB word."""
    return ((alpha & 0xff) << 24) | int(value[1:], 16)


def coverage(x, y, width, height, radius, supersample=4):
    """Coverage of a rounded rectangle for one pixel (0..255)."""
    if radius <= 0:
        return 255
    if (radius <= x <= width - radius) or (radius <= y <= height - radius):
        return 255
    centre_x = min(max(x, radius), width - radius)
    centre_y = min(max(y, radius), height - radius)
    hits = 0
    for sy in range(supersample):
        for sx in range(supersample):
            point_x = x + (sx + 0.5) / supersample
            point_y = y + (sy + 0.5) / supersample
            if (point_x - centre_x) ** 2 + (point_y - centre_y) ** 2 <= radius * radius:
                hits += 1
    return int(round(255 * hits / (supersample * supersample)))


class Dib:
    """A 32-bpp top-down DIB plus the memory DC that GDI+ draws into."""

    def __init__(self, width, height):
        self.width, self.height = int(width), int(height)
        self.gdi = c.WinDLL("gdi32", use_last_error=True)
        self.gdi.CreateCompatibleDC.argtypes = [w.HDC]
        self.gdi.CreateCompatibleDC.restype = w.HDC
        self.gdi.CreateDIBSection.argtypes = [w.HDC, c.POINTER(BITMAPINFO), w.UINT,
                                              c.POINTER(c.c_void_p), w.HANDLE, w.DWORD]
        self.gdi.CreateDIBSection.restype = w.HBITMAP
        self.gdi.SelectObject.argtypes = [w.HDC, w.HGDIOBJ]
        self.gdi.SelectObject.restype = w.HGDIOBJ
        self.gdi.DeleteObject.argtypes = [w.HGDIOBJ]
        self.gdi.DeleteDC.argtypes = [w.HDC]
        info = BITMAPINFO()
        info.bmiHeader.biSize = c.sizeof(BITMAPINFOHEADER)
        info.bmiHeader.biWidth = self.width
        info.bmiHeader.biHeight = -self.height          # top-down rows
        info.bmiHeader.biPlanes = 1
        info.bmiHeader.biBitCount = 32
        info.bmiHeader.biCompression = 0                # BI_RGB
        self.bits = c.c_void_p()
        self.dc = self.gdi.CreateCompatibleDC(None)
        self.bitmap = self.gdi.CreateDIBSection(self.dc, c.byref(info), 0, c.byref(self.bits), None, 0)
        if not self.dc or not self.bitmap or not self.bits:
            raise c.WinError(c.get_last_error())
        self.old = self.gdi.SelectObject(self.dc, self.bitmap)
        self.buffer = (c.c_ubyte * (self.width * self.height * 4)).from_address(self.bits.value)

    def clear(self):
        c.memset(self.bits, 0, self.width * self.height * 4)

    def replace(self, data):
        c.memmove(self.bits, bytes(data), min(len(data), self.width * self.height * 4))

    def set_alpha(self, mask):
        self.buffer[3::4] = mask

    def save_bmp(self, path):
        header = struct.pack("<IiiHHIIiiII", 40, self.width, -self.height, 1, 32, 0,
                             self.width * self.height * 4, 0, 0, 0, 0)
        with open(path, "wb") as out:
            out.write(struct.pack("<2sIHHI", b"BM", 54 + len(self.buffer), 0, 0, 54))
            out.write(header)
            out.write(bytes(self.buffer))

    def close(self):
        if self.dc and self.old:
            self.gdi.SelectObject(self.dc, self.old)
        if self.bitmap:
            self.gdi.DeleteObject(self.bitmap)
        if self.dc:
            self.gdi.DeleteDC(self.dc)
        self.bitmap = self.dc = self.old = None


class SeewoSurface:
    """The bar itself: a static layer plus one scrolling line of text."""

    def __init__(self, area, config):
        self.area = area
        self.config = config
        self.spec = seewo.layout(area, config)
        window = self.spec["window"]
        self.width, self.height = window["width"], window["height"]
        self.dib = Dib(self.width, self.height)
        self.user = c.WinDLL("user32", use_last_error=True)
        self.gp = c.WinDLL("gdiplus")
        self._bind()
        self.token = c.c_size_t()

        class Startup(c.Structure):
            _fields_ = [("version", w.UINT), ("callback", c.c_void_p),
                        ("suppress_thread", w.BOOL), ("suppress_codecs", w.BOOL)]

        self.check(self.gp.GdiplusStartup(c.byref(self.token), c.byref(Startup(1, None, False, False)),
                                          None))
        self.graphics = c.c_void_p()
        self.check(self.gp.GdipCreateFromHDC(self.dib.dc, c.byref(self.graphics)))
        self.gp.GdipSetSmoothingMode(self.graphics, 4)
        self.gp.GdipSetTextRenderingHint(self.graphics, 3)
        self.family = c.c_void_p()
        if self.gp.GdipCreateFontFamilyFromName(config["font"], None, c.byref(self.family)):
            self.check(self.gp.GdipGetGenericFontFamilySansSerif(c.byref(self.family)))
        self.text_font = self._font(self.spec["text_size"])
        self.prefix_font = self._font(self.spec["prefix_size"])
        self.format = c.c_void_p()
        self.check(self.gp.GdipCreateStringFormat(0, 0, c.byref(self.format)))
        # Single line, no wrap, trailing spaces preserved.
        self.gp.GdipSetStringFormatFlags(self.format, 0x1000 | 0x4000)
        self.text = "   ".join(config["text"].splitlines())
        self.text_width, self.text_height = self.measure(self.text, 1 << 20, self.text_font)
        self.mask = None
        self.layer = None
        self.build_static()

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
            "GdipSetSmoothingMode": ([pointer, integer], integer),
            "GdipSetTextRenderingHint": ([pointer, integer], integer),
            "GdipCreateSolidFill": ([w.DWORD, c.POINTER(pointer)], integer),
            "GdipDeleteBrush": ([pointer], integer),
            "GdipFillRectangle": ([pointer, pointer, real, real, real, real], integer),
            "GdipFillPolygon": ([pointer, pointer, c.POINTER(PointF), integer, integer], integer),
            "GdipDrawLine": ([pointer, pointer, real, real, real, real], integer),
            "GdipDrawArc": ([pointer, pointer, real, real, real, real, real, real], integer),
            "GdipCreatePen1": ([w.DWORD, real, integer, c.POINTER(pointer)], integer),
            "GdipDeletePen": ([pointer], integer),
            "GdipCreateLineBrush": ([c.POINTER(PointF), c.POINTER(PointF), w.DWORD, w.DWORD,
                                     integer, c.POINTER(pointer)], integer),
            "GdipDeleteLineBrush": ([pointer], integer),
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
        }
        for name, (args, result) in specs.items():
            function = getattr(self.gp, name)
            function.argtypes = args
            function.restype = result
        self.user.UpdateLayeredWindow.argtypes = [w.HWND, w.HDC, c.POINTER(POINT), c.POINTER(SIZE),
                                                  w.HDC, c.POINTER(POINT), w.DWORD,
                                                  c.POINTER(BLENDFUNCTION), w.DWORD]
        self.user.UpdateLayeredWindow.restype = w.BOOL

    @staticmethod
    def check(status):
        if status:
            raise OSError("GDI+ 渲染失败 (%d)" % status)

    def _font(self, size):
        font = c.c_void_p()
        self.check(self.gp.GdipCreateFont(self.family, float(size), 1, 2, c.byref(font)))
        return font

    def measure(self, text, width, font):
        bounds = RectF()
        layout = RectF(0, 0, float(width), 1 << 20)
        fitted = c.c_int()
        lines = c.c_int()
        self.check(self.gp.GdipMeasureString(self.graphics, text, -1, font, c.byref(layout),
                                            self.format, c.byref(bounds), c.byref(fitted),
                                            c.byref(lines)))
        return max(1.0, bounds.width), max(1.0, bounds.height)

    # ------------------------------------------------------------ static layer
    def build_static(self):
        """Shadow, bar, icon, prefix, divider and close glyph — drawn exactly once."""
        spec = self.spec
        bar = spec["bar"]
        radius = spec["radius"]
        pad = spec["pad"]
        mask = bytearray(self.width * self.height)
        for y in range(self.height):
            row = y * self.width
            local_y = y - bar["y"]
            for x in range(self.width):
                local_x = x - bar["x"]
                inside = 0 <= local_x < bar["width"] and 0 <= local_y < bar["height"]
                if inside:
                    mask[row + x] = coverage(local_x, local_y, bar["width"], bar["height"], radius)
                    continue
                if -pad <= local_x < bar["width"] + pad and -pad <= local_y < bar["height"] + pad:
                    distance_x = -local_x if local_x < 0 else (local_x - bar["width"] + 1 if local_x >= bar["width"] else 0)
                    distance_y = -local_y if local_y < 0 else (local_y - bar["height"] + 1 if local_y >= bar["height"] else 0)
                    distance = max(distance_x, distance_y)
                    falloff = max(0.0, 1.0 - distance / max(1, pad))
                    mask[row + x] = int(round(seewo.SHADOW_ALPHA * falloff ** 1.8))

        layer = bytearray(self.width * self.height * 4)
        top = int(seewo.TOP_COLOR[1:], 16)
        bottom = int(seewo.BOTTOM_COLOR[1:], 16)
        for y in range(self.height):
            row = y * self.width
            local_y = y - bar["y"]
            if 0 <= local_y < bar["height"]:
                t = local_y / max(1, bar["height"] - 1)
                red = int(((top >> 16) & 255) * (1 - t) + ((bottom >> 16) & 255) * t)
                green = int(((top >> 8) & 255) * (1 - t) + ((bottom >> 8) & 255) * t)
                blue = int((top & 255) * (1 - t) + (bottom & 255) * t)
            else:
                red = green = blue = 0
            for x in range(self.width):
                alpha = mask[row + x]
                if not alpha:
                    continue
                index = (row + x) * 4
                layer[index] = blue * alpha // 255
                layer[index + 1] = green * alpha // 255
                layer[index + 2] = red * alpha // 255
                layer[index + 3] = alpha
        self.dib.replace(layer)

        graphics = c.c_void_p()
        self.check(self.gp.GdipCreateFromHDC(self.dib.dc, c.byref(graphics)))
        try:
            self.gp.GdipSetSmoothingMode(graphics, 4)
            self.gp.GdipSetTextRenderingHint(graphics, 3)
            self._paint_static(graphics)
        finally:
            self.gp.GdipDeleteGraphics(graphics)

        # Text and glyphs may have written alpha bytes; the mask is authoritative.
        raw = bytearray(self.dib.buffer)
        raw[3::4] = mask
        self.layer = raw
        self.mask = mask
        self.dib.replace(self.layer)

    def _paint_static(self, graphics):
        spec = self.spec
        bar = spec["bar"]
        icon = spec["icon"]

        pen = c.c_void_p()
        self.check(self.gp.GdipCreatePen1(argb("#5E9FD0", 0x3C), 1.0, 2, c.byref(pen)))
        try:
            self.gp.GdipDrawLine(graphics, pen, float(bar["x"] + spec["radius"]),
                                 float(bar["y"] + 1),
                                 float(bar["x"] + bar["width"] - spec["radius"]),
                                 float(bar["y"] + 1))
        finally:
            self.gp.GdipDeletePen(pen)

        brush = c.c_void_p()
        point1 = PointF(float(icon["x"]), float(icon["y"]))
        point2 = PointF(float(icon["x"]), float(icon["y"] + icon["height"]))
        self.check(self.gp.GdipCreateLineBrush(c.byref(point1), c.byref(point2),
                                               argb("#57BEF9"), argb("#1D7BC8"), 0,
                                               c.byref(brush)))
        try:
            self.gp.GdipFillRectangle(graphics, brush, float(icon["x"]), float(icon["y"]),
                                      float(icon["width"]), float(icon["height"]))
        finally:
            self.gp.GdipDeleteLineBrush(brush)

        cone, arcs = seewo.glyph(icon["width"] * 0.78)
        offset_x = icon["x"] + icon["width"] * 0.11
        offset_y = icon["y"] + icon["height"] * 0.11
        points = (PointF * len(cone))(*[PointF(float(offset_x + x), float(offset_y + y))
                                        for x, y in cone])
        white = c.c_void_p()
        self.check(self.gp.GdipCreateSolidFill(argb("#FFFFFF"), c.byref(white)))
        try:
            self.check(self.gp.GdipFillPolygon(graphics, white, points, len(cone), 0))
        finally:
            self.gp.GdipDeleteBrush(white)
        arc_pen = c.c_void_p()
        self.check(self.gp.GdipCreatePen1(argb("#FFFFFF", 0xE6), max(1.4, icon["width"] / 20.0),
                                          2, c.byref(arc_pen)))
        try:
            radius = icon["width"] * 0.20
            centre_x = offset_x + icon["width"] * 0.78 * 0.56
            centre_y = offset_y + icon["height"] * 0.78 * 0.5
            self.check(self.gp.GdipDrawArc(graphics, arc_pen,
                                           float(centre_x - radius), float(centre_y - radius),
                                           float(radius * 2), float(radius * 2), -55.0, 110.0))
            big = radius * 1.55
            self.check(self.gp.GdipDrawArc(graphics, arc_pen,
                                           float(centre_x - big), float(centre_y - big),
                                           float(big * 2), float(big * 2), -55.0, 110.0))
        finally:
            self.gp.GdipDeletePen(arc_pen)

        if spec["prefix"]:
            prefix = spec["prefix"]
            self._draw_text(graphics, self.config["prefix"], prefix["x"],
                            bar["y"] + (bar["height"] - spec["prefix_size"] * 1.34) / 2.0,
                            prefix["width"] + spec["prefix_size"] * 2, spec["prefix_size"] * 1.6,
                            seewo.ACCENT, self.prefix_font)
            divider = spec["divider"]
            fill = c.c_void_p()
            self.check(self.gp.GdipCreateSolidFill(argb("#FFFFFF", seewo.DIVIDER_ALPHA),
                                                   c.byref(fill)))
            try:
                self.gp.GdipFillRectangle(graphics, fill, float(divider["x"]),
                                          float(divider["y"]), float(divider["width"]),
                                          float(divider["height"]))
            finally:
                self.gp.GdipDeleteBrush(fill)

        close = spec["close"]
        arm = spec["close_size"] / 2.0
        centre_x = close["x"] + close["width"] / 2.0
        centre_y = close["y"] + close["height"] / 2.0
        cross = c.c_void_p()
        self.check(self.gp.GdipCreatePen1(argb(seewo.CLOSE_COLOR, 0xE8), max(1.6, arm / 6.0), 2,
                                          c.byref(cross)))
        try:
            self.gp.GdipDrawLine(graphics, cross, float(centre_x - arm / 2), float(centre_y - arm / 2),
                                 float(centre_x + arm / 2), float(centre_y + arm / 2))
            self.gp.GdipDrawLine(graphics, cross, float(centre_x + arm / 2), float(centre_y - arm / 2),
                                 float(centre_x - arm / 2), float(centre_y + arm / 2))
        finally:
            self.gp.GdipDeletePen(cross)

    def _draw_text(self, graphics, text, x, y, width, height, colour, font):
        brush = c.c_void_p()
        self.check(self.gp.GdipCreateSolidFill(argb(colour), c.byref(brush)))
        try:
            self.check(self.gp.GdipDrawString(graphics, text, -1, font,
                                              c.byref(RectF(float(x), float(y), float(width),
                                                            float(height))),
                                              self.format, brush))
        finally:
            self.gp.GdipDeleteBrush(brush)

    # ------------------------------------------------------------------ frames
    def paint(self, elapsed):
        """Draw one frame and return (cycles, alpha)."""
        spec = self.spec
        view = spec["viewport"]
        self.dib.replace(self.layer)
        x, cycles = seewo.offset(elapsed, self.config["speed"] * seewo.scale(self.area),
                                 view["width"], self.text_width, spec["gap"])
        top = view["y"] + (view["height"] - self.text_height) / 2.0
        self.gp.GdipSetClipRect(self.graphics, float(view["x"]), float(view["y"]),
                                float(view["width"]), float(view["height"]), 0)
        try:
            self._draw_text(self.graphics, self.text, view["x"] + x, top,
                            self.text_width + 8, self.text_height + 4, seewo.TEXT, self.text_font)
            for strip in seewo.fade_rects(view, spec["fade"]):
                self._fade(strip)
        finally:
            self.gp.GdipResetClip(self.graphics)
        self.dib.set_alpha(self.mask)
        return cycles, 1.0

    def _fade(self, strip):
        """Dissolve the text into the bar colour at the viewport edges.

        The bar is a vertical gradient, so the fade is drawn in horizontal bands
        that each use the local gradient colour — one brush per band keeps the
        dissolve invisible against the background.
        """
        bar = self.spec["bar"]
        bands = 8
        left_edge = strip["side"] == "left"
        x1 = strip["x"] + (0 if left_edge else strip["width"])
        x2 = strip["x"] + (strip["width"] if left_edge else 0)
        height = max(1, strip["height"] // bands)
        for index in range(bands):
            top = strip["y"] + index * height
            bottom = strip["y"] + strip["height"] if index == bands - 1 else top + height
            middle = (top + bottom) / 2.0 - bar["y"]
            colour = seewo.gradient_colour(middle / max(1, bar["height"] - 1))
            first = argb(colour, 0xFF)
            second = argb(colour, 0x00)
            if not left_edge:
                first, second = second, first
            brush = c.c_void_p()
            point1 = PointF(float(x1), float(top))
            point2 = PointF(float(x2), float(top))
            if self.gp.GdipCreateLineBrush(c.byref(point1), c.byref(point2), first, second, 0,
                                           c.byref(brush)):
                continue
            try:
                self.gp.GdipFillRectangle(self.graphics, brush, float(strip["x"]), float(top),
                                          float(strip["width"]), float(bottom - top))
            finally:
                self.gp.GdipDeleteLineBrush(brush)

    def present(self, hwnd, x, y, opacity):
        """Push the finished DIB to the layered window."""
        blend = BLENDFUNCTION(0, 0, max(1, min(255, int(opacity))), 1)  # AC_SRC_OVER + AC_SRC_ALPHA
        size = SIZE(self.width, self.height)
        source = POINT(0, 0)
        destination = POINT(int(x), int(y))
        return bool(self.user.UpdateLayeredWindow(hwnd, None, c.byref(destination), c.byref(size),
                                                  self.dib.dc, c.byref(source), 0,
                                                  c.byref(blend), 0x00000002))

    def hit_close(self, x, y):
        close = self.spec["close"]
        return (close["x"] <= x < close["x"] + close["width"] and
                close["y"] <= y < close["y"] + close["height"])

    def save_bmp(self, path):
        self.dib.save_bmp(path)

    def close(self):
        for handle in (self.text_font, self.prefix_font):
            if handle:
                self.gp.GdipDeleteFont(handle)
        if self.format:
            self.gp.GdipDeleteStringFormat(self.format)
        if self.family:
            self.gp.GdipDeleteFontFamily(self.family)
        if self.graphics:
            self.gp.GdipDeleteGraphics(self.graphics)
        self.dib.close()
        self.gp.GdiplusShutdown(self.token)
