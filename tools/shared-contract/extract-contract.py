#!/usr/bin/env python3
"""Extract the host-facing contract that BOTH the iOS and Android ports depend on.

The two ports embed the same staged jars and call into them by name via JNI/reflection. The coupling surface
is therefore narrow and precise: the public-static API of the `net.runelite.client.headless.*` bridges + the
two boot/activation entry points the native hosts invoke, plus the `-D` gate/property names those classes read.
A change to any of these can break the *other* port even though its own files were untouched.

Output: a sorted, deterministic list of `METHOD <Class>#<name>(<paramTypes>)` and `FLAG <property>` lines.
Param names (and a leading `final`) are stripped so only the real signature matters. Run via check-contract.sh.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
HEADLESS = os.path.join(ROOT, 'runelite-client/src/main/java/net/runelite/client/headless')
AWT = os.path.join(ROOT, 'ios/dev-tools/awtsmoke')

# Explicit file list = the actual host-called surface (excludes dev probes/tests that aren't part of the contract).
FILES = [os.path.join(HEADLESS, f) for f in (
    'JavaBridge.java', 'MobileUiBridge.java', 'MenuPeekBridge.java', 'MobileChromeBridge.java',
    'HudDataBridge.java', 'HudVeneerBridge.java', 'QuestHelperBridge.java', 'WorldSwitcherBridge.java',
    'LootTrackerBridge.java', 'XpTrackerBridge.java', 'GrandExchangeBridge.java', 'TimeTrackingBridge.java',
    'HeadlessLogin.java', 'HeadlessResize.java',
)] + [os.path.join(AWT, f) for f in (
    'DeviceHeadlessBoot.java', 'UnsafePokeActivation.java',
)]

METHOD_RE = re.compile(r'public\s+static\s+[\w.$<>\[\],\s]+?\s+(\w+)\s*\(([^)]*)\)')
FLAG_RE = re.compile(r'(?:getBoolean|getInteger|getLong|getProperty)\(\s*"([^"]+)"')


def param_types(params):
    out = []
    for p in params.split(','):
        p = p.strip()
        if not p:
            continue
        if p.startswith('final '):
            p = p[len('final '):]
        toks = p.split()
        t = ' '.join(toks[:-1]) if len(toks) >= 2 else p
        out.append(t.replace(' ', ''))
    return ','.join(out)


def main():
    entries = set()
    missing = []
    for path in FILES:
        if not os.path.isfile(path):
            missing.append(os.path.relpath(path, ROOT))
            continue
        cls = os.path.splitext(os.path.basename(path))[0]
        with open(path, encoding='utf-8', errors='replace') as fh:
            src = fh.read()
        for m in METHOD_RE.finditer(src):
            entries.add('METHOD %s#%s(%s)' % (cls, m.group(1), param_types(m.group(2))))
        for fm in FLAG_RE.finditer(src):
            entries.add('FLAG %s' % fm.group(1))
    if missing:
        # A renamed/removed contract file is itself a breaking change — surface it loudly.
        for m in missing:
            entries.add('MISSING-FILE %s' % m)
    for line in sorted(entries):
        print(line)


if __name__ == '__main__':
    main()
