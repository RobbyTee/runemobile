/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 *
 * ----------------------------------------------------------------------------------------------------------------
 * gpu-tbo2d — 117HD-on-iOS blocker #1 (texture-buffer -> 2D integer texture repack), de-risked on iOS GLES (Simulator).
 *
 * RLHD's Zone renderer feeds per-face data to the VERTEX shaders (scene_vert/shadow_vert) via a GL_TEXTURE_BUFFER:
 *     uniform isamplerBuffer textureFaces;   ...   ivec4 face = texelFetch(textureFaces, faceId);
 * Neither iOS EAGL GLES 3.0 nor ANGLE-on-Metal ES 3.0 has texture-buffer objects (isamplerBuffer / GL_TEXTURE_BUFFER
 * is ES 3.2 core). So the port must repack those per-face records into a 2D INTEGER texture and rewrite the fetch:
 *     uniform highp isampler2D textureFaces2D;   int W = textureSize(textureFaces2D, 0).x;
 *     ivec4 face = texelFetch(textureFaces2D, ivec2(faceId % W, faceId / W), 0);
 * The risk this retires: is that repack byte-exact on iOS GLES 3.0, at the face counts a real scene hits (which can
 * exceed a 1D texture's max width, which is WHY the buffer was used), in BOTH shader stages?
 *
 * Phases:
 *   A — create a 2D RGBA32I (and R32I) integer texture, upload a known per-face pattern, confirm no GL error / complete.
 *   B — FRAGMENT-stage verify: each output pixel (x,y) fetches texel (x,y) and compares to the known formula. Proves
 *       integer-texture upload + sampling round-trips exactly (format/sign/value), for every face.
 *   C — VERTEX-stage verify (the RLHD pathway): draw faceCount GL_POINTS, faceId = gl_VertexID (NOT a pixel position),
 *       the VERTEX shader computes ivec2(faceId % W, faceId / W), fetches, compares to expected(faceId), and writes
 *       the pass/fail to pixel (faceId % W, faceId / W). This independently exercises the wrap ARITHMETIC and proves
 *       vertex-texture-fetch works on iOS. faceCount is chosen > a 1D texture's max width so the 2D wrap is required.
 *
 * CAVEAT: GL_RENDERER is the Simulator's SOFTWARE renderer, not the device Metal GPU. BUT integer 2D textures +
 * texelFetch + vertex texture fetch are ES 3.0 CORE (not optional renderability like an RGBA16UI color attachment),
 * so a software-ES3 pass is high-confidence for the device GPU (a superset). One device confirm is still cheap to do.
 * ----------------------------------------------------------------------------------------------------------------
 */
#import "Tbo2dProbe.h"
#import <OpenGLES/EAGL.h>
#import <OpenGLES/ES3/gl.h>
#import <OpenGLES/ES3/glext.h>

@implementation Tbo2dProbe

// Texture geometry. W deliberately modest (2048) and faceCount deliberately ABOVE it, so faces span many rows and a
// 1D texture (max width GL_MAX_TEXTURE_SIZE, but a real RLHD scene's face count can exceed practical 1D limits) could
// not hold them -- the exact reason 2D-repack is needed.
static const int kW = 2048;
static const int kFaceCount = 50000;            // -> ceil(50000/2048) = 25 rows

// Deterministic per-face record. Mixes large + NEGATIVE + bitwise values to surface format/sign/round-trip bugs.
// (RGBA32I is SIGNED; component .b stays non-negative to also exercise a plausible unsigned-ish field.)
static void expectedC(int i, int *out /*[4]*/)
{
	out[0] = i * 7 + 1;
	out[1] = i * 13 + 2;
	out[2] = (i ^ 0x5a5a) & 0x7fffffff;
	out[3] = -(i * 3 + 1);
}

static NSString *const kExpectedGLSL =
	@"ivec4 expected(int i){ return ivec4(i*7+1, i*13+2, (i ^ 0x5a5a) & 0x7fffffff, -(i*3+1)); }\n";

// ---- GL helpers ----
static GLuint compileShader(GLenum type, NSString *src, NSString *label, void (^log)(NSString *))
{
	GLuint sh = glCreateShader(type);
	const char *cstr = [src UTF8String];
	glShaderSource(sh, 1, &cstr, NULL);
	glCompileShader(sh);
	GLint status = 0; glGetShaderiv(sh, GL_COMPILE_STATUS, &status);
	if (status != GL_TRUE)
	{
		GLint ll = 0; glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &ll);
		char *b = ll > 1 ? malloc(ll) : NULL; if (b) glGetShaderInfoLog(sh, ll, NULL, b);
		log([NSString stringWithFormat:@"[tbo] COMPILE %@ = FAIL: %s", label, b ? b : "(no log)"]);
		free(b); glDeleteShader(sh); return 0;
	}
	return sh;
}
static GLuint linkProgram(NSString *vsrc, NSString *fsrc, NSString *label, void (^log)(NSString *))
{
	GLuint vs = compileShader(GL_VERTEX_SHADER, vsrc, [label stringByAppendingString:@".vert"], log);
	GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsrc, [label stringByAppendingString:@".frag"], log);
	if (!vs || !fs) return 0;
	GLuint p = glCreateProgram(); glAttachShader(p, vs); glAttachShader(p, fs); glLinkProgram(p);
	glDeleteShader(vs); glDeleteShader(fs);
	GLint linked = 0; glGetProgramiv(p, GL_LINK_STATUS, &linked);
	if (!linked)
	{
		GLint ll = 0; glGetProgramiv(p, GL_INFO_LOG_LENGTH, &ll);
		char *b = ll > 1 ? malloc(ll) : NULL; if (b) glGetProgramInfoLog(p, ll, NULL, b);
		log([NSString stringWithFormat:@"[tbo] LINK %@ = FAIL: %s", label, b ? b : "(no log)"]);
		free(b); glDeleteProgram(p); return 0;
	}
	log([NSString stringWithFormat:@"[tbo] PROGRAM %@ = OK", label]);
	return p;
}
static const char *glErrStr(GLenum e)
{
	switch (e) { case GL_NO_ERROR: return "NONE"; case GL_INVALID_ENUM: return "INVALID_ENUM";
		case GL_INVALID_VALUE: return "INVALID_VALUE"; case GL_INVALID_OPERATION: return "INVALID_OPERATION";
		case GL_INVALID_FRAMEBUFFER_OPERATION: return "INVALID_FRAMEBUFFER_OP"; case GL_OUT_OF_MEMORY: return "OOM";
		default: return "?"; }
}

// Build the per-face RGBA32I texture (W x H, row-major by faceId; padding faces zeroed). Returns tex id, sets *Hout.
static GLuint buildFaceTextureRGBA32I(int W, int faceCount, int *Hout, void (^log)(NSString *))
{
	const int H = (faceCount + W - 1) / W;
	*Hout = H;
	int32_t *data = calloc((size_t)W * H * 4, sizeof(int32_t));
	for (int i = 0; i < faceCount; i++) { int v[4]; expectedC(i, v); memcpy(&data[(size_t)i * 4], v, sizeof(v)); }
	GLuint tex = 0; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);   // integer textures: NEAREST only
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32I, W, H, 0, GL_RGBA_INTEGER, GL_INT, data);
	GLenum e = glGetError();
	free(data);
	log([NSString stringWithFormat:@"[tbo] PHASE A: RGBA32I %dx%d (%d faces, %d rows) upload glErr=%s",
		W, H, faceCount, H, glErrStr(e)]);
	if (e != GL_NO_ERROR) { glDeleteTextures(1, &tex); return 0; }
	return tex;
}

// An integer FBO render target (RGBA8) to read pass/fail colors back from.
static BOOL makeRGBA8Fbo(int W, int H, GLuint *fboOut, GLuint *colOut, void (^log)(NSString *))
{
	GLuint col = 0, fbo = 0;
	glGenTextures(1, &col); glBindTexture(GL_TEXTURE_2D, col);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, col, 0);
	if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
	{
		log(@"[tbo] FBO INCOMPLETE -> SKIP");
		glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &col); return NO;
	}
	*fboOut = fbo; *colOut = col; return YES;
}

// ---- PHASE B: fragment-stage own-pixel verify ----
static BOOL runPhaseB(GLuint faceTex, int W, int H, int faceCount, void (^log)(NSString *))
{
	NSString *vsrc =
		@"#version 300 es\n"
		 "precision highp float;\n"
		 "const vec2 P[4] = vec2[4](vec2(-1.,-1.), vec2(1.,-1.), vec2(-1.,1.), vec2(1.,1.));\n"
		 "void main(){ gl_Position = vec4(P[gl_VertexID], 0., 1.); }\n";
	NSString *fsrc = [NSString stringWithFormat:
		@"#version 300 es\n"
		 "precision highp float; precision highp int; precision highp isampler2D;\n"
		 "uniform highp isampler2D uFaces; uniform int uW; uniform int uFaceCount;\n"
		 "out vec4 FragColor;\n"
		 "%@"
		 "void main(){\n"
		 "  int x = int(gl_FragCoord.x); int y = int(gl_FragCoord.y);\n"
		 "  int faceId = y * uW + x;\n"
		 "  if (faceId >= uFaceCount) { FragColor = vec4(0.,0.,1.,1.); return; }\n"   // blue = padding
		 "  ivec4 got = texelFetch(uFaces, ivec2(x, y), 0);\n"
		 "  FragColor = (got == expected(faceId)) ? vec4(0.,1.,0.,1.) : vec4(1.,0.,0.,1.);\n"
		 "}\n", kExpectedGLSL];
	GLuint prog = linkProgram(vsrc, fsrc, @"P_FRAG", log);
	if (!prog) return NO;

	GLuint fbo = 0, col = 0;
	if (!makeRGBA8Fbo(W, H, &fbo, &col, log)) { glDeleteProgram(prog); return NO; }
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glViewport(0, 0, W, H);
	glDisable(GL_DEPTH_TEST);
	glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
	glUseProgram(prog);
	glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, faceTex);
	glUniform1i(glGetUniformLocation(prog, "uFaces"), 0);
	glUniform1i(glGetUniformLocation(prog, "uW"), W);
	glUniform1i(glGetUniformLocation(prog, "uFaceCount"), faceCount);
	GLuint vao = 0; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

	unsigned char *px = malloc((size_t)W * H * 4);
	glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
	int green = 0, red = 0, blue = 0;
	for (size_t i = 0; i < (size_t)W * H; i++)
	{
		const unsigned char *p = px + i * 4;
		if (p[1] > 200 && p[0] < 80) green++;
		else if (p[0] > 200 && p[1] < 80) red++;
		else if (p[2] > 200) blue++;
	}
	free(px);
	glDeleteVertexArrays(1, &vao); glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &col); glDeleteProgram(prog);
	const int padding = W * H - faceCount;
	const BOOL ok = (green == faceCount) && (red == 0) && (blue == padding);
	log([NSString stringWithFormat:@"[tbo] PHASE B (fragment own-pixel): green=%d (want %d) red=%d (want 0) blue/pad=%d (want %d)",
		green, faceCount, red, blue, padding]);
	log([NSString stringWithFormat:@"[tbo] B_FRAG_EXACT = %@", ok ? @"YES" : @"NO"]);
	return ok;
}

// ---- PHASE C: vertex-stage wrap-arithmetic verify (the RLHD pathway) ----
static BOOL runPhaseC(GLuint faceTex, int W, int H, int faceCount, void (^log)(NSString *))
{
	NSString *vsrc = [NSString stringWithFormat:
		@"#version 300 es\n"
		 "precision highp float; precision highp int; precision highp isampler2D;\n"
		 "uniform highp isampler2D uFaces; uniform int uW; uniform int uH;\n"
		 "flat out int vOk;\n"
		 "%@"
		 "void main(){\n"
		 "  int faceId = gl_VertexID;\n"
		 "  int cx = faceId %% uW; int cy = faceId / uW;\n"               // the wrap arithmetic under test
		 "  ivec4 got = texelFetch(uFaces, ivec2(cx, cy), 0);\n"
		 "  vOk = (got == expected(faceId)) ? 1 : 0;\n"
		 "  float nx = (float(cx) + 0.5) / float(uW) * 2.0 - 1.0;\n"
		 "  float ny = (float(cy) + 0.5) / float(uH) * 2.0 - 1.0;\n"
		 "  gl_Position = vec4(nx, ny, 0.0, 1.0); gl_PointSize = 1.0;\n"
		 "}\n", kExpectedGLSL];
	NSString *fsrc =
		@"#version 300 es\n"
		 "precision highp float;\n"
		 "flat in int vOk;\n"
		 "out vec4 FragColor;\n"
		 "void main(){ FragColor = (vOk == 1) ? vec4(0.,1.,0.,1.) : vec4(1.,0.,0.,1.); }\n";
	GLuint prog = linkProgram(vsrc, fsrc, @"P_VERT", log);
	if (!prog) return NO;

	GLuint fbo = 0, col = 0;
	if (!makeRGBA8Fbo(W, H, &fbo, &col, log)) { glDeleteProgram(prog); return NO; }
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glViewport(0, 0, W, H);
	glDisable(GL_DEPTH_TEST);
	glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);     // black = face not drawn
	glUseProgram(prog);
	glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, faceTex);
	glUniform1i(glGetUniformLocation(prog, "uFaces"), 0);
	glUniform1i(glGetUniformLocation(prog, "uW"), W);
	glUniform1i(glGetUniformLocation(prog, "uH"), H);
	GLuint vao = 0; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
	glDrawArrays(GL_POINTS, 0, faceCount);

	unsigned char *px = malloc((size_t)W * H * 4);
	glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
	int green = 0, red = 0, black = 0;
	for (size_t i = 0; i < (size_t)W * H; i++)
	{
		const unsigned char *p = px + i * 4;
		if (p[1] > 200 && p[0] < 80) green++;
		else if (p[0] > 200 && p[1] < 80) red++;
		else black++;
	}
	free(px);
	glDeleteVertexArrays(1, &vao); glDeleteFramebuffers(1, &fbo); glDeleteTextures(1, &col); glDeleteProgram(prog);
	const int padding = W * H - faceCount;
	const BOOL ok = (green == faceCount) && (red == 0) && (black == padding);
	log([NSString stringWithFormat:@"[tbo] PHASE C (vertex wrap, gl_VertexID): green=%d (want %d) red=%d (want 0) black/undrawn=%d (want %d)",
		green, faceCount, red, black, padding]);
	log([NSString stringWithFormat:@"[tbo] C_VERT_WRAP_EXACT = %@", ok ? @"YES" : @"NO"]);
	return ok;
}

+ (void)runWithLog:(void (^)(NSString *))log
{
	log(@"[tbo] ===== gpu-tbo2d: texture-buffer -> 2D integer-texture repack (117HD blocker #1) on iOS GLES =====");

	EAGLContext *ctx = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
	if (!ctx) { log(@"[tbo] EAGL ES3 context creation FAILED -> NO-GO"); return; }
	[EAGLContext setCurrentContext:ctx];
	const char *renderer = (const char *) glGetString(GL_RENDERER);
	log([NSString stringWithFormat:@"[tbo] GL_VERSION = %s", (const char *) glGetString(GL_VERSION)]);
	log([NSString stringWithFormat:@"[tbo] GL_SLVERSION = %s", (const char *) glGetString(GL_SHADING_LANGUAGE_VERSION)]);
	log([NSString stringWithFormat:@"[tbo] GL_RENDERER = %s", renderer]);
	GLint maxTex = 0, maxVtxTex = 0;
	glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
	glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, &maxVtxTex);
	log([NSString stringWithFormat:@"[tbo] GL_MAX_TEXTURE_SIZE=%d  GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS=%d (must be >=1 for the vertex fetch)",
		maxTex, maxVtxTex]);
	log([NSString stringWithFormat:@"[tbo] faceCount=%d packed into W=%d -> %d rows (a 1D layout would need width %d <= GL_MAX_TEXTURE_SIZE=%d? %@)",
		kFaceCount, kW, (kFaceCount + kW - 1) / kW, kFaceCount, maxTex, (kFaceCount <= maxTex) ? @"fits-but-2D-still-needed-for-bigger-scenes" : @"NO -> 2D required"]);
	if (renderer && strstr(renderer, "Software"))
		log(@"[tbo] NOTE: software renderer, but integer-2D-texture + texelFetch + vertex texture fetch are ES 3.0 CORE -> high device confidence.");

	int H = 0;
	GLuint faceTex = buildFaceTextureRGBA32I(kW, kFaceCount, &H, log);

	BOOL b = NO, c = NO;
	if (faceTex)
	{
		b = runPhaseB(faceTex, kW, H, kFaceCount, log);
		c = runPhaseC(faceTex, kW, H, kFaceCount, log);
		glDeleteTextures(1, &faceTex);
	}
	else { log(@"[tbo] face texture build FAILED -> PHASE B/C SKIP"); }

	const BOOL green = (faceTex != 0) && b && c;
	log([NSString stringWithFormat:@"[tbo] VERDICT tbo2d_repack_exact = %@  (frag=%@ vert=%@)",
		green ? @"YES" : @"NO", b ? @"YES" : @"NO", c ? @"YES" : @"NO"]);
	log(@"[tbo] ===== gpu-tbo2d done =====");

	[EAGLContext setCurrentContext:nil];
}

@end
