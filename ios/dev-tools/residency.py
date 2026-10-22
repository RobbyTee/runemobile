#!/usr/bin/env python3
"""Read the residency history off the phone and add it up.

The app records how every session ended (RMResidency) and reports the last one at the next
launch. One line answers nothing: the plausible causes of a short session -- jetsam under the
JVM's footprint, a dropped audio session, the background idle gate doing its job -- call for
OPPOSITE fixes, and what separates them is their proportions across a week of ordinary use.
This pulls the accumulated lines and prints those proportions.

It decides nothing. It names the lever each class points at, and the ground truth that would
confirm it (JetsamEvent-*.ips under Settings -> Privacy & Security -> Analytics Data, and
Documents/jvm-stdio.log), because an inference over a 15s sample is a pattern detector and not
a verdict -- see the header of RMResidency.h.

    ios/dev-tools/residency.py                 pull from the connected iPhone and summarise
    ios/dev-tools/residency.py -v              ... and print each session's sentence
    ios/dev-tools/residency.py --file FILE     summarise a copy already on disk
    ios/dev-tools/residency.py --self-test     check the arithmetic; no phone needed

Every pull is merged into build/residency/history.jsonl, deduplicated by session start, so the
archive outlives the 200-session cap the phone keeps.
"""

import argparse
import datetime
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ARCHIVE = REPO / "build" / "residency" / "history.jsonl"
BUNDLE_ID = "dev.audaro.runemobile"
CONTAINER = "Documents/residency-history.log"

# The live record RMResidency rewrites every beat. Its presence tells the two reasons for a missing
# history apart -- see missing_history_advice.
RECORD = "Documents/residency.plist"

# Every key RMResidency writes, with the type it writes. A missing or wrongly typed key is a hard
# error rather than a skipped line: the writer and this reader ship in the same commit, so a
# mismatch means something is wrong with the data, and a quietly dropped session is a quietly
# wrong proportion -- which is the one output this tool has.
FIELDS = {
    "started": str,
    "start": (int, float),
    "lastBeat": (int, float),
    "livedSec": (int, float),
    "class": str,
    "state": str,
    "clean": bool,
    "jit": bool,
    "idleReleased": bool,
    "rearms": int,
    "memWarnings": int,
    "minAvailMB": (int, float),
    "verdict": str,
}

# Written since the re-arm breakdown was added, and absent from lines older than it. Optional for
# that reason alone -- a field a known earlier writer did not emit, not a field that may go
# missing. Everything in FIELDS stays mandatory.
OPTIONAL = {"rearmsBy": dict}

# What each class means in one line, and the dial it points at. Kept in the same order the
# verdict's branches are tried, so this list reads as that chain does.
CLASSES = {
    "clean": (
        "iOS said it was terminating",
        "nothing to tune -- these are sessions that ended on purpose",
    ),
    "jetsam-suspected": (
        "headroom was low, or a memory warning arrived",
        "the -Xmx1024m heap in ios/app/RuneMobile/JVMLauncher.m, which already carries a note "
        "to dial it back if jetsam kills",
    ),
    "idle-gate": (
        "logged out in the background, so the keepalive was released on purpose",
        "kBgIdleStopSeconds in ios/app/RuneMobile/ViewController.m -- these are by design, and "
        "lengthening the gate trades battery for residency",
    ),
    "background-unknown": (
        "died in the background with headroom to spare",
        "the keepalive is losing the process: the re-arm paths in ios/app/RuneMobile/"
        "ViewController.m, not the heap",
    ),
    "foreground-unexpected": (
        "died in the foreground",
        "not residency at all -- read the crash reports in Analytics Data",
    ),
}


def die(msg):
    sys.exit("residency: " + msg)


def when(started):
    """The stored stamp is exact and long; a table wants the minute it began."""
    try:
        return datetime.datetime.strptime(started, "%Y-%m-%dT%H:%M:%S%z").strftime(
            "%Y-%m-%d %H:%M")
    except ValueError:
        return started


def span(seconds):
    """Same units the app's own line uses, so the two read alike."""
    if seconds >= 3600:
        return "%.1fh" % (seconds / 3600.0)
    if seconds >= 60:
        return "%.0fm" % (seconds / 60.0)
    return "%.0fs" % seconds


def parse(text, source):
    entries = []
    for n, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        where = "%s line %d" % (source, n)
        try:
            entry = json.loads(line)
        except json.JSONDecodeError as e:
            die("%s is not JSON (%s): %s" % (where, e, line[:120]))
        if not isinstance(entry, dict):
            die("%s is not an object: %s" % (where, line[:120]))
        for key, want in FIELDS.items():
            if key not in entry:
                die("%s has no %r -- written by a build this reader does not match" % (where, key))
            # bool is a subclass of int in Python, so EVERY numeric field accepts True without
            # this -- not only the ones declared as a plain int.
            got = entry[key]
            if want is not bool and isinstance(got, bool):
                die("%s has %r = %r, wanted a number" % (where, key, got))
            if not isinstance(got, want):
                die("%s has %r = %r, wanted %s" % (where, key, got, want))
        for key, want in OPTIONAL.items():
            if key in entry and not isinstance(entry[key], want):
                die("%s has %r = %r, wanted %s" % (where, key, entry[key], want))
        for key in ("rearms", "memWarnings"):
            if entry[key] < 0:
                die("%s has %r = %d, which is not a count" % (where, key, entry[key]))
        for tag, n in entry.get("rearmsBy", {}).items():
            if not isinstance(tag, str) or isinstance(n, bool) or not isinstance(n, int):
                die("%s has a re-arm reason %r = %r, wanted a count" % (where, tag, n))
            if n < 0:
                die("%s has a re-arm reason %r = %d, which is not a count" % (where, tag, n))
        # The total and the breakdown are incremented by the same call, so they cannot disagree
        # unless the line is damaged -- and a breakdown that outweighs its total would be read as
        # a flapping keepalive that never happened. Absent is fine: a line older than the tags.
        if "rearmsBy" in entry:
            total = sum(entry["rearmsBy"].values())
            if total != entry["rearms"]:
                die("%s counts %d re-arm(s) but its reasons add up to %d"
                    % (where, entry["rearms"], total))
        if entry["class"] not in CLASSES:
            die("%s has an unknown class %r" % (where, entry["class"]))
        entries.append(entry)
    return entries


def merge(entries, archive=ARCHIVE):
    """Keep every session ever pulled, not just the 200 the phone still holds."""
    kept = {}
    if archive.exists():
        for entry in parse(archive.read_text(), str(archive)):
            kept[entry["start"]] = entry
    added = sum(1 for e in entries if e["start"] not in kept)
    for entry in entries:
        kept[entry["start"]] = entry
    archive.parent.mkdir(parents=True, exist_ok=True)
    ordered = [kept[k] for k in sorted(kept)]
    # Written beside the archive and renamed over it. write_text() truncates first, so an
    # interruption anywhere inside it would leave the only copy of everything past the phone's
    # 200-session cap half-written -- and this file exists BECAUSE the phone drops old sessions.
    # os.replace is atomic within a filesystem, which a sibling path guarantees.
    tmp = archive.with_suffix(archive.suffix + ".new")
    tmp.write_text("".join(json.dumps(e, sort_keys=True) + "\n" for e in ordered))
    os.replace(tmp, archive)
    return ordered, added


def find_device(wanted):
    out = subprocess.run(
        ["xcrun", "devicectl", "list", "devices", "--json-output", "-"],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        die("devicectl could not list devices:\n" + (out.stderr.strip() or out.stdout.strip()))
    try:
        devices = json.loads(out.stdout)["result"]["devices"]
    except (ValueError, KeyError) as e:
        die("devicectl's device list did not parse (%s)" % e)
    phones = [d for d in devices
              if d.get("hardwareProperties", {}).get("deviceType") == "iPhone"]
    if wanted:
        phones = [d for d in phones
                  if wanted in (d.get("identifier"),
                                d.get("deviceProperties", {}).get("name"))]
        if not phones:
            die("no paired iPhone matches %r" % wanted)
    if not phones:
        die("no paired iPhone. Plug it in (or pair it) and unlock it.")
    if len(phones) > 1:
        names = ", ".join("%s (%s)" % (d.get("deviceProperties", {}).get("name", "?"),
                                       d["identifier"])
                          for d in phones)
        die("more than one paired iPhone -- pass --device: " + names)
    return (phones[0]["identifier"],
            phones[0].get("deviceProperties", {}).get("name", phones[0]["identifier"]))


def copy_from_phone(device_id, source, dest):
    """devicectl's copy of one file out of the app container; (ok, detail)."""
    out = subprocess.run(
        ["xcrun", "devicectl", "device", "copy", "from",
         "--device", device_id,
         "--domain-type", "appDataContainer",
         "--domain-identifier", BUNDLE_ID,
         "--user", "mobile",
         "--source", source,
         "--destination", str(dest),
         "--quiet"],
        capture_output=True, text=True,
    )
    ok = out.returncode == 0 and Path(dest).exists()
    return ok, (out.stderr.strip() or out.stdout.strip() or "no output")


def record_present(device_id):
    """Whether the phone has the live record, or None if devicectl will not say.

    Diagnostic only, and deliberately silent on failure: every caller already has something to
    report, and a devicectl that will not answer must not turn "the file is missing" into a
    failure of its own.
    """
    with tempfile.TemporaryDirectory() as tmp:
        ok, detail = copy_from_phone(device_id, RECORD, Path(tmp) / "residency.plist")
    if ok:
        return True
    return False if "7000" in detail else None


def missing_history_advice(record):
    """Which of the two reasons the file can be absent, narrowed by asking the phone.

    Recited together they read as "keep playing", and that is the wrong advice when the phone is
    running a build from before the record existed, which writes nothing launch after launch. The
    phone can be asked which case this is: any build that keeps the history also rewrites the live
    record every few seconds, so its absence means an older build.
    """
    if record is None:
        return ("  Either the installed build predates the residency record or no launch has\n"
                "  reported an ended session yet -- and devicectl would not say whether the live\n"
                "  record (%s) exists, which would tell them apart." % RECORD)
    if not record:
        return ("  The phone has no %s either, so the installed build does not write the\n"
                "  record at all and this step has not started yet. Install a current build\n"
                "  (ios/dev-tools/remote-build.sh) and play." % RECORD)
    return ("  The phone has the live record, so its build does write the history -- no launch\n"
            "  has reported an ended session yet. The history line lands at the launch AFTER one\n"
            "  that recorded a session: two launches from a fresh install, one from an upgrade\n"
            "  that kept %s. Play, relaunch, and run this again." % RECORD)


def pull(device_id, dest):
    ok, detail = copy_from_phone(device_id, CONTAINER, dest)
    if not ok:
        if "7000" in detail:
            die("the phone has no %s yet.\n%s\n"
                "  devicectl said: %s"
                % (CONTAINER, missing_history_advice(record_present(device_id)), detail))
        die("could not read %s from the phone (is it unlocked?):\n  %s" % (CONTAINER, detail))
    return dest.read_text()


def summarise(entries, verbose=False, out=print):
    if not entries:
        die("the history is empty -- no session has ended yet.")

    out("")
    out("%d session%s, %s .. %s" % (
        len(entries), "" if len(entries) == 1 else "s",
        when(entries[0]["started"]), when(entries[-1]["started"])))
    out("")
    out("  %-16s %7s  %-20s %3s %7s %8s" %
        ("started", "lived", "ended", "jit", "re-arms", "min free"))
    for e in entries:
        out("  %-16s %7s  %-20s %3s %7d %6.0fMB" % (
            when(e["started"]),
            ("" if e["clean"] else "≥") + span(e["livedSec"]),
            e["class"],
            "yes" if e["jit"] else "no",
            e["rearms"],
            e["minAvailMB"]))
        if verbose:
            out("      " + e["verdict"])

    out("")
    out("how they ended")
    counts = {}
    for e in entries:
        counts[e["class"]] = counts.get(e["class"], 0) + 1
    for klass, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        out("  %-20s %3d %3d%%  %s" %
            (klass, n, round(100.0 * n / len(entries)), CLASSES[klass][0]))

    lived = sorted(e["livedSec"] for e in entries)
    mid = lived[len(lived) // 2] if len(lived) % 2 else \
        (lived[len(lived) // 2 - 1] + lived[len(lived) // 2]) / 2.0
    withjit = sum(1 for e in entries if e["jit"])
    rearms = sum(e["rearms"] for e in entries)
    out("")
    out("how long they lasted  (≥ means measured to the last beat: a suspended process stops")
    out("                       beating while staying resident, so the number is a floor)")
    out("  median %s   longest %s   shortest %s" % (span(mid), span(lived[-1]), span(lived[0])))
    out("  %d of %d held JIT, so that many deaths cost a re-attach" % (withjit, len(entries)))
    # The count says the keepalive STOPPED and was restarted. It does not say the audio session
    # dropped: a route change, a media-services reset, an interruption and a watchdog tick all
    # arrive at the same counter and do not share a fix, so the reasons print beside the total.
    by = {}
    for e in entries:
        for tag, n in e.get("rearmsBy", {}).items():
            by[tag] = by.get(tag, 0) + n
    detail = ", ".join("%s %d" % (t, n)
                       for t, n in sorted(by.items(), key=lambda kv: (-kv[1], kv[0])))
    out("  the keepalive stopped and was restarted %d time%s%s" %
        (rearms, "" if rearms == 1 else "s", " (%s)" % detail if detail else ""))

    # The dominant class only. Naming all three levers at once is how a tuning session ends up
    # pulling two of them in opposite directions, which is the failure this whole record exists
    # to prevent.
    out("")
    top = max(counts.items(), key=lambda kv: (kv[1], kv[0]))[0]
    out("most sessions ended as %s -- %s." % (top, CLASSES[top][0]))
    out("  the lever for that is %s" % CLASSES[top][1])
    if len(entries) < 10:
        out("")
        out("  %d sessions is not many: one unusual day moves every proportion above." %
            len(entries))
    out("")
    out("  Before tuning, confirm against ground truth -- these classes are inferred from a 15s")
    out("  sample, not reported by the kernel: JetsamEvent-*.ips under Settings -> Privacy &")
    out("  Security -> Analytics & Improvements -> Analytics Data, and Documents/jvm-stdio.log.")
    out("")


def self_test():
    def entry(**kw):
        base = {
            "started": "2026-10-16T22:16:23-0700", "start": 1792214183.0,
            "lastBeat": 1792214212.0, "livedSec": 29.0, "class": "clean", "state": "background",
            "clean": True, "jit": True, "idleReleased": False, "rearms": 0, "memWarnings": 0,
            "minAvailMB": 5273.5, "verdict": "[residency] previous session lasted 29s and ...",
        }
        base.update(kw)
        return base

    checks = []

    def check(name, cond):
        checks.append((name, cond))

    # round trip
    rows = [entry(start=1.0), entry(start=2.0, **{"class": "idle-gate"}, clean=False)]
    text = "".join(json.dumps(r) + "\n" for r in rows)
    back = parse(text, "self-test")
    check("round trip keeps both rows", len(back) == 2)
    check("round trip keeps the class", back[1]["class"] == "idle-gate")

    # blank lines are not rows
    check("blank lines ignored", len(parse("\n" + text + "\n\n", "self-test")) == 2)

    # every malformed shape is fatal, not skipped
    for bad, why in [
        ("not json\n", "garbage"),
        ('["a"]\n', "not an object"),
        ('{"start": 1}\n', "missing keys"),
        (json.dumps({**entry(), "rearms": True}) + "\n", "bool where a count belongs"),
        (json.dumps({**entry(), "class": "invented"}) + "\n", "unknown class"),
        (json.dumps({**entry(), "livedSec": "29"}) + "\n", "string where a number belongs"),
        (json.dumps({**entry(), "livedSec": True}) + "\n", "bool where a float belongs"),
        (json.dumps({**entry(), "rearmsBy": []}) + "\n", "re-arm breakdown that is not a map"),
        (json.dumps({**entry(), "rearmsBy": {"watchdog": "2"}}) + "\n",
         "re-arm count that is not a number"),
        (json.dumps({**entry(), "rearms": 1, "rearmsBy": {"watchdog": 900}}) + "\n",
         "a breakdown that outweighs its total"),
        (json.dumps({**entry(), "rearms": -1}) + "\n", "a negative count"),
    ]:
        try:
            parse(bad, "self-test")
            check("rejects %s" % why, False)
        except SystemExit:
            check("rejects %s" % why, True)

    # the arithmetic the report rests on
    lines = []
    summarise([
        entry(start=1.0, livedSec=60.0, **{"class": "idle-gate"}, clean=False, jit=True),
        entry(start=2.0, livedSec=120.0, **{"class": "idle-gate"}, clean=False, jit=False),
        entry(start=3.0, livedSec=30.0, **{"class": "jetsam-suspected"}, clean=False, rearms=2,
              rearmsBy={"route-change": 1, "watchdog": 1}),
    ], out=lines.append)
    report = "\n".join(lines)
    check("counts the dominant class",
          any(l.split()[:3] == ["idle-gate", "2", "67%"] for l in lines))
    check("median of three is the middle", "median 1m" in report)
    check("names the dominant lever", "kBgIdleStopSeconds" in report)
    check("totals the re-arms", "restarted 2 times" in report)
    check("names the re-arm reasons", "(route-change 1, watchdog 1)" in report)
    check("a line with no breakdown still parses",
          len(parse(json.dumps(entry()) + "\n", "self-test")) == 1)
    check("flags a thin sample", "3 sessions is not many" in report)
    check("marks floors", "≥30s" in report)

    # the missing-file advice names ONE cause, not both
    check("a phone with no record is told its build writes nothing",
          "does not write" in missing_history_advice(False))
    check("a phone with the record is told to relaunch",
          "does write the history" in missing_history_advice(True))
    check("an unanswered question falls back to both causes",
          "Either" in missing_history_advice(None))

    # merge: same session twice is one row, and the archive survives a second pull
    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "history.jsonl"
        merged, added = merge([entry(start=1.0), entry(start=2.0)], archive)
        check("first merge adds both", (len(merged), added) == (2, 2))
        merged, added = merge([entry(start=2.0), entry(start=3.0)], archive)
        check("second merge adds only the new one", (len(merged), added) == (3, 1))
        check("archive is sorted by start", [e["start"] for e in merged] == [1.0, 2.0, 3.0])

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print("  %s %s" % ("ok  " if ok else "FAIL", name))
    if failed:
        print("\nresidency self-test: %d passed, %d FAILED" % (len(checks) - len(failed),
                                                               len(failed)))
        return 1
    print("\nresidency self-test: %d checks passed" % len(checks))
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True, description=__doc__.splitlines()[0])
    ap.add_argument("--device", help="UDID or name, when more than one iPhone is paired")
    ap.add_argument("--file", help="read this copy instead of pulling from the phone")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="print each session's own sentence under its row")
    ap.add_argument("--self-test", action="store_true", help="check the arithmetic; no phone")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.file:
        path = Path(args.file)
        if not path.exists():
            die("no such file: %s" % path)
        summarise(parse(path.read_text(), str(path)), args.verbose)
        return 0

    device_id, name = find_device(args.device)
    with tempfile.TemporaryDirectory() as tmp:
        text = pull(device_id, Path(tmp) / "residency-history.log")
    entries = parse(text, "the phone's %s" % CONTAINER)
    merged, added = merge(entries)
    print("")
    print("%s: %d session%s on the phone, %d new in %s (%d in all)" %
          (name, len(entries), "" if len(entries) == 1 else "s", added,
           ARCHIVE.relative_to(REPO), len(merged)))
    summarise(merged, args.verbose)
    return 0


if __name__ == "__main__":
    sys.exit(main())
