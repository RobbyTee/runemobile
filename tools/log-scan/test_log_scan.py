#!/usr/bin/env python3
"""Tests for log-scan.py, fed the line shapes the app, the phone, the Pixel and the desktop harness print.

Run: python3 -m unittest discover -s tools/log-scan -p 'test_*.py'
"""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("log_scan", HERE / "log-scan.py")
ls = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ls)

WORLDHOPPER = """\
2026-10-22 21:23:46 PDT [pool-5-thread-1] ERROR n.r.c.util.RunnableExceptionLogger - Uncaught exception in runnable net.runelite.client.plugins.worldhopper.WorldHopperPlugin$$Lambda/0x0000000133d1a348@3a4e270b
java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native
\tat net.runelite.client.plugins.worldhopper.ping.RLLibC.<clinit>(RLLibC.java:34)
\tat java.base/java.lang.Thread.run(Unknown Source)
Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.UnsatisfiedLinkError: Could not find JNA native support [in thread "main"]
\tat com.sun.jna.Native.loadNativeDispatchLibrary(Native.java:1053)
2026-10-22 21:24:01 PDT [pool-7-thread-3] ERROR n.r.c.util.RunnableExceptionLogger - Uncaught exception in runnable net.runelite.client.plugins.worldhopper.WorldHopperPlugin$$Lambda/0x0000000133d1a908@a26be53
java.lang.NoClassDefFoundError: Could not initialize class net.runelite.client.plugins.worldhopper.ping.RLLibC
\tat net.runelite.client.plugins.worldhopper.ping.Ping.icmpPing(Ping.java:149)
"""

SESSION_PING = """\
2026-10-22 21:26:01 PDT [Client] INFO  n.r.client.headless.HeadlessCapture - [headless-capture] draw#6640 nullGraphics=17 capturedFrames=40 state=LOGIN_SCREEN
2026-10-22 21:26:01 PDT [pool-2-thread-1] WARN  n.r.client.ClientSessionManager - Unable to ping session service
java.net.ConnectException: Failed to connect to /127.0.0.1:1
\tat okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.java:265)
Caused by: java.net.ConnectException: Connection refused
\t... 20 more
"""

GPU_SHADER_FAIL = """\
2026-06-09 02:26:32 GMT [Client] ERROR n.runelite.client.plugins.gpu.Shader - 1: #version 300 es
2026-06-09 02:26:32 GMT [Client] ERROR n.runelite.client.plugins.gpu.Shader - 573: }
2026-06-09 02:26:32 GMT [Client] ERROR n.r.client.plugins.gpu.GpuPlugin - Error starting GPU plugin
net.runelite.client.plugins.gpu.ShaderException: 0:520: S0001: Type mismatch in arithmetic operation between 'vec2' and 'ivec2'
\tat net.runelite.client.plugins.gpu.Shader.compile(Shader.java:83)
"""

NOISE = {
    "noise": [
        {"id": "ping", "header": "^WARN n\\.r\\.client\\.ClientSessionManager - Unable to ping session service$",
         "cause": "Failed to connect to /127\\.0\\.0\\.1:1", "reason": "deliberate sink"},
        {"id": "sound", "header": "^ERROR injected-client - Error initializing sound engine",
         "sources": ["phone", "android"], "reason": "no Java Sound line on phones"},
        {"id": "gpu-shader", "header": "^ERROR n\\.r\\.client\\.plugins\\.gpu\\.GpuPlugin - Error starting GPU plugin",
         "reason": "test entry that excuses a shader failure"},
    ]
}


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.noise_file = self.write("noise.json", json.dumps(NOISE))

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        p = self.dir / name
        p.write_text(text)
        return p

    def events(self, text, source="sim"):
        return ls.parse_events(self.write("in.log", text), source)

    def scan(self, inputs, noise=None):
        return ls.scan(inputs, ls.load_noise(noise or self.noise_file))

    def main(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = ls.main(list(argv) + ["--noise", str(self.noise_file)])
        return rc, out.getvalue()


class ParseTest(Base):
    def test_logged_throwable_frames_and_causes_belong_to_the_line(self):
        evs = self.events(WORLDHOPPER)
        self.assertEqual(len(evs), 2)
        first = evs[0]
        self.assertEqual(first["level"], "ERROR")
        self.assertEqual(first["line"], 1)
        self.assertEqual(first["frames"], 3)
        self.assertEqual(first["causes"], [
            "java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native",
            "Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.UnsatisfiedLinkError: Could not "
            "find JNA native support [in thread \"main\"]"])
        self.assertEqual(evs[1]["line"], 7)

    def test_info_lines_are_not_events_and_end_the_previous_one(self):
        evs = self.events(SESSION_PING)
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["header"], "WARN n.r.client.ClientSessionManager - Unable to ping session service")
        self.assertEqual(evs[0]["frames"], 2)

    def test_signature_drops_time_thread_hashes_and_numbers(self):
        second = "\n".join(WORLDHOPPER.splitlines()[6:]) + "\n"
        again = second.replace("21:24:01", "22:10:59").replace("pool-7-thread-3", "pool-12-thread-1").replace(
            "0x0000000133d1a908@a26be53", "0x00000001aa00b010@7f00c1d")
        a, b = self.events(second + again)
        self.assertEqual(ls.signature(a), ls.signature(b))
        # The two World Hopper shapes differ: only the first ping carries the JNA cause.
        first, second_ev = self.events(WORLDHOPPER)
        self.assertNotEqual(ls.signature(first), ls.signature(second_ev))

    def test_a_second_trace_after_frames_is_its_own_event(self):
        evs = self.events("java.lang.IllegalStateException: one\n\tat a.B.c(B.java:1)\n"
                          "java.lang.IllegalArgumentException: two\n\tat a.B.d(B.java:2)\n")
        self.assertEqual([e["level"] for e in evs], ["EXCEPTION", "EXCEPTION"])
        self.assertEqual(evs[1]["header"], "java.lang.IllegalArgumentException: two")

    def test_exception_in_thread_is_an_event(self):
        evs = self.events('Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: x\n\tat a.B.c(B.java:1)\n')
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["level"], "EXCEPTION")

    def test_multi_line_message_is_body_not_a_new_event(self):
        evs = self.events("2026-10-22 21:28:52 PDT [Client] ERROR rs117.hd.overlays.ShaderOverlay - Failed to compile shader:\n"
                          "rs117.hd.opengl.shader.ShaderException: Compile error when compiling shader: gamma\n"
                          "ERROR: 0:12: 'smoothstep' : no matching overloaded function found\n"
                          "\tat rs117.hd.opengl.shader.ShaderProgram.compile(ShaderProgram.java:40)\n")
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["body"], 1)
        self.assertTrue(ls.is_shader_error(evs[0]))

    def test_own_event_lines(self):
        evs = self.events("[jvm] opt[12] = -XX:+ExitOnOutOfMemoryError\n"
                          "[bridge-fault] onFrame threw\n"
                          "CacioComponentPeer::setZOrder: NOT YET IMPLEMENTED\n"
                          "[shell] JVM boot FAILED rc=-1\n"
                          "[screen] failed 3 settings: no answer\n"
                          "[shell] 117HD did not survive its last launch — reverted to the standard renderer\n"
                          "OpenJDK 64-Bit Server VM warning: CodeCache is full\n"
                          "# A fatal error has been detected by the Java Runtime Environment:\n"
                          "[glrecon] COMPUTE = UNSUPPORTED (err=0x0500)\n")
        self.assertEqual([e["level"] for e in evs], ["FAULT", "WARN", "FAILED", "FAILED", "FAILED", "WARN", "FATAL"])

    def test_jul_lines(self):
        evs = self.events("Oct 22, 2026 9:15:00 PM sun.awt.Foo bar\nSEVERE: it broke\n"
                          "WARNING: Delegated to the 'execute' command.\n         This behaviour has been deprecated\n")
        self.assertEqual([(e["level"], e["header"]) for e in evs],
                         [("SEVERE", "SEVERE: it broke"), ("WARNING", "WARNING: Delegated to the 'execute' command.")])

    def test_logcat_prefix_is_removed_and_other_tags_skipped(self):
        text = ("--------- beginning of main\n"
                "06-08 19:26:32.180 25446 25501 I jvm-stdout: " + GPU_SHADER_FAIL.splitlines()[2] + "\n"
                "06-08 19:26:32.180 25446 25501 I jvm-stdout: " + GPU_SHADER_FAIL.splitlines()[3] + "\n"
                "06-08 19:26:32.181 25446 25502 E SomeOtherTag: java.lang.RuntimeException: not ours\n")
        evs = self.events(text, "android")
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["line"], 2)
        self.assertEqual(evs[0]["causes"][0][:40], "net.runelite.client.plugins.gpu.ShaderEx")

    def test_logcat_without_jvm_stdout_is_an_input_error(self):
        with self.assertRaises(ls.InputError):
            self.events(SESSION_PING, "android")

    def test_empty_and_missing_files_are_input_errors(self):
        with self.assertRaises(ls.InputError):
            self.events("")
        with self.assertRaises(ls.InputError):
            ls.parse_events(self.dir / "nope.log", "sim")


class ScanTest(Base):
    def test_noise_needs_header_and_cause(self):
        p = self.write("a.log", SESSION_PING)
        self.assertTrue(self.scan([("sim", p)])["green"])
        other = self.write("b.log", SESSION_PING.replace("Failed to connect to /127.0.0.1:1",
                                                         "Failed to connect to api.runelite.net/1.2.3.4:443"))
        res = self.scan([("sim", other)])
        self.assertFalse(res["green"])
        self.assertEqual(res["new"][0]["origin"], "WARN n.r.client.ClientSessionManager")

    def test_noise_sources(self):
        p = self.write("a.log", "2026-10-22 21:28:52 PDT [Client] ERROR injected-client - Error initializing sound "
                                "engine. Audio will be unavailable.\n")
        self.assertTrue(self.scan([("phone", p)])["green"])
        self.assertFalse(self.scan([("sim", p)])["green"])

    def test_new_signatures_group_and_keep_first_seen_order(self):
        p = self.write("a.log", WORLDHOPPER + SESSION_PING)
        res = self.scan([("sim", p)])
        self.assertEqual([g["count"] for g in res["new"]], [1, 1])
        self.assertEqual(res["new"][0]["first"]["line"], 1)
        self.assertEqual(res["newEvents"], 2)
        self.assertEqual({n["id"]: n["count"] for n in res["noise"]}, {"ping": 1, "sound": 0, "gpu-shader": 0})

    def test_repeats_on_other_threads_are_one_signature(self):
        line = "2026-10-22 21:0{}:00 PDT [pool-{}-thread-1] WARN  n.r.c.Foo - thing 0x{}@{} broke\n"
        p = self.write("a.log", line.format(1, 2, "1f", "abcdef12") + line.format(2, 9, "2e", "12abcdef"))
        res = self.scan([("sim", p)])
        self.assertEqual(len(res["new"]), 1)
        self.assertEqual(res["new"][0]["count"], 2)

    def test_shader_errors_count_even_when_excused(self):
        p = self.write("a.log", GPU_SHADER_FAIL)
        res = self.scan([("sim", p)])
        # The two source-dump lines are new; the GpuPlugin line is excused by the test entry, but still counted.
        self.assertEqual(res["shaderErrors"], 3)
        self.assertEqual(len(res["new"]), 2)
        self.assertEqual({n["id"]: n["count"] for n in res["noise"]}["gpu-shader"], 1)

    def test_shader_warnings_are_not_shader_errors(self):
        p = self.write("a.log", "2026-10-22 21:28:52 PDT [Client] WARN  r.h.o.shader.ShaderProgram - "
                                "GammaProgram has missing or unused Uniform: brightness\n")
        self.assertEqual(self.scan([("sim", p)])["shaderErrors"], 0)


class NoiseFileTest(Base):
    def bad(self, entries):
        p = self.write("bad.json", json.dumps({"noise": entries}))
        with self.assertRaises(ls.InputError):
            ls.load_noise(p)

    def test_rejects_bad_entries(self):
        ok = {"id": "a", "header": "x", "reason": "r"}
        self.bad([ok, dict(ok)])                              # duplicate id
        self.bad([dict(ok, header="(")])                      # bad regex
        self.bad([dict(ok, cause="[")])
        self.bad([{"id": "a", "header": "x"}])                # no reason
        self.bad([dict(ok, reason="  ")])
        self.bad([dict(ok, sources=["ipad"])])
        self.bad([dict(ok, sources=[])])
        self.bad([dict(ok, match="x")])                       # unknown field (a typo would silently do nothing)
        self.bad(["x"])
        p = self.write("notjson.json", "{")
        with self.assertRaises(ls.InputError):
            ls.load_noise(p)

    def test_the_shipped_list_loads_and_every_entry_has_a_reason(self):
        noise = ls.load_noise(ls.DEFAULT_NOISE)
        self.assertGreaterEqual(len(noise), 6)
        for n in noise:
            self.assertGreater(len(n["reason"]), 30, n["id"])


class MainTest(Base):
    def test_green_red_and_input_error(self):
        green = self.write("g.log", SESSION_PING)
        rc, out = self.main("--sim", str(green))
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip().splitlines()[-1], "VERDICT: LOG_SCAN_GREEN")
        red = self.write("r.log", WORLDHOPPER)
        js = self.dir / "out.json"
        rc, out = self.main("--sim", str(red), "--json", str(js), "--suggest")
        self.assertEqual(rc, 1)
        self.assertEqual(out.strip().splitlines()[-1], "VERDICT: LOG_SCAN_RED")
        self.assertIn("suggest: {", out)
        self.assertEqual(len(json.loads(js.read_text())["new"]), 2)
        rc, out = self.main("--sim", str(self.dir / "missing.log"))
        self.assertEqual(rc, 2)
        self.assertEqual(out.strip().splitlines()[-1], "VERDICT: LOG_SCAN_RED")
        rc, out = self.main()
        self.assertEqual(rc, 2)

    def test_suggestion_matches_its_own_event(self):
        p = self.write("a.log", "2026-10-22 21:28:52 PDT [Client] WARN  n.r.c.Foo - 3 things (a-b) broke [x]\n")
        res = self.scan([("sim", p)])
        stub = json.loads(ls.suggest(res["new"][0]))
        stub.update(id="t", reason="a reason long enough")
        self.write("n2.json", json.dumps({"noise": [stub]}))
        self.assertTrue(self.scan([("sim", p)], self.dir / "n2.json")["green"])


if __name__ == "__main__":
    unittest.main()
