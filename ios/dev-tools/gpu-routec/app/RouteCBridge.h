/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import <Foundation/Foundation.h>
#define GLES_SILENCE_DEPRECATION 1   // OpenGLES is deprecated-since-iOS-12 but works on iOS 26 (no Metal-GL bridge)
#import <OpenGLES/ES3/gl.h>

/*
 * GPU Route C — native GL bridge core (the iOS half of the thin JNI GL bridge). This is the code the JNI natives
 * backing the `org.lwjgl.opengl` / `net.runelite.rlawt.AWTContext` facade will call. The ~80 GLES-3.0-core calls
 * the base `gpu` plugin makes are 1:1 with the EAGL driver and need NO wrapper (the JNI stub calls glXxx directly).
 * Only the AWTContext offscreen-context analogue and the 5 non-core shims live here. Validated Simulator-first via
 * an ObjC harness (no JVM on the Simulator); the JVM↔JNI plumbing is identical to the proven JavaBridge reverse-JNI
 * and is wired on device.
 */
@interface RouteCBridge : NSObject

// ---- AWTContext analogue (librlawt replacement): EAGL ES3 offscreen context + a COLOR+DEPTH FBO ----
// createGLContext(): make an EAGL ES3 context current + build the offscreen FBO. getFramebuffer(false) returns
// this FBO id — it is "framebuffer 0" for the unmodified plugin. swapBuffers() reads the FBO back and publishes
// it (see RouteCBridge.m / the JNI swapBuffers shim). Returns YES on success.
+ (BOOL)awtCreateGLContextWidth:(int)w height:(int)h;
+ (GLuint)awtGetFramebuffer;
+ (void)awtDestroy;

// ---- per-frame upkeep (Route C 2C: thread-affine EAGL context + canvas-resize) ----
// makeCurrent: re-bind sCtx on the calling thread — cheap (no-op when already current) and removes the
// "draw runs off the createGLContext thread" risk entirely. fbWidth/fbHeight: the live FBO size. ensureSize:
// recreate the FBO when the engine canvas changes (rare on device — the canvas is fixed at the resizeSpec).
+ (void)makeCurrent;
+ (int)fbWidth;
+ (int)fbHeight;
+ (void)ensureSizeWidth:(int)w height:(int)h;

// ---- the GPU publish readback (Route C 2D) ----
// glReadPixels the color FBO (GL_RGBA — the only ES3-core readback format) into `dst` as packed 0x00RRGGBB,
// vertically flipped (GL origin is bottom-left; the display is top-left) and R<->B swizzled, in one row pass.
+ (void)readbackInto:(uint32_t *)dst width:(int)w height:(int)h;

// Async double-buffered PBO readback: glReadPixels into a pixel-pack buffer (returns immediately, no GPU stall),
// then map the PREVIOUS frame's PBO (the GPU finished it a frame ago -> no wait) and flip/swizzle into dst. Removes
// the ~16ms synchronous glReadPixels stall from the render thread, at the cost of 1 frame of latency. Returns NO on
// the very first frame (no prior frame to publish yet).
+ (BOOL)readbackAsyncInto:(uint32_t *)dst width:(int)w height:(int)h;

// ---- shader downport (Route C 2E) ----
// Rewrite desktop GLSL 330 -> GLSL ES 3.00 (the 5 B1 edits) before glShaderSource. Vertex shaders need the
// explicit int->float casts ES forbids implicitly; fragment shaders need precision qualifiers.
+ (NSString *)downportShaderSource:(NSString *)src fragment:(BOOL)fragment;

// ---- the 5 non-GLES-3.0-core shims (everything else is 1:1 with the EAGL driver) ----
+ (void)glClearDepthShim:(double)depth;                  // -> glClearDepthf((float)depth)
+ (void)glMultiDrawArraysShim:(GLenum)mode first:(const GLint *)first count:(const GLsizei *)count drawcount:(GLsizei)n; // -> loop glDrawArrays
+ (void)glClipControlShim:(GLenum)origin depth:(GLenum)depth; // no-op: reverse-Z is handled by the projection matrix + depth state (no glClipControl on GLES)
+ (void)glDebugMessageControlShim;                        // no-op: KHR_debug absent on iOS GLES
+ (void *)glMapBufferShim:(GLenum)target access:(GLenum)access size:(GLsizeiptr)size; // -> glMapBufferRange (ES has range-map only)

@end
