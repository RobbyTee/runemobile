#!/usr/bin/env python3
"""
runelite-ios harness MCP server.

A small stdio MCP (Model Context Protocol) server that exposes the project's
dev-tools harnesses as callable tools, plus two net-new capabilities that the
repo did not have before: a PNG pixel-diff (the long-planned maxChannelDelta
golden compare) and an evidence-catalog indexer.

Transport: newline-delimited JSON-RPC 2.0 over stdin/stdout.
Pinned interpreter: /usr/bin/python3 (system Python — ships PIL + numpy, which
the image_diff tool needs). All other tools are pure stdlib + subprocess.

Tools
-----
  headless_render        run the desktop headless capture, return PNG paths + log markers
  image_diff             per-channel pixel diff of two PNGs (+ heatmap) — the missing T12.1
  jvm_bench              run the jvmbench throughput baseline, return parsed ops/s
  glsl_es_validate       run the GLES 3.00 shader-downport validator
  list_evidence          index evidence/*.png catalogs into JSON (name, dir, size, mtime)
  android_screenshot     adb screencap + pull from a connected device/emulator
  android_set_hd_config  live 117HD preset flip (SET_HD_CONFIG broadcast) — the FIX-13 A/B knob
  android_fps            run the fps-measure harness for N seconds, return mean/median/min/max
  run_verdict            run any *-verdict.sh against a log, return GREEN/RED
  ios_pull_logs          devicectl copy of Documents/jvm-stdio.log (+ client.log) off the iPhone

Nothing here is destructive: every tool reads, renders, measures, or flips a
runtime debug knob.

The output parsers are pure functions (parse_*), covered by test_harness_server.py.
"""

import sys
import os
import re
import io
import json
import time
import glob
import base64
import shutil
import subprocess
import tempfile
import traceback
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVER_NAME = "runelite-ios-harness"
SERVER_VERSION = "0.2.0"
DEFAULT_PROTOCOL = "2024-11-05"

# The JREs each port embeds. Both are gitignored staging output, so either may be missing on a fresh clone;
# jvm_bench falls back to PINNED_JAVA_MAJOR and says so.
EMBEDDED_JRE_RELEASES = ("ios/dev-tools/jre/release", "android/app/src/main/assets/jvm/jre/release")
PINNED_JAVA_MAJOR = 21

# Verdict scripts that take a second log (mobileui: cross-launch persistence). The others ignore $2, so a
# second log passed to them would be dropped without a word; run_verdict refuses that instead.
TWO_LOG_VERDICTS = ("mobileui-verdict.sh",)

IOS_BUNDLE_ID = "dev.audaro.runemobile"
# Debug builds keep .runelite in Documents; Release moves it to Application Support (JVMLauncher.m rlHome).
IOS_CLIENT_LOG_SOURCES = (
    "Documents/.runelite/logs/client.log",
    "Library/Application Support/.runelite/logs/client.log",
)


# --------------------------------------------------------------------------- #
# small helpers
# --------------------------------------------------------------------------- #
def log(msg):
    """Diagnostics go to stderr — stdout is the protocol channel."""
    sys.stderr.write(f"[harness-mcp] {msg}\n")
    sys.stderr.flush()


def run(cmd, cwd=None, timeout=120, env=None, stdin=None):
    """Run a command, return (rc, stdout, stderr). Never raises on non-zero."""
    full_env = dict(os.environ)
    if env:
        full_env.update(env)
    try:
        p = subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else None,
            timeout=timeout,
            env=full_env,
            input=stdin,
            capture_output=True,
            text=True,
        )
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or "") if isinstance(e.stdout, str) else ""
        err = (e.stderr or "") if isinstance(e.stderr, str) else ""
        return 124, out, err + f"\n[timeout after {timeout}s]"
    except FileNotFoundError as e:
        return 127, "", str(e)


def find_jdk():
    # openjdk@11 is also installed here, but only for deliberate cross-version runs (pass jdk= explicitly).
    for cand in (
        "/opt/homebrew/opt/openjdk@21",
        os.environ.get("JAVA_HOME", ""),
    ):
        if cand and Path(cand, "bin", "java").exists():
            return cand
    return None


def java_major(version):
    """'21.0.8' -> 21, '1.8.0_402' -> 8, '26' -> 26. None if unparseable."""
    m = re.match(r"(\d+)(?:\.(\d+))?", version or "")
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):
        return int(m.group(2))
    return major


def jdk_version(jdk):
    rc, o, e = run([f"{jdk}/bin/java", "-version"], timeout=30)
    m = re.search(r'version "([^"]+)"', e + "\n" + o)
    return m.group(1) if m else None


def embedded_java_versions():
    """JAVA_VERSION of each staged port JRE that exists, keyed by repo-relative release path."""
    found = {}
    for rel in EMBEDDED_JRE_RELEASES:
        p = REPO_ROOT / rel
        if p.exists():
            m = re.search(r'^JAVA_VERSION="([^"]+)"', p.read_text(errors="replace"), re.M)
            if m:
                found[rel] = m.group(1)
    return found


def find_adb():
    for cand in (
        shutil.which("adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ):
        if cand and Path(cand).exists():
            return cand
    return None


def text_block(obj):
    if isinstance(obj, str):
        return {"type": "text", "text": obj}
    return {"type": "text", "text": json.dumps(obj, indent=2)}


def err_result(msg):
    return {"content": [text_block(msg)], "isError": True}


def ok_result(blocks):
    if isinstance(blocks, dict) or isinstance(blocks, str):
        blocks = [text_block(blocks)]
    return {"content": blocks, "isError": False}


# --------------------------------------------------------------------------- #
# output parsers (pure, so test_harness_server.py can feed them real log shapes)
# --------------------------------------------------------------------------- #
def frame_draw_number(path):
    """HeadlessCapture names frames frame-<STATE>-<n>-draw<NNNNN>.png; the draw counter is the timeline."""
    m = re.search(r"-draw(\d+)\.png$", str(path))
    return int(m.group(1)) if m else -1


def frame_state(path):
    m = re.match(r"frame-(.+)-\d+-draw\d+\.png$", Path(path).name)
    return m.group(1) if m else None


def order_frames(paths):
    """Chronological by draw counter. A name sort is wrong: LOGIN_SCREEN sorts after LOGGED_IN."""
    return sorted(paths, key=lambda p: (frame_draw_number(p), str(p)))


def pick_last_frame(paths):
    """The newest LOGGED_IN frame if the run reached the game, else the newest frame of any state."""
    ordered = order_frames(paths)
    logged_in = [p for p in ordered if frame_state(p) == "LOGGED_IN"]
    if logged_in:
        return logged_in[-1]
    return ordered[-1] if ordered else None


def parse_capture_markers(text):
    """Markers from a HeadlessCapture log. The game state is never logged as 'gameState='; it rides on
         [headless-capture] draw#200 nullGraphics=0 capturedFrames=3 state=LOGIN_SCREEN
         [headless-capture] gameState LOGGING_IN -> LOADING (draw#253 loginIndex=10 launcher=true)
    plus the loginIndex transition line, which also carries state=. The last one in log order is current."""
    markers = {}
    m = re.findall(r"\[headless-capture\] draw#\d+ nullGraphics=(\d+)", text)
    if m:
        markers["nullGraphics"] = m[-1]
    m = re.findall(r"\[headless-capture\] .*?(?:\bstate=|gameState \w+ -> )(\w+)", text)
    if m:
        markers["gameState"] = m[-1]
    transitions = re.findall(r"\[headless-capture\] gameState (\w+) -> (\w+)", text)
    if transitions:
        markers["gameStateProgression"] = [transitions[0][0]] + [b for _a, b in transitions]
    lines = text.splitlines()
    markers["exceptionLines"] = sum(1 for ln in lines if "Exception" in ln)
    # logback's level column; a login 401 is an ERROR line with no Exception in it.
    markers["errorLines"] = sum(1 for ln in lines if " ERROR " in ln)
    return markers


_VERDICT_WORDS = ("GREEN", "RED", "INCONCLUSIVE")


def parse_verdict(text):
    """(verdict, token) from a *-verdict.sh's output; verdict is GREEN, RED, INCONCLUSIVE, NO_VERDICT,
    LOG_MISSING or UNKNOWN.

    A script that reaches a verdict ends on 'VERDICT: <TOKEN>', where TOKEN is the bare word (mobileui,
    menupeek) or wraps it (EDGE2EDGE_GREEN_AS_EXPECTED, HUD_ISO_RED). Earlier lines print PASS/FAIL per
    predicate and echo the JVM's own '[hud-iso] VERDICT ...' line, so only the last anchored line counts.
    The early exits (2 = probe never emitted, 3 = inconclusive) print no VERDICT: line."""
    tokens = re.findall(r"^\s*VERDICT:\s*([A-Z0-9_]+)", text, re.M)
    if tokens:
        tok = tokens[-1]
        parts = tok.split("_")
        for word in _VERDICT_WORDS:
            if word in parts:
                return word, tok
        return "UNKNOWN", tok
    m = re.search(r"\b[A-Z0-9_]+_NO_VERDICT\b", text)
    if m:
        return "NO_VERDICT", m.group(0)
    if "INSTRUMENT_INVALID" in text:
        return "INCONCLUSIVE", "INSTRUMENT_INVALID"
    if re.search(r"^>>> INCONCLUSIVE\b", text, re.M):
        return "INCONCLUSIVE", "INCONCLUSIVE"
    if "!! log not found" in text:
        return "LOG_MISSING", None
    return "UNKNOWN", None


def parse_glsl_es(text):
    """(rows, summary) from glsl_es_repro.py. Each mode prints a header, then its error lines:
         === uiScalingMode=MITCHELL (ord 2) -> FAIL ===
            [frag] ERROR: 0:207: '=' :  cannot convert from ...
    and the run ends on 'ALL MODES PASS (vert+frag)' or 'SOME MODES FAIL' (summary PASS / FAIL / None)."""
    rows = []
    for ln in text.splitlines():
        m = re.match(r"=== uiScalingMode=(\w+) \(ord (\d+)\) -> (PASS|FAIL) ===$", ln.strip())
        if m:
            rows.append({"mode": m.group(1), "ord": int(m.group(2)), "status": m.group(3), "errors": []})
            continue
        m = re.match(r"\s+\[(vert|frag)\] (.+)", ln)
        if m and rows:
            rows[-1]["errors"].append({"stage": m.group(1), "line": m.group(2).strip()})
    summary = None
    if "ALL MODES PASS" in text:
        summary = "PASS"
    elif "SOME MODES FAIL" in text:
        summary = "FAIL"
    return rows, summary


def parse_bench_results(text):
    """Bench.java prints 'RESULT floatbits.A ops=268435456 ns=1429923791 opsPerSec=187727106 chk=-6971'
    once per timed run (A and B per component). Names carry a dot, so a word-only pattern finds nothing."""
    results = {}
    for name, ops, ns, ops_s in re.findall(
        r"^RESULT ([\w.]+) ops=(\d+) ns=(\d+) opsPerSec=([\d.eE+]+) chk=-?\d+", text, re.M
    ):
        results[name] = {"ops": int(ops), "ns": int(ns), "opsPerSec": float(ops_s)}
    return results


def _num(v):
    for cast in (int, float):
        try:
            return cast(v)
        except ValueError:
            pass
    return v


def parse_fps_measure(text):
    """Summary of android/dev-tools/gpu/fps-measure.sh. It prints one line per probe family that has data:
         LABEL  fps    n=4    mean=49.8  median=49.0  min=46  max=55
         LABEL  frame  windows=4   frames=199   min=17.00  avg=20.09  p95~=22.50  max=140.00  (ms)
         LABEL  gpu    windows=3   frames=149   min=11.00  avg=14.51  max=120.00  (ms GPU)  disjoint=94  hitch=1  empty=1  (excluded: 1 untagged, 1 other-state)
         LABEL  thermal samples=6    hottest=42.5..47.5C  status=1->2 (max 2)
         LABEL  govern changes=1  scale=85  minScale=85  baseline=100
    a 'fps:   no LOGGED_IN ...' line instead when a family saw only other states (kept under 'missing'), the
    '>> thermal[start]: hottest=84.0C status=1' samples, and '!! ...' warnings. frame p95 is reported as 'p95'
    but is the script's p95~: the mean of the per-second p95s, not a percentile of the whole capture."""
    out = {"fps": {}, "frameMs": {}, "gpuMs": {}, "thermalRows": {}, "governor": {}, "thermal": {},
           "warnings": [], "missing": []}
    keys = {"fps": "fps", "frame": "frameMs", "gpu": "gpuMs", "thermal": "thermalRows", "govern": "governor"}
    for ln in text.splitlines():
        m = re.match(r"^\S+\s+(fps|frame|gpu|thermal|govern)\s+(.*)$", ln)
        if m:
            kind, rest = m.groups()
            d = out[keys[kind]]
            for k, v in re.findall(r"([\w~]+)=([^\s()]+)", rest):
                d[k.rstrip("~")] = _num(v)
            ex = re.search(r"excluded: (\d+) untagged, (\d+) other-state", rest)
            if ex:
                d["excludedUntagged"], d["excludedOtherState"] = int(ex.group(1)), int(ex.group(2))
            if kind == "thermal":
                hot = re.match(r"(.+)\.\.(.+)C$", str(d.pop("hottest", "")))
                if hot:
                    d["hottestMinC"], d["hottestMaxC"] = _num(hot.group(1)), _num(hot.group(2))
                st = str(d.pop("status", "")).split("->")
                if len(st) == 2:
                    d["statusStart"], d["statusEnd"] = _num(st[0]), _num(st[1])
                mx = re.search(r"\(max (\S+)\)", rest)
                if mx:
                    d["statusMax"] = _num(mx.group(1))
            continue
        m = re.match(r"^(fps|frame|gpu):\s+(no .*)$", ln)
        if m:
            out["missing"].append(ln.strip())
            continue
        m = re.search(r"thermal\[(\w+)\]: hottest=([\w./]+?)C status=([\w/]+)", ln)
        if m:
            out["thermal"][m.group(1)] = {"hottestC": _num(m.group(2)), "status": _num(m.group(3))}
            continue
        if ln.startswith("!! "):
            out["warnings"].append(ln[3:].strip())
    return out


# --------------------------------------------------------------------------- #
# tools
# --------------------------------------------------------------------------- #
def tool_headless_render(a):
    """Run the desktop headless capture and surface the PNGs it produced."""
    mode = a.get("mode", "login_screen")
    run_seconds = int(a.get("run_seconds", 45))
    interval = int(a.get("capture_interval", 50))

    if mode == "in_game":
        script = REPO_ROOT / "ios/dev-tools/headless/run-headless-login.sh"
        out_dir = REPO_ROOT / "ios/dev-tools/headless/out-login"
        jvm_log = out_dir / "run-native-login.log"
        tokens = REPO_ROOT / "ios/dev-tools/tokens.json"
        if not tokens.exists():
            return err_result(
                "in_game mode needs ios/dev-tools/tokens.json (mint via "
                "ios/dev-tools/fetch-shield-tokens.sh). Use mode=login_screen for no-auth capture."
            )
    else:
        script = REPO_ROOT / "ios/dev-tools/headless/run-headless.sh"
        out_dir = REPO_ROOT / "ios/dev-tools/headless/out"
        jvm_log = out_dir / "run-native.log"

    if not script.exists():
        return err_result(f"missing script: {script}")

    env = {"RUN_SECONDS": str(run_seconds), "CAPTURE_INTERVAL": str(interval)}
    # boot + (optional) login takes a while; give the script room over its own RUN_SECONDS.
    timeout = max(180, run_seconds + 150)
    started = time.time()
    rc, out, err = run(["bash", str(script)], cwd=REPO_ROOT, timeout=timeout, env=env)

    # A script that exits before launching Java (missing tokens/jq) skips its rm of old frames and leaves the
    # previous run's log behind, so only files this run wrote count.
    on_disk = glob.glob(str(out_dir / "frame-*.png"))
    frames = order_frames([f for f in on_disk if os.stat(f).st_mtime >= started])
    combined = out + "\n" + err
    # The script prints only a grep tail of the JVM log, so read the log itself.
    log_fresh = jvm_log.exists() and jvm_log.stat().st_mtime >= started
    markers = parse_capture_markers(jvm_log.read_text(errors="replace") if log_fresh else "")
    markers["frameCount"] = len(frames)
    if len(on_disk) > len(frames):
        markers["staleFramesIgnored"] = len(on_disk) - len(frames)
    by_state = {}
    for f in frames:
        st = frame_state(f) or "?"
        by_state[st] = by_state.get(st, 0) + 1
    markers["framesByState"] = by_state

    return ok_result(
        {
            "ok": rc == 0,
            "mode": mode,
            "exitCode": rc,
            "outputDir": str(out_dir),
            "jvmLog": str(jvm_log) if log_fresh else None,
            "frames": frames[-8:],
            "lastFrame": pick_last_frame(frames),
            "markers": markers,
            "logTail": "\n".join(combined.strip().splitlines()[-25:]),
            "hint": "frames are in draw order; lastFrame is the newest LOGGED_IN frame when there is one. "
            "Open it with the Read tool, or pass two frames to image_diff. jvmLog null = Java never ran.",
        }
    )


def tool_image_diff(a):
    """Per-channel pixel diff of two PNGs. The long-planned T12.1 golden compare."""
    try:
        from PIL import Image
        import numpy as np
    except Exception as e:  # pragma: no cover
        return err_result(
            f"image_diff needs PIL + numpy (run the server with /usr/bin/python3). import error: {e}"
        )

    pa, pb = a.get("image_a"), a.get("image_b")
    if not pa or not pb:
        return err_result("image_diff requires image_a and image_b paths.")
    pa, pb = Path(pa), Path(pb)
    if not pa.exists() or not pb.exists():
        return err_result(f"missing: {pa if not pa.exists() else pb}")

    threshold = int(a.get("threshold", 0))
    ia = Image.open(pa).convert("RGB")
    ib = Image.open(pb).convert("RGB")

    crop = a.get("crop")
    if crop and len(crop) == 4:
        x, y, w, h = crop
        box = (x, y, x + w, y + h)
        ia, ib = ia.crop(box), ib.crop(box)

    if ia.size != ib.size:
        return err_result(
            {
                "ok": False,
                "reason": "size mismatch",
                "image_a": [ia.size[0], ia.size[1]],
                "image_b": [ib.size[0], ib.size[1]],
                "hint": "Pass crop=[x,y,w,h] to compare a common region, or render at matching dims.",
            }
        )

    na = np.asarray(ia, dtype=np.int16)
    nb = np.asarray(ib, dtype=np.int16)
    diff = np.abs(na - nb)
    per_pixel = diff.max(axis=2)  # worst channel per pixel
    max_delta = int(diff.max())
    mean_delta = round(float(diff.mean()), 4)
    rmse = round(float(np.sqrt((diff.astype(np.float64) ** 2).mean())), 4)
    pct_diff = round(float((per_pixel > threshold).mean() * 100.0), 4)

    # heatmap: red channel scaled so even small deltas are visible.
    h, w = per_pixel.shape
    amp = np.clip(per_pixel.astype(np.int32) * 4, 0, 255).astype(np.uint8)
    heat = np.zeros((h, w, 3), dtype=np.uint8)
    heat[..., 0] = amp
    heat[..., 2] = (per_pixel > threshold).astype(np.uint8) * 40
    out_path = a.get("out") or str(Path(pa).with_suffix("").as_posix() + ".diff.png")
    Image.fromarray(heat).save(out_path)

    summary = {
        "ok": max_delta <= threshold,
        "width": w,
        "height": h,
        "maxChannelDelta": max_delta,
        "meanChannelDelta": mean_delta,
        "rmse": rmse,
        "pctPixelsDiffering": pct_diff,
        "threshold": threshold,
        "diffImage": out_path,
        "identical": max_delta == 0,
    }

    blocks = [text_block(summary)]
    # inline the heatmap so it is visible without a second round-trip.
    if a.get("inline", True):
        buf = io.BytesIO()
        Image.fromarray(heat).save(buf, format="PNG")
        blocks.append(
            {
                "type": "image",
                "data": base64.b64encode(buf.getvalue()).decode("ascii"),
                "mimeType": "image/png",
            }
        )
    return ok_result(blocks)


def tool_jvm_bench(a):
    """Compile + run the jvmbench throughput proxy on desktop OpenJDK."""
    explicit = a.get("jdk")
    jdk = explicit or find_jdk()
    if not jdk:
        return err_result("no JDK found (looked at openjdk@21, JAVA_HOME).")
    if not Path(jdk, "bin", "java").exists():
        return err_result(f"not a JDK home (no bin/java): {jdk}")
    version = jdk_version(jdk)
    major = java_major(version)
    if major is None:
        return err_result(f"could not read the version of {jdk}/bin/java (got {version!r}).")
    embedded = embedded_java_versions()
    expected = sorted({java_major(v) for v in embedded.values()} - {None}) or [PINNED_JAVA_MAJOR]
    runtime_match = major in expected
    # A number from the wrong VM reads exactly like a real one, so the default path refuses it; an explicit
    # jdk= is a deliberate cross-version comparison and runs, flagged.
    if not runtime_match and not explicit:
        return err_result(
            {
                "ok": False,
                "reason": f"resolved JDK {version} (major {major}) is not the embedded runtime (major {expected})",
                "jdk": jdk,
                "embeddedRuntime": embedded or {"pinned": PINNED_JAVA_MAJOR},
                "hint": "install openjdk@21, or pass jdk=<home> to benchmark another VM on purpose.",
            }
        )
    bench_dir = REPO_ROOT / "ios/dev-tools/jvmbench"
    src = bench_dir / "Bench.java"
    if not src.exists():
        return err_result(f"missing {src}")
    out = bench_dir / "out"
    out.mkdir(exist_ok=True)

    rc, o, e = run(
        [f"{jdk}/bin/javac", "--release", "8", "-d", str(out), str(src)],
        cwd=bench_dir,
        timeout=120,
    )
    if rc != 0:
        return err_result({"stage": "compile", "exitCode": rc, "stderr": e[-2000:]})

    rc, o, e = run([f"{jdk}/bin/java", "-cp", str(out), "Bench"], cwd=bench_dir, timeout=180)
    results = parse_bench_results(o)
    result_lines = len(re.findall(r"^RESULT ", o, re.M))
    summary = {
        # every RESULT line must parse: a partial table would read as a complete one.
        "ok": rc == 0 and bool(results) and len(results) == result_lines,
        "jdk": jdk,
        "jdkVersion": version,
        "embeddedRuntime": embedded or {"pinned": PINNED_JAVA_MAJOR},
        "runtimeMatch": runtime_match,
        "exitCode": rc,
        "results": results,
        "resultLines": result_lines,
        "raw": o.strip().splitlines()[-10:],
    }
    if not runtime_match:
        summary["warning"] = f"JDK major {major} is not the embedded runtime (major {expected}): cross-version numbers."
    return ok_result(summary)


def tool_glsl_es_validate(a):
    """Run the GLES 3.00 shader-downport validator."""
    script = REPO_ROOT / "ios/dev-tools/headless/glsl_es_repro.py"
    if not script.exists():
        return err_result(f"missing {script}")
    if not shutil.which("glslangValidator"):
        return err_result(
            "glslangValidator not on PATH — install it (brew install glslang) to validate shaders."
        )
    cmd = ["python3", str(script)]
    for cast in a.get("extra_casts") or []:
        if "==" not in cast:
            return err_result(f"extra_casts entries are 'from==to'; got {cast!r}")
        cmd += ["--extra-cast", cast]
    rc, o, e = run(cmd, cwd=REPO_ROOT, timeout=120)
    combined = o + "\n" + e
    rows, summary = parse_glsl_es(combined)
    failures = [r["mode"] for r in rows if r["status"] != "PASS"]
    result = {
        "ok": rc == 0 and bool(rows) and not failures and summary == "PASS",
        "exitCode": rc,
        "summary": summary,
        "results": rows,
        "failures": failures,
        "logTail": combined.strip().splitlines()[-20:],
    }
    if not rows:
        # An exit 0 with nothing parsed means the output format moved, not that every shader passed.
        result["reason"] = "no '=== uiScalingMode=... ===' lines parsed; check glsl_es_repro.py's output format."
    return ok_result(result)


def tool_list_evidence(a):
    """Index evidence PNG catalogs into a sortable JSON manifest."""
    default_dirs = [
        "ios/app/evidence",
        "ios/dev-tools/headless/out",
        "ios/dev-tools/headless/out-login",
        "android/dev-tools/parity",
        "android/dev-tools/qa-2026-06-19/evidence",
        "android/dev-tools/qa-2026-06-19/evidence2",
        "android/dev-tools/firebase/fb-gpu-frames",
    ]
    rel = a.get("dir")
    limit = int(a.get("limit", 60))
    search_dirs = [REPO_ROOT / rel] if rel else [REPO_ROOT / d for d in default_dirs]

    items = []
    for d in search_dirs:
        if not d.exists():
            continue
        for f in glob.glob(str(d / "**/*.png"), recursive=True):
            st = os.stat(f)
            items.append(
                {
                    "path": str(Path(f).relative_to(REPO_ROOT)),
                    "dir": str(Path(f).parent.relative_to(REPO_ROOT)),
                    "name": Path(f).name,
                    "kb": round(st.st_size / 1024, 1),
                    "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(st.st_mtime)),
                    "_t": st.st_mtime,
                }
            )
    items.sort(key=lambda x: x["_t"], reverse=True)
    for it in items:
        it.pop("_t", None)
    return ok_result(
        {"ok": True, "count": len(items), "showing": min(limit, len(items)), "evidence": items[:limit]}
    )


def _adb_base(a):
    adb = find_adb()
    if not adb:
        return None, err_result("adb not found (set ANDROID_HOME or put adb on PATH).")
    cmd = [adb]
    serial = a.get("serial")
    if serial:
        cmd += ["-s", serial]
    return cmd, None


def tool_android_screenshot(a):
    base, e = _adb_base(a)
    if e:
        return e
    out = a.get("out") or str(REPO_ROOT / "android/dev-tools" / f"screen-{int(time.time())}.png")
    rc, o, er = run(base + ["exec-out", "screencap", "-p"], timeout=30)
    if rc != 0:
        return err_result({"exitCode": rc, "stderr": er[-800:], "hint": "is a device/emulator connected? (adb devices)"})
    # exec-out returns binary on stdout; capture_output text mode would corrupt it, so re-run binary-safe.
    p = subprocess.run(base + ["exec-out", "screencap", "-p"], capture_output=True, timeout=30)
    Path(out).write_bytes(p.stdout)
    return ok_result(
        {"ok": True, "path": out, "bytes": len(p.stdout), "hint": "Read this path to view, or feed it to image_diff."}
    )


def tool_android_set_hd_config(a):
    base, e = _adb_base(a)
    if e:
        return e
    scale = a.get("scene_resolution_scale")
    aniso = a.get("anisotropic_filtering_level")
    if scale is None and aniso is None:
        return err_result("pass scene_resolution_scale and/or anisotropic_filtering_level.")
    cmd = base + ["shell", "am", "broadcast", "-a", "dev.audaro.runemobile.SET_HD_CONFIG"]
    if scale is not None:
        cmd += ["--ei", "sceneResolutionScale", str(int(scale))]
    if aniso is not None:
        cmd += ["--ei", "anisotropicFilteringLevel", str(int(aniso))]
    rc, o, er = run(cmd, timeout=20)
    return ok_result(
        {
            # MainActivity's SET_HD_CONFIG receiver applies any non-reserved extra as a live hd key, so these
            # --ei extras (sceneResolutionScale/anisotropicFilteringLevel) take effect with no rebuild.
            "ok": rc == 0 and ("Broadcast completed" in o or "result=0" in o),
            "exitCode": rc,
            "sent": {"sceneResolutionScale": scale, "anisotropicFilteringLevel": aniso},
            "stdout": o.strip(),
            "stderr": er.strip()[-400:],
        }
    )


def tool_android_fps(a):
    script = REPO_ROOT / "android/dev-tools/gpu/fps-measure.sh"
    if not script.exists():
        return err_result(f"missing {script}")
    seconds = int(a.get("seconds", 20))
    label = a.get("label", "mcp")
    csv = a.get("out_csv") or str(REPO_ROOT / "android/dev-tools/gpu" / f"fps-{label}.csv")
    cmd = ["bash", str(script), "-d", str(seconds), "-o", csv]
    if a.get("thermal_every") is not None:
        cmd += ["-t", str(int(a["thermal_every"]))]
    if a.get("serial"):
        cmd += ["-s", a["serial"]]
    cmd += [label]
    rc, o, er = run(cmd, cwd=REPO_ROOT, timeout=seconds + 60)
    combined = o + "\n" + er
    parsed = parse_fps_measure(combined)
    governor_moved = bool(parsed["governor"].get("changes"))
    return ok_result(
        {
            "ok": rc == 0,
            "label": label,
            "exitCode": rc,
            "csv": csv,
            "fps": parsed["fps"],
            "frameMs": parsed["frameMs"],
            "gpuMs": parsed["gpuMs"],
            "thermal": parsed["thermal"],
            "thermalRows": parsed["thermalRows"],
            "governor": parsed["governor"],
            # a ThermalGovernor scale change splits the capture into two workloads
            "abClean": not governor_moved,
            "warnings": parsed["warnings"],
            "missing": parsed["missing"],
            "logTail": combined.strip().splitlines()[-15:],
            "hint": "Flip a preset with android_set_hd_config between two android_fps runs; compare frameMs.avg/p95 "
            "(not fps) for a trustworthy A/B, and only between runs with abClean true. frameMs.p95 is the mean of "
            "per-second p95s.",
        }
    )


def tool_run_verdict(a):
    script = a.get("script")
    log_path = a.get("log")
    log2_path = a.get("log2")
    if not script:
        return err_result("pass script=<path to a *-verdict.sh>.")
    sp = (REPO_ROOT / script).resolve() if not os.path.isabs(script) else Path(script).resolve()
    # safety: only run vetted verdict scripts inside the repo's dev-tools.
    try:
        sp.relative_to(REPO_ROOT)
    except ValueError:
        return err_result("script must live inside the repo.")
    if not sp.name.endswith("-verdict.sh") or not sp.exists():
        return err_result(f"not a verdict script or missing: {sp}")
    if log2_path and sp.name not in TWO_LOG_VERDICTS:
        return err_result(f"{sp.name} takes one log; log2 is only for {', '.join(TWO_LOG_VERDICTS)}.")
    if log2_path and not log_path:
        return err_result("log2 needs log (the first launch's log).")
    cmd = ["bash", str(sp)]
    for p in (log_path, log2_path):
        if not p:
            continue
        full = (REPO_ROOT / p) if not os.path.isabs(p) else Path(p)
        # mobileui-verdict.sh reads a missing log as empty and says RED; name the real problem instead.
        if not full.is_file():
            return err_result(f"log not found: {full}")
        cmd.append(str(full))
    rc, o, er = run(cmd, cwd=REPO_ROOT, timeout=60)
    combined = o + "\n" + er
    verdict, token = parse_verdict(combined)
    # Every script exits 0 exactly when it says GREEN. Anything else means the parse or the script is off.
    consistent = (verdict == "GREEN") == (rc == 0)
    result = {
        "ok": verdict == "GREEN" and rc == 0,
        "verdict": verdict,
        "verdictToken": token,
        "exitCode": rc,
        "consistent": consistent,
        "logs": len(cmd) - 2,
        "output": combined.strip().splitlines()[-25:],
    }
    if not consistent:
        result["warning"] = f"verdict {verdict} disagrees with exit code {rc}; read output before trusting either."
    return ok_result(result)


def _ios_device(a):
    """(udid, None) for the explicit device, else for the one paired iOS device devicectl lists."""
    if a.get("device"):
        return a["device"], None
    tmp = Path(tempfile.mkdtemp(prefix="harness-devicectl-"))
    try:
        out = tmp / "devices.json"
        rc, _o, e = run(["xcrun", "devicectl", "list", "devices", "--json-output", str(out)], timeout=60)
        if rc != 0 or not out.exists():
            return None, err_result({"stage": "list devices", "exitCode": rc, "stderr": e[-800:]})
        devices = json.loads(out.read_text()).get("result", {}).get("devices", [])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    phones = [
        d
        for d in devices
        if d.get("hardwareProperties", {}).get("platform") == "iOS"
        and d.get("connectionProperties", {}).get("pairingState") == "paired"
    ]
    if len(phones) != 1:
        names = [f"{d.get('deviceProperties', {}).get('name')} {d.get('identifier')}" for d in phones]
        return None, err_result(f"need exactly one paired iOS device, found {len(phones)}: {names}; pass device=<UDID>.")
    return phones[0]["identifier"], None


def _devicectl_copy(udid, bundle_id, source, dest):
    return run(
        [
            "xcrun", "devicectl", "device", "copy", "from",
            "--device", udid,
            "--domain-type", "appDataContainer",
            "--domain-identifier", bundle_id,
            "--source", source,
            "--destination", str(dest),
        ],
        timeout=120,
    )


def tool_ios_pull_logs(a):
    """Copy jvm-stdio.log (and optionally RuneLite's client.log) out of the app container."""
    if not shutil.which("xcrun"):
        return err_result("xcrun not found (install the Xcode command-line tools).")
    udid, e = _ios_device(a)
    if e:
        return e
    bundle_id = a.get("bundle_id") or IOS_BUNDLE_ID
    # ios/app/evidence/ is gitignored, and list_evidence already indexes it.
    # A fresh dir per pull: a failed pull deletes its dir, which must never hold an earlier pull's files.
    base = REPO_ROOT / "ios/app/evidence/device-pull"
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest_dir, n = base / stamp, 1
    while dest_dir.exists():
        dest_dir, n = base / f"{stamp}-{n}", n + 1
    dest_dir.mkdir(parents=True)

    wanted = [("jvm-stdio.log", ("Documents/jvm-stdio.log",), True)]
    if a.get("client_log"):
        wanted.append(("client.log", IOS_CLIENT_LOG_SOURCES, False))
    files, attempts = {}, []
    for name, sources, required in wanted:
        dest = dest_dir / name
        for src in sources:
            rc, o, er = _devicectl_copy(udid, bundle_id, src, dest)
            attempts.append({"source": src, "exitCode": rc, "stderr": er.strip()[-400:]})
            if rc == 0 and dest.is_file():
                text = dest.read_text(errors="replace")
                files[name] = {
                    "path": str(dest.relative_to(REPO_ROOT)),
                    "source": src,
                    "bytes": dest.stat().st_size,
                    "lines": text.count("\n"),
                    "tail": text.strip().splitlines()[-15:],
                }
                break
        if required and name not in files:
            for f in files.values():
                (REPO_ROOT / f["path"]).unlink(missing_ok=True)
            shutil.rmtree(dest_dir, ignore_errors=True)
            return err_result(
                {
                    "ok": False,
                    "reason": f"could not copy {name} from {bundle_id} on {udid}",
                    "attempts": attempts,
                    "hint": "is the phone unlocked and paired, and was the app launched at least once? "
                    "The SideStore release build has a different bundle id (pass bundle_id).",
                }
            )
    return ok_result(
        {
            "ok": True,
            "device": udid,
            "bundleId": bundle_id,
            "dir": str(dest_dir.relative_to(REPO_ROOT)),
            "files": files,
            "attempts": attempts,
            "hint": "client.log can name the account; it stays in gitignored ios/app/evidence/, delete it when done.",
        }
    )


# --------------------------------------------------------------------------- #
# tool registry (name -> description, inputSchema, handler)
# --------------------------------------------------------------------------- #
TOOLS = {
    "headless_render": (
        "Run the desktop headless RuneLite capture (real engine, no device) and return the PNG "
        "frames in draw order + markers read from the JVM log (nullGraphics, gameState, "
        "gameStateProgression, exceptionLines, framesByState). lastFrame is the newest LOGGED_IN "
        "frame when the run reached the game. mode=login_screen needs no auth; mode=in_game "
        "requires ios/dev-tools/tokens.json (the script's own default run is 120 s). Slow (~1-3 min).",
        {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["login_screen", "in_game"], "default": "login_screen"},
                "run_seconds": {"type": "integer", "default": 45},
                "capture_interval": {"type": "integer", "default": 50},
            },
        },
        tool_headless_render,
    ),
    "image_diff": (
        "Per-channel pixel diff of two PNGs: maxChannelDelta, meanChannelDelta, RMSE, "
        "pctPixelsDiffering, plus a red heatmap (inlined). The repo's previously-unimplemented "
        "T12.1 golden compare — use it for desktop-vs-port and regression A/B.",
        {
            "type": "object",
            "properties": {
                "image_a": {"type": "string", "description": "baseline / ground-truth PNG path"},
                "image_b": {"type": "string", "description": "candidate PNG path"},
                "out": {"type": "string", "description": "heatmap output path (optional)"},
                "threshold": {"type": "integer", "default": 0, "description": "per-channel delta tolerance"},
                "crop": {"type": "array", "items": {"type": "integer"}, "description": "[x,y,w,h] common region"},
                "inline": {"type": "boolean", "default": True},
            },
            "required": ["image_a", "image_b"],
        },
        tool_image_diff,
    ),
    "jvm_bench": (
        "Compile (--release 8) and run the jvmbench throughput proxy on desktop OpenJDK 21 (the "
        "ports' runtime); returns parsed ops/s for floatbits, raster, mixed. Refuses a default JDK "
        "whose major differs from the staged JREs'.",
        {
            "type": "object",
            "properties": {
                "jdk": {
                    "type": "string",
                    "description": "JDK home (optional; default openjdk@21, then JAVA_HOME). A JDK passed "
                    "here runs even on a major mismatch, flagged runtimeMatch=false.",
                }
            },
        },
        tool_jvm_bench,
    ),
    "glsl_es_validate": (
        "Run the GLES 3.00 shader-downport validator (glsl_es_repro.py) across uiScalingMode "
        "variants; returns per-mode PASS/FAIL with each mode's glslang errors. Catches the "
        "implicit-cast regressions GLES forbids.",
        {
            "type": "object",
            "properties": {
                "extra_casts": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "extra 'from==to' downport casts to try (--extra-cast), optional",
                }
            },
        },
        tool_glsl_es_validate,
    ),
    "list_evidence": (
        "Index evidence PNG catalogs (ad-hoc evidence/ + headless out*/ dirs) into a JSON "
        "manifest sorted newest-first: path, dir, size, mtime.",
        {
            "type": "object",
            "properties": {
                "dir": {"type": "string", "description": "repo-relative dir (optional; defaults to known catalogs)"},
                "limit": {"type": "integer", "default": 60},
            },
        },
        tool_list_evidence,
    ),
    "android_screenshot": (
        "adb screencap + pull a PNG from a connected Android device/emulator.",
        {
            "type": "object",
            "properties": {
                "serial": {"type": "string", "description": "adb serial (optional)"},
                "out": {"type": "string", "description": "output PNG path (optional)"},
            },
        },
        tool_android_screenshot,
    ),
    "android_set_hd_config": (
        "Live 117HD preset flip via the SET_HD_CONFIG broadcast (no relaunch) — the FIX-13 A/B "
        "knob (sceneResolutionScale, anisotropicFilteringLevel).",
        {
            "type": "object",
            "properties": {
                "scene_resolution_scale": {"type": "integer"},
                "anisotropic_filtering_level": {"type": "integer"},
                "serial": {"type": "string"},
            },
        },
        tool_android_set_hd_config,
    ),
    "android_fps": (
        "Run the fps-measure harness for N seconds against a connected device; returns LOGGED_IN fps, "
        "frame-time and (with debug.routec.gputime=1) GPU-time stats, per-second thermal, and any "
        "ThermalGovernor scale change (abClean=false). Pair two runs around android_set_hd_config for an A/B.",
        {
            "type": "object",
            "properties": {
                "seconds": {"type": "integer", "default": 20},
                "thermal_every": {
                    "type": "integer",
                    "description": "seconds between thermal samples (default 1; 0 = start/end only)",
                },
                "label": {"type": "string", "default": "mcp"},
                "out_csv": {"type": "string"},
                "serial": {"type": "string"},
            },
        },
        tool_android_fps,
    ),
    "run_verdict": (
        "Run any *-verdict.sh inside the repo against a log file and return its verdict: GREEN, RED, "
        "INCONCLUSIVE, NO_VERDICT, LOG_MISSING or UNKNOWN, read from the script's final VERDICT: line "
        "and cross-checked against its exit code (consistent=false flags a disagreement).",
        {
            "type": "object",
            "properties": {
                "script": {"type": "string", "description": "repo-relative path to a *-verdict.sh"},
                "log": {"type": "string", "description": "log file to adjudicate (optional)"},
                "log2": {
                    "type": "string",
                    "description": "second launch's log; mobileui-verdict.sh only (gates cross-launch persistence)",
                },
            },
            "required": ["script"],
        },
        tool_run_verdict,
    ),
    "ios_pull_logs": (
        "Copy Documents/jvm-stdio.log (the frame probe, memwatch and fault sink) off the paired iPhone "
        "with devicectl into ios/app/evidence/device-pull/<stamp>/, plus RuneLite's client.log when "
        "client_log=true. The phone must be unlocked; no JIT attach needed.",
        {
            "type": "object",
            "properties": {
                "device": {"type": "string", "description": "UDID (optional; default the one paired iPhone)"},
                "bundle_id": {"type": "string", "default": IOS_BUNDLE_ID},
                "client_log": {"type": "boolean", "default": False, "description": "also pull client.log (can name the account)"},
            },
        },
        tool_ios_pull_logs,
    ),
}


# --------------------------------------------------------------------------- #
# JSON-RPC dispatch
# --------------------------------------------------------------------------- #
def handle(req):
    method = req.get("method")
    rid = req.get("id")
    params = req.get("params") or {}

    if method == "initialize":
        proto = params.get("protocolVersion", DEFAULT_PROTOCOL)
        return {
            "jsonrpc": "2.0",
            "id": rid,
            "result": {
                "protocolVersion": proto,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
        }

    if method in ("notifications/initialized", "initialized"):
        return None  # notification, no reply

    if method == "ping":
        return {"jsonrpc": "2.0", "id": rid, "result": {}}

    if method == "tools/list":
        tools = [
            {"name": name, "description": desc, "inputSchema": schema}
            for name, (desc, schema, _fn) in TOOLS.items()
        ]
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": tools}}

    if method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        entry = TOOLS.get(name)
        if not entry:
            return {"jsonrpc": "2.0", "id": rid, "result": err_result(f"unknown tool: {name}")}
        _desc, _schema, fn = entry
        try:
            result = fn(args)
        except Exception:
            result = err_result("tool raised:\n" + traceback.format_exc())
        return {"jsonrpc": "2.0", "id": rid, "result": result}

    # unknown method
    if rid is not None:
        return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"method not found: {method}"}}
    return None


def main():
    log(f"started; repo={REPO_ROOT}")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            log(f"bad json: {e}")
            continue
        try:
            resp = handle(req)
        except Exception:
            log("handler crash:\n" + traceback.format_exc())
            rid = req.get("id") if isinstance(req, dict) else None
            resp = {"jsonrpc": "2.0", "id": rid, "error": {"code": -32603, "message": "internal error"}}
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
