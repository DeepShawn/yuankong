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
    def test_scroll_rate_changes_pace_and_round_duration_without_moving_the_band(self):
        base=config(style="seewo",messages=["第一条公告","第二条公告"])
        original=seewo.layout(AREA,base)
        for rate in (25,50,100,150,200,300):
            altered=seewo.layout(AREA,dict(base,scroll_rate=rate))
            self.assertAlmostEqual(original["speed"]*rate/100,altered["speed"])
            self.assertEqual({k:v for k,v in original.items() if k!="speed"},
                             {k:v for k,v in altered.items() if k!="speed"})
            before=seewo.durations(base,original["font"],original["speed"],original["viewport"]["width"])
            after=seewo.durations(base,altered["font"],altered["speed"],altered["viewport"]["width"])
            for old,new in zip(before,after):self.assertAlmostEqual(old*100/rate,new)

    def test_default_rate_is_compatible_and_invalid_rates_are_rejected(self):
        before=config(style="seewo")
        self.assertEqual(100,before["scroll_rate"])
        self.assertEqual(seewo.speed(AREA),seewo.speed(AREA,before))
        for rate in (True,0,24,301,1.5,"200",None):
            with self.subTest(rate=rate),self.assertRaises(ValueError):config(style="seewo",scroll_rate=rate)

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
        self.assertLessEqual(view["x"] + view["width"], spec["width"])

    def test_lead_in_starts_at_the_measured_offset(self):
        # Reference: icon left 19, width 20, gap 8 -> the lead-in starts at 46 of
        # 640 on a 360-tall screen; the body track starts after the lead-in.
        area = dict(x=0, y=0, width=640, height=360, dpi=96)
        spec = seewo.layout(area, config(style="seewo"))
        self.assertAlmostEqual(19, spec["icon"]["x"], delta=1)
        self.assertAlmostEqual(20, spec["icon"]["width"], delta=1)
        self.assertAlmostEqual(46, spec["prefix"]["x"], delta=2)
        self.assertGreater(spec["viewport"]["x"], spec["prefix"]["x"] + spec["prefix"]["width"] - 1)
        self.assertAlmostEqual(52, window_rect(config(style="seewo"), area)["height"], delta=1)
        self.assertAlmostEqual(19.5, spec["font"], delta=1)

    def test_advance_handles_cjk_latin_and_emoji(self):
        self.assertAlmostEqual(4 * 20, seewo.advance("消息提醒", 20))
        self.assertAlmostEqual(3 * 20 * 0.55, seewo.advance("abc", 20))
        self.assertGreater(seewo.advance("😀", 20), seewo.advance("a", 20))


class MotionTest(unittest.TestCase):
    def schedule(self, items):
        config = validate(dict(style="seewo", messages=items))
        spec = seewo.layout(AREA, config)
        return (config, spec, seewo.durations(config, spec["font"], spec["speed"],
                                              spec["viewport"]["width"]))

    def test_prefix_is_fixed_and_outside_the_scroll_track(self):
        config, spec, _ = self.schedule(["任意消息"])
        prefix, view = spec["prefix"], spec["viewport"]
        self.assertIsNotNone(prefix)
        self.assertEqual("消息提醒：", prefix["text"])
        self.assertLessEqual(prefix["x"] + prefix["width"], view["x"])
        # The track starts after the lead-in and ends inside the right margin.
        self.assertGreater(view["x"], prefix["x"] + prefix["width"])
        self.assertEqual(0, view["y"])
        self.assertLess(view["x"] + view["width"], spec["width"])

    def test_empty_prefix_leaves_the_track_at_the_icon(self):
        config = validate(dict(style="seewo", prefix="", messages=["消息"]))
        spec = seewo.layout(AREA, config)
        self.assertIsNone(spec["prefix"])
        icon = spec["icon"]
        self.assertGreater(spec["viewport"]["x"], icon["x"] + icon["width"])

    def test_body_enters_from_the_right_edge(self):
        config, spec, _ = self.schedule(["明天上午大扫除"])
        width = spec["viewport"]["width"]
        # t=0 sits just past the right edge, walking left afterwards.
        self.assertEqual(width, seewo.offset(0, spec["speed"], width))
        self.assertLess(seewo.offset(1, spec["speed"], width), width)
        self.assertGreater(seewo.offset(1, spec["speed"], width), 0)

    def test_body_leaves_past_the_lead_in_and_comes_back(self):
        config, spec, durations = self.schedule(["明天上午大扫除"])
        width, speed = spec["viewport"]["width"], spec["speed"]
        run = seewo.advance("明天上午大扫除", spec["font"])
        # Crossing takes (track + body) / speed, so the body is fully out at the end.
        self.assertAlmostEqual((width + run * seewo.WIDTH_SLACK) / speed, durations[0], delta=1e-6)
        self.assertLess(seewo.offset(durations[0] - 0.01, speed, width) + run, 1.0)
        # The next pass starts again at the right edge.
        _, local, rounds = seewo.locate(durations[0] + 0.001, durations)
        self.assertEqual(1, rounds)
        self.assertGreater(seewo.offset(local, speed, width), width - 5)

    def test_every_message_scrolls_even_a_short_one(self):
        config, spec, durations = self.schedule(["集合"])
        width, speed = spec["viewport"]["width"], spec["speed"]
        self.assertGreater(durations[0], 1.0)
        self.assertGreater(seewo.offset(0.5, speed, width), 0)
        self.assertLess(seewo.offset(2.5, speed, width), seewo.offset(0.5, speed, width))

    def test_messages_take_turns_in_order(self):
        _, spec, durations = self.schedule(["第一条", "第二条更长一些的内容", "第三条"])
        self.assertEqual(3, len(durations))
        seen, moment = [], 0.0
        for duration in durations:
            index, local, rounds = seewo.locate(moment, durations)
            self.assertEqual(len(seen), index)
            self.assertEqual(0, rounds)
            self.assertAlmostEqual(0.0, local, delta=1e-6)
            seen.append(index)
            moment += duration
        self.assertEqual([0, 1, 2], seen)
        index, local, rounds = seewo.locate(moment, durations)
        self.assertEqual(0, index)
        self.assertAlmostEqual(0.0, local, delta=1e-6)
        self.assertEqual(1, rounds)

    def test_locate_survives_edge_times(self):
        _, _, durations = self.schedule(["a", "b"])
        for elapsed in (0, -5, 1e-9, sum(durations), sum(durations) * 3 + 0.5, 1e6):
            index, local, rounds = seewo.locate(elapsed, durations)
            self.assertIn(index, (0, 1))
            self.assertGreaterEqual(local, 0)
            self.assertLess(local, max(durations) + 1e-6)
            self.assertGreaterEqual(rounds, 0)

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
                # Comment tails (GAP_EM carries a trailing note) are not arithmetic.
                cls.values[name] = eval(text.split("//")[0].replace("f", "").strip(),
                                        {"__builtins__": {}}, {})

    def test_every_shared_metric_matches(self):
        expect = dict(BAR_HEIGHT=seewo.BAR_HEIGHT, TOP=seewo.TOP, FONT=seewo.FONT,
                      ICON=seewo.ICON, ICON_INSET=seewo.ICON_INSET, ICON_GAP=seewo.ICON_GAP,
                      RIGHT_INSET=seewo.RIGHT_INSET, STROKE=seewo.STROKE,
                      SPEED_EM=seewo.SPEED_EM, GAP_EM=seewo.GAP_EM,
                      RATE_DEFAULT=seewo.RATE_DEFAULT,RATE_MIN=seewo.RATE_MIN,RATE_MAX=seewo.RATE_MAX,
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

    def pixels(self, elapsed=None):
        # At t=0 the body is still outside the track (it enters from the right),
        # so sample the middle of the first pass unless told otherwise.
        if elapsed is None:
            elapsed = self.surface.durations[0] / 2
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "band.bmp"
            self.surface.paint(elapsed)
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

    def test_entry_instant_shows_only_the_lead_in(self):
        # Nothing of the body is inside the track at t=0: it starts at the right edge.
        pixel = self.pixels(0.0)
        white = sum(1 for y in range(0, self.surface.height, 3)
                    for x in range(self.surface.spec["viewport"]["x"] + 4, self.surface.width, 3)
                    if min(pixel(x, y)[:3]) > 200)
        self.assertEqual(0, white, "the body has not entered yet")

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
