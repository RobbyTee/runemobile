#!/usr/bin/env python3
"""Render the shared contract as a readable reference page: docs/dev/bridge-api-reference.md.

The list of members is exactly what extract-contract.py (and so baseline.txt) pins: every public static method the
native shells can call, every JSON key the bridges emit, every -D property they read. The descriptions come from the
source's own Javadoc (or the comment directly above a member), so the page follows the code instead of drifting.

    gen-bridge-api.py            # rewrite docs/dev/bridge-api-reference.md
    gen-bridge-api.py --stdout   # print the page instead
    gen-bridge-api.py --check    # exit 1 if the committed page differs from what the source generates

Output is deterministic (sorted, no timestamps), so --check is a plain comparison. No line numbers are emitted:
they would make every unrelated edit to a bridge file stale the page.
"""
import html
import importlib.util
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('extract_contract', os.path.join(HERE, 'extract-contract.py'))
ec = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ec)

ROOT = ec.ROOT
OUT = os.path.join(ROOT, 'docs', 'dev', 'bridge-api-reference.md')
FLAG_CALL_RE = r'(Boolean\.getBoolean|Integer\.getInteger|Long\.getLong|System\.getProperty|getBoolean|getInteger|getLong|getProperty)\(\s*%s'
READER_FNS = {'getBoolean', 'getInteger', 'getLong', 'getProperty'}
WRITER_FNS = {'setProperty', 'clearProperty'}


# ── comment extraction ──────────────────────────────────────────────────────────────────────────────────────────

def comment_before(src, pos):
    """The Javadoc/block comment or run of // lines directly above pos, skipping annotation lines. '' if none."""
    lines = src[:pos].rstrip().split('\n')
    while lines and lines[-1].strip().startswith('@'):
        lines.pop()
    if not lines:
        return ''
    if lines[-1].rstrip().endswith('*/'):
        block = []
        while lines:
            ln = lines.pop()
            block.append(ln)
            if ln.strip().startswith('/*'):
                break
        else:
            return ''
        raw = '\n'.join(reversed(block)).strip()
        return raw[3:-2] if raw.startswith('/**') else raw[2:-2]
    block = []
    while lines and lines[-1].strip().startswith('//'):
        block.append(lines.pop().strip()[2:])
    return '\n'.join(reversed(block))


def strip_stars(raw):
    out = []
    for ln in raw.split('\n'):
        ln = ln.strip()
        if ln.startswith('*'):
            ln = ln[1:]
        out.append(ln.strip())
    text = '\n'.join(out).strip()
    # Drop the block tags (@param, @return, …): the summary paragraph is what the reference shows.
    return re.split(r'(?:^|\n)\s*@[a-zA-Z]+\b', text, maxsplit=1)[0].strip()


def first_paragraph(raw):
    text = strip_stars(raw)
    return re.split(r'\n\s*\n|<p>|<ul>|<ol>|<h\d>|<pre>|<table>', text, flags=re.I)[0].strip()


# ── Javadoc → Markdown ──────────────────────────────────────────────────────────────────────────────────────────

def _inline_tags(text):
    """Split text into (is_code, chunk) runs, resolving {@code}/{@link}/{@literal}/{@value} with nested braces."""
    runs, i = [], 0
    while True:
        j = text.find('{@', i)
        if j < 0:
            runs.append((False, text[i:]))
            return runs
        runs.append((False, text[i:j]))
        depth, k = 0, j
        while k < len(text):
            if text[k] == '{':
                depth += 1
            elif text[k] == '}':
                depth -= 1
                if depth == 0:
                    break
            k += 1
        body = text[j + 2:k]
        tag, _, arg = body.partition(' ')
        arg = arg.strip()
        if tag in ('code', 'value'):
            runs.append((True, arg))
        elif tag in ('link', 'linkplain'):
            target, _, label = arg.partition(' ')
            if label.strip():
                runs.append((tag == 'link', label.strip()))
            else:
                # net.runelite.Foo#bar(String) -> Foo#bar(String); #bar -> bar
                cls, hash_, member = target.partition('#')
                cls = cls.rsplit('.', 1)[-1]
                runs.append((True, (cls + '#' + member if cls else member) if hash_ else cls))
        else:
            runs.append((False, arg))
        i = k + 1


def _code_span(s):
    s = s.replace('\n', ' ')
    fence = '``' if '`' in s else '`'
    pad = ' ' if s.startswith('`') or s.endswith('`') else ''
    return '%s%s%s%s%s' % (fence, pad, s, pad, fence)


def to_markdown(text):
    out = []
    for is_code, chunk in _inline_tags(text):
        if is_code:
            out.append(_code_span(html.unescape(chunk)))
            continue
        chunk = re.sub(r'<code>(.*?)</code>', lambda m: '\x00' + m.group(1) + '\x01', chunk, flags=re.S | re.I)
        chunk = re.sub(r'</?(b|strong)>', '**', chunk, flags=re.I)
        chunk = re.sub(r'</?(em|i)>', '*', chunk, flags=re.I)
        chunk = re.sub(r'</?[a-zA-Z][^>]*>', ' ', chunk)
        chunk = html.unescape(chunk)
        parts = re.split(r'(\x00.*?\x01)', chunk, flags=re.S)
        for p in parts:
            if p.startswith('\x00'):
                out.append(_code_span(p[1:-1]))
            else:
                # Keep raw '<' from opening an HTML tag on GitHub.
                out.append(p.replace('<', '&lt;'))
    return re.sub(r'\s+', ' ', ''.join(out)).strip()


# ── scanning ────────────────────────────────────────────────────────────────────────────────────────────────────

def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, '/')


def scan_methods(src):
    """{contract key: (display signature, return type, description)} for every public static method in src."""
    found = {}
    for m in ec.METHOD_RE.finditer(src):
        name, params = m.group(1), m.group(2)
        key = '%s(%s)' % (name, ec.param_types(params))
        ret = ec.return_type(src, m)
        shown = ', '.join(re.sub(r'^final\s+', '', p.strip()) for p in params.split(',') if p.strip())
        shown = re.sub(r'\s+', ' ', shown)
        found.setdefault(key, ('%s(%s)' % (name, shown), ret, to_markdown(first_paragraph(comment_before(src, m.start())))))
    return found


def class_summary(src, cls):
    m = re.search(r'^(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s+%s\b' % re.escape(cls), src, re.M)
    return to_markdown(first_paragraph(comment_before(src, m.start()))) if m else ''


def flag_details(flag, sources):
    """(type, default, [reader classes], description) for one -D property, from its read sites.

    A read names the property directly, through a `static final String` constant (whose own comment then describes
    it), or by handing the literal to a helper method (typed as a string, with no default)."""
    kind, default, readers, desc = None, None, [], ''
    for cls, src in sources:
        names = ['"%s"' % re.escape(flag)]
        for cm in re.finditer(ec.STRING_CONST_RE % r'([A-Z][A-Z0-9_]*)', src):
            if cm.group(2) == flag:
                names.append(re.escape(cm.group(1)) + r'\b')
                if not desc:
                    desc = to_markdown(first_paragraph(comment_before(src, src.rfind('\n', 0, cm.start()) + 1)))
        reads = []
        for name in names:
            reads += re.finditer(FLAG_CALL_RE % name + r'\s*(?:,\s*([^()]*?))?\s*(\)|\()', src)
        helpers = [h for h in re.finditer(r'\b([a-z]\w*)\(\s*"%s"' % re.escape(flag), src) if h.group(1) not in READER_FNS | WRITER_FNS]
        if (reads or helpers) and cls not in readers:
            readers.append(cls)
        if helpers and not reads and kind is None:
            kind = 'string'
        for m in sorted(reads, key=lambda r: r.start()):
            fn, arg, closer = m.group(1).split('.')[-1], m.group(2), m.group(3)
            before = src[max(0, m.start() - 40):m.start()]
            if kind is None:
                if fn == 'getBoolean' or 'parseBoolean(' in before:
                    kind = 'boolean'
                elif fn == 'getInteger':
                    kind = 'int'
                elif fn == 'getLong':
                    kind = 'long'
                else:
                    kind = 'string'
                if closer == '(':
                    default = 'computed'
                elif arg:
                    default = arg.strip()
                    if kind in ('int', 'long'):
                        default = default.rstrip('Ll')
                    elif kind == 'boolean':
                        default = default.strip('"')
                elif fn == 'getBoolean':
                    default = 'false'
            if not desc:
                start = field_decl_start(src, m.start())
                if start is not None:
                    desc = to_markdown(first_paragraph(comment_before(src, start)))
    return kind or 'string', default, readers, desc


def field_decl_start(src, pos):
    """Start of the static field declaration that reads a flag at pos, or None for a read inside a method body.

    Only a field's own comment describes the flag; the comment above a statement in a method describes the code."""
    line_start = src.rfind('\n', 0, pos) + 1
    while line_start > 0:
        prev_start = src.rfind('\n', 0, line_start - 1) + 1
        prev = src[prev_start:line_start - 1].strip()
        if not prev or prev.endswith((';', '{', '}', '*/')) or prev.startswith(('//', '*', '/*', '@')):
            break
        line_start = prev_start
    decl = src[line_start:pos]
    return line_start if re.match(r'\s*(?:(?:private|public|protected)\s+)?static\b', decl) else None


# ── rendering ───────────────────────────────────────────────────────────────────────────────────────────────────

def render():
    entries = ec.collect()
    methods, payload, dynamic, flags, missing = defaultdict(list), defaultdict(list), {}, [], []
    for line in entries:
        kind, _, rest = line.partition(' ')
        if kind == 'METHOD':
            cls, _, key = rest.partition('#')
            methods[cls].append(key.partition(' -> ')[0])
        elif kind == 'PAYLOAD':
            cls, _, key = rest.partition(' ')
            payload[cls].append(key)
        elif kind == 'PAYLOAD-DYNAMIC':
            cls, _, count = rest.partition(' ')
            dynamic[cls] = count.partition('=')[2]
        elif kind == 'FLAG':
            flags.append(rest)
        else:
            missing.append(line)
    if missing:
        sys.exit('gen-bridge-api: the contract scan reports %s; fix extract-contract.py FILES first' % ', '.join(missing))

    sources = []
    for path in ec.FILES:
        with open(path, encoding='utf-8', errors='replace') as fh:
            sources.append((os.path.splitext(os.path.basename(path))[0], fh.read(), path))
    by_class = {cls: (src, path) for cls, src, path in sources}
    # Flags are read in more files than the classes with a section here (ec.flag_files() and the upstream seams).
    flag_sources = []
    for path in ec.flag_files() + sorted(ec.SEAM_FLAGS):
        with open(path, encoding='utf-8', errors='replace') as fh:
            flag_sources.append((os.path.splitext(os.path.basename(path))[0], fh.read(), path))
    flag_paths = {cls: path for cls, _, path in flag_sources}
    flag_info = {f: flag_details(f, [(c, s) for c, s, _ in flag_sources]) for f in flags}
    flags_by_class = defaultdict(list)
    for f, (_, _, readers, _) in flag_info.items():
        for cls in readers:
            flags_by_class[cls].append(f)

    classes = sorted(by_class)
    n_methods = sum(len(v) for v in methods.values())
    n_keys = sum(len(v) for v in payload.values())
    o = []
    w = o.append
    w('# Bridge API reference')
    w('')
    w('<!-- Generated by tools/shared-contract/gen-bridge-api.py. Do not edit by hand: run that script (or')
    w('     tools/shared-contract/check-contract.sh --update) and commit the result. -->')
    w('')
    w('Every member of the shared contract between the native shells and the Java bridge: the public static methods')
    w('the iOS and Android code calls by name, the JSON keys the bridges emit for the native screens to parse, and the')
    w('`-D` system properties the bridge reads at launch. It is generated from the same scan that writes')
    w('[`tools/shared-contract/baseline.txt`](../../tools/shared-contract/baseline.txt), and each description is the')
    w('Javadoc (or the comment directly above the member) in the source, so it matches the code at this commit. For')
    w('how the bridge works, which thread each call runs on, and how to change it safely, read')
    w('[bridge-api.md](bridge-api.md).')
    w('')
    w('| Classes | Methods | JSON keys | System properties |')
    w('|---:|---:|---:|---:|')
    w('| %d | %d | %d | %d |' % (len(classes), n_methods, n_keys, len(flags)))
    w('')
    w('## Classes')
    w('')
    for cls in classes:
        bits = []
        if methods.get(cls):
            bits.append('%d method%s' % (len(methods[cls]), '' if len(methods[cls]) == 1 else 's'))
        if payload.get(cls):
            bits.append('%d JSON key%s' % (len(payload[cls]), '' if len(payload[cls]) == 1 else 's'))
        if flags_by_class.get(cls):
            n = len(flags_by_class[cls])
            bits.append('%d propert%s' % (n, 'y' if n == 1 else 'ies'))
        w('- [`%s`](#%s): %s' % (cls, cls.lower(), ', '.join(bits) or 'no members'))
    w('- [System properties](#system-properties)')
    for cls in classes:
        src, path = by_class[cls]
        w('')
        w('## `%s`' % cls)
        w('')
        w('Source: [`%s`](../../%s)' % (rel(path), rel(path)))
        summary = class_summary(src, cls)
        if summary:
            w('')
            w(summary)
        if methods.get(cls):
            details = scan_methods(src)
            w('')
            w('**Methods**')
            w('')
            for key in sorted(methods[cls]):
                shown, ret, desc = details.get(key, (key, '?', ''))
                w('- `%s` → `%s`%s' % (shown, ret, ('  \n  ' + desc) if desc else ''))
        if payload.get(cls):
            w('')
            w('**JSON keys emitted:** ' + ', '.join('`%s`' % k for k in sorted(payload[cls])))
        if cls in dynamic:
            w('')
            w('Plus %s key%s built from a variable (not listed by name; the count is pinned in the baseline).'
              % (dynamic[cls], '' if dynamic[cls] == '1' else 's'))
        if flags_by_class.get(cls):
            w('')
            w('**System properties read:** ' + ', '.join('`%s`' % f for f in sorted(flags_by_class[cls])))
    w('')
    w('## System properties')
    w('')
    w('Set with `-D<name>=<value>` in the JVM options each port passes at launch. A `boolean` read with')
    w('`Boolean.getBoolean` is off unless set to `true`.')
    w('')
    for f in sorted(flags):
        kind, default, readers, desc = flag_info[f]
        dflt = '' if default is None else (', default `%s`' % default if default != 'computed' else ', default computed')
        who = ', '.join('`%s`' % r if r in by_class else '[`%s`](../../%s)' % (r, rel(flag_paths[r])) for r in readers)
        w('- `%s`: %s%s. Read by %s.%s' % (f, kind, dflt, who, (' ' + desc) if desc else ''))
    w('')
    return '\n'.join(o)


def main(argv):
    page = render()
    if '--stdout' in argv:
        sys.stdout.write(page)
        return 0
    if '--check' in argv:
        try:
            with open(OUT, encoding='utf-8') as fh:
                current = fh.read()
        except FileNotFoundError:
            current = None
        if current == page:
            print('bridge API reference: CURRENT (%s)' % rel(OUT))
            return 0
        print('bridge API reference: STALE — %s no longer matches the bridge sources.' % rel(OUT))
        print('  Regenerate and stage it: tools/shared-contract/gen-bridge-api.py && git add %s' % rel(OUT))
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as fh:
        fh.write(page)
    print('wrote %s' % rel(OUT))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
