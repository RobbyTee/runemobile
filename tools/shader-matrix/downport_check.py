#!/usr/bin/env python3
"""Apply a port's runtime shader downport to the stages RlhdShaderDump assembled, then validate each one with
glslangValidator.

  ios      RouteCBridge downportShaderSource (default modes), for an ES 3.0 driver. The header, the vertex casts and
           the rlhdCasts pairs are parsed out of RouteCBridge.m.
  android  librlawt.cpp rc_downport at `#version 320 es` (the Pixel's Mali is ES 3.2; ES_VER="310 es" overrides).
           The precision header and kSceneVertexCasts are parsed out of librlawt.cpp.
A parse that finds nothing fails the run, so the checker stays in step with the native code.

Usage: downport_check.py <ios|android> <RouteCBridge.m|librlawt.cpp> <assembled-dir> <out-dir>

iOS stages are validated at `#version 320 es`, not 300 es. glslang rejects in/out interface blocks below 320, but
Apple's ES 3.0 driver accepts them, and the int<->float strictness is the same at both versions. Validating at 320
would also accept ES 3.1+ built-ins, which the ES 3.0 driver rejects. Two scans on glslang's preprocessed output
(comments and dead #if branches removed) cover what glslang does not report:
  - an ES 3.1+ built-in or qualifier in live code (iOS only);
  - a sampler type with no precision of its own, either in the header or on the declaration (both ports). ES gives
    only sampler2D and samplerCube a default, and that default is lowp.
Output: one line per stage with errors, then `TOTAL <n>; <k> distinct`, then each distinct error with its source
line. Exit status 0 only when TOTAL is 0.
"""
import os
import pathlib
import re
import subprocess
import sys

if len(sys.argv) != 5 or sys.argv[1] not in ('ios', 'android'):
	sys.exit('usage: downport_check.py <ios|android> <RouteCBridge.m|librlawt.cpp> <assembled-dir> <out-dir>')
port = sys.argv[1]
bridge, src_dir, out_dir = map(pathlib.Path, sys.argv[2:5])
m = bridge.read_text()
LIT = r'"((?:[^"\\]|\\.)*)"'
F_SUFFIX = re.compile(r'(\d*\.\d+|\d+\.\d*)[fF](?![A-Za-z0-9_])')


def c_str(s):
	return s.encode().decode('unicode_escape')


if port == 'ios':
	body = m[m.index('+ (NSString *)downportShaderSource'):]
	body = body[:body.index('\n}\n')]
	hdr = re.search(r'NSString \*header = fragment\s*\?\s*@' + LIT + r'\s*:\s*@' + LIT + ';', body)
	if not hdr:
		sys.exit('!! could not find the downport header in ' + str(bridge))
	HDR_FRAG, HDR_VERT = c_str(hdr.group(1)), c_str(hdr.group(2))
	vblock = body[body.index('NSDictionary<NSString *, NSString *> *casts = @{'):]
	vblock = vblock[:vblock.index('};')]
	VCASTS = [(c_str(a), c_str(b)) for a, b in re.findall('@' + LIT + r'\s*:\s*@' + LIT, vblock)]
	rblock = body[body.index('NSArray<NSArray<NSString *> *> *rlhdCasts = @['):]
	rblock = rblock[:rblock.index('\n\t];')]
	RCASTS = [(c_str(a), c_str(b)) for a, b in re.findall(r'@\[@' + LIT + r',\s*@' + LIT + r'\]', rblock)]
	if not VCASTS or not RCASTS:
		sys.exit(f'!! parsed {len(VCASTS)} vertex casts and {len(RCASTS)} rlhdCasts pairs from {bridge}; expected both > 0')
else:
	cblock = m[m.index('static const char *kSceneVertexCasts[][2] = {'):]
	cblock = cblock[:cblock.index('\n};')]
	ACASTS = [(c_str(a), c_str(b)) for a, b in re.findall(r'\{' + LIT + r',\s*' + LIT + r'\}', cblock)]
	body = m[m.index('static std::string rc_downport('):]
	body = body[:body.index('\n}\n')]
	if 'body.find("samplerBuffer")' not in body:
		sys.exit('!! rc_downport no longer gates the buffer-sampler precision on "samplerBuffer"; update downport_check.py')
	base = body[body.index('std::string precision ='):]
	PREC = ''.join(c_str(x) for x in re.findall(LIT, base[:base.index(';\n')]))
	extra = body[body.index('precision +='):]
	PREC_BUF = ''.join(c_str(x) for x in re.findall(LIT, extra[:extra.index(';\n')]))
	ES_VER = os.environ.get('ES_VER', '320 es')
	if not ACASTS or 'precision highp float;' not in PREC or not PREC_BUF:
		sys.exit(f'!! parsed {len(ACASTS)} casts, precision {PREC!r}, buffer precision {PREC_BUF!r} from {bridge}')


def downport_ios(s, fragment):
	s = re.sub(r'(?m)^[ \t]*#version[^\n]*\n?', '', s)
	s = (HDR_FRAG if fragment else HDR_VERT) + s
	s = re.sub(r'\bnoperspective\s+', '', s)
	s = F_SUFFIX.sub(r'\1', s)
	if not fragment:
		for a, b in VCASTS:
			s = s.replace(a, b)
	if 'textureFaces' in s:
		s = re.sub(r'uniform\s+isamplerBuffer\s+textureFaces\s*;', 'uniform highp isampler2D textureFaces;\n#define RC_TEXTURE_FACES 1', s)
		i = s.find('void main()')
		if i >= 0:
			j = s.find('{', i)
			if j >= 0:
				s = s[:j + 1] + '\n#ifdef RC_TEXTURE_FACES\n  int tfW = textureSize(textureFaces, 0).x;\n#endif' + s[j + 1:]
		s = re.sub(r'texelFetch\(\s*textureFaces\s*,\s*([^(),]+?)\s*\)', r'texelFetch(textureFaces, ivec2((\1)%tfW,(\1)/tfW), 0)', s)
	for a, b in RCASTS:
		s = s.replace(a, b)
	return s


def downport_android(s):
	v = s.find('#version')
	s = s[v:] if v >= 0 else s
	nl = s.find('\n')
	s = s[nl + 1:] if nl >= 0 else ''
	s = re.sub(r'#line[^\n]*\n', '', s)
	s = re.sub(r'\bnoperspective\b', '', s)
	s = F_SUFFIX.sub(r'\1', s)
	for a, b in ACASTS:
		s = s.replace(a, b)
	return '#version ' + ES_VER + '\n' + PREC + (PREC_BUF if 'samplerBuffer' in s else '') + s


ES31_PLUS = re.compile(r'\b(textureGather\w*|bitfield\w+|findLSB|findMSB|bitCount|uaddCarry|usubBorrow|umulExtended|'
	r'imulExtended|frexp|ldexp|pack[SU]norm4x8|unpack[SU]norm4x8|image(?:Load|Store|Size|Atomic\w*)|memoryBarrier\w*|'
	r'atomic\w+|gl_HelperInvocation|interpolateAt\w+|fma|precise|textureQueryLevels|gl_Sample\w+|gl_PrimitiveID|'
	r'gl_Layer|[iu]?samplerBuffer|[iu]?sampler2DMS\w*|readonly|writeonly|coherent|barrier)\b')
SAMPLER_DECL = re.compile(r'\buniform\s+(?:(lowp|mediump|highp)\s+)?([iu]?sampler\w+)\s+\w+')

out_dir.mkdir(parents=True, exist_ok=True)
stages = sorted(src_dir.glob('*.glsl'))
if not stages:
	sys.exit('!! no assembled stages in ' + str(src_dir))
total = 0
uniq = {}   # (message, source line text) -> [stage:line, ...]
for f in stages:
	frag = '.frag.' in f.name
	stage = 'frag' if frag else 'vert'
	if port == 'ios':
		d = downport_ios(f.read_text(), frag).replace('#version 300 es', '#version 320 es', 1)
	else:
		d = downport_android(f.read_text())
	# #line directives make glslang report include-relative lines; drop them so errors point into chk-*.glsl
	text = re.sub(r'(?m)^#line [^\n]*$', '', d)
	chk = out_dir / ('chk-' + f.name)
	chk.write_text(text)
	lines = text.split('\n')
	r = subprocess.run(['glslangValidator', '-C', '-S', stage, str(chk)], capture_output=True, text=True)
	errs = []
	for l in r.stdout.splitlines():
		mm = re.match(r'ERROR: \S+?:(\d+): (.*)', l)
		if mm:
			n = int(mm.group(1))
			errs.append((re.sub(r' \(or there is no acceptable conversion\)', '', mm.group(2)),
				lines[n - 1].strip() if 0 < n <= len(lines) else '?', n))
	if r.returncode != 0 and not errs:
		errs.append(('glslangValidator exited ' + str(r.returncode) + ' with no located error', (r.stdout + r.stderr).strip()[-200:], 0))
	pre = subprocess.run(['glslangValidator', '-E', '-S', stage, str(chk)], capture_output=True, text=True).stdout
	declared = set(re.findall(r'(?m)^precision\s+\w+\s+(\w+)\s*;', pre))
	for ln in pre.splitlines():
		if port == 'ios':
			for tok in sorted(set(ES31_PLUS.findall(ln))):
				errs.append((f"'{tok}' is ES 3.1+; Apple's ES 3.0 driver rejects it", ln.strip(), 0))
		for prec, typ in SAMPLER_DECL.findall(ln):
			if not prec and typ not in declared:
				errs.append((f"'{typ}' has no precision (header or declaration); ES makes it lowp or rejects it", ln.strip(), 0))
	total += len(errs)
	if errs:
		print(f'=== {f.name}: {len(errs)} error(s)')
	for msg, src, n in errs:
		uniq.setdefault((msg, src), []).append(f'{f.name}:{n}')
print(f'TOTAL {total}; {len(uniq)} distinct; {len(stages)} stages')
for (msg, src), where in uniq.items():
	print(f'\n* {src}\n    {msg}\n    in {len(where)}: {", ".join(where[:4])}{" ..." if len(where) > 4 else ""}')
sys.exit(1 if total else 0)
