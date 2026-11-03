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
    def schedule(self, items, viewport=1729.0):
        font = seewo.font_size(AREA)
        speed = seewo.speed(AREA)
        gap = round(font * seewo.GAP_EM)
        config = validate(dict(style="seewo", messages=items))
        return config, font, speed, gap, seewo.durations(config, font, speed, gap)

    def test_every_message_scrolls_even_a_short_one(self):
        _, font, speed, gap, durations = self.schedule(["集合"])
        run = seewo.advance("消息提醒：集合", font)
        self.assertLess(run, 1729.0)
        _, local, _ = seewo.locate(0.0, durations)
        self.assertEqual(0.0, seewo.offset(local, speed, run))
        # One second later it has moved left: a 跑马灯 never stands still.
        self.assertLess(seewo.offset(1.0, speed, run), 0)
        self.assertGreater(seewo.offset(1.0, speed, run), -run)

    def test_offset_stops_at_the_edge_and_never_overshoots(self):
        run, speed = 300.0, 130.0
        self.assertEqual(0.0, seewo.offset(0, speed, run))
        self.assertAlmostEqual(-130.0, seewo.offset(1, speed, run))
        self.assertAlmostEqual(-run, seewo.offset(99, speed, run))
        self.assertEqual(-run, seewo.offset(99, speed, run))

    def test_messages_take_turns_in_order(self):
        items = ["第一条", "第二条更长一些的内容", "第三条"]
        _, _, _, _, durations = self.schedule(items)
        self.assertEqual(3, len(durations))
        seen = []
        moment = 0.0
        for duration in durations:
            index, local, rounds = seewo.locate(moment, durations)
            self.assertEqual(len(seen), index)
            self.assertEqual(0, rounds)
            self.assertAlmostEqual(0.0, local, delta=1e-6)
            seen.append(index)
            moment += duration
        self.assertEqual([0, 1, 2], seen)
        # After the last one the first takes over again and the round counter ticks.
        index, local, rounds = seewo.locate(moment, durations)
        self.assertEqual(0, index)
        self.assertAlmostEqual(0.0, local, delta=1e-6)
        self.assertEqual(1, rounds)

    def test_durations_are_shared_between_platforms(self):
        # Fixed by the estimate table, not by the local font's real metrics.
        _, font, speed, gap, durations = self.schedule(["通知"])
        run = seewo.advance("消息提醒：通知", font)
        expected = max(seewo.MIN_SECONDS, (run * seewo.WIDTH_SLACK + gap) / speed)
        self.assertAlmostEqual(expected, durations[0], delta=1e-6)
        self.assertGreaterEqual(durations[0], seewo.MIN_SECONDS)

    def test_locate_survives_edge_times(self):
        _, _, _, _, durations = self.schedule(["a", "b"])
        for elapsed in (0, -5, 1e-9, sum(durations), sum(durations) * 3 + 0.5, 1e6):
            index, local, rounds = seewo.locate(elapsed, durations)
            self.assertIn(index, (0, 1))
            self.assertGreaterEqual(local, 0)
            self.assertLess(local, max(durations) + 1e-6)
            self.assertGreaterEqual(rounds, 0)

    def test_single_message_loops_itself(self):
        config, font, speed, gap, durations = self.schedule(["只有一条通知"])
        self.assertEqual(1, len(durations))
        run = seewo.advance("消息提醒：只有一条通知", font)
        first = seewo.locate(0.1, durations)
        again = seewo.locate(durations[0] + 0.1, durations)
        self.assertEqual(0, first[0])
        self.assertEqual(0, again[0])       # same message, second round
        self.assertEqual(1, again[2])
        self.assertAlmostEqual(first[1], again[1], delta=1e-6)

    def test_speed_is_proportional_to_the_font(self):
        small = seewo.speed(dict(AREA, height=520))
        large = seewo.speed(dict(AREA, height=1040))
        self.assertAlmostEqual(small * 2, large, delta=2)
        self.assertAlmostEqual(seewo.speed(AREA), seewo.font_size(AREA) * seewo.SPEED_EM, delta=0.1)

    def test_classic_motion_gained_the_gap_parameter_but_keeps_its_behaviour(self):
        self.assertEqual((300, 15.0, 0, 1.0), motion_frame("left", 300, 60, 200, 30, 0, 100, 32))
        self.assertNotEqual(motion_frame("left", 300, 60, 200, 30, 10, 100, 32),
                            motion_frame("left", 300, 60, 200, 30, 10, 100, 64))


class MessagesTest(unittest.TestCase):
    def test_list_wins_and_text_is_the_fallback(self):
        both = validate(dict(style="seewo", text="单条", messages=["第一条", "第二条"]))
        self.assertEqual(["第一条", "第二条"], seewo.messages(both))
        self.assertEqual("第一条", both["text"])       # mirrored for older receivers
        single = validate(dict(style="seewo", text="只有 text"))
        self.assertEqual(["只有 text"], seewo.messages(single))

    def test_blank_and_whitespace_entries_are_dropped(self):
        config = validate(dict(style="seewo", messages=["  第一条  ", "", "   ", "第二条\n换行"]))
        self.assertEqual(["第一条", "第二条 换行"], seewo.messages(config))
        self.assertEqual(["只有一条"], seewo.messages(validate(dict(style="seewo", messages=["只有一条"]))))

    def test_limits_are_enforced(self):
        with self.assertRaises(ValueError):
            validate(dict(style="seewo", messages=["x" * 501]))
        with self.assertRaises(ValueError):
            validate(dict(style="seewo", messages=["a"] * (seewo.MAX_MESSAGES + 1)))
        many = validate(dict(style="seewo", messages=["a"] * seewo.MAX_MESSAGES))
        self.assertEqual(seewo.MAX_MESSAGES, len(seewo.messages(many)))

    def test_only_messages_is_enough(self):
        config = validate(dict(style="seewo", messages=["第一条", "第二条"]))
        self.assertEqual("第一条", config["text"])
        self.assertTrue(config["text"].strip())

    def test_prefix_is_not_part_of_the_list(self):
        config = validate(dict(style="seewo", prefix="校园通知", messages=["内容"]))
        self.assertEqual(["内容"], seewo.messages(config))
        self.assertEqual("校园通知：", seewo.prefix_text(config))


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
