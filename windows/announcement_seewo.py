"""Pixel specification of the Seewo-style notice bar.

One table drives three renderers — the Windows GDI+ window, the Android preview
and the offline PIL preview — so the phone shows exactly what the classroom
screen shows.  Every number below is a Windows logical pixel (96 dpi); each
renderer multiplies by ``dpi/96``.

Layout, left to right::

    ┌───────────────────────────────────────────────────────────────┐
    │ (icon) 消息通知 │ 正文向左滚动 …                          (×) │
    └───────────────────────────────────────────────────────────────┘
      ↑rounded   ↑blue badge   ↑divider   ↑viewport with fading edges

The bar is a single row inside the window; the window keeps ``PAD`` logical
pixels of transparent margin so the drop shadow can be drawn without moving the
reported rectangle.
"""
import math
import unicodedata

# --------------------------------------------------------------------- metrics
HEIGHT = 56
RADIUS = 10
SIDE_MARGIN = 24
TOP = 12
MAX_WIDTH = 1760
MIN_WIDTH = 480
PAD = 8

ICON = 30
ICON_RADIUS = 8
ICON_GAP = 12

PREFIX_SIZE = 20
PREFIX_GAP = 4

DIVIDER_GAP = 12
DIVIDER_WIDTH = 1
DIVIDER_HEIGHT = 20

TEXT_SIZE = 22
FADE = 20
GAP = 64

CLOSE = 24
CLOSE_HIT = 36
CLOSE_GAP = 14

SPEED = 132          # logical pixels per second, ~ one 20-character line in 4 s
OPACITY = 96

# ---------------------------------------------------------------------- colors
TOP_COLOR = "#12324F"
BOTTOM_COLOR = "#071522"
BORDER_COLOR = "#2E9BF0"
BORDER_ALPHA = 0x48
ACCENT = "#8FD8FF"
TEXT = "#FFFFFF"
DIVIDER_ALPHA = 0x28
CLOSE_COLOR = "#DCE8F5"
SHADOW_ALPHA = 0x6E
SHADOW_STEPS = 6

# Values the style owns.  A phone that still carries an old draft must not be
# able to break the bar, so validate() rewrites them instead of rejecting.
LOCKED = dict(mode="ticker", motion="left", position="top", shape="rounded",
              bold=True, font="Microsoft YaHei UI", background=TOP_COLOR,
              foreground=TEXT, accent=ACCENT, width=100, font_size=TEXT_SIZE,
              speed=SPEED, opacity=OPACITY)


def scale(area):
    return area["dpi"] / 96


def advance(text, size):
    """Approximate advance width in logical pixels: CJK 1 em, Latin 0.55 em.

    The exact advance is whatever GDI+ reports; this estimate exists so the
    phone can reserve the same prefix column without asking Windows.
    """
    total = 0.0
    for character in text:
        wide = unicodedata.east_asian_width(character) in ("W", "F")
        total += 1.0 if wide else 0.55
    return total * size


def px(value, area):
    """Logical -> device pixels, rounded like every other size in the pack."""
    return int(round(value * scale(area)))


def window_rect(area):
    """The bar's outer rectangle, shadow margin included.

    The reported box is what the phone draws, so the transparent margin is part
    of it: the bar itself is inset by ``PAD`` on every side.
    """
    side = px(SIDE_MARGIN, area)
    pad = px(PAD, area)
    widest = min(px(MAX_WIDTH, area), max(1, area["width"] - 2 * side))
    width = min(area["width"], widest + 2 * pad)
    height = min(area["height"], px(HEIGHT + 2 * PAD, area))
    x = area["x"] + (area["width"] - width) // 2
    y = area["y"] + min(max(0, px(TOP, area)), max(0, area["height"] - height))
    return dict(x=x, y=y, width=width, height=height)


def gradient_colour(fraction):
    """Interpolated #RRGGBB of the bar background at ``fraction`` from the top."""
    fraction = min(1.0, max(0.0, fraction))
    top = int(TOP_COLOR[1:], 16)
    bottom = int(BOTTOM_COLOR[1:], 16)
    parts = []
    for shift in (16, 8, 0):
        start = (top >> shift) & 0xFF
        end = (bottom >> shift) & 0xFF
        parts.append(int(round(start * (1 - fraction) + end * fraction)))
    return "#%02X%02X%02X" % tuple(parts)


def layout(area, config):
    """Every sub-rectangle of the bar in device pixels."""
    window = window_rect(area)
    pad = px(PAD, area)
    bar = dict(x=pad, y=pad, width=max(1, window["width"] - 2 * pad),
               height=max(1, window["height"] - 2 * pad))
    icon_size = px(ICON, area)
    icon = dict(x=pad + px(16, area), y=bar["y"] + (bar["height"] - icon_size) // 2,
                width=icon_size, height=icon_size)
    close_hit = px(CLOSE_HIT, area)
    close = dict(x=bar["x"] + bar["width"] - px(CLOSE_GAP, area) - close_hit,
                 y=bar["y"] + (bar["height"] - close_hit) // 2,
                 width=close_hit, height=close_hit)
    prefix = None
    divider = None
    left = icon["x"] + icon["width"] + px(ICON_GAP, area)
    if config.get("prefix"):
        # Width comes from the same advance estimate on every platform, so the
        # message starts at the same pixel on the phone and on the classroom
        # screen without the phone needing GDI+ metrics.
        column = px(advance(config["prefix"], PREFIX_SIZE) + PREFIX_GAP * 2, area)
        prefix = dict(x=left, y=bar["y"], width=column, height=bar["height"],
                      size=px(PREFIX_SIZE, area))
        divider = dict(x=prefix["x"] + prefix["width"] + px(DIVIDER_GAP, area),
                       y=bar["y"] + (bar["height"] - px(DIVIDER_HEIGHT, area)) // 2,
                       width=max(1, px(DIVIDER_WIDTH, area)),
                       height=px(DIVIDER_HEIGHT, area))
        left = divider["x"] + divider["width"] + px(DIVIDER_GAP, area)
    viewport = dict(x=left, y=bar["y"], width=max(1, close["x"] - px(CLOSE_GAP, area) - left),
                    height=bar["height"])
    return dict(window=window, bar=bar, icon=icon, prefix=prefix, divider=divider,
                viewport=viewport, close=close, pad=pad, radius=px(RADIUS, area),
                icon_radius=px(ICON_RADIUS, area), prefix_size=px(PREFIX_SIZE, area),
                text_size=px(TEXT_SIZE, area), fade=px(FADE, area), gap=px(GAP, area),
                close_size=px(CLOSE, area), divider_width=max(1, px(DIVIDER_WIDTH, area)))


def offset(elapsed, speed, viewport_width, text_width, gap):
    """Leftward offset in device pixels, plus the completed cycle count."""
    elapsed = max(0.0, elapsed)
    period = max(1.0, viewport_width + text_width + gap)
    travel = elapsed * speed
    return viewport_width - (travel % period), int(travel // period)


def fade_rects(view, fade):
    """The two strips where the text dissolves into the bar."""
    if fade <= 0 or view["width"] <= 2 * fade:
        return []
    return [dict(x=view["x"], y=view["y"], width=fade, height=view["height"], side="left"),
            dict(x=view["x"] + view["width"] - fade, y=view["y"], width=fade,
                 height=view["height"], side="right")]


def shadow_rects(bar, pad, steps=SHADOW_STEPS):
    """Concentric rounded rectangles standing in for a blurred shadow."""
    items = []
    for step in range(steps, 0, -1):
        grow = int(round(pad * step / steps))
        alpha = int(SHADOW_ALPHA * (1 - (step - 1) / steps) ** 1.6 / steps * 3)
        items.append(dict(x=bar["x"] - grow, y=bar["y"] - grow + max(1, pad // 3),
                          width=bar["width"] + 2 * grow, height=bar["height"] + 2 * grow,
                          alpha=max(0, min(SHADOW_ALPHA, alpha)),
                          radius=max(2, pad * step // steps)))
    return items


def glyph(size):
    """Speaker glyph in a ``size`` box: polygon points plus two sound arcs."""
    s = size
    cone = [(0.16, 0.36), (0.36, 0.36), (0.56, 0.14), (0.56, 0.86), (0.36, 0.64), (0.16, 0.64)]
    arcs = []
    for radius, spread in ((0.16, 90), (0.28, 80)):
        points = []
        for index in range(9):
            angle = -math.radians(spread) + index * (2 * math.radians(spread)) / 8
            points.append((0.56 + 0.34 + math.cos(angle) * radius,
                           0.5 + math.sin(angle) * radius))
        arcs.append(points)
    return [(x * s, y * s) for x, y in cone], [[(x * s, y * s) for x, y in arc] for arc in arcs]


def preset():
    """Config fragment the phone sends when it picks the Seewo style."""
    return dict(style="seewo", mode="ticker", motion="left", position="top",
                shape="rounded", bold=True, font="Microsoft YaHei UI",
                background=TOP_COLOR, foreground=TEXT, accent=ACCENT, width=100,
                font_size=TEXT_SIZE, speed=SPEED, opacity=OPACITY,
                placement="preset", rect=None, prefix="消息通知")
