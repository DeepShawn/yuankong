"""Pixel spec of the Seewo notice bar, measured from a real 消息通知 screenshot.

The reference image is a 640x360 render of a 1920x1080 desktop (Windows icons
measure 16 px there, i.e. 48 px at 1/3 scale), so every number below is a
fraction of the screen height.  That keeps the bar looking identical on a
1080p classroom panel and on a 4K one.

Measured layout (640x360 reference, therefore "mock units"):

    y=158  screen top
    y=168  bar top            <- 10 units below the edge, desktop shows through
    y=219  bar bottom         <- 52 units tall
    x=54   screen left edge
    x=73   speaker icon left  <- 19 units inset
    x=93   speaker icon right <- 20 units wide, stroke 2
    x=100  text left          <- 8 units after the icon
    y=184..201  text ink      <- 18 units: ~18.7 px font, regular weight

There is no rounded corner, no shadow, no badge box, no divider and no close
button: the bar is a flat full-bleed band.  The prefix ("消息提醒：") is part of
the same single line as the body and scrolls with it.
"""
import unicodedata

# ----------------------------------------------------------- fractions of height
BAR_HEIGHT = 52 / 360
TOP = 10 / 360
FONT = 19.5 / 360
ICON = 20 / 360
ICON_INSET = 19 / 360
ICON_GAP = 8 / 360
RIGHT_INSET = 19 / 360
STROKE = 2 / 360

# Scroll speed and the gap between repeats, both in ems.
SPEED_EM = 2.4
GAP_EM = 3.0

MIN_BAR = 36           # never thinner than this, whatever the screen
MIN_FONT = 12

# ---------------------------------------------------------------------- colours
TOP_COLOR = "#020914"
BOTTOM_COLOR = "#040E1C"
ACCENT = "#559BE2"
TEXT = "#FFFFFF"
BACKGROUND = TOP_COLOR

DEFAULT_PREFIX = "消息提醒"
COLONS = ("：", ":")

# Fields the style owns.  A phone that still carries an old draft must not be
# able to deform the bar, so validate() rewrites them instead of rejecting.
LOCKED = dict(mode="ticker", motion="left", position="top", shape="rectangle",
              bold=False, font="Microsoft YaHei UI", background=TOP_COLOR,
              foreground=TEXT, accent=ACCENT, width=100, opacity=100,
              placement="preset", rect=None)


def scale(area):
    """Device pixels per design unit: the constants above are already ratios."""
    return float(area["height"])


def px(value, area):
    return max(1, int(round(value * scale(area))))


def font_size(area):
    return max(MIN_FONT, int(round(FONT * scale(area))))


def bar_height(area):
    return max(MIN_BAR, int(round(BAR_HEIGHT * scale(area))))


def speed(area):
    """Logical pixels per second: 2.4 em, so the pace matches the font size."""
    return font_size(area) * SPEED_EM


def advance(text, size):
    """Advance width: CJK 1 em, Latin 0.55 em, punctuation of either width."""
    total = 0.0
    for character in text:
        wide = unicodedata.east_asian_width(character) in ("W", "F")
        total += 1.0 if wide else 0.55
    return total * size


def prefix_text(config):
    """The blue lead-in with exactly one full-width colon."""
    prefix = (config.get("prefix") or "").strip().rstrip("：:")
    return prefix + "：" if prefix else ""


def window_rect(area):
    """Full-bleed band: the whole screen width, no shadow margin."""
    height = min(area["height"], bar_height(area))
    top = min(px(TOP, area), max(0, area["height"] - height))
    return dict(x=area["x"], y=area["y"] + top, width=area["width"], height=height)


def layout(area, config):
    """Sub-rectangles of the band **in window-local pixels**.

    window_rect() is the absolute screen position; everything painted into the
    band bitmap starts at (0, 0).
    """
    window = window_rect(area)
    height = window["height"]
    size = font_size(area)
    icon_size = px(ICON, area)
    inset = px(ICON_INSET, area)
    icon = dict(x=inset, y=(height - icon_size) // 2, width=icon_size, height=icon_size)
    text_left = icon["x"] + icon_size + px(ICON_GAP, area)
    viewport = dict(x=text_left, y=0,
                    width=max(1, window["width"] - px(RIGHT_INSET, area) - text_left),
                    height=height)
    return dict(window=window, icon=icon, viewport=viewport, font=size,
                stroke=max(1.0, STROKE * scale(area)), gap=int(round(size * GAP_EM)),
                speed=speed(area), width=window["width"], height=height)


def gradient_colour(fraction):
    """Interpolated #RRGGBB of the band at ``fraction`` from the top."""
    fraction = min(1.0, max(0.0, fraction))
    top = int(TOP_COLOR[1:], 16)
    bottom = int(BOTTOM_COLOR[1:], 16)
    parts = []
    for shift in (16, 8, 0):
        start = (top >> shift) & 0xFF
        end = (bottom >> shift) & 0xFF
        parts.append(int(round(start * (1 - fraction) + end * fraction)))
    return "#%02X%02X%02X" % tuple(parts)


def static(viewport_width, run_width):
    """A message that fits sits still at the left edge, like the reference."""
    return run_width <= viewport_width


def offset(elapsed, speed_px, viewport_width, run_width, gap):
    """Leftward travel for one scrolling line; returns (offset, cycles).

    The line enters from the right edge, leaves at the left, waits ``gap`` and
    comes back.  A short line never moves.
    """
    if static(viewport_width, run_width):
        return 0.0, 0
    elapsed = max(0.0, float(elapsed))
    period = max(1.0, viewport_width + run_width + gap)
    travel = elapsed * speed_px
    return viewport_width - (travel % period), int(travel // period)


def positions(elapsed, speed_px, viewport_width, run_width, gap):
    """Every copy of the line that can be on screen right now, plus the cycle."""
    if static(viewport_width, run_width):
        return [0.0], 0
    x, cycles = offset(elapsed, speed_px, viewport_width, run_width, gap)
    period = viewport_width + run_width + gap
    copies = [x]
    if x + run_width < viewport_width:
        copies.append(x + period)      # the previous copy is still leaving
    if x > 0:
        copies.append(x - period)      # the next copy is already entering
    return [value for value in copies if value <= viewport_width and value + run_width > 0], cycles


def glyph(size):
    """Stroked speaker: body polygon plus two sound arcs, in a size x size box.

    Matches the reference: a small back box on the left, a cone flaring to a
    tall vertical edge, then two arcs.  Coordinates are relative to the box.
    """
    body = [(0.05, 0.32), (0.22, 0.32), (0.53, 0.03), (0.53, 0.97),
            (0.22, 0.68), (0.05, 0.68), (0.05, 0.32)]
    arcs = [(0.56, 0.50, 0.17, -52.0, 104.0), (0.58, 0.50, 0.36, -50.0, 100.0)]
    return [(x * size, y * size) for x, y in body], [(x * size, y * size, r * size, a, b)
                                                    for x, y, r, a, b in arcs]


def preset(prefix=DEFAULT_PREFIX):
    """Config fragment the phone sends when it picks the Seewo style."""
    return dict(style="seewo", mode="ticker", motion="left", position="top",
                shape="rectangle", bold=False, font="Microsoft YaHei UI",
                background=TOP_COLOR, foreground=TEXT, accent=ACCENT, width=100,
                font_size=18, speed=150, opacity=100,
                placement="preset", rect=None, prefix=prefix)
