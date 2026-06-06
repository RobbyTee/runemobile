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

	// 6) WATER/GRASS Z-FIGHTING FIX — true reverse-Z depth in the SCENE shaders. iOS GLES has no glClipControl, so
	//    the plugin's reverse-Z projection (NDC.z = clip.z/clip.w, near=1 far=0) only fills window depth [0.5,1] via
	//    the fixed (z+1)/2 transform, losing the float-near-0 precision reverse-Z relies on -> z-fighting on far flat
	//    surfaces (water/ground). Write the depth directly: pass clip.z (with the per-vertex bias) as a varying and
	//    set gl_FragDepth = clip.z * gl_FragCoord.w (= clip.z/clip.w = NDC.z) at full float precision. (Disables
	//    early-z, but the GPU has huge headroom here.) Scene vertex detected by "gl_Position = screenPos;", scene
	//    fragment by "fTextureId" (the UI shaders have neither, so they're untouched).
	if (!fragment && [s rangeOfString:@"gl_Position = screenPos;"].location != NSNotFound)
	{
		[s replaceOccurrencesOfString:@"void main() {"
			withString:@"out highp float vClipZ;\nvoid main() {"
			options:0 range:NSMakeRange(0, s.length)];
		[s replaceOccurrencesOfString:@"gl_Position = screenPos;"
			withString:@"gl_Position = screenPos;\n  vClipZ = screenPos.z;"
			options:0 range:NSMakeRange(0, s.length)];
	}
	else if (fragment && [s rangeOfString:@"fTextureId"].location != NSNotFound)
	{
		[s replaceOccurrencesOfString:@"void main() {"
			withString:@"in highp float vClipZ;\nvoid main() {\n  gl_FragDepth = vClipZ * gl_FragCoord.w;"
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
