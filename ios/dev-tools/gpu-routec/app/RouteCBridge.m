/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import "RouteCBridge.h"
#import <OpenGLES/EAGL.h>
#import <OpenGLES/ES3/glext.h>
#import <QuartzCore/QuartzCore.h>   // CAEAGLLayer (direct present)

@implementation RouteCBridge

+ (NSString *)waterDiagMode
{
	static NSString *mode = nil; static BOOL read = NO;
	if (!read)
	{
		read = YES;
		NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
		NSString *raw = [NSString stringWithContentsOfFile:[docs stringByAppendingPathComponent:@"routec-water-mode.txt"]
			encoding:NSUTF8StringEncoding error:nil];
		mode = [[raw stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] copy];
	}
	return mode;
}

+ (BOOL)waterFixActive
{
	NSString *m = [self waterDiagMode];
	return !([m isEqualToString:@"stripes"] || [m isEqualToString:@"baseline"]
		|| [m isEqualToString:@"solidtex"] || [m isEqualToString:@"depthalways"]
		|| [m isEqualToString:@"fragdepth"]);
}

+ (BOOL)depthFixActive
{
	// gl_FragDepth reverse-Z fix is GATED TO THE "fragdepth" A/B ONLY — NOT in the shipping fix. The device
	// depth probe showed the scene buffer is genuine DEPTH_COMPONENT32F, so far z-fighting is NOT buffer-
	// precision-limited; the gl_FragDepth remap didn't help (and looked worse). The distant streaking is
	// sub-pixel aliasing of thin far geometry, which MSAA (anti-aliasing) is the right lever for, not depth.
	return [[self waterDiagMode] isEqualToString:@"fragdepth"];
}

+ (BOOL)superSampleActive
{
	return [[self waterDiagMode] isEqualToString:@"fix2x"];
}

+ (float)alphaOffsetUnits
{
	NSString *m = [self waterDiagMode];
	if ([m isEqualToString:@"offsetalpha8"])
	{
		return 8.0f;
	}
	if ([m isEqualToString:@"offsetalpha128"])
	{
		return 128.0f;
	}
	if ([m isEqualToString:@"offsetalpha-32"])
	{
		return -32.0f;
	}
	if ([m isEqualToString:@"offsetalpha"] || [m isEqualToString:@"offsetalpha32"])
	{
		return 32.0f;
	}
	return 0.0f;
}

static EAGLContext *sCtx = nil;
static GLuint sFbo = 0, sColorRbo = 0, sDepthRbo = 0;
static int sFboW = 0, sFboH = 0;
static uint32_t *sReadback = NULL;   // reusable glReadPixels scratch (sized to sFboW*sFboH)

// Direct present: when set, the default-FBO color attachment is this layer's drawable and swapBuffers presents it
// (no readback). nil = the offscreen-RBO + glReadPixels path (L1 probe / CPU fallback).
static CAEAGLLayer *sPresentLayer = nil;

// Build (or rebuild) the offscreen FBO at w*h on the current context. COLOR (RGBA8) + DEPTH24 — the plugin
// renders the scene with depth testing, so the AWTContext analogue must supply a depth attachment like
// librlawt's window FBO. This FBO is what the unmodified plugin sees as "framebuffer 0" via getFramebuffer(false).
static BOOL rcBuildFbo(int w, int h)
{
	if (sDepthRbo) { glDeleteRenderbuffers(1, &sDepthRbo); sDepthRbo = 0; }
	if (sColorRbo) { glDeleteRenderbuffers(1, &sColorRbo); sColorRbo = 0; }
	if (sFbo)      { glDeleteFramebuffers(1, &sFbo); sFbo = 0; }

	glGenFramebuffers(1, &sFbo);
	glBindFramebuffer(GL_FRAMEBUFFER, sFbo);

	glGenRenderbuffers(1, &sColorRbo);
	glBindRenderbuffer(GL_RENDERBUFFER, sColorRbo);
	if (sPresentLayer)
	{
		// Direct present: the color RBO IS the CAEAGLLayer drawable. presentRenderbuffer scans it out -> no readback.
		[sCtx renderbufferStorage:GL_RENDERBUFFER fromDrawable:sPresentLayer];
		GLint dw = 0, dh = 0;
		glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH, &dw);
		glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT, &dh);
		if (dw > 0 && dh > 0) { w = dw; h = dh; }   // the drawable's actual pixel size is authoritative
	}
	else
	{
		glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
	}
	glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, sColorRbo);

	glGenRenderbuffers(1, &sDepthRbo);
	glBindRenderbuffer(GL_RENDERBUFFER, sDepthRbo);
	glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
	glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sDepthRbo);

	GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	glViewport(0, 0, w, h);
	sFboW = w; sFboH = h;
	free(sReadback); sReadback = NULL;   // re-alloc lazily at the new size in readbackInto:
	return status == GL_FRAMEBUFFER_COMPLETE;
}

+ (BOOL)awtCreateGLContextWidth:(int)w height:(int)h
{
	sCtx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
	if (!sCtx)
	{
		return NO;
	}
	[EAGLContext setCurrentContext:sCtx];
	return rcBuildFbo(w, h);
}

+ (GLuint)awtGetFramebuffer
{
	return sFbo; // the plugin's "framebuffer 0"
}

+ (void)makeCurrent
{
	// Thread-affine EAGL context: re-bind on the calling (client/draw) thread each frame. A no-op when already
	// current (the expected same-thread case), but removes the "draw runs off the createGLContext thread" risk.
	if (sCtx && [EAGLContext currentContext] != sCtx)
	{
		[EAGLContext setCurrentContext:sCtx];
	}
}

+ (int)fbWidth  { return sFboW; }
+ (int)fbHeight { return sFboH; }

+ (void)ensureSizeWidth:(int)w height:(int)h
{
	// When presenting, the drawable size is fixed by the CAEAGLLayer (set once on main); don't rebuild it from the
	// engine canvas (the login screen is briefly smaller than the in-world canvas — that's a cosmetic corner only).
	if (sPresentLayer) return;
	if (w > 0 && h > 0 && (w != sFboW || h != sFboH) && sCtx)
	{
		[EAGLContext setCurrentContext:sCtx];
		rcBuildFbo(w, h);
	}
}

+ (void)setPresentLayer:(id)caEaglLayer
{
	sPresentLayer = (CAEAGLLayer *)caEaglLayer;
}

+ (BOOL)isPresenting
{
	return sPresentLayer != nil;
}

+ (void)present
{
	if (!sCtx || !sColorRbo)
	{
		return;
	}
	glBindRenderbuffer(GL_RENDERBUFFER, sColorRbo);
	[sCtx presentRenderbuffer:GL_RENDERBUFFER];
}

+ (void)readbackInto:(uint32_t *)dst width:(int)w height:(int)h
{
	if (!dst || w <= 0 || h <= 0)
	{
		return;
	}
	const size_t n = (size_t)w * (size_t)h;
	if (!sReadback)
	{
		sReadback = (uint32_t *)malloc(n * sizeof(uint32_t));
	}
	if (!sReadback)
	{
		return;
	}
	// GL_RGBA is the only ES3-core readback format. Each little-endian word is 0xAABBGGRR.
	glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, sReadback);
	// One pass: vertical flip (GL bottom-left -> display top-left) + R<->B swizzle -> packed 0x00RRGGBB.
	for (int y = 0; y < h; y++)
	{
		const uint32_t *srow = sReadback + (size_t)(h - 1 - y) * w;
		uint32_t *drow = dst + (size_t)y * w;
		for (int x = 0; x < w; x++)
		{
			uint32_t p = srow[x];
			uint32_t r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
			drow[x] = (r << 16) | (g << 8) | b;
		}
	}
}

+ (void)awtDestroy
{
	if (sDepthRbo) glDeleteRenderbuffers(1, &sDepthRbo), sDepthRbo = 0;
	if (sColorRbo) glDeleteRenderbuffers(1, &sColorRbo), sColorRbo = 0;
	if (sFbo) glDeleteFramebuffers(1, &sFbo), sFbo = 0;
	free(sReadback); sReadback = NULL;
	sFboW = sFboH = 0;
	sPresentLayer = nil;
	[EAGLContext setCurrentContext:nil];
	sCtx = nil;
}

// ---- shims ----

+ (void)glClearDepthShim:(double)depth
{
	// desktop glClearDepth takes a double; ES exports glClearDepthf (float). Same semantics.
	glClearDepthf((float) depth);
}

+ (void)glMultiDrawArraysShim:(GLenum)mode first:(const GLint *)first count:(const GLsizei *)count drawcount:(GLsizei)n
{
	// ES 3.0 has no glMultiDrawArrays — loop glDrawArrays over the same (first[], count[]) arrays. Functionally
	// identical: glMultiDrawArrays is defined as exactly this loop with no inter-draw state change.
	for (GLsizei i = 0; i < n; i++)
	{
		if (count[i] > 0)
		{
			glDrawArrays(mode, first[i], count[i]);
		}
	}
}

+ (void)glClipControlShim:(GLenum)origin depth:(GLenum)depth
{
	// No glClipControl on GLES. The plugin pairs glClipControl(LOWER_LEFT, ZERO_TO_ONE) with a reverse-Z
	// projection (clip.z = 2*near constant) + glClearDepth(0) + glDepthFunc(GL_GREATER/GEQUAL). On GLES the NDC-z
	// range is fixed [-1,1]; the SAME reverse-Z ordering is preserved by the projection + depth state (only depth
	// PRECISION differs, not ordering). So this shim is intentionally a no-op — correctness lives in the matrix +
	// depth func, which this probe verifies.
	(void) origin; (void) depth;
}

+ (void)glDebugMessageControlShim
{
	// KHR_debug is not core on iOS GLES — no-op (debug output only).
}

+ (void *)glMapBufferShim:(GLenum)target access:(GLenum)access size:(GLsizeiptr)size
{
	// ES 3.0 has glMapBufferRange (not glMapBuffer). Map the whole [0,size) range. The plugin maps PBOs/VBOs
	// WRITE_ONLY for streaming; GL_MAP_WRITE_BIT + invalidate matches GL_WRITE_ONLY semantics.
	(void) access;
	return glMapBufferRange(target, 0, size, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
}

// ---- shader downport (Route C 2E) — desktop GLSL 330 -> GLSL ES 3.00 (the 5 B1 edits, proven to compile+link
// +render on GLES 3.00). Applied at glShaderSource time on the unmodified base `gpu` plugin's shaders. ----
+ (NSString *)downportShaderSource:(NSString *)src fragment:(BOOL)fragment
{
	if (!src)
	{
		return src;
	}
	NSMutableString *s = [src mutableCopy];
	NSError *err = nil;

	// 1) version: `#version 330[ core]` -> `#version 300 es`.
	NSRegularExpression *ver = [NSRegularExpression regularExpressionWithPattern:@"#version\\s+330(\\s+core)?"
		options:0 error:&err];
	[ver replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@"#version 300 es"];

	// 2) inject precision qualifiers right after the #version line (ES fragment shaders have NO default float/int
	//    precision; they must be declared before first use).
	NSString *prec = fragment
		? @"\nprecision highp float;\nprecision highp int;\nprecision highp sampler2DArray;\nprecision highp sampler2D;\n"
		: @"\nprecision highp float;\nprecision highp int;\n";
	NSRange verLine = [s rangeOfString:@"#version 300 es"];
	if (verLine.location != NSNotFound)
	{
		[s insertString:prec atIndex:verLine.location + verLine.length];
	}

	// 3) drop `noperspective` (not in ES).
	NSRegularExpression *nop = [NSRegularExpression regularExpressionWithPattern:@"\\bnoperspective\\s+"
		options:0 error:&err];
	[nop replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@""];

	// 4) strip float-literal `f`/`F` suffix (requires a decimal point so ints/hex are untouched).
	NSRegularExpression *fsuf = [NSRegularExpression regularExpressionWithPattern:@"(\\d*\\.\\d+|\\d+\\.\\d*)[fF]"
		options:0 error:&err];
	[fsuf replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@"$1"];

	// 5) explicit int->float / ivec->vec casts (vertex only). GLSL ES 3.00 does NO implicit numeric conversion;
	//    the base scene vertex shader mixes integer engine uniforms/attributes with floats at these exact sites.
	if (!fragment)
	{
		NSDictionary<NSString *, NSString *> *casts = @{
			@"vec4(vertf + base, 1)": @"vec4(vertf + vec3(base), 1)",
			@"hsl += ((entityTint.xyz - hsl) * entityTint.w) / 128;":
				@"hsl += ((vec3(entityTint.xyz) - hsl) * float(entityTint.w)) / 128.0;",
			@"((-expandedMapLoadingChunks * 8 + 1) * TILE_SIZE)":
				@"(float(-expandedMapLoadingChunks * 8 + 1) * TILE_SIZE)",
			@"((104 + expandedMapLoadingChunks * 8 - 1) * TILE_SIZE)":
				@"(float(104 + expandedMapLoadingChunks * 8 - 1) * TILE_SIZE)",
			@"cameraX - drawDistance": @"cameraX - float(drawDistance)",
			@"cameraX + drawDistance": @"cameraX + float(drawDistance)",
			@"cameraZ - drawDistance": @"cameraZ - float(drawDistance)",
			@"cameraZ + drawDistance": @"cameraZ + float(drawDistance)",
			@"fogDepth * TILE_SIZE) * useFog": @"float(fogDepth) * TILE_SIZE) * float(useFog)",
		};
		for (NSString *from in casts)
		{
			[s replaceOccurrencesOfString:from withString:casts[from]
				options:0 range:NSMakeRange(0, s.length)];
		}
	}

	// WATER-SHIMMER ISOLATION DIAGNOSTIC (gated; STRIP once the cause is found). When the file
	// Documents/routec-water-mode.txt contains "stripes", split the SCENE fragment into 4 vertical 88px
	// bands by gl_FragCoord.x so ONE device frame A/Bs the top suspects against a control — texture() is
	// kept in uniform control flow (no GLSL-ES undefined-derivative confound; only the colour SOURCE/branch
	// is selected). Bands, left edge first: 0=baseline  1=force LOD0 colour (mips off)  2=no discard
	// 3=no fog. Absent / != "stripes" -> NO edits, so the default build stays byte-identical to baseline.
	if (fragment && [s containsString:@"fFogAmount"])   // scene fragment only (the UI fragment has no fFogAmount)
	{
		if ([[RouteCBridge waterDiagMode] isEqualToString:@"stripes"])
		{
			// rcBand declared at the head of main() (just before the first statement `vec4 c;`).
			[s replaceOccurrencesOfString:@"vec4 c;"
				withString:@"int rcBand = int(mod(floor(gl_FragCoord.x / 88.0), 4.0));\n  vec4 c;"
				options:0 range:NSMakeRange(0, s.length)];
			// band 1: colour from LOD0 (textureColor0 is already sampled in uniform flow) instead of the mip chain.
			[s replaceOccurrencesOfString:@"textureColor = vec4(textureColor.rgb, 1.);"
				withString:@"textureColor = vec4((rcBand == 1 ? textureColor0.rgb : textureColor.rgb), 1.);"
				options:0 range:NSMakeRange(0, s.length)];
			// band 2: keep the alpha test everywhere except band 2 (single non-overlapping replace; not re-scanned).
			[s replaceOccurrencesOfString:@"discard;"
				withString:@"{ if (rcBand != 2) discard; }"
				options:0 range:NSMakeRange(0, s.length)];
			// band 3: skip the fog mix (fog is already ~off at fogDepth=0; this is the control for a NaN-fog path).
			[s replaceOccurrencesOfString:@"vec3 mixedColor = mix(c.rgb, fogColor.rgb, fFogAmount);"
				withString:@"vec3 mixedColor = (rcBand == 3 ? c.rgb : mix(c.rgb, fogColor.rgb, fFogAmount));"
				options:0 range:NSMakeRange(0, s.length)];
		}
		// "solidtex": ignore the texture COLOUR sample entirely on textured faces (keep discard + lighting).
		// If the grazing-river flicker VANISHES -> it is the texture colour sampling; if it REMAINS -> the
		// texture path is fully exonerated (the frame-to-frame change is camera micro-motion on a depth/
		// geometry artifact, not the animated UVs). STRIP after diagnosis.
		else if ([[RouteCBridge waterDiagMode] isEqualToString:@"solidtex"])
		{
			[s replaceOccurrencesOfString:@"textureColor = vec4(textureColor.rgb, 1.);"
				withString:@"textureColor = vec4(0.20, 0.42, 0.66, 1.);"
				options:0 range:NSMakeRange(0, s.length)];
		}
		else if ([RouteCBridge waterFixActive])
		{
			// WATER-SHIMMER FIX (shader half). The scene colour sample uses implicit-LOD texture(), whose
			// derivatives are corrupted by the scrolling-alpha discard later in main() -> the mip LOD flickers
			// at the water's alpha/geometry edges (the thin shoreline shimmer the trilinear GL-state fix alone
			// can't remove). Sample with textureGrad + explicit gradients computed before the discard so the LOD
			// is stable. Only the COLOUR sample changes; the discard's textureLod(...,0) is left as-is.
			[s replaceOccurrencesOfString:@"texture(textures, vec3(fUv, float(textureIdx)))"
				withString:@"textureGrad(textures, vec3(fUv, float(textureIdx)), dFdx(fUv), dFdy(fUv))"
				options:0 range:NSMakeRange(0, s.length)];
		}
		// DEPTH-PRECISION FIX (fragment half) — applies for the shipping fix AND the "fragdepth" A/B.
		// Long-distance terrain z-fights because iOS GLES has no glClipControl, so the plugin's reverse-Z
		// (clip.z = 2*near constant, NDC.z = 100/viewZ) is squeezed into window-z [0.5,~0.55] at distance =
		// almost no precision. Write the depth directly: gl_FragDepth = clip.z/clip.w, putting the far plane at
		// window-z 0 (full 32F precision, like desktop's glClipControl). clip.z is carried as a FLAT varying
		// (it is ~constant per face — the per-vertex bias is a coplanar hack), so clip.z*gl_FragCoord.w equals
		// the screen-linear depth at ALL angles (the prior gl_FragDepth attempt used a perspective varying ->
		// wrong at grazing). Depth func stays GL_GREATER + clearDepth 0 (near=1, far=0). Base SOURCE unchanged.
		if ([RouteCBridge depthFixActive])
		{
			[s replaceOccurrencesOfString:@"in float fFogAmount;"
				withString:@"in float fFogAmount;\nflat in float vClipDepth;"
				options:0 range:NSMakeRange(0, s.length)];
			[s replaceOccurrencesOfString:@"FragColor = vec4(mixedColor, c.a);"
				withString:@"FragColor = vec4(mixedColor, c.a);\n  gl_FragDepth = vClipDepth * gl_FragCoord.w;"
				options:0 range:NSMakeRange(0, s.length)];
		}
	}
	// DEPTH-PRECISION FIX (scene-vertex half): carry clip.z (= screenPos.z, the reverse-Z 2*near + bias) to
	// the fragment as a flat varying so it can write gl_FragDepth. Scene vertex only (has gl_Position=screenPos).
	if (!fragment && [s containsString:@"gl_Position = screenPos;"] && [RouteCBridge depthFixActive])
	{
		[s replaceOccurrencesOfString:@"out float fFogAmount;"
			withString:@"out float fFogAmount;\nflat out float vClipDepth;"
			options:0 range:NSMakeRange(0, s.length)];
		[s replaceOccurrencesOfString:@"gl_Position = screenPos;"
			withString:@"gl_Position = screenPos;\n  vClipDepth = screenPos.z;"
			options:0 range:NSMakeRange(0, s.length)];
	}

	// WHITE-FLASH DIAGNOSTIC (one-shot per shader): dump the INPUT (330) + OUTPUT (ES 3.00) shader to
	// Documents/routec-shaders/ so a corrupted scene-shader downport (the top Route-C-specific suspect) is
	// visible off-device. Small text files; pull with devicectl. STRIP after the white-flash bug is fixed.
	{
		static int n = 0;
		NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
		NSString *dir = [docs stringByAppendingPathComponent:@"routec-shaders"];
		[[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
		NSString *tag = fragment ? @"frag" : @"vert";
		NSString *base = [NSString stringWithFormat:@"shader-%02d-%@", n++, tag];
		[src writeToFile:[dir stringByAppendingPathComponent:[base stringByAppendingString:@".in.glsl"]]
			atomically:YES encoding:NSUTF8StringEncoding error:nil];
		[s writeToFile:[dir stringByAppendingPathComponent:[base stringByAppendingString:@".out.glsl"]]
			atomically:YES encoding:NSUTF8StringEncoding error:nil];
	}

	return [s copy];
}

@end
