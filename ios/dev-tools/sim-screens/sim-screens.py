#!/usr/bin/env python3
"""sim-screens.py - golden screenshots of the iOS app's native screens, taken in the iOS Simulator.

  ios/dev-tools/sim-screens/sim-screens.py                 # build, install, check the screens in screens.json
  ios/dev-tools/sim-screens/sim-screens.py settings hub    # only these (the network screens run only when named)
  ios/dev-tools/sim-screens/sim-screens.py --update        # (re)write the goldens from this run
  --no-build reuses the last simulator build; --jars restages the jars first (after a Java change).

The app runs logged out (-RMSkipLogin) on the CPU renderer with the game picture covered (-RMBlankGame), so a
screen looks the same from run to run whatever the game behind it is doing. -RMScreenDriver lets this script open
each screen through the same entry point as its button (ViewController.m, simScreenTick): it writes
"<seq> <screen>" to the app's Documents/screen-cmd and waits for "[screen] ready <seq> <screen>" in hello-shell.log
(screendriver.py, shared with plugin-sweep.py).

Goldens are kept per simulator model and per way its window faces, in
ios/app/evidence/sim-golden/<model>/<LandscapeLeft|LandscapeRight>/ (the Dynamic Island is on the side the top of
the device points to). The folder is gitignored: the goldens show the plugin list, and they change with Xcode's
simulator runtime, so each Mac keeps its own. Each run's shots and diff
heatmaps land in ios/app/evidence/sim-screens/<stamp>/. A screen passes when at most maxPctDiffering % of its
pixels differ from the golden by more than threshold in any channel (screens.json). Exits 0 exactly when GREEN;
the last line is VERDICT: SIM_SCREENS_GREEN or _RED. Run it with /usr/bin/python3 (it needs PIL + numpy).
"""
import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path

from screendriver import REPO, Driver, DriverError, device_name, hs, install, slug

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "screens.json"
GOLDEN_ROOT = REPO / "ios/app/evidence/sim-golden"
RUNS_ROOT = REPO / "ios/app/evidence/sim-screens"


def red(msg):
    print(f"!! {msg}")
    print("SIM_SCREENS_RED")
    print("VERDICT: SIM_SCREENS_RED")
    sys.exit(1)


def load_manifest():
    m = json.loads(MANIFEST.read_text())
    base = {"settle": 1.0, "threshold": 0, "maxPctDiffering": 0.0, "network": False, **m.get("defaults", {})}
    screens = [{**base, **s} for s in m["screens"]]
    slugs = [slug(s["name"]) for s in screens]
    if len(set(slugs)) != len(slugs):
        raise ValueError("two screens share a file name")
    return screens


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
    # Simulator.app keeps whichever way the window was last turned, and the island moves with it.
    _angle, facing = hs._sim_rotation(udid)
    golden_dir = GOLDEN_ROOT / model / (facing or "unknown-facing")
    run_dir = RUNS_ROOT / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    try:
        install(udid, no_build=args.no_build, jars=args.jars)
        drv = Driver(udid)
        print(f"\n>> Screens: {len(screens)} on {model}, CPU renderer, logged out, game covered")
        drv.launch(model)
    except DriverError as e:
        red(str(e))

    results = []
    for s in screens:
        name, file = s["name"], slug(s["name"]) + ".png"
        r = {"screen": name, "file": file}
        opened, why, _seq = drv.open(name)
        if not opened:
            results.append(dict(r, status="ERROR", why=why))
            print(f"   {name:<22} ERROR  {why}")
            if why == "the app exited":
                break
            continue
        time.sleep(s["settle"])
        try:
            im = drv.capture()
        except DriverError as e:
            results.append(dict(r, status="ERROR", why=str(e)))
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
            why = f"no golden at {golden}; run with --update"
            if any(d != golden_dir and (d / file).exists() for d in golden_dir.parent.iterdir()):
                why += f" (there is one for the window facing the other way; it faces {facing} now)"
            results.append(dict(r, status="NO_GOLDEN", why=why))
            print(f"   {name:<22} NO_GOLDEN  {why}")
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
    drv.open("-")

    summary = {"model": model, "facing": facing, "simUdid": udid, "update": args.update, "goldens": str(golden_dir),
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
