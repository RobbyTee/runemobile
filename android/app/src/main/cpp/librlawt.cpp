// Track G AG1a — the per-ABI `librlawt` replacement: an Android EGL/GLES backing for the unmodified base `gpu`
// plugin's `net.runelite.rlawt.AWTContext` + `org.lwjgl.opengl.*` facade. Ported from the iOS Route-C JNI GL
// bridge (gpu branch: ios/dev-tools/gpu-routec/jni/routec_gl_jni.m + RouteCBridge.m's EGL path), swapping EAGL
// for Android's native EGL — and DROPPING the iOS platform tax (ANGLE platform selectors, the water/2x-SS diag
// modes, the perf logging, the CAEAGLLayer present path, the JavaBridge readback publish — all AG2+).
//
// It creates an offscreen pbuffer ES3 context + a COLOR(RGBA8)+DEPTH(24) FBO, makes it current on the calling
// (embedded-VM worker) thread, and forwards the plugin's GL surface straight to GLES (Android restores the
// objects/entry points iOS lacked). The host (jvm_bootstrap, smoke mode) dlopen's this + calls
// RouteC_registerGLNatives(env) to RegisterNatives the facade BEFORE the probe/plugin runs. `GpuPlugin.java` and
// the real `AWTContext` stay byte-for-byte unmodified — the work is this native + the pure-Java facade.
//
// AG1a scope: prove `GL.createCapabilities().OpenGL33==true` + a non-zero program id through the facade on a live
// EGL context, headless. The full forward table is wired here (reusable for AG2), but the offscreen+no readback
// present, the GLSL 330->ES downport, and the real-shader/canvas path are AG1b/AG2.

#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <android/log.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "librlawt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- GL constants the plugin passes that are NOT in the GLES headers (desktop/LWJGL values) ------------------
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_UNSIGNED_INT_8_8_8_8_REV
#define GL_UNSIGNED_INT_8_8_8_8_REV 0x8367
#endif
// EXT_clip_control (reverse-Z) — resolved at runtime via eglGetProcAddress; absent on a driver -> the shim no-ops
// (the GEQUAL projection fallback handles reverse-Z, AG2).
#ifndef GL_LOWER_LEFT
#define GL_LOWER_LEFT 0x8CA1
#endif
#ifndef GL_ZERO_TO_ONE
#define GL_ZERO_TO_ONE 0x935F
#endif
typedef void (*PFNGLCLIPCONTROLEXTPROC)(GLenum origin, GLenum depth);
static PFNGLCLIPCONTROLEXTPROC pglClipControlEXT = nullptr;

// ---- EGL offscreen context + FBO (ported from RouteCBridge.m's RC_BACKEND_ANGLE path, ANGLE selectors dropped)
static EGLDisplay sDpy  = EGL_NO_DISPLAY;
static EGLContext sCtx  = EGL_NO_CONTEXT;
static EGLSurface sSurf = EGL_NO_SURFACE;
static EGLConfig  sCfg  = nullptr;
static GLuint sFbo = 0, sColorRbo = 0, sDepthRbo = 0;
static int sFboW = 0, sFboH = 0;

static bool rcEglPrepareSurface(int w, int h) {
    if (sDpy == EGL_NO_DISPLAY) {
        sDpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (sDpy == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }
        EGLint vmaj = 0, vmin = 0;
        if (!eglInitialize(sDpy, &vmaj, &vmin)) { LOGE("eglInitialize failed"); return false; }
        LOGI("EGL %d.%d initialized", vmaj, vmin);
    }
    const EGLint cfgAttr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_NONE };
    EGLint nc = 0;
    if (!eglChooseConfig(sDpy, cfgAttr, &sCfg, 1, &nc) || nc < 1) { LOGE("eglChooseConfig failed (nc=%d)", nc); return false; }
    const EGLint pb[] = { EGL_WIDTH, (w > 0 ? w : 16), EGL_HEIGHT, (h > 0 ? h : 16), EGL_NONE };
    sSurf = eglCreatePbufferSurface(sDpy, sCfg, pb);
    if (sSurf == EGL_NO_SURFACE) { LOGE("eglCreatePbufferSurface failed 0x%x", eglGetError()); return false; }
    return true;
}

static bool rcEglCreateContextCurrent() {
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ctxAttr[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
    sCtx = eglCreateContext(sDpy, sCfg, EGL_NO_CONTEXT, ctxAttr);
    if (sCtx == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed 0x%x", eglGetError()); return false; }
    if (!eglMakeCurrent(sDpy, sSurf, sSurf, sCtx)) { LOGE("eglMakeCurrent failed 0x%x", eglGetError()); return false; }
    return true;
}

// Offscreen FBO (RGBA8 COLOR + DEPTH24) — the plugin renders depth-tested, so the AWTContext analogue supplies a
// depth attachment like librlawt's window FBO. Returned to the plugin as "framebuffer 0".
static bool rcBuildFbo(int w, int h) {
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
    return status == GL_FRAMEBUFFER_COMPLETE;
}

// ---- ByteBuffer native-order helper (LWJGL maps return native-ordered buffers) ------------------------------
static jobject rc_native_order(JNIEnv *env, jobject bb) {
    if (!bb) return bb;
    jclass boCls = env->FindClass("java/nio/ByteOrder");
    jclass bbCls = env->FindClass("java/nio/ByteBuffer");
    if (boCls && bbCls) {
        jmethodID nativeOrder = env->GetStaticMethodID(boCls, "nativeOrder", "()Ljava/nio/ByteOrder;");
        jmethodID order = env->GetMethodID(bbCls, "order", "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;");
        if (nativeOrder && order) {
            jobject no = env->CallStaticObjectMethod(boCls, nativeOrder);
            jobject ret = env->CallObjectMethod(bb, order, no);
            if (ret) bb = ret;
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return bb;
}

static bool rc_gl_has_ext(const char *name) {
    const char *ext = (const char *) glGetString(GL_EXTENSIONS);
    return ext && strstr(ext, name) != nullptr;
}

// =============================================================================================================
// GL forwards — kGLALL. Clean GLES forwards (iOS diag/perf/water/SS stripped). The 5 non-ES-core entry points
// are shimmed; the BGRA UI-texture upload is swizzled to RGBA (silently corrupts colour on GLES otherwise).
// =============================================================================================================
#define GLF static void JNICALL
GLF rc_glActiveTexture(JNIEnv*, jclass, jint a0){ glActiveTexture(a0); }
GLF rc_glAttachShader(JNIEnv*, jclass, jint a0, jint a1){ glAttachShader(a0,a1); }
GLF rc_glBindBuffer(JNIEnv*, jclass, jint a0, jint a1){ glBindBuffer(a0,a1); }
GLF rc_glBindBufferBase(JNIEnv*, jclass, jint a0, jint a1, jint a2){ glBindBufferBase(a0,a1,a2); }
GLF rc_glBindFramebuffer(JNIEnv*, jclass, jint a0, jint a1){ glBindFramebuffer(a0,a1); }
GLF rc_glBindRenderbuffer(JNIEnv*, jclass, jint a0, jint a1){ glBindRenderbuffer(a0,a1); }
GLF rc_glBindTexture(JNIEnv*, jclass, jint a0, jint a1){ glBindTexture(a0,a1); }
GLF rc_glBindVertexArray(JNIEnv*, jclass, jint a0){ glBindVertexArray(a0); }
GLF rc_glBlendFunc(JNIEnv*, jclass, jint a0, jint a1){ glBlendFunc(a0,a1); }
GLF rc_glBlendFuncSeparate(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3){ glBlendFuncSeparate(a0,a1,a2,a3); }
GLF rc_glBlitFramebuffer(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jint a6, jint a7, jint a8, jint a9){ glBlitFramebuffer(a0,a1,a2,a3,a4,a5,a6,a7,a8,a9); }
GLF rc_glBufferData_J(JNIEnv*, jclass, jint a0, jlong a1, jint a2){ glBufferData((GLenum)a0,(GLsizeiptr)a1,nullptr,(GLenum)a2); }
GLF rc_glBufferData_F(JNIEnv* e, jclass, jint a0, jobject a1, jint a2){ void*d=e->GetDirectBufferAddress(a1); jlong cap=e->GetDirectBufferCapacity(a1); glBufferData((GLenum)a0,(GLsizeiptr)(cap*4),d,(GLenum)a2); }
GLF rc_glBufferData_I(JNIEnv* e, jclass, jint a0, jobject a1, jint a2){ void*d=e->GetDirectBufferAddress(a1); jlong cap=e->GetDirectBufferCapacity(a1); glBufferData((GLenum)a0,(GLsizeiptr)(cap*4),d,(GLenum)a2); }
static jint JNICALL rc_glCheckFramebufferStatus(JNIEnv*, jclass, jint a0){ return (jint)glCheckFramebufferStatus(a0); }
GLF rc_glClear(JNIEnv*, jclass, jint a0){ glClear(a0); }
GLF rc_glClearColor(JNIEnv*, jclass, jfloat a0, jfloat a1, jfloat a2, jfloat a3){ glClearColor(a0,a1,a2,a3); }
GLF rc_glClearDepth(JNIEnv*, jclass, jdouble a0){ glClearDepthf((GLfloat)a0); }              // shim: GLES has only glClearDepthf
GLF rc_glColorMask(JNIEnv*, jclass, jboolean a0, jboolean a1, jboolean a2, jboolean a3){ glColorMask(a0,a1,a2,a3); }
GLF rc_glCompileShader(JNIEnv*, jclass, jint a0){ glCompileShader(a0); }
static jint JNICALL rc_glCreateProgram(JNIEnv*, jclass){ return (jint)glCreateProgram(); }
static jint JNICALL rc_glCreateShader(JNIEnv*, jclass, jint a0){ return (jint)glCreateShader(a0); }
GLF rc_glDeleteBuffers(JNIEnv*, jclass, jint a0){ GLuint x=(GLuint)a0; glDeleteBuffers(1,&x); }
GLF rc_glDeleteFramebuffers(JNIEnv*, jclass, jint a0){ GLuint x=(GLuint)a0; glDeleteFramebuffers(1,&x); }
GLF rc_glDeleteProgram(JNIEnv*, jclass, jint a0){ glDeleteProgram(a0); }
GLF rc_glDeleteRenderbuffers(JNIEnv*, jclass, jint a0){ GLuint x=(GLuint)a0; glDeleteRenderbuffers(1,&x); }
GLF rc_glDeleteShader(JNIEnv*, jclass, jint a0){ glDeleteShader(a0); }
GLF rc_glDeleteTextures(JNIEnv*, jclass, jint a0){ GLuint x=(GLuint)a0; glDeleteTextures(1,&x); }
GLF rc_glDeleteVertexArrays(JNIEnv*, jclass, jint a0){ GLuint x=(GLuint)a0; glDeleteVertexArrays(1,&x); }
GLF rc_glDepthFunc(JNIEnv*, jclass, jint a0){ glDepthFunc(a0); }
GLF rc_glDepthMask(JNIEnv*, jclass, jboolean a0){ glDepthMask(a0); }
GLF rc_glDetachShader(JNIEnv*, jclass, jint a0, jint a1){ glDetachShader(a0,a1); }
GLF rc_glDisable(JNIEnv*, jclass, jint a0){ glDisable(a0); }
GLF rc_glDrawArrays(JNIEnv*, jclass, jint a0, jint a1, jint a2){ glDrawArrays(a0,a1,a2); }
GLF rc_glDrawElements(JNIEnv*, jclass, jint a0, jint a1, jint a2, jlong a3){ glDrawElements(a0,a1,a2,(const void*)(intptr_t)a3); }
GLF rc_glEnable(JNIEnv*, jclass, jint a0){ glEnable(a0); }
GLF rc_glEnableVertexAttribArray(JNIEnv*, jclass, jint a0){ glEnableVertexAttribArray(a0); }
GLF rc_glFlushMappedBufferRange(JNIEnv*, jclass, jint a0, jlong a1, jlong a2){ glFlushMappedBufferRange(a0,(GLintptr)a1,(GLsizeiptr)a2); }
GLF rc_glFramebufferRenderbuffer(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3){ glFramebufferRenderbuffer(a0,a1,a2,a3); }
static jint JNICALL rc_glGenBuffers(JNIEnv*, jclass){ GLuint x=0; glGenBuffers(1,&x); return (jint)x; }
static jint JNICALL rc_glGenFramebuffers(JNIEnv*, jclass){ GLuint x=0; glGenFramebuffers(1,&x); return (jint)x; }
static jint JNICALL rc_glGenRenderbuffers(JNIEnv*, jclass){ GLuint x=0; glGenRenderbuffers(1,&x); return (jint)x; }
static jint JNICALL rc_glGenTextures(JNIEnv*, jclass){ GLuint x=0; glGenTextures(1,&x); return (jint)x; }
static jint JNICALL rc_glGenVertexArrays(JNIEnv*, jclass){ GLuint x=0; glGenVertexArrays(1,&x); return (jint)x; }
GLF rc_glGenerateMipmap(JNIEnv*, jclass, jint a0){ glGenerateMipmap(a0); }
static jint JNICALL rc_glGetError(JNIEnv*, jclass){ return (jint)glGetError(); }
static jfloat JNICALL rc_glGetFloat(JNIEnv*, jclass, jint a0){ GLfloat v=0; glGetFloatv(a0,&v); return v; }
static jint JNICALL rc_glGetInteger(JNIEnv*, jclass, jint a0){ GLint v=0; glGetIntegerv(a0,&v); return (jint)v; }
static jstring JNICALL rc_glGetProgramInfoLog(JNIEnv* e, jclass, jint a0){ char buf[4096]; GLsizei n=0; glGetProgramInfoLog(a0,sizeof(buf),&n,buf); buf[n<(GLsizei)sizeof(buf)?n:sizeof(buf)-1]=0; return e->NewStringUTF(buf); }
static jint JNICALL rc_glGetProgrami(JNIEnv*, jclass, jint a0, jint a1){ GLint v=0; glGetProgramiv(a0,a1,&v); return (jint)v; }
static jstring JNICALL rc_glGetShaderInfoLog(JNIEnv* e, jclass, jint a0){ char buf[4096]; GLsizei n=0; glGetShaderInfoLog(a0,sizeof(buf),&n,buf); buf[n<(GLsizei)sizeof(buf)?n:sizeof(buf)-1]=0; return e->NewStringUTF(buf); }
static jint JNICALL rc_glGetShaderi(JNIEnv*, jclass, jint a0, jint a1){ GLint v=0; glGetShaderiv(a0,a1,&v); return (jint)v; }
static jstring JNICALL rc_glGetString(JNIEnv* e, jclass, jint a0){ const char* s=(const char*)glGetString((GLenum)a0); return e->NewStringUTF(s?s:""); }
static jint JNICALL rc_glGetUniformBlockIndex(JNIEnv* e, jclass, jint a0, jstring a1){ const char* n=e->GetStringUTFChars(a1,nullptr); jint r=(jint)glGetUniformBlockIndex(a0,n); e->ReleaseStringUTFChars(a1,n); return r; }
static jint JNICALL rc_glGetUniformLocation(JNIEnv* e, jclass, jint a0, jstring a1){ const char* n=e->GetStringUTFChars(a1,nullptr); jint r=(jint)glGetUniformLocation(a0,n); e->ReleaseStringUTFChars(a1,n); return r; }
GLF rc_glLinkProgram(JNIEnv*, jclass, jint a0){ glLinkProgram(a0); }
static jobject JNICALL rc_glMapBuffer(JNIEnv* e, jclass, jint a0, jint a1){
    GLint sz=0; glGetBufferParameteriv((GLenum)a0, GL_BUFFER_SIZE, &sz);
    if (sz<=0) return nullptr;
    GLbitfield acc = GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT;   // glMapBuffer(WRITE_ONLY) -> range equivalent
    void* p = glMapBufferRange((GLenum)a0, 0, sz, acc);
    return p ? rc_native_order(e, e->NewDirectByteBuffer(p, sz)) : nullptr;
}
static jobject JNICALL rc_glMapBufferRange(JNIEnv* e, jclass, jint a0, jlong a1, jlong a2, jint a3, jobject){
    void* p = glMapBufferRange((GLenum)a0,(GLintptr)a1,(GLsizeiptr)a2,(GLbitfield)a3);
    return p ? rc_native_order(e, e->NewDirectByteBuffer(p, a2)) : nullptr;
}
GLF rc_glMultiDrawArrays(JNIEnv* e, jclass, jint a0, jobject a1, jobject a2){
    const GLint* first=(const GLint*)e->GetDirectBufferAddress(a1);
    const GLsizei* count=(const GLsizei*)e->GetDirectBufferAddress(a2);
    GLsizei n=(GLsizei)e->GetDirectBufferCapacity(a1);
    for (GLsizei i=0;i<n && first && count;i++) glDrawArrays((GLenum)a0, first[i], count[i]);   // GLES has no glMultiDrawArrays
}
GLF rc_glReadBuffer(JNIEnv*, jclass, jint a0){ glReadBuffer(a0); }
GLF rc_glReadPixels(JNIEnv* e, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jobject a6){ glReadPixels(a0,a1,a2,a3,(GLenum)a4,(GLenum)a5,e->GetDirectBufferAddress(a6)); }
GLF rc_glRenderbufferStorageMultisample(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4){
    GLenum ifmt=(GLenum)a2; if (ifmt==GL_RGBA) ifmt=GL_RGBA8;   // ES needs a SIZED internalformat
    glRenderbufferStorageMultisample(a0,a1,ifmt,a3,a4);
}
GLF rc_glShaderSource(JNIEnv* e, jclass, jint a0, jstring a1){ const char* s=e->GetStringUTFChars(a1,nullptr); glShaderSource((GLuint)a0,1,&s,nullptr); e->ReleaseStringUTFChars(a1,s); }  // AG2: GLSL 330->ES downport goes here
GLF rc_glTexImage2D(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jint a6, jint a7, jlong a8){
    if ((GLenum)a6==GL_BGRA){ a6=GL_RGBA; glTexImage2D((GLenum)a0,a1,a2,a3,a4,a5,(GLenum)a6,(GLenum)a7,(const void*)(intptr_t)a8);
        glTexParameteri((GLenum)a0, GL_TEXTURE_SWIZZLE_R, GL_BLUE); glTexParameteri((GLenum)a0, GL_TEXTURE_SWIZZLE_B, GL_RED); return; }
    glTexImage2D((GLenum)a0,a1,a2,a3,a4,a5,(GLenum)a6,(GLenum)a7,(const void*)(intptr_t)a8);
}
GLF rc_glTexImage3D(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jint a6, jint a7, jint a8, jlong a9){ glTexImage3D((GLenum)a0,a1,a2,a3,a4,a5,a6,(GLenum)a7,(GLenum)a8,(const void*)(intptr_t)a9); }
GLF rc_glTexParameterf(JNIEnv*, jclass, jint a0, jint a1, jfloat a2){ glTexParameterf(a0,a1,a2); }
GLF rc_glTexParameteri(JNIEnv*, jclass, jint a0, jint a1, jint a2){ glTexParameteri(a0,a1,a2); }
GLF rc_glTexSubImage2D(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jint a6, jint a7, jlong a8){
    if ((GLenum)a6==GL_BGRA) a6=GL_RGBA;
    if ((GLenum)a7==GL_UNSIGNED_INT_8_8_8_8_REV) a7=GL_UNSIGNED_BYTE;
    glTexSubImage2D((GLenum)a0,a1,a2,a3,a4,a5,(GLenum)a6,(GLenum)a7,(const void*)(intptr_t)a8);
}
GLF rc_glTexSubImage3D(JNIEnv* e, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5, jint a6, jint a7, jint a8, jint a9, jobject a10){ glTexSubImage3D((GLenum)a0,a1,a2,a3,a4,a5,a6,a7,(GLenum)a8,(GLenum)a9,e->GetDirectBufferAddress(a10)); }
GLF rc_glUniform1f(JNIEnv*, jclass, jint a0, jfloat a1){ glUniform1f(a0,a1); }
GLF rc_glUniform1i(JNIEnv*, jclass, jint a0, jint a1){ glUniform1i(a0,a1); }
GLF rc_glUniform2fv(JNIEnv* e, jclass, jint a0, jfloatArray a1){ jfloat* p=e->GetFloatArrayElements(a1,nullptr); jsize n=e->GetArrayLength(a1); glUniform2fv(a0,n/2,p); e->ReleaseFloatArrayElements(a1,p,JNI_ABORT); }
GLF rc_glUniform2i(JNIEnv*, jclass, jint a0, jint a1, jint a2){ glUniform2i(a0,a1,a2); }
GLF rc_glUniform3i(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3){ glUniform3i(a0,a1,a2,a3); }
GLF rc_glUniform4f(JNIEnv*, jclass, jint a0, jfloat a1, jfloat a2, jfloat a3, jfloat a4){ glUniform4f(a0,a1,a2,a3,a4); }
GLF rc_glUniform4i(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4){ glUniform4i(a0,a1,a2,a3,a4); }
GLF rc_glUniformBlockBinding(JNIEnv*, jclass, jint a0, jint a1, jint a2){ glUniformBlockBinding(a0,a1,a2); }
GLF rc_glUniformMatrix4fv(JNIEnv* e, jclass, jint a0, jboolean a1, jfloatArray a2){ jfloat* p=e->GetFloatArrayElements(a2,nullptr); jsize n=e->GetArrayLength(a2); glUniformMatrix4fv(a0,n/16,a1,p); e->ReleaseFloatArrayElements(a2,p,JNI_ABORT); }
static jboolean JNICALL rc_glUnmapBuffer(JNIEnv*, jclass, jint a0){ return (jboolean)glUnmapBuffer(a0); }
GLF rc_glUseProgram(JNIEnv*, jclass, jint a0){ glUseProgram(a0); }
GLF rc_glValidateProgram(JNIEnv*, jclass, jint a0){ glValidateProgram(a0); }
GLF rc_glVertexAttribIPointer(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jlong a4){ glVertexAttribIPointer(a0,a1,(GLenum)a2,a3,(const void*)(intptr_t)a4); }
GLF rc_glVertexAttribPointer(JNIEnv*, jclass, jint a0, jint a1, jint a2, jboolean a3, jint a4, jlong a5){ glVertexAttribPointer(a0,a1,(GLenum)a2,a3,a4,(const void*)(intptr_t)a5); }
GLF rc_glViewport(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3){ glViewport(a0,a1,a2,a3); }
GLF rc_glTexStorage3D(JNIEnv*, jclass, jint a0, jint a1, jint a2, jint a3, jint a4, jint a5){ glTexStorage3D(a0,a1,a2,a3,a4,a5); }
GLF rc_glDebugMessageControl(JNIEnv*, jclass, jint, jint, jint, jint, jboolean){ /* KHR_debug optional; no-op */ }
GLF rc_glClipControl(JNIEnv*, jclass, jint a0, jint a1){
    if (!pglClipControlEXT) pglClipControlEXT = (PFNGLCLIPCONTROLEXTPROC) eglGetProcAddress("glClipControlEXT");
    if (pglClipControlEXT) pglClipControlEXT((GLenum)a0, (GLenum)a1);   // reverse-Z if EXT_clip_control; else GEQUAL fallback (AG2)
}

// ---- AWTContext (kAWT) ---------------------------------------------------------------------------------------
static void JNICALL rc_AWT_configurePixelFormat(JNIEnv*, jobject, jint, jint, jint){ /* FBO format fixed in createGLContext */ }
static void JNICALL rc_AWT_createGLContext(JNIEnv*, jobject){
    int w = 512, h = 512;   // L1 default (no engine canvas); AG2 queries JavaBridge.canvasWidth/Height
    LOGI("createGLContext ENTER thread=%p", (void*)pthread_self());
    if (!rcEglPrepareSurface(w, h) || !rcEglCreateContextCurrent()) { LOGE("createGLContext: EGL bring-up FAILED"); return; }
    bool fboOk = rcBuildFbo(w, h);
    LOGI("createGLContext DONE fbo=%u %dx%d complete=%d GL_VERSION=%s", sFbo, sFboW, sFboH, fboOk, (const char*)glGetString(GL_VERSION));
}
static void JNICALL rc_AWT_destroy(JNIEnv*, jobject){
    if (sDpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(sDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (sCtx != EGL_NO_CONTEXT) { eglDestroyContext(sDpy, sCtx); sCtx = EGL_NO_CONTEXT; }
        if (sSurf != EGL_NO_SURFACE) { eglDestroySurface(sDpy, sSurf); sSurf = EGL_NO_SURFACE; }
    }
}
static jint JNICALL rc_AWT_getBufferMode(JNIEnv*, jobject){ return (jint)GL_COLOR_ATTACHMENT0; }
static jint JNICALL rc_AWT_getFramebuffer(JNIEnv*, jobject, jboolean){ return (jint)sFbo; }
static void JNICALL rc_AWT_loadNatives(JNIEnv*, jobject){ /* no-op (the .so is already loaded + registered) */ }
static jint JNICALL rc_AWT_setSwapInterval(JNIEnv*, jobject, jint a0){ return a0; }
static void JNICALL rc_AWT_swapBuffers(JNIEnv*, jobject){ /* AG2: glReadPixels -> JavaBridge publish (offscreen) */ }

// ---- GL.createCapabilities (kGL) -----------------------------------------------------------------------------
static jobject rc_make_caps(JNIEnv* env){
    jclass c = env->FindClass("org/lwjgl/opengl/GLCapabilities");
    if (!c) return nullptr;
    jmethodID ctor = env->GetMethodID(c, "<init>", "()V");
    jobject o = env->NewObject(c, ctor);
    env->SetBooleanField(o, env->GetFieldID(c, "OpenGL33", "Z"), JNI_TRUE);   // REQUIRED or GpuPlugin aborts at :317
    env->SetBooleanField(o, env->GetFieldID(c, "OpenGL45", "Z"), JNI_FALSE);
    env->SetLongField(o, env->GetFieldID(c, "glTexStorage3D", "J"), 1);
    env->SetLongField(o, env->GetFieldID(c, "glDebugMessageControl", "J"), 0);
    jboolean aniso = rc_gl_has_ext("GL_EXT_texture_filter_anisotropic") ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanField(o, env->GetFieldID(c, "GL_EXT_texture_filter_anisotropic", "Z"), aniso);
    return o;
}
static jobject JNICALL rc_GL_createCapabilities(JNIEnv* env, jclass){ return rc_make_caps(env); }
static jobject JNICALL rc_GL_getCapabilities(JNIEnv* env, jclass){ return rc_make_caps(env); }

// ---- RegisterNatives tables (signatures match GLALL.java / AWTContext.java / GL.java exactly) ----------------
static const JNINativeMethod kGLALL[] = {
    {"glActiveTexture","(I)V",(void*)rc_glActiveTexture},
    {"glAttachShader","(II)V",(void*)rc_glAttachShader},
    {"glBindBuffer","(II)V",(void*)rc_glBindBuffer},
    {"glBindBufferBase","(III)V",(void*)rc_glBindBufferBase},
    {"glBindFramebuffer","(II)V",(void*)rc_glBindFramebuffer},
    {"glBindRenderbuffer","(II)V",(void*)rc_glBindRenderbuffer},
    {"glBindTexture","(II)V",(void*)rc_glBindTexture},
    {"glBindVertexArray","(I)V",(void*)rc_glBindVertexArray},
    {"glBlendFunc","(II)V",(void*)rc_glBlendFunc},
    {"glBlendFuncSeparate","(IIII)V",(void*)rc_glBlendFuncSeparate},
    {"glBlitFramebuffer","(IIIIIIIIII)V",(void*)rc_glBlitFramebuffer},
    {"glBufferData","(IJI)V",(void*)rc_glBufferData_J},
    {"glBufferData","(ILjava/nio/FloatBuffer;I)V",(void*)rc_glBufferData_F},
    {"glBufferData","(ILjava/nio/IntBuffer;I)V",(void*)rc_glBufferData_I},
    {"glCheckFramebufferStatus","(I)I",(void*)rc_glCheckFramebufferStatus},
    {"glClear","(I)V",(void*)rc_glClear},
    {"glClearColor","(FFFF)V",(void*)rc_glClearColor},
    {"glClearDepth","(D)V",(void*)rc_glClearDepth},
    {"glColorMask","(ZZZZ)V",(void*)rc_glColorMask},
    {"glCompileShader","(I)V",(void*)rc_glCompileShader},
    {"glCreateProgram","()I",(void*)rc_glCreateProgram},
    {"glCreateShader","(I)I",(void*)rc_glCreateShader},
    {"glDeleteBuffers","(I)V",(void*)rc_glDeleteBuffers},
    {"glDeleteFramebuffers","(I)V",(void*)rc_glDeleteFramebuffers},
    {"glDeleteProgram","(I)V",(void*)rc_glDeleteProgram},
    {"glDeleteRenderbuffers","(I)V",(void*)rc_glDeleteRenderbuffers},
    {"glDeleteShader","(I)V",(void*)rc_glDeleteShader},
    {"glDeleteTextures","(I)V",(void*)rc_glDeleteTextures},
    {"glDeleteVertexArrays","(I)V",(void*)rc_glDeleteVertexArrays},
    {"glDepthFunc","(I)V",(void*)rc_glDepthFunc},
    {"glDepthMask","(Z)V",(void*)rc_glDepthMask},
    {"glDetachShader","(II)V",(void*)rc_glDetachShader},
    {"glDisable","(I)V",(void*)rc_glDisable},
    {"glDrawArrays","(III)V",(void*)rc_glDrawArrays},
    {"glDrawElements","(IIIJ)V",(void*)rc_glDrawElements},
    {"glEnable","(I)V",(void*)rc_glEnable},
    {"glEnableVertexAttribArray","(I)V",(void*)rc_glEnableVertexAttribArray},
    {"glFlushMappedBufferRange","(IJJ)V",(void*)rc_glFlushMappedBufferRange},
    {"glFramebufferRenderbuffer","(IIII)V",(void*)rc_glFramebufferRenderbuffer},
    {"glGenBuffers","()I",(void*)rc_glGenBuffers},
    {"glGenFramebuffers","()I",(void*)rc_glGenFramebuffers},
    {"glGenRenderbuffers","()I",(void*)rc_glGenRenderbuffers},
    {"glGenTextures","()I",(void*)rc_glGenTextures},
    {"glGenVertexArrays","()I",(void*)rc_glGenVertexArrays},
    {"glGenerateMipmap","(I)V",(void*)rc_glGenerateMipmap},
    {"glGetError","()I",(void*)rc_glGetError},
    {"glGetFloat","(I)F",(void*)rc_glGetFloat},
    {"glGetInteger","(I)I",(void*)rc_glGetInteger},
    {"glGetProgramInfoLog","(I)Ljava/lang/String;",(void*)rc_glGetProgramInfoLog},
    {"glGetProgrami","(II)I",(void*)rc_glGetProgrami},
    {"glGetShaderInfoLog","(I)Ljava/lang/String;",(void*)rc_glGetShaderInfoLog},
    {"glGetShaderi","(II)I",(void*)rc_glGetShaderi},
    {"glGetString","(I)Ljava/lang/String;",(void*)rc_glGetString},
    {"glGetUniformBlockIndex","(ILjava/lang/CharSequence;)I",(void*)rc_glGetUniformBlockIndex},
    {"glGetUniformLocation","(ILjava/lang/CharSequence;)I",(void*)rc_glGetUniformLocation},
    {"glLinkProgram","(I)V",(void*)rc_glLinkProgram},
    {"glMapBuffer","(II)Ljava/nio/ByteBuffer;",(void*)rc_glMapBuffer},
    {"glMapBufferRange","(IJJILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;",(void*)rc_glMapBufferRange},
    {"glMultiDrawArrays","(ILjava/nio/IntBuffer;Ljava/nio/IntBuffer;)V",(void*)rc_glMultiDrawArrays},
    {"glReadBuffer","(I)V",(void*)rc_glReadBuffer},
    {"glReadPixels","(IIIIIILjava/nio/ByteBuffer;)V",(void*)rc_glReadPixels},
    {"glRenderbufferStorageMultisample","(IIIII)V",(void*)rc_glRenderbufferStorageMultisample},
    {"glShaderSource","(ILjava/lang/CharSequence;)V",(void*)rc_glShaderSource},
    {"glTexImage2D","(IIIIIIIIJ)V",(void*)rc_glTexImage2D},
    {"glTexImage3D","(IIIIIIIIIJ)V",(void*)rc_glTexImage3D},
    {"glTexParameterf","(IIF)V",(void*)rc_glTexParameterf},
    {"glTexParameteri","(III)V",(void*)rc_glTexParameteri},
    {"glTexSubImage2D","(IIIIIIIIJ)V",(void*)rc_glTexSubImage2D},
    {"glTexSubImage3D","(IIIIIIIIIILjava/nio/ByteBuffer;)V",(void*)rc_glTexSubImage3D},
    {"glUniform1f","(IF)V",(void*)rc_glUniform1f},
    {"glUniform1i","(II)V",(void*)rc_glUniform1i},
    {"glUniform2fv","(I[F)V",(void*)rc_glUniform2fv},
    {"glUniform2i","(III)V",(void*)rc_glUniform2i},
    {"glUniform3i","(IIII)V",(void*)rc_glUniform3i},
    {"glUniform4f","(IFFFF)V",(void*)rc_glUniform4f},
    {"glUniform4i","(IIIII)V",(void*)rc_glUniform4i},
    {"glUniformBlockBinding","(III)V",(void*)rc_glUniformBlockBinding},
    {"glUniformMatrix4fv","(IZ[F)V",(void*)rc_glUniformMatrix4fv},
    {"glUnmapBuffer","(I)Z",(void*)rc_glUnmapBuffer},
    {"glUseProgram","(I)V",(void*)rc_glUseProgram},
    {"glValidateProgram","(I)V",(void*)rc_glValidateProgram},
    {"glVertexAttribIPointer","(IIIIJ)V",(void*)rc_glVertexAttribIPointer},
    {"glVertexAttribPointer","(IIIZIJ)V",(void*)rc_glVertexAttribPointer},
    {"glViewport","(IIII)V",(void*)rc_glViewport},
    {"glTexStorage3D","(IIIIII)V",(void*)rc_glTexStorage3D},
    {"glDebugMessageControl","(IIIIZ)V",(void*)rc_glDebugMessageControl},
    {"glClipControl","(II)V",(void*)rc_glClipControl},
};
static const JNINativeMethod kAWT[] = {
    {"configurePixelFormat","(III)V",(void*)rc_AWT_configurePixelFormat},
    {"createGLContext","()V",(void*)rc_AWT_createGLContext},
    {"destroy","()V",(void*)rc_AWT_destroy},
    {"getBufferMode","()I",(void*)rc_AWT_getBufferMode},
    {"getFramebuffer","(Z)I",(void*)rc_AWT_getFramebuffer},
    {"loadNatives","()V",(void*)rc_AWT_loadNatives},
    {"setSwapInterval","(I)I",(void*)rc_AWT_setSwapInterval},
    {"swapBuffers","()V",(void*)rc_AWT_swapBuffers},
};
static const JNINativeMethod kGL[] = {
    {"createCapabilities","()Lorg/lwjgl/opengl/GLCapabilities;",(void*)rc_GL_createCapabilities},
    {"getCapabilities","()Lorg/lwjgl/opengl/GLCapabilities;",(void*)rc_GL_getCapabilities},
};

// Called by the host (jvm_bootstrap) once after JNI_CreateJavaVM, before the gpu plugin / smoke runs, with the
// facade classes on the classpath. Returns JNI_OK (0) on success.
extern "C" jint RouteC_registerGLNatives(JNIEnv *env) {
    jclass glall = env->FindClass("org/lwjgl/opengl/GLALL");
    jclass awt = env->FindClass("net/runelite/rlawt/AWTContext");
    jclass gl = env->FindClass("org/lwjgl/opengl/GL");
    if (!glall || !awt || !gl) { LOGE("FindClass FAIL glall=%p awt=%p gl=%p", glall, awt, gl); if (env->ExceptionCheck()) env->ExceptionClear(); return JNI_ERR; }
    jint rg = env->RegisterNatives(glall, kGLALL, (jint)(sizeof(kGLALL)/sizeof(kGLALL[0])));
    jint ra = env->RegisterNatives(awt, kAWT, (jint)(sizeof(kAWT)/sizeof(kAWT[0])));
    jint rl = env->RegisterNatives(gl, kGL, (jint)(sizeof(kGL)/sizeof(kGL[0])));
    LOGI("RegisterNatives glall=%d awt=%d gl=%d (0=OK)", rg, ra, rl);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return (rg < 0 || ra < 0 || rl < 0) ? JNI_ERR : JNI_OK;
}
