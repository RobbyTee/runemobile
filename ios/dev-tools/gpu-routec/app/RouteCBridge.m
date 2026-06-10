/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import "RouteCBridge.h"
#import "routec_gl_compat.h"   // selects EAGL vs MetalANGLE/EGL at build time (RC_BACKEND_ANGLE) + the GL/EGL headers

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
	// gl_FragDepth reverse-Z depth-precision fix — the LOCKED iOS depth path (always on). iOS GLES has no
	// glClipControl, so the plugin's reverse-Z is squeezed into window-z [0.5,~0.55] at distance; writing
	// gl_FragDepth = clip.z/clip.w restores full 32F far-plane precision (gpu-depthsim md5-GREEN: 64x finer
	// far-plane depth, ~15x less z-fight, coplanar bias preserved). Device EAGL-vs-ANGLE A/B (2026-06-09,
	// Lumbridge river): this write's early-Z cost is 1.31x avg / 1.32x p95 GPU time — under the 1.5x that
	// would have forced ANGLE, whose clip_control path drags back the near-plane wedge bug and adds nothing
	// structural for 117HD (still ES 3.0). Verdict: EAGL + gl_FragDepth is the iOS renderer. (The "depthsplit"
	// single-frame band A/B still works — its shader-gen branch keys off waterDiagMode directly.)
	return YES;
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

// ---- shared state (backend-agnostic) ----
static int sFboW = 0, sFboH = 0;
static uint32_t *sReadback = NULL;   // reusable glReadPixels scratch (sized to sFboW*sFboH), offscreen mode only

// On-screen present layer (set on main before the GPU launch). nil/NULL => the offscreen-FBO + glReadPixels path
// (the safe Phase A1 default + L1/CPU fallback). Non-nil => direct present (EAGL: CAEAGLLayer; ANGLE: CAMetalLayer
// EGL window surface). Stored opaquely so this TU needs no QuartzCore import in the ANGLE build.
static void *sPresentLayer = NULL;

// Count of frames actually presented to the screen (eglSwapBuffers / presentRenderbuffer). Lets the UI switch from
// the CPU-frame blit to direct-present LAZILY — only once the GPU plugin has presented a real frame — so a parked/
// slow startup (or the boot/login phase before the plugin runs) still shows the CPU frames instead of black.
static long sPresentedFrames = 0;

#if defined(RC_BACKEND_ANGLE)
// ================================================================================================================
// MetalANGLE / EGL backend (Phase A1). EGL display+context+surface over ANGLE-on-Metal. Two present modes:
//   - OFFSCREEN (no present layer): a pbuffer surface makes the context current; the plugin renders into sFbo
//     (RGBA8 + DEPTH24), which getFramebuffer returns as "framebuffer 0"; swapBuffers reads it back (glReadPixels).
//   - WINDOW    (present layer set): an EGL window surface from the CAMetalLayer; getFramebuffer returns 0 (the
//     window default framebuffer IS the drawable, no custom FBO); swapBuffers = eglSwapBuffers (no readback).
// EXT_clip_control is wired for real in the clip-control shim -> reverse-Z at full depth precision (the z-fight fix).
// ================================================================================================================
static EGLDisplay sDpy  = EGL_NO_DISPLAY;
static EGLContext sCtx  = EGL_NO_CONTEXT;
static EGLSurface sSurf = EGL_NO_SURFACE;
static EGLConfig  sCfg  = NULL;
static GLuint sFbo = 0, sColorRbo = 0, sDepthRbo = 0;   // offscreen mode only
static BOOL sWindowMode = NO;                            // YES once a window surface is created

// Display + config + surface. Split from context creation so the WINDOW surface can be made on the MAIN thread
// (where the CAMetalLayer lives + where the de-risk probe proved it works), while the context is created + made
// current on the render (JVM) thread. `window`=YES uses sPresentLayer (a CAMetalLayer); NO makes a pbuffer.
static BOOL rcEglPrepareSurface(BOOL window, int w, int h)
{
	if (sDpy == EGL_NO_DISPLAY)
	{
		PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
			(PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
		if (getPlatformDisplay)
		{
			EGLint da[] = { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE };
			sDpy = getPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, (void *) EGL_DEFAULT_DISPLAY, da);
		}
		if (sDpy == EGL_NO_DISPLAY) sDpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		if (sDpy == EGL_NO_DISPLAY) return NO;
		EGLint vmaj = 0, vmin = 0;
		if (!eglInitialize(sDpy, &vmaj, &vmin)) return NO;
	}
	sWindowMode = window;
	EGLint cfgAttr[] = {
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
		EGL_SURFACE_TYPE, window ? EGL_WINDOW_BIT : EGL_PBUFFER_BIT,
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_NONE };
	EGLint nc = 0;
	if (!eglChooseConfig(sDpy, cfgAttr, &sCfg, 1, &nc) || nc < 1) return NO;

	if (window)
	{
		if (!sPresentLayer) return NO;
		sSurf = eglCreateWindowSurface(sDpy, sCfg, (EGLNativeWindowType) sPresentLayer, NULL);
	}
	else
	{
		EGLint pb[] = { EGL_WIDTH, (w > 0 ? w : 16), EGL_HEIGHT, (h > 0 ? h : 16), EGL_NONE };
		sSurf = eglCreatePbufferSurface(sDpy, sCfg, pb);
	}
	return sSurf != EGL_NO_SURFACE;
}

// Create the ES3 context + make it current — on the RENDER (JVM) thread (eglBindAPI is per-thread; the context
// becomes current on the thread that will issue the GL calls).
static BOOL rcEglCreateContextCurrent(void)
{
	eglBindAPI(EGL_OPENGL_ES_API);
	EGLint ctxAttr[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
	sCtx = eglCreateContext(sDpy, sCfg, EGL_NO_CONTEXT, ctxAttr);
	if (sCtx == EGL_NO_CONTEXT) return NO;
	return eglMakeCurrent(sDpy, sSurf, sSurf, sCtx) ? YES : NO;
}

// Called on the MAIN thread (from ViewController, before the JVM render thread starts) when a CAMetalLayer present
// layer is set: bring up the display + window surface NOW so eglCreateWindowSurface runs on main (the proven path).
// awtCreateGLContext (render thread) then only creates + binds the context. Returns NO -> caller falls back to the
// offscreen readback path. Safe to call once.
+ (BOOL)prepareWindowSurfaceOnMain
{
	if (!sPresentLayer) return NO;
	if (sSurf != EGL_NO_SURFACE) return YES;   // already prepared
	return rcEglPrepareSurface(YES, 0, 0);
}

// Offscreen FBO (RGBA8 COLOR + DEPTH24) — the plugin renders the scene with depth testing, so the AWTContext
// analogue supplies a depth attachment like librlawt's window FBO. Returned to the plugin as "framebuffer 0".
// Window mode has no custom FBO (the window default framebuffer is the drawable).
static BOOL rcBuildFbo(int w, int h)
{
	if (sWindowMode)
	{
		EGLint qw = 0, qh = 0;
		eglQuerySurface(sDpy, sSurf, EGL_WIDTH, &qw); eglQuerySurface(sDpy, sSurf, EGL_HEIGHT, &qh);
		if (qw > 0 && qh > 0) { w = qw; h = qh; }
		sFboW = w; sFboH = h;
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glViewport(0, 0, w, h);
		return YES;
	}
	if (sDepthRbo) { glDeleteRenderbuffers(1, &sDepthRbo); sDepthRbo = 0; }
	if (sColorRbo) { glDeleteRenderbuffers(1, &sColorRbo); sColorRbo = 0; }
	if (sFbo)      { glDeleteFramebuffers(1, &sFbo); sFbo = 0; }

	glGenFramebuffers(1, &sFbo);
	glBindFramebuffer(GL_FRAMEBUFFER, sFbo);
	glGenRenderbuffers(1, &sColorRbo);
	glBindRenderbuffer(GL_RENDERBUFFER, sColorRbo);
	glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
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
	// Window mode: the surface was already created on main (prepareWindowSurfaceOnMain). Offscreen mode: make a
	// pbuffer here on the render thread. Either way, create + bind the context on THIS (render) thread.
	if (sSurf == EGL_NO_SURFACE)
	{
		if (!rcEglPrepareSurface(NO, w, h)) { return NO; }   // offscreen pbuffer
	}
	if (!rcEglCreateContextCurrent()) { return NO; }
	BOOL ok = rcBuildFbo(w, h);
	// Force reverse-Z clip control ON. The base plugin only calls glClipControl when glCapabilities.OpenGL45 is true
	// (GpuPlugin.java:356), and our bridge reports OpenGL45=false — so without this the plugin runs with the squeezed
	// window-z [0.5,1] depth (NDC.z+1)/2, exactly like native EAGL, and the far/river depth z-fight persists. The
	// gpu-present probe proved ANGLE honours this (far window_z 0.508 -> 0.0167 = full precision). The plugin's
	// reverse-Z projection + clearDepth(0) + GL_GEQUAL are applied regardless of OpenGL45, so wiring clip_control
	// here completes the desktop-equivalent reverse-Z. This is blueprint P0.2 ("clip_control is ours to wire").
	glClipControlEXT(GL_LOWER_LEFT_EXT, GL_ZERO_TO_ONE_EXT);
	return ok;
}

+ (unsigned int)awtGetFramebuffer
{
	return sWindowMode ? 0u : sFbo;   // window mode: the EGL default framebuffer IS the drawable
}

+ (void)makeCurrent
{
	// Thread-affine context: re-bind on the calling (client/draw) thread each frame. Cheap when already current.
	if (sDpy != EGL_NO_DISPLAY && sCtx != EGL_NO_CONTEXT && eglGetCurrentContext() != sCtx)
	{
		eglMakeCurrent(sDpy, sSurf, sSurf, sCtx);
	}
}

+ (int)fbWidth  { return sFboW; }
+ (int)fbHeight { return sFboH; }

+ (void)ensureSizeWidth:(int)w height:(int)h
{
	// Window mode: the drawable size is fixed by the CAMetalLayer (set once on main); don't rebuild from the engine
	// canvas. Offscreen mode: rebuild the FBO if the engine canvas changed.
	if (sWindowMode) return;
	if (w > 0 && h > 0 && (w != sFboW || h != sFboH) && sCtx != EGL_NO_CONTEXT)
	{
		eglMakeCurrent(sDpy, sSurf, sSurf, sCtx);
		rcBuildFbo(w, h);
	}
}

+ (void)setPresentLayer:(id)layer
{
	sPresentLayer = (__bridge void *) layer;   // a CAMetalLayer (passed before createGLContext)
}

+ (BOOL)isPresenting
{
	return sWindowMode;   // window surface present (eglSwapBuffers) vs offscreen readback
}

+ (void)present
{
	if (sDpy != EGL_NO_DISPLAY && sSurf != EGL_NO_SURFACE) { eglSwapBuffers(sDpy, sSurf); sPresentedFrames++; }
}

+ (void)awtDestroy
{
	if (sDpy != EGL_NO_DISPLAY)
	{
		eglMakeCurrent(sDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		if (sDepthRbo) { glDeleteRenderbuffers(1, &sDepthRbo); sDepthRbo = 0; }
		if (sColorRbo) { glDeleteRenderbuffers(1, &sColorRbo); sColorRbo = 0; }
		if (sFbo)      { glDeleteFramebuffers(1, &sFbo); sFbo = 0; }
		if (sCtx  != EGL_NO_CONTEXT) { eglDestroyContext(sDpy, sCtx); sCtx = EGL_NO_CONTEXT; }
		if (sSurf != EGL_NO_SURFACE) { eglDestroySurface(sDpy, sSurf); sSurf = EGL_NO_SURFACE; }
		eglTerminate(sDpy);
		sDpy = EGL_NO_DISPLAY;
	}
	free(sReadback); sReadback = NULL;
	sFboW = sFboH = 0;
	sPresentLayer = NULL;
	sWindowMode = NO;
	sPresentedFrames = 0;
}

#else
// ================================================================================================================
// Native EAGL ES3 backend (prior / A-B baseline / §12 fallback).
// ================================================================================================================
static EAGLContext *sCtx = nil;
static GLuint sFbo = 0, sColorRbo = 0, sDepthRbo = 0;

// Build (or rebuild) the offscreen FBO at w*h on the current context. COLOR (RGBA8) + DEPTH24. With a present layer
// the color RBO IS the CAEAGLLayer drawable (presentRenderbuffer scans it out -> no readback).
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
		[sCtx renderbufferStorage:GL_RENDERBUFFER fromDrawable:(__bridge CAEAGLLayer *) sPresentLayer];
		GLint dw = 0, dh = 0;
		glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH, &dw);
		glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT, &dh);
		if (dw > 0 && dh > 0) { w = dw; h = dh; }
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
	free(sReadback); sReadback = NULL;
	return status == GL_FRAMEBUFFER_COMPLETE;
}

+ (BOOL)awtCreateGLContextWidth:(int)w height:(int)h
{
	sCtx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
	if (!sCtx) return NO;
	[EAGLContext setCurrentContext:sCtx];
	BOOL ok = rcBuildFbo(w, h);
	return ok;
}

+ (unsigned int)awtGetFramebuffer { return sFbo; }

+ (void)makeCurrent
{
	if (sCtx && [EAGLContext currentContext] != sCtx) [EAGLContext setCurrentContext:sCtx];
}

+ (int)fbWidth  { return sFboW; }
+ (int)fbHeight { return sFboH; }

+ (void)ensureSizeWidth:(int)w height:(int)h
{
	if (sPresentLayer) return;
	if (w > 0 && h > 0 && (w != sFboW || h != sFboH) && sCtx)
	{
		[EAGLContext setCurrentContext:sCtx];
		rcBuildFbo(w, h);
	}
}

+ (void)setPresentLayer:(id)caEaglLayer { sPresentLayer = (__bridge void *) caEaglLayer; }

+ (BOOL)prepareWindowSurfaceOnMain { return NO; }   // ANGLE-only; EAGL builds its drawable RBO in rcBuildFbo

+ (BOOL)isPresenting { return sPresentLayer != NULL; }

+ (void)present
{
	if (!sCtx || !sColorRbo) return;
	glBindRenderbuffer(GL_RENDERBUFFER, sColorRbo);
	[sCtx presentRenderbuffer:GL_RENDERBUFFER];
	sPresentedFrames++;
}

+ (void)awtDestroy
{
	if (sDepthRbo) glDeleteRenderbuffers(1, &sDepthRbo), sDepthRbo = 0;
	if (sColorRbo) glDeleteRenderbuffers(1, &sColorRbo), sColorRbo = 0;
	if (sFbo) glDeleteFramebuffers(1, &sFbo), sFbo = 0;
	free(sReadback); sReadback = NULL;
	sFboW = sFboH = 0;
	sPresentLayer = NULL;
	sPresentedFrames = 0;
	[EAGLContext setCurrentContext:nil];
	sCtx = nil;
}

#endif  /* RC_BACKEND_ANGLE */

// ---- present-state (backend-agnostic) ----
// YES once the GPU plugin has presented at least one frame to the screen. The UI uses this to switch from the
// CPU-frame blit to direct present LAZILY, so boot/login (and a parked startup) show CPU frames, not black.
+ (BOOL)hasPresentedFrame { return sPresentedFrames > 0; }

// ---- readback (offscreen present path; backend-agnostic — glReadPixels is core) ----
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
	// Read from OUR offscreen FBO explicitly. After the plugin composites + swapBuffers the READ_FRAMEBUFFER binding
	// is not guaranteed (the plugin's last bind may differ on ANGLE), and glReadPixels reads whatever READ target is
	// bound — so bind sFbo + select COLOR_ATTACHMENT0. Without this the readback can sample an empty default
	// framebuffer (black) even though the scene composited into sFbo.
	glBindFramebuffer(GL_READ_FRAMEBUFFER, sFbo);
	glReadBuffer(GL_COLOR_ATTACHMENT0);
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
#if defined(RC_BACKEND_ANGLE)
	// MetalANGLE exposes a REAL EXT_clip_control (gpu-angle probe: far window_z 0.508 -> 0.0167). The plugin pairs
	// glClipControl(LOWER_LEFT, ZERO_TO_ONE) with a reverse-Z projection + glClearDepth(0) + GL_GEQUAL; honouring it
	// maps the far plane to window_z 0 at full float precision -> fixes the distant depth z-fight at the root
	// (this is the whole point of the Phase A1 backend swap).
	glClipControlEXT(origin, depth);
#else
	// No glClipControl on native EAGL GLES. The reverse-Z ordering is preserved by the projection + depth state
	// (only depth PRECISION is squeezed, not ordering), so on EAGL this shim is intentionally a no-op.
	(void) origin; (void) depth;
#endif
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

	// 1+2) version + precision. ANGLE's GLSL-ES compiler requires `#version` to be the VERY FIRST line of the
	//    shader (nothing before it — not even a comment). The base plugin's shaders carry a ~24-line BSD license
	//    comment BEFORE `#version 330`, which native EAGL GLES tolerated but ANGLE rejects ("#version directive must
	//    occur on the first line"), cascading into a `layout` syntax error (no version -> ES 1.00). So STRIP any
	//    `#version` line wherever it sits and PREPEND `#version 300 es` + the precision qualifiers as the absolute
	//    first bytes (ES fragment shaders have no default float/int/sampler precision; declare before first use).
	//    The displaced license comment becomes a normal comment after #version, which is legal. Safe for EAGL too.
	NSRegularExpression *ver = [NSRegularExpression regularExpressionWithPattern:@"(?m)^[ \\t]*#version[^\\n]*\\n?"
		options:0 error:&err];
	[ver replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@""];
	// Precision: the full sampler set RLHD declares (usampler/isampler 2D + 2DArray + sampler2DShadow). ES 3.0
	// requires a precision qualifier for EVERY sampler type used; declaring unused ones is harmless for the base
	// plugin's shaders. (Z0 gate: dev-tools/gpu-zone/assemble/ZoneShaderAssemble.java.)
	NSString *header = fragment
		? @"#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\nprecision highp isampler2D;\nprecision highp isampler2DArray;\nprecision highp usampler2D;\nprecision highp usampler2DArray;\nprecision highp sampler2DShadow;\n"
		: @"#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\nprecision highp isampler2D;\nprecision highp isampler2DArray;\nprecision highp usampler2D;\nprecision highp usampler2DArray;\n";
	[s insertString:header atIndex:0];

	// 3) drop `noperspective` (not in ES).
	NSRegularExpression *nop = [NSRegularExpression regularExpressionWithPattern:@"\\bnoperspective\\s+"
		options:0 error:&err];
	[nop replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@""];

	// 4) strip float-literal `f`/`F` suffix (requires a decimal point so ints/hex are untouched). The trailing
	//    (?![A-Za-z0-9_]) is REQUIRED: without it, `material1.flowMap` matches `1.f` (digit-dot-<zero digits>-f) and
	//    the `f` of the identifier flowMap/flags is stripped -> `material1.lowMap` (silent corruption; RLHD's water
	//    shader hit exactly this -> "Structure has no field named lowMap"). Only strip a REAL literal suffix, i.e.
	//    f/F not followed by an identifier char. (Same fix as the Z0 assembler downport.)
	NSRegularExpression *fsuf = [NSRegularExpression regularExpressionWithPattern:@"(\\d*\\.\\d+|\\d+\\.\\d*)[fF](?![A-Za-z0-9_])"
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

	// W3 GPU Zone B0.5 — RLHD (117HD) downport SUPERSET. RLHD's assembled scene/shadow/ui shaders need their own
	// int<->float (#13) cast set + the textureFaces TBO->2D repack. These are no-ops on the base plugin's shaders
	// (distinct strings; base has no textureFaces), so the downporter stays ONE shared seam. The pervasively
	// cast-heavy includes (color_utils/water/fog) ship pre-ported as an overlay IN the rlhd jar (RLHD's own
	// #include resolves them), so they are NOT in this map. Source of truth + provenance: the Z0 gate assembler
	// dev-tools/gpu-zone/assemble/ZoneShaderAssemble.java (md5-sealed device-green).
	// 5b) textureFaces isamplerBuffer -> 2D isampler2D repack (paired with the facade glTexBuffer 2D upload).
	if ([s containsString:@"textureFaces"])
	{
		NSRegularExpression *tfDecl = [NSRegularExpression regularExpressionWithPattern:@"uniform\\s+isamplerBuffer\\s+textureFaces\\s*;" options:0 error:&err];
		[tfDecl replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@"uniform highp isampler2D textureFaces;"];
		NSRange mainR = [s rangeOfString:@"void main()"];
		if (mainR.location != NSNotFound)
		{
			NSRange brace = [s rangeOfString:@"{" options:0 range:NSMakeRange(mainR.location, s.length - mainR.location)];
			if (brace.location != NSNotFound)
				[s insertString:@"\n  int tfW = textureSize(textureFaces, 0).x;" atIndex:brace.location + 1];
		}
		NSRegularExpression *tfFetch = [NSRegularExpression regularExpressionWithPattern:@"texelFetch\\(\\s*textureFaces\\s*,\\s*([^(),]+?)\\s*\\)" options:0 error:&err];
		[tfFetch replaceMatchesInString:s options:0 range:NSMakeRange(0, s.length) withTemplate:@"texelFetch(textureFaces, ivec2(($1)%tfW,($1)/tfW), 0)"];
	}
	// 6) RLHD explicit int<->float (#13) casts — applied to BOTH stages (RLHD's cast sites span vert + frag).
	NSArray<NSArray<NSString *> *> *rlhdCasts = @[
			@[@"if (t < 1)", @"if (t < 1.0)"],
			@[@"if (t < 2)", @"if (t < 2.0)"],
			@[@"vec2 texSize = textureSize(sampler, 0);", @"vec2 texSize = vec2(textureSize(sampler, 0));"],
			@[@"uv *= sourceDimensions;", @"uv *= vec2(sourceDimensions);"],
			@[@"(1 - texelUv)", @"(vec2(1.0) - texelUv)"],
			@[@"projectionMatrix[2][3] != 0)", @"projectionMatrix[2][3] != 0.0)"],
			@[@"-IN.position.xz / (128 * scale)", @"-IN.position.xz / (128.0 * scale)"],
			@[@"clipPosition.z += depthBias / 128.0;", @"clipPosition.z += float(depthBias) / 128.0;"],
			@[@"#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0", @"#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0.0"],
			@[@"if (fogDepth == 0)", @"if (fogDepth == 0.0)"],
			@[@"(position.y + cameraPos.y) / 2,", @"(position.y + cameraPos.y) / 2.0,"],
			@[@"n.xy = n.xy * 2 - 1;", @"n.xy = n.xy * 2.0 - 1.0;"],
			@[@"waterDepth1 * IN.texBlend.x", @"float(waterDepth1) * IN.texBlend.x"],
			@[@"waterDepth2 * IN.texBlend.y", @"float(waterDepth2) * IN.texBlend.y"],
			@[@"waterDepth3 * IN.texBlend.z", @"float(waterDepth3) * IN.texBlend.z"],
			@[@"bool isUnderwater = waterDepth != 0;", @"bool isUnderwater = waterDepth != 0.0;"],
			@[@"float mipBias = 0;", @"float mipBias = 0.0;"],
			@[@"float selfShadowing = 0;", @"float selfShadowing = 0.0;"],
			@[@"float overlayMix = 0;", @"float overlayMix = 0.0;"],
			@[@"float shadow = 0;", @"float shadow = 0.0;"],
			@[@"float closeFadeDistance = 1500;", @"float closeFadeDistance = 1500.0;"],
			@[@"clamp(blendedUv.x, 0, .984375)", @"clamp(blendedUv.x, 0.0, .984375)"],
			@[@"clamp(blendedUv.y, 0, .984375)", @"clamp(blendedUv.y, 0.0, .984375)"],
			@[@"clamp(underlayBlend, 0, 1)", @"clamp(underlayBlend, 0.0, 1.0)"],
			@[@"clamp(overlayBlend, 0, 1)", @"clamp(overlayBlend, 0.0, 1.0)"],
			@[@"clamp(result * 2 - 1, 0, 1)", @"clamp(result * 2.0 - 1.0, 0.0, 1.0)"],
			@[@"clamp((1 - baseColor1.a) * 2, 0, 1)", @"clamp((1.0 - baseColor1.a) * 2.0, 0.0, 1.0)"],
			@[@"clamp((1 - baseColor2.a) * 2, 0, 1)", @"clamp((1.0 - baseColor2.a) * 2.0, 0.0, 1.0)"],
			@[@"clamp((1 - baseColor3.a) * 2, 0, 1)", @"clamp((1.0 - baseColor3.a) * 2.0, 0.0, 1.0)"],
			@[@"max(normals.y, 0)", @"max(normals.y, 0.0)"],
			@[@"clamp(outputColor.rgb, 0, 1)", @"clamp(outputColor.rgb, 0.0, 1.0)"],
			@[@"10 * waterType.duration", @"10.0 * waterType.duration"],
			@[@"animationFrame(231)", @"animationFrame(231.0)"],
			@[@"animationFrame(19)", @"animationFrame(19.0)"],
			@[@"animationFrame(37)", @"animationFrame(37.0)"],
			@[@"hsl1 += ((tint.xyz - hsl1) * tint.w) / 128;", @"hsl1 += ((vec3(tint.xyz) - hsl1) * float(tint.w)) / 128.0;"],
			@[@"hsl2 += ((tint.xyz - hsl2) * tint.w) / 128;", @"hsl2 += ((vec3(tint.xyz) - hsl2) * float(tint.w)) / 128.0;"],
			@[@"hsl3 += ((tint.xyz - hsl3) * tint.w) / 128;", @"hsl3 += ((vec3(tint.xyz) - hsl3) * float(tint.w)) / 128.0;"],
			@[@"1 - float(fAlphaBiasHsl[0] >> 24 & 0xff) / 255.", @"1.0 - float(fAlphaBiasHsl[0] >> 24 & 0xff) / 255."],
			@[@"1 - float(fAlphaBiasHsl[1] >> 24 & 0xff) / 255.", @"1.0 - float(fAlphaBiasHsl[1] >> 24 & 0xff) / 255."],
			@[@"1 - float(fAlphaBiasHsl[2] >> 24 & 0xff) / 255.", @"1.0 - float(fAlphaBiasHsl[2] >> 24 & 0xff) / 255."],
			@[@"vec3 underlayBlend = IN.texBlend * isUnderlay;", @"vec3 underlayBlend = IN.texBlend * vec3(isUnderlay);"],
			@[@"vec3 overlayBlend = IN.texBlend * isOverlay;", @"vec3 overlayBlend = IN.texBlend * vec3(isOverlay);"],
			@[@"float result = dot(IN.texBlend, isPrimary);", @"float result = dot(IN.texBlend, vec3(isPrimary));"],
			@[@"result = 1 - result;", @"result = 1.0 - result;"],
			@[@"float inverseShadow = 1 - shadow;", @"float inverseShadow = 1.0 - shadow;"],
			@[@"Map == -1 ? 1 :", @"Map == -1 ? 1.0 :"],
			@[@"const ivec2 direction = ivec2(1, -1);", @"const vec2 direction = vec2(1.0, -1.0);"],
			@[@"* ivec2(1, -2)", @"* vec2(1.0, -2.0)"],
			@[@"sampleCaustics(flow1, flow2) * 2;", @"sampleCaustics(flow1, flow2) * 2.0;"],
			@[@"floor(IN.position.xz / 128)", @"floor(IN.position.xz / 128.0)"],
			@[@"floor(cameraPos.xz / 128)", @"floor(cameraPos.xz / 128.0)"],
			@[@"outputColor.a *= -256;", @"outputColor.a *= -256.0;"],
			@[@"if (saturation != 1 || contrast != 1)", @"if (saturation != 1.0 || contrast != 1.0)"],
			@[@"float combinedFog = 1 - (1 - fogAmount) * (1 - groundFog);", @"float combinedFog = 1.0 - (1.0 - fogAmount) * (1.0 - groundFog);"],
			@[@"outputColor.a * (1 - combinedFog);", @"outputColor.a * (1.0 - combinedFog);"],
			@[@"float opacity = 1 - (alphaBiasHsl >> 24 & 0xFF) / float(0xFF)", @"float opacity = 1.0 - float(alphaBiasHsl >> 24 & 0xFF) / float(0xFF)"],
			@[@"MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT & 0x3F) / 0x3F;", @"MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT & 0x3F) / float(0x3F);"],
			@[@"if (opacityThreshold == 0)", @"if (opacityThreshold == 0.0)"],
			@[@"float opacity = 1;", @"float opacity = 1.0;"],
			@[@"if (animationDuration == 0)", @"if (animationDuration == 0.0)"],
			@[@"if (vanillaLightDotNormals > 0)", @"if (vanillaLightDotNormals > 0.0)"],
			@[@"float lighten = max(0, lightness - IGNORE_LOW_LIGHTNESS);", @"float lighten = float(max(0, lightness - IGNORE_LOW_LIGHTNESS));"],
			@[@"int(127 - 72 * pow(saturation / 7., .05))", @"int(127.0 - 72.0 * pow(float(saturation) / 7., .05))"],
			@[@"(lighten * LIGHTNESS_MULTIPLIER + BASE_LIGHTEN - lightness) * vanillaLightDotNormals", @"(lighten * float(LIGHTNESS_MULTIPLIER) + float(BASE_LIGHTEN) - float(lightness)) * vanillaLightDotNormals"],
	];
	for (NSArray<NSString *> *c in rlhdCasts)
		[s replaceOccurrencesOfString:c[0] withString:c[1] options:0 range:NSMakeRange(0, s.length)];

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
			// "depthsplit": SINGLE-FRAME A/B. Alternate 200px vertical bands — baseline (gl_FragCoord.z, the
			// (NDC.z+1)/2 window depth, written back as a no-op) vs the fix (NDC.z = clip.z/clip.w). The band is a
			// function of gl_FragCoord.x ONLY, so every pixel keeps one convention across all draws and the depth
			// test stays self-consistent per pixel; the river z-fight then shows in baseline bands and (if the fix
			// works) is clean in fix bands, on IDENTICAL geometry, in one screenshot. Both branches write
			// gl_FragDepth (defined depth everywhere). Strip with the rest of the diagnostics.
			// depthsplit fix bands are marked with a 10px MAGENTA left-edge tick (zero ambiguity about which is which).
			NSString *depthWrite = [[RouteCBridge waterDiagMode] isEqualToString:@"depthsplit"]
				? @"bool rcFix = mod(floor(gl_FragCoord.x / 200.0), 2.0) >= 0.5;\n  FragColor = (rcFix && mod(gl_FragCoord.x, 200.0) < 10.0) ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(mixedColor, c.a);\n  gl_FragDepth = rcFix ? (vClipDepth * gl_FragCoord.w) : gl_FragCoord.z;"
				: @"FragColor = vec4(mixedColor, c.a);\n  gl_FragDepth = vClipDepth * gl_FragCoord.w;";
			[s replaceOccurrencesOfString:@"FragColor = vec4(mixedColor, c.a);"
				withString:depthWrite
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
