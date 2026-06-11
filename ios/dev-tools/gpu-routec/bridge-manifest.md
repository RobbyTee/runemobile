# Route C — JNI GL bridge manifest (the device-side spec)

The base `gpu` plugin invokes exactly **100 facade methods** (extracted from the compiled plugin bytecode →
`used-descriptors.txt`): **87 GL overloads** + **13 support calls**. The native GL surface is **GREEN**: all 81
distinct GLES-3.0 target symbols compile + link against `OpenGLES.framework` for iOS arm64
(`check/routec-glsurface-verdict.sh`, `ROUTEC_GLSURFACE_GREEN_AS_EXPECTED`). Step-2 already built + proved the
context/depth/shim core (`RouteCBridge`). What remains is **mechanical JNI marshalling**, which is **device-only
testable** (no JVM on the Simulator) → deferred to the on-device step. This file is its spec so it's a fill-in.

## Binding mechanism

Facade methods become `native`; a `RegisterNatives` table maps `{name, JNI-descriptor, fn}` (avoids name-mangling
across the overloads). The ~80 core GL calls forward straight to `glXxx`; the 5 census shims + the `AWTContext`
analogue route to `RouteCBridge` (already built, step 2). `BufferUtils.createIntBuffer` and `Configuration.set`
stay **pure-Java** facade methods (no JNI). So ~98 native entries.

## Marshalling categories (each forwarder is 1–3 lines)

1. **Primitive-only (~52)** — params `I/F/Z/D`, return `void/I/F/Z`: forward directly, e.g.
   `glActiveTexture(a0)`, `glClearColor(a0..a3)`, `glCheckFramebufferStatus(a0)→jint`. The `(II)I` getters
   `glGetProgrami`/`glGetShaderi` → `glGetProgramiv(a0,a1,&v); return v;` (and `glGetInteger/glGetFloat` →
   `glGetIntegerv/glGetFloatv`).
2. **gen/delete single (12)** — LWJGL's no-arg `glGenBuffers():I` → `GLuint id; glGenBuffers(1,&id); return id;`;
   `glDeleteBuffers(I):V` → `GLuint id=a0; glDeleteBuffers(1,&id);`. (Buffers/Framebuffers/Renderbuffers/Textures/
   VertexArrays.)
3. **J = byte-offset pointer (6)** — `glDrawElements(...J)`, `glTexImage2D/3D(...J)`, `glTexSubImage2D(...J)`,
   `glVertexAttribPointer/IPointer(...J)`: last arg → `(const void *)(intptr_t)aLast` (PBO/index offset).
4. **J = size/length (3)** — `glBufferData(I,J,I)` → `glBufferData(t,(GLsizeiptr)a1,NULL,a2)`;
   `glFlushMappedBufferRange(I,J,J)`; `glMapBufferRange(I,J,J,I,ByteBuffer)` → see (6).
5. **nio.Buffer arg (5)** — `glBufferData(I,FloatBuffer/IntBuffer,I)`, `glReadPixels(...ByteBuffer)`,
   `glTexSubImage3D(...ByteBuffer)`, `glMultiDrawArrays(I,IntBuffer,IntBuffer)` [shim]: arg →
   `(*env)->GetDirectBufferAddress(env, buf)` (the plugin uses direct buffers throughout).
6. **nio.Buffer return (2)** — `glMapBuffer(II):ByteBuffer` [shim → `glMapBufferRange`], `glMapBufferRange(...)`:
   `(*env)->NewDirectByteBuffer(env, ptr, length)` where length = the J size arg (reuse the passed old-buffer arg
   if non-null, LWJGL style).
7. **primitive array [F (2)** — `glUniform2fv(I,[F)`, `glUniformMatrix4fv(I,Z,[F)`:
   `jfloat *p = GetFloatArrayElements; glUniformXfv(loc, count, p); Release...`. count = arrayLen (÷16 for mat4).
8. **CharSequence in (3)** — `glShaderSource(I,CharSequence)`, `glGetUniformLocation/glGetUniformBlockIndex(I,
   CharSequence):I`: the plugin passes `String`; `const char *s = GetStringUTFChars; glXxx(...); ReleaseStringUTFChars`.
9. **String return (3)** — `glGetProgramInfoLog/glGetShaderInfoLog(I):String`, `glGetString(I):String`:
   `glGetXxxInfoLog(id, n, &len, buf); return NewStringUTF(env, buf);` (`glGetString` → `(const char*)glGetString`).

## The 5 shims (built — `RouteCBridge`, step 2 GREEN)

| facade call | shim |
| --- | --- |
| `glClearDepth(D)` | `glClearDepthf((float)d)` |
| `glMultiDrawArrays(I,IntBuffer,IntBuffer)` | loop `glDrawArrays` over `(first[],count[])` |
| `glClipControl(I,I)` | no-op (reverse-Z via projection + `GL_GEQUAL`; **proven step 2**) |
| `glDebugMessageControl(IIIIZ)` | no-op (KHR_debug absent on iOS GLES) |
| `glMapBuffer(I,I):ByteBuffer` | `glMapBufferRange(target,0,size,GL_MAP_WRITE_BIT\|INVALIDATE)` |

## Support calls (13)

- **`AWTContext` ×8** (instance natives → `RouteCBridge` statics, ignore `this`): `loadNatives`(no-op),
  `configurePixelFormat`(no-op), `createGLContext`(→`awtCreateGLContext` at the render size), `getFramebuffer(Z)`
  (→`awtGetFramebuffer`), `getBufferMode`(→`GL_COLOR_ATTACHMENT0`), `setSwapInterval`(no-op, return arg),
  `swapBuffers`(no-op — we `glReadPixels` into the `int[]` blit), `destroy`(→`awtDestroy`).
- **`GL.createCapabilities`/`getCapabilities`():GLCapabilities** — `NewObject` a `GLCapabilities`, set fields:
  `OpenGL33=true` (**required or the plugin aborts**, GpuPlugin.java:317), `OpenGL45=false` (→ skips
  `glClipControl`), `glTexStorage3D=1` (core ES3), `glDebugMessageControl=0`, `GL_EXT_texture_filter_anisotropic`
  = query `GL_EXTENSIONS` (present on iOS).
- **`GLUtil.setupDebugMessageCallback():Callback`** → return `null` (no debug on iOS GLES).
- **`BufferUtils.createIntBuffer(I)` + `Configuration.set(Object)`** — keep as **pure-Java** facade impls (no JNI).

## MSAA note (deferred, not blocking)

If the plugin's AA is enabled it adds an MSAA FBO via `glRenderbufferStorageMultisample` + `glBlitFramebuffer`
(both **ES3 core**, in the verified 81). The step-2 `AWTContext` FBO is single-sample; add the MSAA path when
wiring AA on device.

## Next

On device: declare the facade methods `native`, generate these forwarders + the `RegisterNatives` table (mirror
the predecessor `jni_gl.c` auto-stub pattern), `dlopen` the bridge, point `AWTContext` at the EAGL analogue, feed
the **B1-proven downported shaders** through the plugin's real `Shader.compile`, drive the real `DrawCallbacks`
scene pass → `glReadPixels` → the `int[]` blit (`JavaBridge`). md5-gated device run on the primary machine.
