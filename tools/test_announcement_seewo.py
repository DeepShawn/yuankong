"""The Seewo-style band: locked config, geometry and the Windows raster.

Numbers come from the reference screenshot: a 640x360 render of a 1920x1080
desktop, so the bar is 52/360 of the screen height and its text 19.5/360.
"""
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
TEXT = "今天下午放学后，请各班班长安排全体学生进行大扫除。"


def config(**fields):
    return validate(dict({"text": TEXT}, **fields))


class LockTest(unittest.TestCase):
    def test_style_defaults_to_classic(self):
        self.assertEqual("classic", DEFAULT["style"])
        self.assertEqual(("classic", "seewo"), STYLES)
        self.assertEqual("classic", config()["style"])

    def test_seewo_rewrites_every_owned_field(self):
        cramped = config(style="seewo", mode="popup", motion="fade", position="bottom",
                         shape="pill", background="#FF0000", foreground="#00FF00",
                         accent="#0000FF", font="SimSun", bold=True, opacity=30, width=40)
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
        self.assertEqual("", seewo.prefix_text(config(style="seewo", prefix="")))

    def test_prefix_colon_is_never_doubled(self):
        self.assertEqual("消息提醒：", seewo.prefix_text(config(style="seewo")))
        self.assertEqual("注意：", seewo.prefix_text(config(style="seewo", prefix="注意：")))
        self.assertEqual("注意：", seewo.prefix_text(config(style="seewo", prefix="注意:")))
        self.assertEqual("", seewo.prefix_text(config(style="seewo", prefix="   ")))

    def test_lock_helper_is_idempotent(self):
        once = lock_seewo(config(style="seewo"))
        self.assertEqual(once, lock_seewo(dict(once)))

    def test_preset_round_trips(self):
        validated = validate(dict(seewo.preset(), text=TEXT))
        for key, value in seewo.LOCKED.items():
            self.assertEqual(value, validated[key], key)
        self.assertEqual("消息提醒", validated["prefix"])


class GeometryTest(unittest.TestCase):
    def test_band_is_full_bleed_and_near_the_top(self):
        for width, height in ((1920, 1040), (3840, 2120), (1366, 728), (1024, 600), (800, 480)):
            area = dict(x=-1920, y=40, width=width, height=height, dpi=96)
            box = window_rect(config(style="seewo"), area)
            self.assertEqual(area["x"], box["x"])
            self.assertEqual(area["width"], box["width"])
            self.assertGreater(box["y"], area["y"])
            self.assertLess(box["y"] - area["y"], height * 0.04)
            self.assertLessEqual(box["y"] + box["height"], area["y"] + area["height"])
            self.assertAlmostEqual(height * seewo.BAR_HEIGHT, box["height"], delta=2)

    def test_height_follows_the_screen(self):
        low = window_rect(config(style="seewo"), dict(AREA))
        high = window_rect(config(style="seewo"), dict(AREA, dpi=192, height=2080))
        self.assertAlmostEqual(low["height"] * 2, high["height"], delta=2)

    def test_layout_is_window_local_and_ordered(self):
        spec = seewo.layout(AREA, config(style="seewo"))
        icon, view = spec["icon"], spec["viewport"]
        self.assertEqual(0, view["y"])
        self.assertEqual(spec["height"], view["height"])
        self.assertGreater(icon["x"], 0)
        self.assertGreaterEqual(icon["y"], 0)
        self.assertLessEqual(icon["y"] + icon["height"], spec["height"])
        self.assertGreater(view["x"], icon["x"] + icon["width"])
        self.assertLess(view["x"] + view["width"], spec["width"] + 1)

    def test_text_starts_at_the_measured_offset(self):
        # Reference: icon left 19, width 20, gap 8 -> text at 46 of 640 on a 360-tall screen.
        area = dict(x=0, y=0, width=640, height=360, dpi=96)
        spec = seewo.layout(area, config(style="seewo"))
        self.assertAlmostEqual(19, spec["icon"]["x"], delta=1)
        self.assertAlmostEqual(20, spec["icon"]["width"], delta=1)
        self.assertAlmostEqual(46, spec["viewport"]["x"], delta=2)
        self.assertAlmostEqual(52, window_rect(config(style="seewo"), area)["height"], delta=1)
        self.assertAlmostEqual(19.5, spec["font"], delta=1)

    def test_advance_handles_cjk_latin_and_emoji(self):
        self.assertAlmostEqual(4 * 20, seewo.advance("消息提醒", 20))
        self.assertAlmostEqual(3 * 20 * 0.55, seewo.advance("abc", 20))
        self.assertGreater(seewo.advance("😀", 20), seewo.advance("a", 20))


class MotionTest(unittest.TestCase):
    def test_short_message_never_moves(self):
        self.assertTrue(seewo.static(1000, 900))
        self.assertEqual(([0.0], 2), seewo.positions(12.0, 100, 1000, 900, 60))
        self.assertEqual(0.0, seewo.offset(12.0, 100, 1000, 900, 60)[0])

    def test_long_message_starts_visible_then_loops(self):
        width, run, gap, speed = 1000.0, 2000.0, 60.0, 100.0
        # The first pass is on screen immediately, then scrolls out to the left.
        self.assertEqual(([0.0], 0), seewo.positions(0, speed, width, run, gap))
        self.assertEqual(([-100.0], 0), seewo.positions(1, speed, width, run, gap))
        # Gap: nothing visible while the line is fully outside.
        self.assertEqual(([], 1), seewo.positions((run + 30) / speed, speed, width, run, gap))
        # The next pass re-enters from the right edge.
        x, cycles = seewo.positions((run + gap) / speed, speed, width, run, gap)
        self.assertEqual([width], x)
        self.assertEqual(1, cycles)
        for elapsed in (0, 0.5, 7.3, 123.4):
            copies, _ = seewo.positions(elapsed, speed, width, run, gap)
            for value in copies:
                self.assertGreaterEqual(value, -run)
                self.assertLessEqual(value, width)

    def test_positions_stay_in_range_and_only_blank_in_the_gap(self):
        width, run, gap, speed = 500.0, 800.0, 60.0, 100.0
        period = width + run + gap
        blank = 0
        samples = 600
        for step in range(samples):
            elapsed = step / 4.0
            copies, cycles = seewo.positions(elapsed, speed, width, run, gap)
            travel = elapsed * speed
            if not copies:
                blank += 1
                # The band is only empty when the whole line is off screen,
                # which is exactly the repeat gap of every pass.
                self.assertGreaterEqual(travel, run, elapsed)
                continue
            for value in copies:
                self.assertLessEqual(value, width, elapsed)
                self.assertGreater(value + run, 0, elapsed)
            self.assertGreaterEqual(cycles, 0)
        # ~one gap per period: 60 / 1360 of the time, measured over 150 s.
        self.assertAlmostEqual(blank / samples, gap / period, delta=0.06)

    def test_speed_is_proportional_to_the_font(self):
        small = seewo.speed(dict(AREA, height=520))
        large = seewo.speed(dict(AREA, height=1040))
        self.assertAlmostEqual(small * 2, large, delta=2)
        self.assertAlmostEqual(seewo.speed(AREA), seewo.font_size(AREA) * seewo.SPEED_EM, delta=0.1)

    def test_classic_motion_gained_the_gap_parameter_but_keeps_its_behaviour(self):
        self.assertEqual((300, 15.0, 0, 1.0), motion_frame("left", 300, 60, 200, 30, 0, 100, 32))
        self.assertNotEqual(motion_frame("left", 300, 60, 200, 30, 10, 100, 32),
                            motion_frame("left", 300, 60, 200, 30, 10, 100, 64))


class ColourTest(unittest.TestCase):
    def test_gradient_matches_the_measured_ends(self):
        self.assertEqual("#020914", seewo.TOP_COLOR)
        self.assertEqual("#040E1C", seewo.BOTTOM_COLOR)
        self.assertEqual(seewo.TOP_COLOR, seewo.gradient_colour(0))
        self.assertEqual(seewo.BOTTOM_COLOR, seewo.gradient_colour(1))
        self.assertEqual(seewo.TOP_COLOR, seewo.gradient_colour(-5))
        self.assertEqual(seewo.BOTTOM_COLOR, seewo.gradient_colour(9))

    def test_glyph_is_stroked_and_inside_its_box(self):
        body, arcs = seewo.glyph(100)
        xs = [x for x, _ in body]
        ys = [y for _, y in body]
        self.assertGreaterEqual(min(xs), 0)
        self.assertLessEqual(max(xs), 100)
        self.assertGreaterEqual(min(ys), 0)
        self.assertLessEqual(max(ys), 100)
        self.assertEqual(7, len(body))          # closed speaker polygon
        self.assertEqual(2, len(arcs))          # two sound waves
        for _, _, radius, start, sweep in arcs:
            self.assertGreater(radius, 0)
            self.assertGreater(sweep, 0)


class ParityTest(unittest.TestCase):
    """The phone must draw the same numbers as Windows; the table lives in two files."""

    @classmethod
    def setUpClass(cls):
        source = KOTLIN.read_text(encoding="utf-8")
        cls.values = {}
        for name, expression in re.findall(
                r'const val ([A-Z_]+)\s*=\s*("(?:[^"]*)"|[-0-9.f/ ]+)', source):
            text = expression.strip()
            if text.startswith('"'):
                cls.values[name] = text.strip('"')
            else:
                cls.values[name] = eval(text.replace("f", ""), {"__builtins__": {}}, {})

    def test_every_shared_metric_matches(self):
        expect = dict(BAR_HEIGHT=seewo.BAR_HEIGHT, TOP=seewo.TOP, FONT=seewo.FONT,
                      ICON=seewo.ICON, ICON_INSET=seewo.ICON_INSET, ICON_GAP=seewo.ICON_GAP,
                      RIGHT_INSET=seewo.RIGHT_INSET, STROKE=seewo.STROKE,
                      SPEED_EM=seewo.SPEED_EM, GAP_EM=seewo.GAP_EM,
                      MIN_BAR=seewo.MIN_BAR, MIN_FONT=seewo.MIN_FONT,
                      TOP_COLOR=seewo.TOP_COLOR, BOTTOM_COLOR=seewo.BOTTOM_COLOR,
                      ACCENT=seewo.ACCENT, TEXT_COLOR=seewo.TEXT, STYLE="seewo",
                      DEFAULT_PREFIX=seewo.DEFAULT_PREFIX)
        for name, value in expect.items():
            self.assertIn(name, self.values, "Kotlin table is missing " + name)
            if isinstance(value, str):
                self.assertEqual(value, self.values[name], name)
            else:
                self.assertAlmostEqual(float(value), float(self.values[name]), delta=1e-6,
                                       msg=name)

    def test_glyph_points_match(self):
        source = KOTLIN.read_text(encoding="utf-8")
        numbers = set(re.findall(r"(\d\.\d+)f", source))
        body, _ = seewo.glyph(1.0)
        for x, y in body:
            self.assertIn("%.2f" % x, numbers)
            self.assertIn("%.2f" % y, numbers)


@unittest.skipUnless(os.name == "nt", "GDI+ raster")
class NativeSeewoTest(unittest.TestCase):
    def setUp(self):
        from announcement_layer import SeewoSurface
        self.surface = SeewoSurface(AREA, config(style="seewo"))

    def tearDown(self):
        self.surface.close()

    def pixels(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "band.bmp"
            self.surface.paint(0.0)
            self.surface.save_bmp(path)
            data = path.read_bytes()
        header = 54
        stride = self.surface.width * 4

        def pixel(x, y):
            index = header + y * stride + x * 4
            return tuple(data[index:index + 4])       # BGRA

        return pixel

    def test_band_covers_the_whole_rectangle(self):
        # The band is opaque by construction (a plain window, no per-pixel alpha),
        # so only the colour matters: corners are painted, not cut away.
        pixel = self.pixels()
        for x, y in ((0, 0), (0, self.surface.height - 1), (self.surface.width - 1, 0),
                     (self.surface.width - 1, self.surface.height - 1)):
            blue, green, red = pixel(x, y)[:3]
            self.assertLess(blue + green + red, 90, "corner must be dark band, not empty: %s" % ((x, y),))
        top = pixel(self.surface.width // 2, 1)
        bottom = pixel(self.surface.width // 2, self.surface.height - 2)
        self.assertGreaterEqual(bottom[0] + bottom[1] + bottom[2], top[0] + top[1] + top[2])

    def test_blue_glyph_and_white_text_are_drawn(self):
        pixel = self.pixels()
        blue = white = 0
        for y in range(self.surface.height):
            for x in range(0, self.surface.width, 2):
                b, g, r, a = pixel(x, y)
                if b > 150 and b - r > 60:
                    blue += 1
                if r > 200 and g > 200 and b > 200:
                    white += 1
        self.assertGreater(blue, 40, "speaker glyph and prefix must be blue")
        self.assertGreater(white, 200, "body text must be white")

    def test_no_close_button_and_no_fill_behind_the_glyph(self):
        self.assertFalse(self.surface.hit_close(self.surface.width - 20, 20))
        pixel = self.pixels()
        icon = self.surface.spec["icon"]
        # Inside the cone, away from the stroked outline: the glyph has no fill.
        inside = pixel(icon["x"] + int(icon["width"] * 0.36), icon["y"] + icon["height"] // 2)
        self.assertLess(sum(inside[:3]), 150, "glyph is stroke-only")


if __name__ == "__main__":
    unittest.main()
