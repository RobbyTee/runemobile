#!/usr/bin/env python3
"""Parser tests for harness_server.py, fed the line shapes the real scripts print.

Run: /usr/bin/python3 -m unittest discover -s tools/mcp -p 'test_*.py'
"""

import re
import subprocess
import sys
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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


# logcat -v time, in the shapes Hooks.frame, librlawt and ThermalGovernor print.
_HOOKS = "I/jvm-stdout(28833): 2026-10-20 17:00:00 GMT [Client] INFO  net.runelite.client.callback.Hooks -"
_RLAWT = "I/librlawt(28833):"
_GOV = "I/MainActivity(28833):"
FPS_LOGCAT = "\n".join(
    [
        f"10-20 17:00:00.050 {_RLAWT} [gpuprobe] timer queries armed",
        f"10-20 17:00:00.900 {_RLAWT} [gpuprobe] frames=40 avg=9.10 min=8.00 max=11.00 ms disjoint=40 hitch=0",
        f"10-20 17:00:01.000 {_HOOKS} [fpsprobe] fps=50 gs=LOGIN_SCREEN",
        f"10-20 17:00:01.000 {_HOOKS} [frameprobe] n=50 min=19.00 avg=20.00 p95=21.00 max=22.00 gs=LOGIN_SCREEN",
        f"10-20 17:00:01.900 {_RLAWT} [gpuprobe] frames=50 avg=9.20 min=8.10 max=10.50 ms disjoint=50 hitch=0",
        f"10-20 17:00:02.010 {_GOV} thermal status=0 -> sceneResolutionScale -1->100 (baseline=100)",
        f"10-20 17:00:02.000 {_HOOKS} [fpsprobe] fps=48 gs=LOGGED_IN",
        f"10-20 17:00:02.000 {_HOOKS} [frameprobe] n=48 min=18.00 avg=20.80 p95=24.00 max=30.00 gs=LOGGED_IN",
        f"10-20 17:00:02.900 {_RLAWT} [gpuprobe] frames=48 avg=15.00 min=13.00 max=17.00 ms disjoint=48 hitch=0",
        f"10-20 17:00:03.000 {_HOOKS} [fpsprobe] fps=46 gs=LOGGED_IN",
        f"10-20 17:00:03.000 {_HOOKS} [frameprobe] n=46 min=18.50 avg=21.70 p95=26.00 max=140.00 gs=LOGGED_IN",
        f"10-20 17:00:03.900 {_RLAWT} [gpuprobe] frames=46 avg=17.00 min=13.50 max=120.00 ms disjoint=46 hitch=1",
        f"10-20 17:00:04.000 {_HOOKS} [fpsprobe] fps=50 gs=LOGGED_IN",
        f"10-20 17:00:04.000 {_HOOKS} [frameprobe] n=50 min=19.00 avg=20.00 p95=21.00 max=22.00 gs=LOGGED_IN",
        f"10-20 17:00:04.500 {_GOV} thermal status=2 -> sceneResolutionScale 100->85 (baseline=100)",
        f"10-20 17:00:04.900 {_RLAWT} [gpuprobe] no usable GPU-time this window (availSeen=0 disjoint=50)",
        f"10-20 17:00:05.000 {_HOOKS} [fpsprobe] fps=55 gs=LOGGED_IN",
        f"10-20 17:00:05.000 {_HOOKS} [frameprobe] n=55 min=17.00 avg=18.20 p95=19.00 max=20.00 gs=LOGGED_IN",
        f"10-20 17:00:05.900 {_RLAWT} [gpuprobe] frames=55 avg=12.00 min=11.00 max=13.00 ms disjoint=0 hitch=0",
    ]
) + "\n"

FPS_LIVE_OUT = """\
>> capturing 20s of fps/frame probe (label=base) ...
>> thermal[start]: hottest=41.5C status=1
>> thermal[end]: hottest=n/aC status=n/a
>> wrote 23 rows to /tmp/x.csv
base        fps    n=4    mean=49.8  median=49.0  min=46  max=55
frame: no LOGGED_IN windows captured
base        gpu    windows=2   frames=94    min=13.00  avg=15.98  max=120.00  (ms GPU)  disjoint=n/a  hitch=1  empty=0  (excluded: 1 untagged, 1 other-state)
base        thermal samples=4    hottest=42.5..45.5C  status=1->2 (max 2)
"""


class FpsMeasure(unittest.TestCase):
    def test_summary_lines(self):
        r = hs.parse_fps_measure(FPS_LIVE_OUT)
        self.assertEqual(r["fps"], {"n": 4, "mean": 49.8, "median": 49.0, "min": 46, "max": 55})
        self.assertEqual(r["frameMs"], {})
        self.assertEqual(r["missing"], ["frame: no LOGGED_IN windows captured"])
        self.assertEqual(r["gpuMs"]["disjoint"], "n/a")
        self.assertEqual((r["gpuMs"]["excludedUntagged"], r["gpuMs"]["excludedOtherState"]), (1, 1))
        self.assertEqual(
            r["thermalRows"],
            {"samples": 4, "hottestMinC": 42.5, "hottestMaxC": 45.5, "statusStart": 1, "statusEnd": 2, "statusMax": 2},
        )
        self.assertEqual(r["thermal"], {"start": {"hottestC": 41.5, "status": 1}, "end": {"hottestC": "n/a", "status": "n/a"}})
        self.assertEqual(r["governor"], {})
        self.assertEqual(r["warnings"], [])

    def test_script_and_parser_agree(self):
        """Runs the real fps-measure.sh on FPS_LOGCAT, so a format change on either side fails here."""
        script = hs.REPO_ROOT / "android/dev-tools/gpu/fps-measure.sh"
        with tempfile.TemporaryDirectory() as td:
            log, csv = Path(td, "logcat.txt"), Path(td, "out.csv")
            log.write_text(FPS_LOGCAT)
            p = subprocess.run(
                ["bash", str(script), "--from-file", str(log), "-o", str(csv), "fixture"],
                capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(p.returncode, 0, p.stderr)
            rows = csv.read_text().splitlines()
        r = hs.parse_fps_measure(p.stdout + "\n" + p.stderr)
        self.assertEqual(r["fps"], {"n": 4, "mean": 49.8, "median": 49.0, "min": 46, "max": 55})
        # frame-weighted over the 4 LOGGED_IN windows: 3997.6 / 199
        self.assertEqual(r["frameMs"], {"windows": 4, "frames": 199, "min": 17.0, "avg": 20.09, "p95": 22.5, "max": 140.0})
        # the first window is before any Hooks line (untagged), the second is LOGIN_SCREEN; 2162 / 149
        self.assertEqual(
            r["gpuMs"],
            {"windows": 3, "frames": 149, "min": 11.0, "avg": 14.51, "max": 120.0, "disjoint": 94, "hitch": 1,
             "empty": 1, "excludedUntagged": 1, "excludedOtherState": 1},
        )
        # the arm-time apply (-1->100) is not a change; 100->85 is
        self.assertEqual(r["governor"], {"changes": 1, "scale": 85, "minScale": 85, "baseline": 100})
        self.assertEqual(len(r["warnings"]), 1)
        self.assertIn("100->85@17:00:04.500", r["warnings"][0])
        self.assertEqual(rows[0], "label,kind,idx,a,b,c,d,e,gs,f,g")
        self.assertTrue(all(len(row.split(",")) == 11 for row in rows))
        kinds = [row.split(",")[1] for row in rows[1:]]
        self.assertEqual(
            {k: kinds.count(k) for k in set(kinds)}, {"fps": 5, "frame": 5, "gpu": 5, "gpuempty": 1, "govern": 2}
        )
        self.assertIn("fixture,gpu,3,46,13.50,17.00,,120.00,LOGGED_IN,46,1", rows)


# The frame-pacing lines FrameStats, -Xlog:gc,safepoint and the native present probe print, as logcat shows them.
_JVM = "I/jvm-stdout(28833):"
PACING_LOGCAT = "\n".join(
    [
        f"10-20 17:00:00.500 {_RLAWT} [presentprobe] presents=30 gapMaxMs=40.00 swapAvgMs=2.00 swapMaxMs=9.00",
        f"10-20 17:00:01.000 {_HOOKS} [fpsprobe] fps=58 gs=LOGGED_IN",
        f"10-20 17:00:01.000 {_HOOKS} [framebins] dt=44:50,60:1 jit=20:49,55:1 max=105.00 jitMax=88.00 gcN=1 gcMs=12 heapMB=180/256 gs=LOGGED_IN",
        f"10-20 17:00:01.100 {_JVM} [2026-10-20T17:00:01.100-0700][12.345s][gc] GC(7) Pause Young (Normal) (G1 Evacuation Pause) 120M->60M(256M) 11.500ms",
        f"10-20 17:00:01.100 {_JVM} [2026-10-20T17:00:01.100-0700][12.346s][safepoint] Safepoint \"G1CollectForAllocation\", Time since last: 900000000 ns, Reaching safepoint: 2000 ns, Cleanup: 3000 ns, At safepoint: 11795000 ns, Leaving safepoint: 2000 ns, Total: 11800000 ns",
        f"10-20 17:00:01.200 {_JVM} [2026-10-20T17:00:01.200-0700][12.400s][gc       ] GC(8) Concurrent Mark Cycle 30.000ms",
        f"10-20 17:00:01.500 {_RLAWT} [presentprobe] presents=57 gapMaxMs=105.00 swapAvgMs=1.50 swapMaxMs=6.00",
        f"10-20 17:00:02.000 {_HOOKS} [framebins] dt=44:58 jit=20:57 max=17.70 jitMax=1.10 gcN=0 gcMs=0 heapMB=190/256 gs=LOGGED_IN",
        f"10-20 17:00:02.300 {_JVM} [2026-10-20T17:00:02.300-0700][13.500s][safepoint ] Safepoint \"Cleanup\", Time since last: 1000000000 ns, Reaching safepoint: 1000 ns, Cleanup: 1000 ns, At safepoint: 197000 ns, Leaving safepoint: 1000 ns, Total: 200000 ns",
        f"10-20 17:00:02.500 {_RLAWT} [presentprobe] presents=58 gapMaxMs=18.00 swapAvgMs=1.20 swapMaxMs=3.00",
        f"10-20 17:00:03.000 {_HOOKS} [framehist] n=2950 p50=17.78 p95=19.95 p99=35.48 p999=112.20 max=140.00 jit50=1.00 jit95=2.82 jit99=17.78 pauses=1 gcN=12 gcMs=95 up=60s scope=LOGGED_IN",
    ]
) + "\n"

TESTDATA = Path(__file__).resolve().parent / "testdata"


def run_fps_measure(text, label="fixture"):
    script = hs.REPO_ROOT / "android/dev-tools/gpu/fps-measure.sh"
    with tempfile.TemporaryDirectory() as td:
        log, csv = Path(td, "logcat.txt"), Path(td, "out.csv")
        log.write_text(text)
        p = subprocess.run(
            ["bash", str(script), "--from-file", str(log), "-o", str(csv), label],
            capture_output=True, text=True, timeout=60,
        )
        rows = csv.read_text().splitlines() if csv.exists() else []
    return p, rows


class Pacing(unittest.TestCase):
    def test_capture_percentiles_match_the_java_session(self):
        """testdata/framestats.log is real FrameStats output (12 s of synthetic frames, the first 2 s LOGIN_SCREEN,
        LCG jitter, every 97th frame doubled, one 120 ms hitch). Cut at the [framehist] line, the capture holds
        exactly the seconds that line summarises, so the script's bucket decode must reproduce the Java one."""
        lines = (TESTDATA / "framestats.log").read_text().splitlines()
        cut = next(i for i, ln in enumerate(lines) if "[framehist]" in ln)
        hist_line = lines[cut]
        head = "\n".join(lines[:cut]) + "\n"
        p, _ = run_fps_measure(head)
        self.assertEqual(p.returncode, 0, p.stderr)
        pacing = hs.parse_fps_measure(p.stdout)["pacingMs"]
        java = dict((k, hs._num(v)) for k, v in re.findall(r"(\w+)=([\d.]+)", hist_line))
        self.assertEqual(pacing["frames"], java["n"])
        for k in ("p50", "p95", "p99", "p999", "max", "jit50", "jit95", "jit99"):
            self.assertEqual(pacing[k], java[k], k)
        # LOGIN_SCREEN seconds are in the log but in neither summary
        self.assertIn("gs=LOGIN_SCREEN", head)

    def test_gc_present_and_session(self):
        p, rows = run_fps_measure(PACING_LOGCAT)
        self.assertEqual(p.returncode, 0, p.stderr)
        r = hs.parse_fps_measure(p.stdout + "\n" + p.stderr)
        # 44:108 + 60:1 -> the 109th frame is the slow one; its 112.2 edge is capped at the 105 max
        self.assertEqual(
            r["pacingMs"],
            {"windows": 2, "frames": 109, "p50": 17.78, "p95": 17.78, "p99": 17.78, "p999": 105.0, "max": 105.0,
             "jit50": 1.12, "jit95": 1.12, "jit99": 1.12},
        )
        # a concurrent cycle is not a pause; both safepoint tag paddings count
        self.assertEqual(
            r["gc"],
            {"gcN": 1, "gcMs": 12, "heapMaxMB": 190, "pauses": 1, "pauseMs": 11.5, "pauseMax": 11.5,
             "safepoints": 2, "safepointMs": 12.0, "safepointMax": 11.8},
        )
        # the first present window predates any Hooks line; swapAvg is present-weighted: 155.1 / 115
        self.assertEqual(
            r["present"],
            {"windows": 2, "presents": 115, "perSec": 57.5, "gapMax": 105.0, "swapAvg": 1.35, "swapMax": 6.0,
             "excludedUntagged": 1, "excludedOtherState": 0},
        )
        self.assertEqual(r["session"]["n"], 2950)
        self.assertEqual(r["session"]["upSeconds"], 60)
        self.assertEqual(r["session"]["p999"], 112.2)
        self.assertTrue(all(len(row.split(",")) == 11 for row in rows))
        self.assertIn("fixture,framebins,0,1,12,180,105.00,88.00,LOGGED_IN,44:50;60:1,20:49;55:1", rows)
        self.assertIn("fixture,present,0,30,40.00,2.00,,9.00,,,", rows)
        self.assertIn("fixture,gcpause,0,11.500,,,,,LOGGED_IN,,", rows)
        self.assertIn("fixture,safepoint,1,200000,,,,,LOGGED_IN,,", rows)

    def test_old_builds_without_framebins_still_parse(self):
        r = hs.parse_fps_measure(FPS_LIVE_OUT)
        self.assertEqual((r["pacingMs"], r["gc"], r["present"], r["session"]), ({}, {}, {}, {}))



# A Release perf-probe.log as ViewController.m's filter writes it: the probe's own lines and nothing else.
IOS_PERF_LOG = "\n".join(
    [
        "[memwatch t= 0.0s] avail=2210.4MB footprint=96.2MB heapMB=- thermal=nominal",
        "[perfprobe] ARMED: fpsprobe + -Xlog:gc,safepoint + presentprobe",
        "[memwatch t= 0.5s] avail=2190.0MB footprint=180.5MB heapMB=12/64 thermal=nominal",
        "2026-10-20 17:00:01 [Client] INFO  n.r.c.callback.Hooks - [fpsprobe] fps=58 gs=LOGGED_IN",
        "2026-10-20 17:00:01 [Client] INFO  n.r.c.h.FrameStats - [framebins] dt=44:58 jit=20:57 max=17.70 jitMax=1.10 gcN=0 gcMs=0 heapMB=190/256 gs=LOGGED_IN",
        "[coldstart] firstFrame sinceStartMs=8123",
        "[presentprobe] presents=59 gapMaxMs=19.00 swapAvgMs=0.80 swapMaxMs=2.50",
        "[memwatch t=35.1s] avail=1650.2MB footprint=640.0MB heapMB=201/300 thermal=fair",
        "[coldstart] gpuFirstPresent sinceStartMs=15011",
        "[memwatch t=1205.3s] avail=1601.9MB footprint=655.5MB heapMB=190/300 thermal=nominal",
    ]
) + "\n"


class MemAndColdStart(unittest.TestCase):
    def test_ios_memwatch_and_coldstart(self):
        p, rows = run_fps_measure(IOS_PERF_LOG)
        self.assertEqual(p.returncode, 0, p.stderr)
        r = hs.parse_fps_measure(p.stdout)
        # every sample counts, whatever the game state; the worst thermal state wins over the last one
        self.assertEqual(
            r["mem"],
            {"samples": 4, "tFirst": 0.0, "tLast": 1205.3, "availMinMB": 1601.9, "footprintFirstMB": 96.2,
             "footprintLastMB": 655.5, "footprintMaxMB": 655.5, "heapUsedMB": 190, "heapCommittedMB": 300,
             "heapUsedMaxMB": 201, "thermalFirst": "nominal", "thermalLast": "nominal", "thermalWorst": "fair"},
        )
        self.assertEqual(r["coldstartMs"], {"firstFrame": 8123, "gpuFirstPresent": 15011})
        self.assertEqual(r["present"]["presents"], 59)
        self.assertIn("fixture,mem,0,0.0,2210.4,96.2,,,,nominal,", rows)
        self.assertIn("fixture,mem,2,35.1,1650.2,640.0,201,300,LOGGED_IN,fair,", rows)
        self.assertIn("fixture,coldstart,0,8123,,,,,,firstFrame,", rows)
        self.assertEqual({len(row.split(",")) for row in rows}, {11})

    def test_android_splash_stamp(self):
        text = PACING_LOGCAT + (
            "10-20 17:00:04.000 I/MainActivity( 4321): onboard: splash dismissed "
            "(frame=false state=LOGIN_SCREEN timeout=true sinceStartMs=60412)\n"
        )
        p, _ = run_fps_measure(text)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(hs.parse_fps_measure(p.stdout)["coldstartMs"], {"splashTimeout": 60412})

    def test_memwatch_alone_reports_but_a_stamp_alone_does_not(self):
        p, _ = run_fps_measure("[memwatch t= 0.0s] avail=2210.4MB footprint=96.2MB\n")
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(hs.parse_fps_measure(p.stdout)["mem"]["thermalWorst"], "n/a")  # a build before heap/thermal
        p, _ = run_fps_measure("[coldstart] firstFrame sinceStartMs=8123\n")
        self.assertEqual(p.returncode, 2)


class IosPullLogs(unittest.TestCase):
    """devicectl is mocked: each test says which Documents files the phone has."""

    def pull(self, on_phone):
        root = Path(tempfile.mkdtemp(prefix="harness-pull-"))
        self.addCleanup(shutil.rmtree, root, True)

        def copy(_udid, _bundle, source, dest):
            if source not in on_phone:
                return 1, "", f"ERROR: {source} not found"
            Path(dest).write_text(on_phone[source])
            return 0, "", ""

        with mock.patch.object(hs, "REPO_ROOT", root), mock.patch.object(hs.shutil, "which", return_value="/usr/bin/xcrun"), \
                mock.patch.object(hs, "_devicectl_copy", side_effect=copy):
            res = hs.tool_ios_pull_logs({"device": "UDID"})
        return res["isError"], json.loads(res["content"][0]["text"]), root

    def test_release_build_has_only_the_perf_probe_log(self):
        is_err, body, _ = self.pull({"Documents/perf-probe.log": IOS_PERF_LOG})
        self.assertFalse(is_err, body)
        self.assertEqual(sorted(body["files"]), ["perf-probe.log"])
        perf = body["files"]["perf-probe.log"]["perf"]
        self.assertEqual(perf["coldstartMs"], {"firstFrame": 8123, "gpuFirstPresent": 15011})
        self.assertEqual(perf["mem"]["samples"], 4)

    def test_debug_build_has_only_jvm_stdio(self):
        is_err, body, _ = self.pull({"Documents/jvm-stdio.log": "[jvm] booting\n"})
        self.assertFalse(is_err, body)
        self.assertEqual(sorted(body["files"]), ["jvm-stdio.log"])
        self.assertNotIn("perf", body["files"]["jvm-stdio.log"])  # no probe lines -> no summary

    def test_neither_log_is_an_error_and_leaves_no_dir(self):
        is_err, body, root = self.pull({})
        self.assertTrue(is_err)
        self.assertIn("perfprobe marker", body["hint"])
        pulls = root / "ios/app/evidence/device-pull"
        self.assertEqual(list(pulls.iterdir()) if pulls.exists() else [], [])


SIM_LOG = """\
[boot] poll 0ms  draws=0 nullGraphics=0 frames=0 state=null
2026-10-22 19:38:05 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] draw#1 nullGraphics=0 capturedFrames=0 state=null
2026-10-22 19:38:05 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] gameState null -> LOGIN_SCREEN (draw#1 loginIndex=0 launcher=false)
[boot] poll 250ms  draws=2 nullGraphics=0 frames=0 state=LOGIN_SCREEN
2026-10-22 19:38:05 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] draw#10 nullGraphics=0 capturedFrames=0 state=LOGIN_SCREEN
[boot] poll 500ms  draws=12 nullGraphics=1 frames=1 state=LOGIN_SCREEN
[census] checked=196 missing=0 (first frame after 4795 ms, seq 1)
"""

CENSUS_GREEN = """\
>> Census: 196 lookups, CPU renderer
dev.audaro.runemobile: 70940
   result after 9 s (the app keeps running)
result=/repo/build/bridge-census/result.txt
checked=196 missing=0 (list 196) first frame after 4795 ms, seq 1
census_md5=43a95ea6f128bc3eb574fe2d8db2be20
IOS_BRIDGE_CENSUS_GREEN md5=43a95ea6f128bc3eb574fe2d8db2be20
VERDICT: IOS_BRIDGE_CENSUS_GREEN
"""

CENSUS_RED = """\
   missing: smethod net/runelite/client/headless/JavaBridge noSuchMethod ()V java.lang.NoSuchMethodError: static Lnet/runelite/client/headless/JavaBridge;.noSuchMethod()V
   missing: field org/lwjgl/opengl/GLCapabilities OpenGL99 Z java.lang.NoSuchFieldError: org.lwjgl.opengl.GLCapabilities.OpenGL99 Z
!! 2 of 198 lookups did not resolve
IOS_BRIDGE_CENSUS_RED
VERDICT: IOS_BRIDGE_CENSUS_RED
"""


class SimProgress(unittest.TestCase):
    def test_draws_state_and_transitions(self):
        p = hs.parse_sim_progress(SIM_LOG)
        self.assertEqual(p["draws"], 12)  # the highest count either line kind reports
        self.assertEqual(p["nullGraphics"], 1)
        self.assertEqual(p["state"], "LOGIN_SCREEN")
        self.assertEqual(p["states"], ["LOGIN_SCREEN"])  # state=null is not a state
        self.assertEqual(p["fatal"], [])
        self.assertTrue(hs.sim_reached(p, "frame"))
        self.assertTrue(hs.sim_reached(p, "LOGIN_SCREEN"))
        self.assertFalse(hs.sim_reached(p, "LOGGED_IN"))

    def test_states_reached_stay_reached(self):
        p = hs.parse_sim_progress(INGAME_LOG)
        self.assertEqual(p["states"], ["LOGIN_SCREEN", "LOGGING_IN", "LOADING", "LOGGED_IN"])
        self.assertEqual(p["state"], "LOGGED_IN")
        self.assertTrue(hs.sim_reached(p, "LOGGING_IN"))

    def test_nothing_drawn_yet(self):
        p = hs.parse_sim_progress("[boot] poll 0ms  draws=0 nullGraphics=0 frames=0 state=null\n")
        self.assertFalse(hs.sim_reached(p, "frame"))
        self.assertIsNone(p["state"])

    def test_fatal_lines_from_the_shell_log(self):
        p = hs.parse_sim_progress("[jvm] FATAL: embedded jre not found in bundle\n[jvm] boot ok\n")
        self.assertEqual(p["fatal"], ["[jvm] FATAL: embedded jre not found in bundle"])


class SimProcessOutput(unittest.TestCase):
    def test_simctl_launch_pid(self):
        self.assertEqual(hs.parse_simctl_launch_pid("dev.audaro.runemobile: 70940\n", hs.IOS_BUNDLE_ID), 70940)
        self.assertIsNone(hs.parse_simctl_launch_pid("An error was encountered processing the command\n", hs.IOS_BUNDLE_ID))

    def test_launchctl_pid(self):
        listing = (
            "PID\tStatus\tLabel\n"
            "52185\t0\tUIKitApplication:com.apple.Spotlight[5b25][rb-legacy]\n"
            "-\t0\tUIKitApplication:dev.audaro.runemobileX[1111][rb-legacy]\n"
            "73260\t0\tUIKitApplication:dev.audaro.runemobile[ee3e][rb-legacy]\n"
        )
        self.assertEqual(hs.parse_launchctl_app_pid(listing, hs.IOS_BUNDLE_ID), 73260)
        self.assertIsNone(hs.parse_launchctl_app_pid(listing.replace("73260", "-"), hs.IOS_BUNDLE_ID))
        self.assertIsNone(hs.parse_launchctl_app_pid(listing.splitlines()[1], hs.IOS_BUNDLE_ID))

    def test_census_green(self):
        c = hs.parse_census_output(CENSUS_GREEN)
        self.assertEqual((c["checked"], c["missing"], c["listed"]), (196, 0, 196))
        self.assertEqual(c["md5"], "43a95ea6f128bc3eb574fe2d8db2be20")
        self.assertEqual((c["missingLookups"], c["problems"]), ([], []))
        self.assertEqual(hs.parse_verdict(CENSUS_GREEN), ("GREEN", "IOS_BRIDGE_CENSUS_GREEN"))

    def test_census_red(self):
        c = hs.parse_census_output(CENSUS_RED)
        self.assertIsNone(c["checked"])
        self.assertEqual(len(c["missingLookups"]), 2)
        self.assertTrue(c["missingLookups"][0].startswith("smethod net/runelite/client/headless/JavaBridge noSuchMethod"))
        self.assertEqual(c["problems"], ["2 of 198 lookups did not resolve"])
        self.assertEqual(hs.parse_verdict(CENSUS_RED), ("RED", "IOS_BRIDGE_CENSUS_RED"))


class SimIsland(unittest.TestCase):
    """A raw portrait screenshot the size of an iPhone 17 Pro Max: the native rail (gray 39) runs along rows 18-140,
    the letterbox (15) everywhere else, and the island sits over both."""

    W, H = 1320, 2868

    def shot(self, island=True):
        import numpy as np

        a = np.full((self.H, self.W, 3), 15, np.uint8)
        a[18:141] = 39
        self.background = a.copy()
        if island:
            x0, y0, x1, y1 = hs.sim_island_rect(self.W, self.H)
            a[y0 : y1 + 1, x0 : x1 + 1] = 0
        return a

    def test_island_is_painted_with_what_lies_beside_it(self):
        import numpy as np

        out, info = hs.mask_sim_island(self.shot())
        self.assertTrue(info["masked"], info)
        self.assertTrue(np.array_equal(out, self.background))
        self.assertEqual(info["rect"], [469, 39, 382, 116])

    def test_no_island_no_change(self):
        a = self.shot(island=False)
        out, info = hs.mask_sim_island(a)
        self.assertFalse(info["masked"])
        self.assertIn("no island", info["reason"])
        self.assertIs(out, a)

    def test_an_icon_beside_the_island_stops_the_fill(self):
        a = self.shot()
        x0, y0, _x1, _y1 = hs.sim_island_rect(self.W, self.H)
        a[y0 + 20, x0 - 8] = 200  # one bright pixel in the sample band
        out, info = hs.mask_sim_island(a)
        self.assertFalse(info["masked"])
        self.assertIn(f"row {y0 + 20}", info["reason"])
        self.assertIs(out, a)

    def test_landscape_input_is_refused(self):
        import numpy as np

        _out, info = hs.mask_sim_island(np.zeros((1320, 2868, 3), np.uint8))
        self.assertEqual(info, {"masked": False, "reason": "not a portrait screenshot"})


if __name__ == "__main__":
    unittest.main()
