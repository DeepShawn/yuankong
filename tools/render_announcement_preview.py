"""Render the Seewo-style bar offline, from the same spec the Windows build uses.

    python tools/render_announcement_preview.py out.png [--text ...] [--elapsed 2.4]
    python tools/render_announcement_preview.py out.png --desktop 1920x1080

This is a design/QA aid, not part of the receiver: it lets you diff the bar
against a screenshot of an actual classroom screen without a Windows machine,
and it fails loudly if ``announcement_seewo`` grows a metric the preview does not
draw.

Fonts: the preview substitutes a CJK font for Microsoft YaHei UI. Glyph shapes in
the real bar come from GDI+, so treat letterforms as indicative and the layout
(boxes, gaps, colours, scroll offset) as exact.
"""
import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "windows"))
import announcement_seewo as seewo  # noqa: E402

FONT_CANDIDATES = (
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc",
)
PREFERRED = ("yahei", "cjk sc", "wenquanyi", "cjk jp")

_CACHE = {}


def _pick(path):
    """Choose the face inside a .ttc that actually has CJK glyphs."""
    ranked = []
    for index in range(8):
        try:
            face = ImageFont.truetype(path, 22, index=index)
        except OSError:
            break
        name = str(face.getname()).casefold()
        for rank, keyword in enumerate(PREFERRED):
            if keyword in name:
                ranked.append((rank, index))
                break
    return min(ranked)[1] if ranked else 0


def font(size):
    if size in _CACHE:
        return _CACHE[size]
    fallback = None
    for candidate in FONT_CANDIDATES:
        if not Path(candidate).exists():
            continue
        try:
            face = ImageFont.truetype(candidate, int(size), index=_pick(candidate))
        except OSError:
            continue
        fallback = fallback or face
        if "dejavu" not in str(face.getname()).casefold():
            _CACHE[size] = face
            return face
    result = fallback or ImageFont.load_default()
    _CACHE[size] = result
    return result


def hex_rgb(value):
    return tuple(int(value[index:index + 2], 16) for index in (1, 3, 5))


def rounded_mask(width, height, radius, supersample=4):
    mask = Image.new("L", (width * supersample, height * supersample), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, width * supersample - 1, height * supersample - 1),
                           radius=radius * supersample, fill=255)
    return mask.resize((width, height), Image.LANCZOS)


def vertical_gradient(width, height, top, bottom):
    strip = Image.new("RGB", (1, max(1, height)))
    for y in range(max(1, height)):
        t = y / max(1, height - 1)
        strip.putpixel((0, y), tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)))
    return strip.resize((max(1, width), max(1, height)))


def draw_bar(area, config, elapsed):
    """Mirror of announcement_layer.SeewoSurface: gradient, glyph, current message."""
    spec = seewo.layout(area, config)
    window = spec["window"]
    band_w, band_h = window["width"], window["height"]
    image = Image.new("RGB", (band_w, band_h))
    image.paste(vertical_gradient(band_w, band_h, hex_rgb(seewo.TOP_COLOR),
                                  hex_rgb(seewo.BOTTOM_COLOR)), (0, 0))
    draw = ImageDraw.Draw(image)

    icon = spec["icon"]
    body, arcs = seewo.glyph(icon["width"])
    stroke = max(1, int(round(spec["stroke"])))
    draw.line([(icon["x"] + x, icon["y"] + y) for x, y in body],
              fill=hex_rgb(seewo.ACCENT), width=stroke, joint="curve")
    for centre_x, centre_y, radius, start_angle, sweep in arcs:
        draw.arc((icon["x"] + centre_x - radius, icon["y"] + centre_y - radius,
                  icon["x"] + centre_x + radius, icon["y"] + centre_y + radius),
                 start=start_angle, end=start_angle + sweep,
                 fill=hex_rgb(seewo.ACCENT), width=stroke)

    view = spec["viewport"]
    prefix = seewo.prefix_text(config)
    items = seewo.messages(config)
    text_font = font(spec["font"])
    metrics = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    prefix_width = metrics.textlength(prefix, font=text_font) if prefix else 0
    durations = seewo.durations(config, spec["font"], spec["speed"], spec["gap"])
    index, local, rounds = seewo.locate(elapsed, durations)
    message = items[index] if index < len(items) else ""
    run_width = prefix_width + metrics.textlength(message, font=text_font)
    middle = view["height"] / 2
    layer = Image.new("RGBA", (view["width"], view["height"]), (0, 0, 0, 0))
    layer_draw = ImageDraw.Draw(layer)
    x = seewo.offset(local, spec["speed"], run_width)
    if prefix:
        layer_draw.text((x, middle), prefix, font=text_font, anchor="lm",
                        fill=hex_rgb(seewo.ACCENT) + (255,))
        x += prefix_width
    layer_draw.text((x, middle), message, font=text_font, anchor="lm",
                    fill=hex_rgb(seewo.TEXT) + (255,))
    image.paste(layer, (int(view["x"]), int(view["y"])), layer)
    return image, rounds


def desktop_canvas(width, height):
    canvas = Image.new("RGB", (width, height), (0x20, 0x3A, 0x4B))
    draw = ImageDraw.Draw(canvas)
    for y in range(height):
        t = y / max(1, height - 1)
        draw.line((0, y, width, y), fill=(int(0x25 + 40 * t), int(0x44 + 30 * t),
                                          int(0x5C + 26 * t)))
    draw.rectangle((0, height - 48, width, height), fill=(0x10, 0x18, 0x22))
    return canvas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    parser.add_argument("--text", default="本周五下午 15:00 召开家长会，请各班主任提前布置教室，组织学生有序离场。")
    parser.add_argument("--message", action="append", default=[],
                        help="add one message; repeat for a rotation, overrides --text")
    parser.add_argument("--prefix", default="消息通知")
    parser.add_argument("--elapsed", type=float, default=0.0,
                        help="seconds of scrolling; 0 starts the text at the right edge")
    parser.add_argument("--center", action="store_true",
                        help="pick the elapsed time that centres the message")
    parser.add_argument("--index", type=int, default=0,
                        help="which message to centre (with --center)")
    parser.add_argument("--desktop", default="", help="e.g. 1920x1080 to place the bar on a canvas")
    parser.add_argument("--scale", type=int, default=1)
    args = parser.parse_args()

    area = dict(x=0, y=0, width=1920, height=1040, dpi=96)
    items = args.message or [args.text]
    config = dict(seewo.preset(), text=items[0], messages=items, prefix=args.prefix)
    elapsed = args.elapsed
    if args.center:
        # 让当前这条消息刚好居中：进入它自己那一轮，走过 (run/2) 的位置
        spec = seewo.layout(area, config)
        probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
        text_font = font(spec["font"])
        prefix = seewo.prefix_text(config)
        items = seewo.messages(config)
        durations = seewo.durations(config, spec["font"], spec["speed"], spec["gap"])
        start = sum(durations[:args.index])
        message = items[args.index] if args.index < len(items) else ""
        run_width = ((probe.textlength(prefix, font=text_font) if prefix else 0) +
                     probe.textlength(message, font=text_font))
        target = min(run_width, max(0.0, (run_width - spec["viewport"]["width"]) / 2))
        # +1 ms so floating-point drift in the running total cannot land on the
        # previous message's last instant (which renders as an empty band).
        elapsed = start + target / spec["speed"] + 0.001
    image, cycles = draw_bar(area, config, elapsed)
    if args.desktop:
        width, height = (int(part) for part in args.desktop.lower().split("x"))
        canvas = desktop_canvas(width, height)
        window = seewo.window_rect(dict(area, width=width, height=height))
        canvas.paste(image, (window["x"], window["y"]))
        image = canvas
    if args.scale != 1:
        image = image.resize((image.width * args.scale, image.height * args.scale), Image.LANCZOS)
    image.save(args.output)
    print("wrote %s (%dx%d, rounds=%d)" % (args.output, image.width, image.height, cycles))


if __name__ == "__main__":
    main()
