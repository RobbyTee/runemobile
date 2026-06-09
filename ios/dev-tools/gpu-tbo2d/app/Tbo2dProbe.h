/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */
#import <Foundation/Foundation.h>

// 117HD-on-iOS blocker #1, de-risked off-device. RLHD's Zone path feeds per-face data to the VERTEX shader via a
// GL_TEXTURE_BUFFER object: `uniform isamplerBuffer textureFaces; texelFetch(textureFaces, faceId)`. iOS EAGL
// GLES 3.0 (and ANGLE-on-Metal ES 3.0) have NO texture-buffer object / isamplerBuffer. The repack: store the same
// per-face records in a 2D INTEGER texture and rewrite the fetch to texelFetch(tex2d, ivec2(faceId % W, faceId / W)).
// This probe proves that repack is correct on iOS GLES 3.0: integer 2D textures are creatable + exactly sampleable,
// the faceId->(x,y) wrap is byte-exact across row boundaries and at face counts a 1D texture could not hold, and the
// fetch works in BOTH the fragment AND the vertex stage (RLHD fetches in scene_vert/shadow_vert). Core ES 3.0 only,
// so a Simulator GREEN is high-confidence for the device GPU. Runs fully offscreen.
@interface Tbo2dProbe : NSObject
+ (void)runWithLog:(void (^)(NSString *))log;
@end
