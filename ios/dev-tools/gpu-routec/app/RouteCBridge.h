/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import <Foundation/Foundation.h>
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
// this FBO id — it is "framebuffer 0" for the unmodified plugin. swapBuffers() is a no-op (we glReadPixels into
// the int[] blit). Returns YES on success.
+ (BOOL)awtCreateGLContextWidth:(int)w height:(int)h;
+ (GLuint)awtGetFramebuffer;
+ (void)awtDestroy;

// ---- the 5 non-GLES-3.0-core shims (everything else is 1:1 with the EAGL driver) ----
+ (void)glClearDepthShim:(double)depth;                  // -> glClearDepthf((float)depth)
+ (void)glMultiDrawArraysShim:(GLenum)mode first:(const GLint *)first count:(const GLsizei *)count drawcount:(GLsizei)n; // -> loop glDrawArrays
+ (void)glClipControlShim:(GLenum)origin depth:(GLenum)depth; // no-op: reverse-Z is handled by the projection matrix + depth state (no glClipControl on GLES)
+ (void)glDebugMessageControlShim;                        // no-op: KHR_debug absent on iOS GLES
+ (void *)glMapBufferShim:(GLenum)target access:(GLenum)access size:(GLsizeiptr)size; // -> glMapBufferRange (ES has range-map only)

@end
