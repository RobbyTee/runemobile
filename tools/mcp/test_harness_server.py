#!/usr/bin/env python3
"""Parser tests for harness_server.py, fed the line shapes the real scripts print.

Run: /usr/bin/python3 -m unittest discover -s tools/mcp -p 'test_*.py'
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness_server as hs  # noqa: E402

LOGIN_LOG = """\
2026-10-20 16:25:01 PDT [main] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] ENABLED dir=ios/dev-tools/headless/out interval=50 maxFrames=20 preLoginMax=6
2026-10-20 16:25:02 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] draw#1 nullGraphics=0 capturedFrames=0 state=null
2026-10-20 16:25:02 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] gameState null -> LOGIN_SCREEN (draw#1 loginIndex=0 launcher=false)
2026-10-20 16:25:02 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] loginIndex -2 -> 0 (draw#1 state=LOGIN_SCREEN launcher=false)
2026-10-20 16:25:03 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] draw#50 nullGraphics=0 capturedFrames=0 state=LOGIN_SCREEN
2026-10-20 16:25:03 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] wrote frame-LOGIN_SCREEN-001-draw00050.png (765x503)
"""

INGAME_LOG = """\
[headless-capture] draw#1 nullGraphics=0 capturedFrames=0 state=null
[headless-capture] gameState null -> LOGIN_SCREEN (draw#1 loginIndex=10 launcher=true)
[headless-capture] gameState LOGIN_SCREEN -> LOGGING_IN (draw#21 loginIndex=10 launcher=true)
[headless-capture] draw#250 nullGraphics=0 capturedFrames=4 state=LOGGING_IN
[headless-capture] gameState LOGGING_IN -> LOADING (draw#253 loginIndex=10 launcher=true)
[headless-capture] gameState LOADING -> LOGGED_IN (draw#265 loginIndex=10 launcher=true)
[headless-capture] draw#300 nullGraphics=2 capturedFrames=5 state=LOGGED_IN
java.lang.IllegalStateException: boom
2026-10-20 16:40:48 PDT [Client] ERROR injected-client - Client error: Login authentication error. Response code: 401
"""

EDGE2EDGE_GREEN = """\
verdict line: 2026-06-04 04:32:11 PDT [Client] INFO  n.r.client.headless.EdgeToEdgeProbe - [edge2edge] EDGE2EDGE root=PreEoc canvas=1560x720 viewport=1560x720@0,0 viewportFillsCanvas=true hudTotal=20 hudHidden=20

  canvas=1560x720 viewport=1560x720
  viewport fills wide canvas: 1
  all HUD hidden (20/20):      1

actual md5=daf7f1396b360a5202c754ae716454fd
expect md5=daf7f1396b360a5202c754ae716454fd
VERDICT: EDGE2EDGE_GREEN_AS_EXPECTED
"""

HUD_ISO_RED = """\
verdict line: 2026-06-04 00:15:41 PDT [Client] INFO  n.r.client.headless.HudIsolationTest - [hud-iso] VERDICT instrument_ok=true base_run_flip=true mask_hidden=false hidden_held=true hidden_clickable=false trans_held=false trans_clickable=false resolver=none

---- instrument ----
  instrument_ok=true (base_run_flip=true)
---- predictions (gated; removal also confirmed visually in hud-*.png) ----
  PASS mask_hidden=false
  PASS hidden_held=true
  PASS hidden_clickable=false
  PASS trans_held=false
  FAIL trans_clickable=false (expected true)

actual md5=2671b87ad11905f9341715909c2423ee
expect md5=77d03997ff0b7d3062b6fe8b8a6d778d
VERDICT: HUD_ISO_RED — predictions violated (1 fail / 4 pass); inspect the trail + PNGs.
"""

HUD_ISO_INVALID = """\
verdict line: [hud-iso] VERDICT instrument_ok=false base_run_flip=false mask_hidden=false

---- instrument ----
  instrument_ok=false (base_run_flip=false)

>>> INSTRUMENT_INVALID — the run-orb click did not flip OPTION_RUN at baseline.
>>> The hidden/transparent click-through results are NOT trustworthy. Fix the orb instrument first.
"""

MOBILEUI_RED = """\
================ MobileUiBridge STEP-0 SMOKE ================
  ea-live                    true
  persist x-launch           false
  tuple: ea=true;list=true;xlaunch=false
  md5:   0123456789abcdef0123456789abcdef
  VERDICT: RED
============================================================
"""

GLSL_PASS = """\
loaded 73 downport casts (incl. 0 extra)

=== uiScalingMode=NEAREST (ord 0) -> PASS ===

=== uiScalingMode=LINEAR (ord 1) -> PASS ===

ALL MODES PASS (vert+frag)
"""

GLSL_FAIL = """\
loaded 74 downport casts (incl. 1 extra)

=== uiScalingMode=NEAREST (ord 0) -> FAIL ===
   [frag] ERROR: 0:561: '-' :  wrong operand types: no operation '-' exists
   [frag] ERROR: 0:561: '' : compilation terminated

=== uiScalingMode=LINEAR (ord 1) -> PASS ===

=== uiScalingMode=MITCHELL (ord 2) -> FAIL ===
   [vert] ERROR: 0:207: '=' :  cannot convert from ' const 4-component vector of float'

SOME MODES FAIL
"""


class CaptureMarkers(unittest.TestCase):
    def test_login_screen_state_from_state_lines(self):
        m = hs.parse_capture_markers(LOGIN_LOG)
        self.assertEqual(m["gameState"], "LOGIN_SCREEN")
        self.assertEqual(m["nullGraphics"], "0")
        self.assertEqual(m["gameStateProgression"], ["null", "LOGIN_SCREEN"])
        self.assertEqual(m["exceptionLines"], 0)
        self.assertEqual(m["errorLines"], 0)

    def test_in_game_progression_and_last_state(self):
        m = hs.parse_capture_markers(INGAME_LOG)
        self.assertEqual(m["gameState"], "LOGGED_IN")
        self.assertEqual(m["nullGraphics"], "2")
        self.assertEqual(
            m["gameStateProgression"], ["null", "LOGIN_SCREEN", "LOGGING_IN", "LOADING", "LOGGED_IN"]
        )
        self.assertEqual(m["exceptionLines"], 1)
        self.assertEqual(m["errorLines"], 1)

    def test_empty_log_reports_nothing(self):
        self.assertEqual(hs.parse_capture_markers(""), {"exceptionLines": 0, "errorLines": 0})


class Frames(unittest.TestCase):
    FRAMES = [
        "/x/frame-LOGGED_IN-001-draw00300.png",
        "/x/frame-LOGGED_IN-002-draw00350.png",
        "/x/frame-LOGGING_IN-001-draw00100.png",
        "/x/frame-LOGIN_SCREEN-001-draw00050.png",
        "/x/frame-LOGIN_SCREEN-002-draw00900.png",  # logged out again late in the run
    ]

    def test_order_is_by_draw_counter(self):
        self.assertEqual(
            [hs.frame_draw_number(f) for f in hs.order_frames(self.FRAMES)], [50, 100, 300, 350, 900]
        )

    def test_last_frame_prefers_logged_in(self):
        self.assertEqual(hs.pick_last_frame(self.FRAMES), "/x/frame-LOGGED_IN-002-draw00350.png")

    def test_last_frame_without_logged_in_is_newest(self):
        pre = [f for f in self.FRAMES if "LOGGED_IN" not in f]
        self.assertEqual(hs.pick_last_frame(pre), "/x/frame-LOGIN_SCREEN-002-draw00900.png")
        self.assertIsNone(hs.pick_last_frame([]))

    def test_frame_state_keeps_underscores(self):
        self.assertEqual(hs.frame_state("/x/frame-LOGIN_SCREEN-001-draw00050.png"), "LOGIN_SCREEN")
        self.assertEqual(hs.frame_state("/x/frame-NULL-001-draw00050.png"), "NULL")


class Verdict(unittest.TestCase):
    def test_wrapped_green_token(self):
        self.assertEqual(hs.parse_verdict(EDGE2EDGE_GREEN), ("GREEN", "EDGE2EDGE_GREEN_AS_EXPECTED"))

    def test_red_run_with_pass_lines_is_red(self):
        self.assertEqual(hs.parse_verdict(HUD_ISO_RED), ("RED", "HUD_ISO_RED"))

    def test_bare_token(self):
        self.assertEqual(hs.parse_verdict(MOBILEUI_RED), ("RED", "RED"))
        self.assertEqual(hs.parse_verdict("  VERDICT: GREEN\n"), ("GREEN", "GREEN"))
        self.assertEqual(hs.parse_verdict("  VERDICT: INCONCLUSIVE\n"), ("INCONCLUSIVE", "INCONCLUSIVE"))

    def test_jvm_verdict_echo_is_not_the_verdict(self):
        self.assertEqual(hs.parse_verdict(HUD_ISO_INVALID), ("INCONCLUSIVE", "INSTRUMENT_INVALID"))

    def test_early_exits(self):
        self.assertEqual(
            hs.parse_verdict("HUD_ISO_NO_VERDICT — the probe never reached DONE. [hud-iso] trail:\n"),
            ("NO_VERDICT", "HUD_ISO_NO_VERDICT"),
        )
        self.assertEqual(
            hs.parse_verdict(">>> INCONCLUSIVE — clicking the visible minimap did not issue a walk\n"),
            ("INCONCLUSIVE", "INCONCLUSIVE"),
        )
        self.assertEqual(hs.parse_verdict("!! log not found: x.log\n"), ("LOG_MISSING", None))
        self.assertEqual(hs.parse_verdict("nothing useful\n"), ("UNKNOWN", None))


class Glsl(unittest.TestCase):
    def test_pass(self):
        rows, summary = hs.parse_glsl_es(GLSL_PASS)
        self.assertEqual(summary, "PASS")
        self.assertEqual([(r["mode"], r["ord"], r["status"]) for r in rows], [("NEAREST", 0, "PASS"), ("LINEAR", 1, "PASS")])
        self.assertTrue(all(r["errors"] == [] for r in rows))

    def test_fail_attaches_errors_to_their_mode(self):
        rows, summary = hs.parse_glsl_es(GLSL_FAIL)
        self.assertEqual(summary, "FAIL")
        self.assertEqual([r["status"] for r in rows], ["FAIL", "PASS", "FAIL"])
        self.assertEqual([e["stage"] for e in rows[0]["errors"]], ["frag", "frag"])
        self.assertEqual(rows[1]["errors"], [])
        self.assertEqual(rows[2]["errors"][0]["stage"], "vert")

    def test_unrecognised_output_parses_to_nothing(self):
        self.assertEqual(hs.parse_glsl_es("uiScalingMode=NEAREST, vertex: OK\n"), ([], None))


BENCH_OUT = """\
BENCH-START target_ns=1000000000
WARMUP-DONE sink=891409065879486
RESULT floatbits.A ops=268435456 ns=1429923791 opsPerSec=187727106 chk=287386259807748185
RESULT raster.B ops=1073741824 ns=1599354208 opsPerSec=671359614 chk=-139434858112
RESULT mixed.A ops=33554432 ns=1853708083 opsPerSec=18101249 chk=19883888602358105
BENCH-END sink=921982210806120507
"""


class Bench(unittest.TestCase):
    def test_dotted_names_parse(self):
        r = hs.parse_bench_results(BENCH_OUT)
        self.assertEqual(sorted(r), ["floatbits.A", "mixed.A", "raster.B"])
        self.assertEqual(r["raster.B"], {"ops": 1073741824, "ns": 1599354208, "opsPerSec": 671359614.0})


class JavaMajor(unittest.TestCase):
    def test_versions(self):
        self.assertEqual(hs.java_major("21.0.8"), 21)
        self.assertEqual(hs.java_major("21.0.12.1"), 21)
        self.assertEqual(hs.java_major("11.0.32"), 11)
        self.assertEqual(hs.java_major("1.8.0_402"), 8)
        self.assertEqual(hs.java_major("26"), 26)
        self.assertIsNone(hs.java_major(None))
        self.assertIsNone(hs.java_major("abc"))


if __name__ == "__main__":
    unittest.main()
