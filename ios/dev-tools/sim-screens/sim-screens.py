#!/usr/bin/env python3
"""sim-screens.py - golden screenshots of the iOS app's native screens, taken in the iOS Simulator.

  ios/dev-tools/sim-screens/sim-screens.py                 # build, install, check the screens in screens.json
  ios/dev-tools/sim-screens/sim-screens.py settings hub    # only these (the network screens run only when named)
  ios/dev-tools/sim-screens/sim-screens.py --update        # (re)write the goldens from this run
  --no-build reuses the last simulator build; --jars restages the jars first (after a Java change).

The app runs logged out (-RMSkipLogin) on the CPU renderer with the game picture covered (-RMBlankGame), so a
screen looks the same from run to run whatever the game behind it is doing. -RMScreenDriver lets this script open
each screen through the same entry point as its button (ViewController.m, simScreenTick): it writes
"<seq> <screen>" to the app's Documents/screen-cmd and waits for "[screen] ready <seq> <screen>" in hello-shell.log.

Goldens are kept per simulator model in ios/app/evidence/sim-golden/<model>/, which is gitignored: they show the
plugin list, and they change with Xcode's simulator runtime, so each Mac keeps its own. Each run's shots and diff
heatmaps land in ios/app/evidence/sim-screens/<stamp>/. A screen passes when at most maxPctDiffering % of its
pixels differ from the golden by more than threshold in any channel (screens.json). Exits 0 exactly when GREEN;
the last line is VERDICT: SIM_SCREENS_GREEN or _RED. Run it with /usr/bin/python3 (it needs PIL + numpy).
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "tools/mcp"))
import harness_server as hs  # noqa: E402  (simulator, capture and diff helpers)

MANIFEST = HERE / "screens.json"
GOLDEN_ROOT = REPO / "ios/app/evidence/sim-golden"
RUNS_ROOT = REPO / "ios/app/evidence/sim-screens"
LAUNCH_ARGS = ["-rendererPref", "cpu", "-RMAutoPlay", "YES", "-RMSkipLogin", "YES", "-RMBlankGame", "YES",
               "-RMScreenDriver", "YES"]
# After the login screen first shows, the plugin rail and the account rows are still filling in.
BOOT_SETTLE = 3.0
SCREEN_TIMEOUT = 30
_SCREEN_LINE = re.compile(r"\[screen\] (ready|failed) (\S+) (.+)$")


def red(msg):
    print(f"!! {msg}")
    print("SIM_SCREENS_RED")
    print("VERDICT: SIM_SCREENS_RED")
    sys.exit(1)


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def load_manifest():
    m = json.loads(MANIFEST.read_text())
    base = {"settle": 1.0, "threshold": 0, "maxPctDiffering": 0.0, "network": False, **m.get("defaults", {})}
    screens = [{**base, **s} for s in m["screens"]]
    slugs = [slug(s["name"]) for s in screens]
    if len(set(slugs)) != len(slugs):
        raise ValueError("two screens share a file name")
    return screens


def device_name(udid):
    rc, o, _e = hs.run(["xcrun", "simctl", "list", "devices", "-j"], timeout=60)
    if rc == 0:
        for devs in json.loads(o)["devices"].values():
            for d in devs:
                if d["udid"] == udid:
                    return d["name"]
    return udid


def screen_event(shell_log, seq, name):
    """('ready'|'failed', detail) once the app has answered command `seq`, else None."""
    for line in reversed(hs._read_tail(shell_log, 300)):
        m = _SCREEN_LINE.search(line)
        if not m or m.group(2) != str(seq):
            continue
        if m.group(1) == "ready":
            return ("ready", "") if m.group(3) == name else ("failed", f"the app answered for {m.group(3)!r}")
        rest = m.group(3)
        return "failed", rest[len(name) + 2:] if rest.startswith(name + ": ") else rest
    return None


def send(cmd_file, udid, shell_log, seq, name):
    """Have the app open `name` ('-' closes); (True, '') once it says ready, else (False, why)."""
    tmp = cmd_file.with_name(cmd_file.name + ".tmp")
    tmp.write_text(f"{seq} {name}\n")
    os.replace(tmp, cmd_file)
    start = time.time()
    while time.time() - start < SCREEN_TIMEOUT:
        ev = screen_event(shell_log, seq, name)
        if ev:
            return ev[0] == "ready", ev[1]
        if hs._sim_app_pid(udid) is None:
            return False, "the app exited"
        time.sleep(0.2)
    return False, f"no answer from the app within {SCREEN_TIMEOUT}s"


def main():
    ap = argparse.ArgumentParser(description="Golden screenshots of the native screens in the iOS Simulator.")
    ap.add_argument("screens", nargs="*", help="screens to check (default: screens.json's non-network screens)")
    ap.add_argument("--update", action="store_true", help="write this run's shots as the goldens")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--jars", action="store_true")
    args = ap.parse_args()

    try:
        screens = load_manifest()
    except (OSError, ValueError, KeyError) as e:
        red(f"can't read {MANIFEST}: {e}")
    if args.screens:
        known = {s["name"] for s in screens}
        unknown = [n for n in args.screens if n not in known]
        if unknown:
            red(f"not in screens.json: {', '.join(unknown)}")
        screens = [s for s in screens if s["name"] in args.screens]
    else:
        screens = [s for s in screens if not s["network"]]

    udid, err = hs._sim_udid({"sim_udid": os.environ.get("SIM_UDID")})
    if err:
        red(f"no simulator: {err}")
    model = device_name(udid)
    golden_dir = GOLDEN_ROOT / model
    run_dir = RUNS_ROOT / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    flags = ["--no-launch"] + (["--no-build"] if args.no_build else []) + (["--jars"] if args.jars else [])
    env = dict(os.environ, SIM_UDID=udid, RENDERER="cpu")
    if subprocess.run(["bash", str(hs.RUN_SIM)] + flags, cwd=REPO, env=env).returncode != 0:
        red("run-sim.sh failed to build or install the app")
    docs, why = hs._sim_docs(udid)
    if not docs:
        red(why)
    cmd_file, shell_log = docs / "screen-cmd", docs / "hello-shell.log"
    cmd_file.unlink(missing_ok=True)

    print(f"\n>> Screens: {len(screens)} on {model}, CPU renderer, logged out, game covered")
    launched = time.time()
    rc = subprocess.run(["xcrun", "simctl", "launch", "--terminate-running-process", udid, hs.IOS_BUNDLE_ID]
                        + LAUNCH_ARGS, stdout=subprocess.DEVNULL).returncode
    if rc != 0:
        red(f"simctl launch failed ({rc})")
    ok, details = hs._sim_wait(udid, "LOGIN_SCREEN", 300, since=launched)
    if not ok:
        red(f"the app didn't reach the login screen: {details.get('reason')}")
    time.sleep(BOOT_SETTLE)

    results = []
    seq = 0
    for s in screens:
        seq += 1
        name, file = s["name"], slug(s["name"]) + ".png"
        r = {"screen": name, "file": file}
        opened, why = send(cmd_file, udid, shell_log, seq, name)
        if not opened:
            results.append(dict(r, status="ERROR", why=why))
            print(f"   {name:<22} ERROR  {why}")
            if why == "the app exited":
                break
            continue
        time.sleep(s["settle"])
        try:
            im, _info = hs.sim_capture(udid, mask_island=False)
        except RuntimeError as e:
            results.append(dict(r, status="ERROR", why=f"screenshot failed: {e.args[0]}"))
            print(f"   {name:<22} ERROR  screenshot failed")
            continue
        shot = run_dir / file
        im.save(shot)
        golden = golden_dir / file
        if args.update:
            golden_dir.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(shot, golden)
            results.append(dict(r, status="UPDATED"))
            print(f"   {name:<22} UPDATED  {golden}")
            continue
        if not golden.exists():
            results.append(dict(r, status="NO_GOLDEN", why=f"no golden at {golden}; run with --update"))
            print(f"   {name:<22} NO_GOLDEN  (run with --update)")
            continue
        from PIL import Image

        ref = Image.open(golden).convert("RGB")
        if ref.size != im.size:
            results.append(dict(r, status="FAIL", why=f"size {list(im.size)} vs golden {list(ref.size)}"))
            print(f"   {name:<22} FAIL  size {im.size} vs golden {ref.size}")
            continue
        delta, heat = hs.image_delta(ref, im, int(s["threshold"]))
        passed = delta["pctPixelsDiffering"] <= float(s["maxPctDiffering"])
        r.update(status="PASS" if passed else "FAIL", pctPixelsDiffering=delta["pctPixelsDiffering"],
                 maxChannelDelta=delta["maxChannelDelta"], threshold=s["threshold"],
                 maxPctDiffering=s["maxPctDiffering"])
        if not passed:
            heat_path = run_dir / (slug(name) + ".diff.png")
            Image.fromarray(heat).save(heat_path)
            r["diffImage"] = str(heat_path)
        results.append(r)
        print(f"   {name:<22} {r['status']:<5} {delta['pctPixelsDiffering']:.4f}% over {s['threshold']} "
              f"(budget {s['maxPctDiffering']}%), max delta {delta['maxChannelDelta']}")
    send(cmd_file, udid, shell_log, seq + 1, "-")

    summary = {"model": model, "simUdid": udid, "update": args.update, "goldens": str(golden_dir),
               "shots": str(run_dir), "results": results}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"shots={run_dir}")
    print(f"summary={run_dir / 'summary.json'}")
    good = "UPDATED" if args.update else "PASS"
    bad = [r for r in results if r["status"] != good]
    if len(results) < len(screens):
        red(f"stopped after {len(results)} of {len(screens)} screens")
    if bad:
        red(f"{len(bad)} of {len(results)} screens: " + ", ".join(f"{r['screen']} {r['status']}" for r in bad))
    print(f"{'updated' if args.update else 'matched'} {len(results)} screens")
    print("SIM_SCREENS_GREEN")
    print("VERDICT: SIM_SCREENS_GREEN")


if __name__ == "__main__":
    main()
