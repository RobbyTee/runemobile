#!/usr/bin/env python3
"""plugin-sweep.py - open every plugin's settings screen and every side panel in the iOS Simulator, and check each.

  ios/dev-tools/sim-screens/plugin-sweep.py                           # build, install, sweep everything
  ios/dev-tools/sim-screens/plugin-sweep.py --no-build                # reuse the last simulator build
  ios/dev-tools/sim-screens/plugin-sweep.py --only panels             # only the side panels (or: config)
  ios/dev-tools/sim-screens/plugin-sweep.py "config:Agility" "panel:Notes"   # only these screens
  --jars restages the jars first (after a Java change).

The app runs as for the screen goldens (screendriver.py: logged out, CPU renderer, game covered). The script asks it
for the plugin list and the rail's panels ("inventory"). Before the panels it has the app turn on what sweep.json
names ("prepare"): plugins that are switched off and settings that hide a panel's button, so their panels are on the
rail too. Then it opens "config:<plugin>" for every plugin that has settings and "panel:<tooltip>" for every panel on
the rail, through the same methods their buttons call, and closes each before the next. At the end the app puts
back what prepare changed ("restore"), and the script checks the plugin list and the rail are as they were; if the
sweep dies first, the app's next launch restores them. A screen passes when:
  - the app reports it ready;
  - no [bridge-fault] or [json-fault] line reaches the app's logs between opening and closing it;
  - it isn't blank. A settings screen must list at least one setting. A rendered (Swing) panel must have drawn a
    frame that isn't one flat colour. And on every screen, the content area the app reports must show something
    other than its background in the screenshot.
Other new log lines (not on tools/log-scan/known-noise.json) while a screen is open are listed with it, but don't
fail it; sim-gate.sh's log scan judges them.

Each run's shots, its summary.json and a results.md table land in
ios/app/evidence/plugin-sweep/<stamp>/ (gitignored: the shots show the plugin list): full size for a screen that
failed, half size otherwise. Exits 0 exactly when GREEN; the last line is VERDICT: PLUGIN_SWEEP_GREEN or _RED. Run
it with /usr/bin/python3 (it needs PIL + numpy).
"""
import argparse
import importlib.util
import json
import os
import sys
import tempfile
import time
from pathlib import Path

from screendriver import REPO, Driver, DriverError, device_name, hs, install, slug

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "sweep.json"
RUNS_ROOT = REPO / "ios/app/evidence/plugin-sweep"
SETTLE = 0.3        # after "ready", before the screenshot
CLOSE_GRACE = 0.3   # after the close, for a fault the close queued on the EDT to reach the log
INVENTORY_TRIES = 6
# A pixel counts as ink when a channel differs from the content area's median colour by more than this.
INK_DELTA = 40
# The share of the content area that has to be ink for a screen not to count as blank. An empty area reads 0; the
# sparsest real screens (the empty Notes panel, a one-setting plugin) read 0.0025-0.003 on an iPhone 17 Pro Max.
MIN_SHOT_INK = 0.001
# A rendered panel's frame, measured by the app: below this share of pixels off its corner colour, it is flat.
MIN_PANEL_INK = 0.002
LOG_LINES_SHOWN = 3


def load_log_scan():
    spec = importlib.util.spec_from_file_location("log_scan", REPO / "tools/log-scan/log-scan.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


LOG_SCAN = load_log_scan()


def red(msg):
    print(f"!! {msg}")
    print("PLUGIN_SWEEP_RED")
    print("VERDICT: PLUGIN_SWEEP_RED")
    sys.exit(1)


def size_of(path):
    try:
        return path.stat().st_size
    except OSError:
        return 0


def read_from(path, offset):
    try:
        with open(path, "rb") as f:
            f.seek(offset)
            return f.read().decode("utf-8", errors="replace")
    except OSError:
        return ""


def log_events(text, noise):
    """(faults, new): the [bridge-fault]/[json-fault] lines in `text`, and the other events no noise entry covers,
    each as its header line."""
    if not text.strip():
        return [], []
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
        f.write(text)
        tmp = f.name
    try:
        events = LOG_SCAN.parse_events(tmp, "sim")
    finally:
        os.unlink(tmp)
    faults = [e["header"] for e in events if e["level"] == "FAULT"]
    new = [e["header"] for e in events if e["level"] != "FAULT" and not LOG_SCAN.match_noise(e, noise)]
    return faults, new


def shot_ink(im, info):
    """The share of the screen's content area (info["rect"], window points) that differs from the area's median
    colour by more than INK_DELTA in any channel. None when the app gave no area."""
    import numpy as np

    rect, window = info.get("rect"), info.get("window")
    if not rect or not window or not window[0]:
        return None
    scale = im.width / float(window[0])
    x0, y0 = max(0, round(rect[0] * scale)), max(0, round(rect[1] * scale))
    x1, y1 = min(im.width, round((rect[0] + rect[2]) * scale)), min(im.height, round((rect[1] + rect[3]) * scale))
    if x1 - x0 < 2 or y1 - y0 < 2:
        return 0.0
    area = np.asarray(im.crop((x0, y0, x1, y1)), dtype=np.int16)
    bg = np.median(area.reshape(-1, 3), axis=0)
    return round(float((np.abs(area - bg).max(axis=2) > INK_DELTA).mean()), 4)


def blank_reasons(kind, info, ink):
    """Why the screen counts as blank (empty when it doesn't)."""
    why = []
    if kind == "config":
        if info.get("screen") != "PluginConfigViewController":
            why.append(f"opened {info.get('screen')!r}, not the settings screen")
        elif not info.get("items"):
            why.append(f"no settings listed (load={info.get('load')})")
    if info.get("screen") == "rendered-panel":
        if info.get("image") is None:
            why.append("the panel never drew a frame")
        elif (info.get("ink") or 0) < MIN_PANEL_INK:
            why.append(f"the panel's frame is one flat colour (ink {info.get('ink')})")
    if ink is not None and ink < MIN_SHOT_INK:
        why.append(f"nothing but background in the content area (ink {ink})")
    return why


def get_inventory(drv):
    """The app's plugin list and rail panels, read until two reads in a row agree (the rail fills in after boot, and
    after a plugin starts or stops)."""
    inv_file = drv.docs / "screen-inventory.json"
    last = None
    for _ in range(INVENTORY_TRIES):
        inv_file.unlink(missing_ok=True)
        ok, why, _seq = drv.open("inventory")
        if not ok:
            raise DriverError(f"inventory failed: {why}")
        inv = json.loads(inv_file.read_text())
        key = ([(p.get("name"), p.get("enabled")) for p in inv["plugins"]], inv["panels"])
        if key == last:
            return inv
        last = key
        time.sleep(2.0)
    raise DriverError(f"the plugin list or the rail kept changing over {INVENTORY_TRIES} reads")


def prepare(drv, inv):
    """Have the app turn on what sweep.json names. Returns (the inventory after, plugins it names that aren't
    installed)."""
    want = json.loads(MANIFEST.read_text())["turnOn"]
    by_name = {p["name"]: p for p in inv["plugins"]}
    missing = [n for n in want["plugins"] if n not in by_name]
    classes = [by_name[n]["className"] for n in want["plugins"] if n in by_name]
    (drv.docs / "screen-prepare.json").write_text(json.dumps({"enable": classes, "config": want["config"]}))
    ok, why, _seq = drv.open("prepare")
    if not ok:
        raise DriverError(f"prepare failed: {why}")
    after = get_inventory(drv)
    off = [p["name"] for p in after["plugins"] if p["className"] in classes and not p.get("enabled")]
    if off:
        raise DriverError(f"still switched off after prepare: {', '.join(off)}")
    return after, missing


def restore(drv, before):
    """Have the app put back what prepare changed; why the plugin list or the rail still differ, or None."""
    ok, why, _seq = drv.open("restore")
    if not ok:
        return f"restore failed: {why}"
    after = get_inventory(drv)
    was = {p["name"]: p.get("enabled") for p in before["plugins"]}
    changed = [p["name"] for p in after["plugins"] if was.get(p["name"]) != p.get("enabled")]
    if changed:
        return "switched the other way after the restore: " + ", ".join(changed)
    if set(after["panels"]) != set(before["panels"]):
        return f"the rail after the restore {after['panels']} isn't the one before {before['panels']}"
    return None


def main():
    ap = argparse.ArgumentParser(description="Open every plugin settings screen and side panel in the simulator.")
    ap.add_argument("screens", nargs="*", help="only these, as config:<plugin> or panel:<tooltip>")
    ap.add_argument("--only", choices=["config", "panels"])
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--jars", action="store_true")
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)  # one line per screen, as it happens, also into a log file

    try:
        noise = LOG_SCAN.load_noise(LOG_SCAN.DEFAULT_NOISE)
    except LOG_SCAN.InputError as e:
        red(str(e))
    udid, err = hs._sim_udid({"sim_udid": os.environ.get("SIM_UDID")})
    if err:
        red(f"no simulator: {err}")
    model = device_name(udid)
    run_dir = RUNS_ROOT / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    try:
        install(udid, no_build=args.no_build, jars=args.jars)
        drv = Driver(udid)
        print(f"\n>> Plugin sweep on {model}, CPU renderer, logged out, game covered")
        drv.launch(model)
        before = get_inventory(drv)
        inv, not_installed = before, []
        if args.only != "config":
            inv, not_installed = prepare(drv, before)
    except (DriverError, OSError, ValueError, KeyError) as e:
        red(str(e))
    (run_dir / "inventory.json").write_text(json.dumps({"before": before, "swept": inv}, indent=2) + "\n")
    turned_on = [t for t in inv["panels"] if t not in before["panels"]]
    if turned_on:
        print(f"   turned on for the sweep: {', '.join(turned_on)} (sweep.json)")
    if not_installed:
        print(f"   sweep.json names plugins that aren't installed: {', '.join(not_installed)}")

    plugins = sorted(inv["plugins"], key=lambda p: str(p.get("name", "")).lower())
    todo = []
    if args.only != "panels":
        todo += [("config", f"config:{p['name']}", p["name"]) for p in plugins if p.get("hasConfig")]
    if args.only != "config":
        todo += [("panel", f"panel:{t}", t) for t in inv["panels"]]
    if args.screens:
        known = {name for _k, name, _l in todo}
        unknown = [n for n in args.screens if n not in known]
        if unknown:
            restore(drv, before)
            red(f"not in the app's inventory: {', '.join(unknown)}")
        todo = [t for t in todo if t[1] in args.screens]
    no_settings = [p["name"] for p in plugins if not p.get("hasConfig")]
    print(f"   {len(plugins)} plugins ({len(plugins) - len(no_settings)} with settings), "
          f"{len(inv['panels'])} panels on the rail; checking {len(todo)} screens")

    results = []
    started = time.time()
    for kind, name, label in todo:
        r = {"kind": kind, "screen": name, "label": label, "file": slug(name) + ".png",
             "turnedOn": kind == "panel" and label in turned_on}
        marks = {p: size_of(p) for p in (drv.jvm_log, drv.shell_log)}
        opened, why, seq = drv.open(name)
        info, ink, im = None, None, None
        if opened:
            info = drv.info(seq) or {}
            time.sleep(SETTLE)
            try:
                im = drv.capture()
                ink = shot_ink(im, info)
            except DriverError as e:
                why = str(e)
        closed, close_why, _s = drv.open("-")
        time.sleep(CLOSE_GRACE)
        faults, new = [], []
        for path, mark in marks.items():
            f, n = log_events(read_from(path, mark), noise)
            faults += f
            new += n
        problems = []
        if not opened:
            problems.append(f"not ready: {why}")
        elif im is None:
            problems.append(why)
        elif not info:
            problems.append("the app sent no [screen] info")
        else:
            problems += blank_reasons(kind, info, ink)
        if faults:
            problems.append(f"{len(faults)} fault line(s): {faults[0]}")
        if not closed:
            problems.append(f"didn't close: {close_why}")
        r.update(status="FAIL" if problems else "PASS", problems=problems, info=info, shotInk=ink, faults=faults,
                 newLogLines=new)
        if im is not None:
            if problems:
                im.save(run_dir / r["file"])
            else:
                im.resize((im.width // 2, im.height // 2)).save(run_dir / r["file"])
        else:
            r["file"] = None
        results.append(r)
        detail = "; ".join(problems) if problems else _detail(kind, info, ink)
        if new:
            detail += f"  [{len(new)} new log line(s)]"
        print(f"   {r['status']:<4} {name:<44} {detail}")
        if why == "the app exited" or close_why == "the app exited":
            break

    elapsed = round(time.time() - started)
    exited = len(results) < len(todo)
    restored = None
    if inv is not before and not exited:
        try:
            restored = restore(drv, before)
        except (DriverError, OSError, ValueError, KeyError) as e:
            restored = str(e)
    summary = {"model": model, "simUdid": udid, "shots": str(run_dir), "seconds": elapsed,
               "plugins": len(plugins), "pluginsWithoutSettings": no_settings, "panels": inv["panels"],
               "turnedOn": turned_on, "restoreProblem": restored, "results": results}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (run_dir / "results.md").write_text(results_table(summary))
    print(f"shots={run_dir}")
    print(f"summary={run_dir / 'summary.json'}")
    if exited:
        red(f"stopped after {len(results)} of {len(todo)} screens (the app exited; its next launch restores what "
            "prepare changed)")
    if restored:
        red(f"the simulator's config isn't as it was: {restored}")
    bad = [r for r in results if r["status"] != "PASS"]
    if bad:
        red(f"{len(bad)} of {len(results)} screens failed: " + ", ".join(r["screen"] for r in bad))
    print(f"checked {len(results)} screens in {elapsed} s")
    print("PLUGIN_SWEEP_GREEN")
    print("VERDICT: PLUGIN_SWEEP_GREEN")


def _detail(kind, info, ink):
    if kind == "config":
        extra = f", {info['unknown']} read-only" if info.get("unknown") else ""
        return f"{info.get('items')} settings{extra}, ink {ink}"
    if info.get("screen") == "rendered-panel":
        return f"rendered {info.get('image')}, frame ink {info.get('ink')}, ink {ink}"
    return f"{info.get('screen')}: {info.get('texts')} texts, {info.get('cells')} cells, ink {ink}"


def results_table(summary):
    rows = ["| Screen | Status | What it showed | Problems | New log lines |", "|---|---|---|---|---|"]
    for r in summary["results"]:
        shown = _detail(r["kind"], r["info"], r["shotInk"]) if r["info"] else ""
        if r["turnedOn"]:
            shown = "(turned on for the sweep) " + shown
        rows.append("| {} | {} | {} | {} | {} |".format(
            r["screen"], r["status"], shown, "; ".join(r["problems"]).replace("|", "/"),
            "; ".join(r["newLogLines"][:LOG_LINES_SHOWN]).replace("|", "/")))
    for name in summary["pluginsWithoutSettings"]:
        rows.append(f"| config:{name} | n/a | the plugin has no settings | | |")
    return "\n".join(rows) + "\n"


if __name__ == "__main__":
    main()
