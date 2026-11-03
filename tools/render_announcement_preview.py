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
    """Mirror of announcement_layer.SeewoSurface: same boxes, same order."""
    spec = seewo.layout(area, config)
    window = spec["window"]
    bar = spec["bar"]
    image = Image.new("RGBA", (window["width"], window["height"]), (0, 0, 0, 0))

    # Shadow: one blurred rounded rectangle instead of the six drawn on Windows.
    shadow = Image.new("RGBA", (window["width"], window["height"]), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (bar["x"], bar["y"] + max(1, spec["pad"] // 3),
         bar["x"] + bar["width"] - 1, bar["y"] + bar["height"] - 1),
        radius=spec["radius"], fill=(0, 0, 0, seewo.SHADOW_ALPHA))
    image = Image.alpha_composite(image, shadow.filter(ImageFilter.GaussianBlur(spec["pad"] * 0.7)))

    body = Image.new("RGBA", (bar["width"], bar["height"]), (0, 0, 0, 0))
    body.paste(vertical_gradient(bar["width"], bar["height"], hex_rgb(seewo.TOP_COLOR),
                                 hex_rgb(seewo.BOTTOM_COLOR)), (0, 0))
    body.putalpha(rounded_mask(bar["width"], bar["height"], spec["radius"]))
    image.alpha_composite(body, (bar["x"], bar["y"]))

    draw = ImageDraw.Draw(image)
    draw.line((bar["x"] + spec["radius"], bar["y"] + 1,
               bar["x"] + bar["width"] - spec["radius"], bar["y"] + 1),
              fill=(0x5E, 0x9F, 0xD0, 0x3C), width=1)

    icon = spec["icon"]
    badge = Image.new("RGBA", (icon["width"], icon["height"]), (0, 0, 0, 0))
    badge.paste(vertical_gradient(icon["width"], icon["height"], hex_rgb("#57BEF9"),
                                  hex_rgb("#1D7BC8")), (0, 0))
    badge.putalpha(rounded_mask(icon["width"], icon["height"], spec["icon_radius"]))
    image.alpha_composite(badge, (icon["x"], icon["y"]))

    cone, arcs = seewo.glyph(icon["width"] * 0.78)
    offset_x = icon["x"] + icon["width"] * 0.11
    offset_y = icon["y"] + icon["height"] * 0.11
    draw.polygon([(offset_x + x, offset_y + y) for x, y in cone], fill=(255, 255, 255, 255))
    centre_x = offset_x + icon["width"] * 0.78 * 0.56
    centre_y = offset_y + icon["height"] * 0.78 * 0.5
    for factor in (1.0, 1.55):
        radius = icon["width"] * 0.20 * factor
        draw.arc((centre_x - radius, centre_y - radius, centre_x + radius, centre_y + radius),
                 start=-55, end=55, fill=(255, 255, 255, 0xE6), width=max(1, int(icon["width"] / 20)))

    if spec["prefix"]:
        prefix = spec["prefix"]
        draw.text((prefix["x"], bar["y"] + bar["height"] / 2), config["prefix"],
                  font=font(spec["prefix_size"]), anchor="lm",
                  fill=hex_rgb(seewo.ACCENT) + (255,))
        divider = spec["divider"]
        draw.rectangle((divider["x"], divider["y"], divider["x"] + divider["width"] - 1,
                        divider["y"] + divider["height"] - 1),
                       fill=(255, 255, 255, seewo.DIVIDER_ALPHA))

    close = spec["close"]
    arm = spec["close_size"] / 2
    centre_x = close["x"] + close["width"] / 2
    centre_y = close["y"] + close["height"] / 2
    width = max(1, int(arm / 6))
    draw.line((centre_x - arm / 2, centre_y - arm / 2, centre_x + arm / 2, centre_y + arm / 2),
              fill=hex_rgb(seewo.CLOSE_COLOR) + (0xE8,), width=width)
    draw.line((centre_x + arm / 2, centre_y - arm / 2, centre_x - arm / 2, centre_y + arm / 2),
              fill=hex_rgb(seewo.CLOSE_COLOR) + (0xE8,), width=width)

    # Scrolling text, clipped to the viewport, then dissolved at both edges.
    view = spec["viewport"]
    message = "   ".join(config["text"].splitlines())
    text_font = font(spec["text_size"])
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    text_width = probe.textlength(message, font=text_font)
    x, cycles = seewo.offset(elapsed, config["speed"] * seewo.scale(area), view["width"],
                             text_width, spec["gap"])
    layer = Image.new("RGBA", (view["width"], view["height"]), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((x, view["height"] / 2), message, font=text_font, anchor="lm",
                               fill=hex_rgb(seewo.TEXT) + (255,))
    fade = spec["fade"]
    if fade and view["width"] > 2 * fade:
        mask = Image.new("L", (view["width"], view["height"]), 255)
        mask_draw = ImageDraw.Draw(mask)
        for index in range(fade):
            value = int(255 * index / fade)
            mask_draw.line((index, 0, index, view["height"]), fill=value)
            mask_draw.line((view["width"] - 1 - index, 0, view["width"] - 1 - index,
                            view["height"]), fill=value)
        layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", layer.size, 0), mask))
    image.alpha_composite(layer, (view["x"], view["y"]))
    return image, cycles


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
    parser.add_argument("--prefix", default="消息通知")
    parser.add_argument("--elapsed", type=float, default=0.0,
                        help="seconds of scrolling; 0 starts the text at the right edge")
    parser.add_argument("--center", action="store_true",
                        help="pick the elapsed time that centres the message")
    parser.add_argument("--desktop", default="", help="e.g. 1920x1080 to place the bar on a canvas")
    parser.add_argument("--scale", type=int, default=1)
    args = parser.parse_args()

    area = dict(x=0, y=0, width=1920, height=1040, dpi=96)
    config = {"text": args.text, "prefix": args.prefix, **seewo.preset()}
    elapsed = args.elapsed
    if args.center:
        spec = seewo.layout(area, config)
        probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
        width = probe.textlength("   ".join(config["text"].splitlines()),
                                 font=font(spec["text_size"]))
        travel = spec["viewport"]["width"] + (spec["viewport"]["width"] - width) / 2
        elapsed = travel / (config["speed"] * seewo.scale(area))
    image, cycles = draw_bar(area, config, elapsed)
    if args.desktop:
        width, height = (int(part) for part in args.desktop.lower().split("x"))
        canvas = desktop_canvas(width, height)
        window = seewo.window_rect(dict(area, width=width, height=height))
        canvas.paste(image, (window["x"], window["y"]), image)
        image = canvas
    if args.scale != 1:
        image = image.resize((image.width * args.scale, image.height * args.scale), Image.LANCZOS)
    image.save(args.output)
    print("wrote %s (%dx%d, cycles=%d)" % (args.output, image.width, image.height, cycles))


if __name__ == "__main__":
    main()
