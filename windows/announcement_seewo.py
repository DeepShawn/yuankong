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
button: the bar is a flat full-bleed band.

Behaviour is a 跑马灯: the line always moves left — short messages included —
and several messages take turns.  Each message starts aligned with the left
edge (a teacher who presses send must read it at once), travels left until it
has fully left the viewport, holds for ``gap``, and then the next message takes
over.  When it is the only message the cycle simply repeats.
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

MAX_MESSAGES = 30      # a phone cannot flood the bar
MAX_MESSAGE = 500      # per message, characters
MIN_SECONDS = 4.0      # a very short message still gets its moment
WIDTH_SLACK = 1.05     # estimate slack so a real glyph is never cut by a millisecond

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


def messages(config):
    """The lines to take turns; an empty list falls back to the single text."""
    items = []
    for value in (config.get("messages") or [])[:MAX_MESSAGES]:
        if not isinstance(value, str):
            continue
        text = " ".join(value.split())
        if text:
            items.append(text[:MAX_MESSAGE])
    if not items:
        text = " ".join(str(config.get("text") or "").split())
        if text:
            items = [text[:MAX_MESSAGE]]
    return items


def runs(config, font):
    """Estimated advance of every line (prefix included), from the same table."""
    prefix = prefix_text(config)
    return [advance(prefix + item, font) for item in messages(config)]


def durations(config, font, speed_px, gap):
    """Seconds per message: travel to the left edge plus the hold.

    The estimate is used, not the measured glyph run, so the schedule is the
    same on the phone and on Windows; ``WIDTH_SLACK`` keeps a slightly wider
    real line from being cut off mid-glyph.
    """
    pace = max(1.0, float(speed_px))
    return [max(MIN_SECONDS, (run * WIDTH_SLACK + gap) / pace) for run in runs(config, font)]


def locate(elapsed, durations):
    """Which message is on now, how far into it, and how many full rounds ran."""
    if not durations:
        return 0, 0.0, 0
    total = sum(durations)
    elapsed = max(0.0, float(elapsed))
    rounds = int(elapsed // total) if total > 0 else 0
    local = max(0.0, elapsed - rounds * total)
    for index, duration in enumerate(durations):
        # A microsecond of slack absorbs float drift in the running total, so a
        # boundary time always lands on the next message instead of the previous
        # one's last instant (which paints an empty band).
        if local < duration - 1e-6:
            return index, local, rounds
        local -= duration
    return len(durations) - 1, durations[-1], rounds


def offset(local, speed_px, run_width):
    """Left edge of the line inside the viewport: 0 until it has scrolled out."""
    return -min(max(0.0, float(local)) * speed_px, float(run_width))


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
                placement="preset", rect=None, prefix=prefix, messages=[])
