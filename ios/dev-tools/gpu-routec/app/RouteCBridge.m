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

@implementation RouteCBridge

static EAGLContext *sCtx = nil;
static GLuint sFbo = 0, sColorRbo = 0, sDepthRbo = 0;

+ (BOOL)awtCreateGLContextWidth:(int)w height:(int)h
{
	sCtx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
	if (!sCtx)
	{
		return NO;
	}
	[EAGLContext setCurrentContext:sCtx];

	// Offscreen FBO with COLOR + DEPTH renderbuffers — the plugin renders the scene with depth testing, so the
	// AWTContext analogue must supply a depth attachment (librlawt's window FBO has one). This FBO is what the
	// plugin sees as "framebuffer 0" via getFramebuffer(false).
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
	return status == GL_FRAMEBUFFER_COMPLETE;
}

+ (GLuint)awtGetFramebuffer
{
	return sFbo; // the plugin's "framebuffer 0"
}

+ (void)awtDestroy
{
	if (sDepthRbo) glDeleteRenderbuffers(1, &sDepthRbo), sDepthRbo = 0;
	if (sColorRbo) glDeleteRenderbuffers(1, &sColorRbo), sColorRbo = 0;
	if (sFbo) glDeleteFramebuffers(1, &sFbo), sFbo = 0;
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

@end
