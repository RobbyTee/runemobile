/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import "ZoneProbe.h"
#import "ZoneDownport.h"
#import <OpenGLES/EAGL.h>
#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>

@implementation ZoneProbe

static void dumpNumbered(NSString *src, void (^log)(NSString *))
{
	int n = 1;
	for (NSString *line in [src componentsSeparatedByString:@"\n"])
		log([NSString stringWithFormat:@"    %d: %@", n++, line]);
}

// Infer the GL shader stage from the assembled filename convention `<name>.<stage>.assembled.glsl`.
static GLenum stageForName(NSString *name)
{
	if ([name containsString:@".vert."]) return GL_VERTEX_SHADER;
	if ([name containsString:@".frag."]) return GL_FRAGMENT_SHADER;
	return 0;   // geometry/compute are not part of the zone path — skipped
}

static BOOL compileOne(NSString *name, NSString *src, GLenum stage, void (^log)(NSString *))
{
	BOOL fragment = (stage == GL_FRAGMENT_SHADER);
	NSString *down = [ZoneDownport downport:src fragment:fragment];

	GLuint sh = glCreateShader(stage);
	const char *c = [down UTF8String];
	glShaderSource(sh, 1, &c, NULL);
	glCompileShader(sh);

	GLint ok = 0; glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
	GLint len = 0; glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &len);
	NSString *info = @"";
	if (len > 1) { char *b = malloc(len); glGetShaderInfoLog(sh, len, NULL, b); info = @(b); free(b); }

	if (ok != GL_TRUE)
	{
		log([NSString stringWithFormat:@"[z0] COMPILE %@ = FAIL", name]);
		log([NSString stringWithFormat:@"[z0]   infolog: %@", info]);
		dumpNumbered(down, log);
		glDeleteShader(sh);
		return NO;
	}
	log([NSString stringWithFormat:@"[z0] COMPILE %@ = OK%@", name,
		info.length ? [@" (infolog: " stringByAppendingFormat:@"%@)", info] : @""]);
	glDeleteShader(sh);
	return YES;
}

+ (void)runWithLog:(void (^)(NSString *))log
{
	EAGLContext *ctx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
	if (!ctx || ![EAGLContext setCurrentContext:ctx])
	{
		log(@"[z0] FAIL: could not create an EAGL ES3 context");
		return;
	}
	log([NSString stringWithFormat:@"[z0] GL_VERSION = %s", glGetString(GL_VERSION)]);
	log([NSString stringWithFormat:@"[z0] GL_SLVERSION = %s", glGetString(GL_SHADING_LANGUAGE_VERSION)]);

	NSArray<NSString *> *paths = [[NSBundle mainBundle] pathsForResourcesOfType:@"glsl" inDirectory:nil];
	NSArray<NSString *> *assembled = [paths filteredArrayUsingPredicate:
		[NSPredicate predicateWithFormat:@"self CONTAINS '.assembled.'"]];
	assembled = [assembled sortedArrayUsingSelector:@selector(compare:)];

	if (assembled.count == 0)
	{
		// Skeleton state: no assembled shaders bundled yet. The assemble step (replicate HdPlugin.getShaderIncludes
		// over the vendored ShaderIncludes; see README "Status") produces `assembled/*.assembled.glsl`. Report
		// PENDING, not GREEN — the verdict script keys off this.
		log(@"[z0] NO_ASSEMBLED_SHADERS — assemble step not wired yet (see gpu-zone/README.md 'Status'). PENDING.");
		return;
	}

	int total = 0, okCount = 0, skipped = 0;
	for (NSString *path in assembled)
	{
		NSString *name = [path lastPathComponent];
		GLenum stage = stageForName(name);
		if (stage == 0) { skipped++; log([NSString stringWithFormat:@"[z0] SKIP %@ (non-vert/frag stage)", name]); continue; }
		NSString *src = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
		if (!src) { log([NSString stringWithFormat:@"[z0] FAIL: could not read %@", name]); continue; }
		total++;
		if (compileOne(name, src, stage, log)) okCount++;
	}
	log([NSString stringWithFormat:@"[z0] SUMMARY compiled=%d/%d skipped=%d", okCount, total, skipped]);
	log(okCount == total && total > 0 ? @"[z0] VERDICT = ALL_ZONE_SHADERS_COMPILE_ES300"
	                                  : @"[z0] VERDICT = ZONE_SHADER_COMPILE_INCOMPLETE");
}

@end
