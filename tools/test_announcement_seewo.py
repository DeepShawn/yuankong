"""The Seewo-style bar: locked config, shared geometry and the Windows raster."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "windows"))

import announcement_seewo as seewo
from announcements import DEFAULT, STYLES, lock_seewo, motion_frame, validate, window_rect

AREA = dict(x=0, y=0, width=1920, height=1040, dpi=96)
KOTLIN = ROOT / "android/app/src/main/java/com/aicode/lancmd/AnnouncementSeewo.kt"


def config(**fields):
    return validate(dict({"text": "本周五下午三点召开家长会。"}, **fields))


class LockTest(unittest.TestCase):
    def test_style_defaults_to_classic(self):
        self.assertEqual("classic", DEFAULT["style"])
        self.assertEqual(("classic", "seewo"), STYLES)
        self.assertEqual("classic", config()["style"])

    def test_seewo_rewrites_every_owned_field(self):
        # A phone carrying an old draft must not be able to deform the bar.
        cramped = config(style="seewo", mode="popup", motion="fade", position="bottom",
                         shape="pill", background="#FF0000", foreground="#00FF00",
                         accent="#0000FF", font="SimSun", font_size=88, speed=400,
                         opacity=30, width=40, bold=False,
                         placement="custom", rect=dict(x=10, y=10, width=200, height=100))
        for key, value in seewo.LOCKED.items():
            self.assertEqual(value, cramped[key], key)
        self.assertEqual("seewo", cramped["style"])
        self.assertEqual("preset", cramped["placement"])
        self.assertIsNone(cramped["rect"])

    def test_text_prefix_and_timing_stay_editable(self):
        message = config(style="seewo", prefix="校园通知", duration=45, repeats=3)
        self.assertEqual("校园通知", message["prefix"])
        self.assertEqual(45, message["duration"])
        self.assertEqual(3, message["repeats"])
        # An empty prefix is a legitimate choice: no icon column, no divider.
        blank = config(style="seewo", prefix="")
        self.assertEqual("", blank["prefix"])
        self.assertIsNone(seewo.layout(AREA, blank)["prefix"])

    def test_lock_helper_is_idempotent(self):
        once = lock_seewo(config(style="seewo"))
        self.assertEqual(once, lock_seewo(dict(once)))
        self.assertEqual({"text": once["text"]},
                         {k: v for k, v in lock_seewo(dict(once)).items() if k == "text"})

    def test_preset_round_trips(self):
        preset = dict(seewo.preset(), text="家长会通知")
        validated = validate(preset)
        for key, value in seewo.LOCKED.items():
            self.assertEqual(value, validated[key], key)
        self.assertEqual("消息通知", validated["prefix"])


class GeometryTest(unittest.TestCase):
    def test_window_fits_and_is_centred_on_every_size(self):
        for width, height, dpi in ((1920, 1040, 96), (3840, 2120, 192), (1366, 728, 96),
                                   (1024, 600, 120), (800, 480, 96), (320, 240, 96)):
            area = dict(x=-1920, y=40, width=width, height=height, dpi=dpi)
            box = window_rect(config(style="seewo"), area)
            self.assertGreaterEqual(box["x"], area["x"])
            self.assertGreaterEqual(box["y"], area["y"])
            self.assertLessEqual(box["x"] + box["width"], area["x"] + area["width"])
            self.assertLessEqual(box["y"] + box["height"], area["y"] + area["height"])
            self.assertEqual(area["x"] + (area["width"] - box["width"]) // 2, box["x"])
            self.assertEqual(area["y"] + int(round(seewo.TOP * dpi / 96)), box["y"])

    def test_window_is_bar_plus_shadow_margin(self):
        box = window_rect(config(style="seewo"), AREA)
        bar = seewo.layout(AREA, config(style="seewo"))["bar"]
        self.assertEqual(seewo.PAD * 2, box["height"] - bar["height"])
        self.assertEqual(seewo.PAD * 2, box["width"] - bar["width"])
        self.assertEqual(int(round(seewo.HEIGHT)), bar["height"])
        self.assertLessEqual(bar["width"], seewo.MAX_WIDTH)

    def test_boxes_are_ordered_left_to_right(self):
        for prefix in ("消息通知", "校园通知公告", ""):
            spec = seewo.layout(AREA, config(style="seewo", prefix=prefix))
            bar, icon, view, close = spec["bar"], spec["icon"], spec["viewport"], spec["close"]
            self.assertGreater(icon["x"], bar["x"])
            self.assertGreaterEqual(icon["y"], bar["y"])
            self.assertLessEqual(icon["y"] + icon["height"], bar["y"] + bar["height"])
            self.assertGreater(view["x"], icon["x"] + icon["width"])
            if spec["prefix"]:
                divider = spec["divider"]
                self.assertGreater(divider["x"], spec["prefix"]["x"] + spec["prefix"]["width"] - 1)
                self.assertGreater(view["x"], divider["x"])
            self.assertLess(view["x"] + view["width"], close["x"])
            self.assertGreater(close["x"] + close["width"], bar["x"] + bar["width"] - 2 * spec["pad"])
            self.assertEqual(bar["height"], view["height"])

    def test_prefix_column_tracks_the_text_not_a_fixed_width(self):
        short = seewo.layout(AREA, config(style="seewo", prefix="通知"))
        long = seewo.layout(AREA, config(style="seewo", prefix="校园重要通知"))
        self.assertLess(short["prefix"]["width"], long["prefix"]["width"])
        self.assertAlmostEqual(seewo.advance("通知", seewo.PREFIX_SIZE) + 2 * seewo.PREFIX_GAP,
                               short["prefix"]["width"], delta=1)
        self.assertAlmostEqual(seewo.advance("校园重要通知", seewo.PREFIX_SIZE) + 2 * seewo.PREFIX_GAP,
                               long["prefix"]["width"], delta=1)
        # Latin prefixes are narrower than the same number of CJK characters.
        self.assertLess(seewo.advance("Notice", seewo.PREFIX_SIZE),
                        seewo.advance("通知内容字样", seewo.PREFIX_SIZE))

    def test_dpi_scaling_is_uniform(self):
        low = seewo.layout(dict(AREA), config(style="seewo"))
        high = seewo.layout(dict(AREA, dpi=192), config(style="seewo"))
        for key in ("radius", "text_size", "prefix_size", "fade", "gap", "close_size", "pad"):
            self.assertEqual(low[key] * 2, high[key], key)


class MotionTest(unittest.TestCase):
    def test_offset_wraps_after_one_screen_plus_text_plus_gap(self):
        width, text, gap, speed = 1000.0, 400.0, 64.0, 100.0
        period = width + text + gap
        x, cycles = seewo.offset(0, speed, width, text, gap)
        self.assertEqual(width, x)
        self.assertEqual(0, cycles)
        self.assertAlmostEqual(width - 100, seewo.offset(1, speed, width, text, gap)[0])
        self.assertEqual(1, seewo.offset(period / speed, speed, width, text, gap)[1])
        middle = seewo.offset(period / speed / 2, speed, width, text, gap)[0]
        self.assertAlmostEqual(width - period / 2, middle)
        for elapsed in (0, 0.5, 7.3, 123.4):
            value = seewo.offset(elapsed, speed, width, text, gap)[0]
            self.assertGreaterEqual(value, -text - gap)
            self.assertLessEqual(value, width)

    def test_classic_motion_gained_the_gap_parameter_but_keeps_its_behaviour(self):
        self.assertEqual((300, 15.0, 0, 1.0), motion_frame("left", 300, 60, 200, 30, 0, 100, 32))
        self.assertEqual((300, 15.0, 0, 1.0), motion_frame("left", 300, 60, 200, 30, 0, 100))
        # The gap only changes the cycle once the travel exceeds one period.
        self.assertNotEqual(motion_frame("left", 300, 60, 200, 30, 10, 100, 32),
                            motion_frame("left", 300, 60, 200, 30, 10, 100, 64))


class ColourTest(unittest.TestCase):
    def test_gradient_endpoints_and_midpoint(self):
        self.assertEqual(seewo.TOP_COLOR, seewo.gradient_colour(0))
        self.assertEqual(seewo.BOTTOM_COLOR, seewo.gradient_colour(1))
        self.assertEqual("#0C2438", seewo.gradient_colour(0.5))
        self.assertEqual(seewo.TOP_COLOR, seewo.gradient_colour(-5))
        self.assertEqual(seewo.BOTTOM_COLOR, seewo.gradient_colour(9))

    def test_fade_strips_are_symmetric_and_skipped_when_narrow(self):
        view = dict(x=200, y=8, width=1200, height=56)
        strips = seewo.fade_rects(view, 20)
        self.assertEqual(["left", "right"], [item["side"] for item in strips])
        self.assertEqual(view["x"], strips[0]["x"])
        self.assertEqual(view["x"] + view["width"], strips[1]["x"] + strips[1]["width"])
        self.assertEqual([], seewo.fade_rects(dict(view, width=30), 20))

    def test_shadow_rects_shrink_towards_the_bar(self):
        bar = dict(x=8, y=8, width=1700, height=56)
        items = seewo.shadow_rects(bar, 8)
        self.assertEqual(seewo.SHADOW_STEPS, len(items))
        widths = [item["width"] for item in items]
        self.assertTrue(all(a > b for a, b in zip(widths, widths[1:])))
        self.assertTrue(all(item["alpha"] <= seewo.SHADOW_ALPHA for item in items))
        self.assertGreaterEqual(items[-1]["alpha"], 0)


class ParityTest(unittest.TestCase):
    """The phone must draw the same numbers as Windows; the table lives in two files."""

    @classmethod
    def setUpClass(cls):
        source = KOTLIN.read_text(encoding="utf-8")
        cls.values = {}
        for name, value in re.findall(r'const val ([A-Z_]+) = (0x[0-9A-Fa-f]+|[0-9.]+f?|"[^"]*")',
                                      source):
            cls.values[name] = value

    def test_every_shared_metric_matches(self):
        expect = dict(HEIGHT=seewo.HEIGHT, RADIUS=seewo.RADIUS, SIDE_MARGIN=seewo.SIDE_MARGIN,
                      TOP=seewo.TOP, MAX_WIDTH=seewo.MAX_WIDTH, PAD=seewo.PAD, ICON=seewo.ICON,
                      ICON_RADIUS=seewo.ICON_RADIUS, ICON_GAP=seewo.ICON_GAP,
                      PREFIX_SIZE=seewo.PREFIX_SIZE, PREFIX_GAP=seewo.PREFIX_GAP,
                      DIVIDER_GAP=seewo.DIVIDER_GAP, DIVIDER_HEIGHT=seewo.DIVIDER_HEIGHT,
                      TEXT_SIZE=seewo.TEXT_SIZE, FADE=seewo.FADE, GAP=seewo.GAP,
                      CLOSE=seewo.CLOSE, CLOSE_HIT=seewo.CLOSE_HIT, CLOSE_GAP=seewo.CLOSE_GAP,
                      SPEED=seewo.SPEED, OPACITY=seewo.OPACITY,
                      TOP_COLOR=seewo.TOP_COLOR, BOTTOM_COLOR=seewo.BOTTOM_COLOR,
                      ACCENT=seewo.ACCENT, TEXT_COLOR=seewo.TEXT, CLOSE_COLOR=seewo.CLOSE_COLOR,
                      SHADOW_ALPHA=seewo.SHADOW_ALPHA, SHADOW_STEPS=seewo.SHADOW_STEPS,
                      DIVIDER_ALPHA=seewo.DIVIDER_ALPHA)
        for name, value in expect.items():
            self.assertIn(name, self.values, "Kotlin table is missing " + name)
            raw = self.values[name]
            if isinstance(value, str):
                self.assertEqual(value, raw.strip('"'), name)
            else:
                number = float(int(raw, 16)) if raw.lower().startswith("0x") else float(raw.rstrip("f"))
                self.assertAlmostEqual(float(value), number, delta=1e-6, msg=name)

    def test_style_tag_and_preset_fields_agree(self):
        source = KOTLIN.read_text(encoding="utf-8")
        self.assertIn('const val STYLE = "seewo"', source)
        self.assertEqual("seewo", seewo.preset()["style"])
        for field in ("mode", "motion", "position", "shape", "bold", "font", "background",
                      "foreground", "accent", "width", "font_size", "speed", "opacity",
                      "placement", "rect", "prefix"):
            self.assertIn('put("%s"' % field, source, field)


@unittest.skipUnless(os.name == "nt", "UpdateLayeredWindow and GDI+")
class NativeSeewoTest(unittest.TestCase):
    def test_layered_bar_has_transparent_margin_rounded_corners_and_text(self):
        from announcement_layer import SeewoSurface
        area = dict(x=0, y=0, width=1920, height=1040, dpi=96)
        surface = SeewoSurface(area, config(style="seewo"))
        try:
            cycles, alpha = surface.paint(0.4)
            self.assertEqual(1.0, alpha)
            with tempfile.TemporaryDirectory() as folder:
                path = Path(folder) / "seewo.bmp"
                surface.save_bmp(path)
                data = path.read_bytes()
            header = 54
            stride = surface.width * 4
            def pixel(x, y):
                index = header + y * stride + x * 4
                return tuple(data[index:index + 4])
            self.assertEqual(0, pixel(0, 0)[3])                 # shadow margin is transparent
            corner = pixel(9, 9)
            self.assertLess(corner[3], 255)                      # anti-aliased rounded corner
            self.assertEqual(255, pixel(surface.width // 2, surface.spec["bar"]["height"] // 2)[3])
            self.assertIn("消息通知", surface.text) if False else None
            body = data[header:]
            bright = sum(1 for index in range(0, len(body), 4) if body[index] > 200
                         and body[index + 1] > 200 and body[index + 2] > 200 and body[index + 3] > 128)
            self.assertGreater(bright, 200, "the white message must be drawn")
        finally:
            surface.close()

    def test_window_geometry_matches_the_phone_table(self):
        from announcement_layer import SeewoSurface
        area = dict(x=0, y=0, width=1920, height=1040, dpi=96)
        surface = SeewoSurface(area, config(style="seewo"))
        try:
            box = window_rect(config(style="seewo"), area)
            self.assertEqual((box["width"], box["height"]), (surface.width, surface.height))
            close = surface.spec["close"]
            self.assertTrue(surface.hit_close(close["x"] + 2, close["y"] + 2))
            self.assertFalse(surface.hit_close(2, 2))
        finally:
            surface.close()


if __name__ == "__main__":
    unittest.main()
