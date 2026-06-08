package dev.audaro.glesrecon

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * Track G AG0 — GLES tier recon on a REAL device (the emulator is ANGLE→SwiftShader and INVALID for this). On
 * launch it brings up a headless EGL ES3 context (highest of 3.2/3.1/3.0 the driver gives) and queries the
 * facts the GPU track rides on: ES tier, compute (GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS), EXT_clip_control
 * (reverse-Z), EXT_texture_format_BGRA8888, GL_MAX_SAMPLES (MSAA), RGBA16UI 2D-array color-renderability,
 * DEPTH_COMPONENT24/32F renderability, and an offscreen FBO + glReadPixels round-trip. Logs `ANDROID_AG0 …` +
 * a VERDICT to logcat — gated by android/dev-tools/firebase/fb-gate.sh recon. Pure Kotlin (android.opengl.*):
 * no embedded JVM, no NDK, so Firebase Robo runs it by just launching the default activity.
 */
class ReconActivity : Activity() {

    private val tag = "AG0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "gles-recon") { runRecon() }
    }

    private fun runRecon() {
        var dpy: EGLDisplay? = null
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (dpy == EGL14.EGL_NO_DISPLAY) { fail("eglGetDisplay -> NO_DISPLAY"); return }
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { fail("eglInitialize failed 0x${err()}"); return }

            // ES3-renderable pbuffer config (RGBA8 + DEPTH24).
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040 /* EGL_OPENGL_ES3_BIT_KHR */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val nCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] < 1) { fail("eglChooseConfig failed"); return }

            // Request the HIGHEST ES context the driver supports: 3.2 -> 3.1 -> 3.0 (EGL_BAD_MATCH falls through).
            val EGL_CONTEXT_MINOR_VERSION = 0x30FB
            // EGL14 has no EGL_CONTEXT_MAJOR_VERSION constant; it's the same value as EGL_CONTEXT_CLIENT_VERSION.
            var reqMinor = 2
            for (mn in intArrayOf(2, 1, 0)) {
                val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, mn, EGL14.EGL_NONE)
                ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
                if (ctx != EGL14.EGL_NO_CONTEXT) { reqMinor = mn; break }
            }
            if (ctx == EGL14.EGL_NO_CONTEXT) { fail("eglCreateContext failed for ES 3.x 0x${err()}"); return }

            val pbAttr = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], pbAttr, 0)
            if (surf == EGL14.EGL_NO_SURFACE) { fail("eglCreatePbufferSurface failed 0x${err()}"); return }
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { fail("eglMakeCurrent failed 0x${err()}"); return }

            // ---- strings ----
            val glVer = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
            val glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: ""
            val glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
            val glsl = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: ""
            val exts = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            // Parse "OpenGL ES X.Y" (the real context version the driver gave).
            val m = Regex("OpenGL ES (\\d)\\.(\\d)").find(glVer)
            val esMajor = m?.groupValues?.get(1)?.toIntOrNull() ?: 3
            val esMinor = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val esTier = esMajor * 10 + esMinor   // 30 / 31 / 32

            // ---- compute (ES 3.1) ----
            val tmp = IntArray(1)
            drainGlErrors()
            GLES30.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, tmp, 0)
            val computeErr = GLES30.glGetError()
            val maxComputeInvocations = if (computeErr == GLES30.GL_NO_ERROR) tmp[0] else -1
            // Compute work-group count limits (x), if compute present.
            var maxComputeGroupsX = -1
            if (maxComputeInvocations > 0) {
                drainGlErrors()
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, tmp, 0)
                if (GLES30.glGetError() == GLES30.GL_NO_ERROR) maxComputeGroupsX = tmp[0]
            }

            // ---- extensions ----
            val clipControl = exts.contains("GL_EXT_clip_control")
            val bgra = exts.contains("GL_EXT_texture_format_BGRA8888")
            val aniso = exts.contains("GL_EXT_texture_filter_anisotropic")
            val computeShaderExt = exts.contains("GL_ARB_compute_shader") || esTier >= 31

            // ---- MSAA ----
            drainGlErrors()
            GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, tmp, 0); val maxSamples = if (GLES30.glGetError()==0) tmp[0] else -1

            // ---- offscreen RGBA8 FBO + glReadPixels round-trip ----
            val readbackOk = probeReadback()
            // ---- RGBA16UI 2D-array color-renderable (117HD's TBO-replacement atlas) ----
            val rgba16uiArray = probeRgba16uiArray()
            // ---- depth renderbuffer formats ----
            val depth24 = probeDepthRenderbuffer(GLES30.GL_DEPTH_COMPONENT24)
            val depth32f = probeDepthRenderbuffer(GLES30.GL_DEPTH_COMPONENT32F)

            // base `gpu` needs ES≥3.0 (OpenGL33-equiv) + MSAA + readback; the CLEAN 117HD compute path needs ES≥3.1.
            val baseGpuOk = esTier >= 30 && maxSamples >= 1 && readbackOk
            val hd117Clean = esTier >= 31 && maxComputeInvocations > 0
            val verdict = if (baseGpuOk && hd117Clean) "PASS" else if (baseGpuOk) "PASS_BASE_GPU_ONLY" else "FAIL"

            Log.i(tag, "ANDROID_AG0 esTier=$esTier(reqMinor=$reqMinor) GL_VERSION=\"$glVer\" GLSL=\"$glsl\" vendor=\"$glVendor\" renderer=\"$glRenderer\"")
            Log.i(tag, "ANDROID_AG0 compute=${maxComputeInvocations>0} maxComputeInvocations=$maxComputeInvocations maxComputeGroupsX=$maxComputeGroupsX computeShader=$computeShaderExt")
            Log.i(tag, "ANDROID_AG0 clipControl=$clipControl bgra8888=$bgra anisotropic=$aniso maxSamples=$maxSamples")
            Log.i(tag, "ANDROID_AG0 readback=$readbackOk rgba16uiArrayRenderable=$rgba16uiArray depth24=$depth24 depth32f=$depth32f")
            Log.i(tag, "ANDROID_AG0 baseGpuOk=$baseGpuOk hd117CleanComputePath=$hd117Clean")
            Log.i(tag, "ANDROID_AG0 VERDICT=$verdict")
        } catch (t: Throwable) {
            Log.e(tag, "ANDROID_AG0 EXCEPTION $t", t)
            Log.i(tag, "ANDROID_AG0 VERDICT=FAIL")
        } finally {
            if (dpy != null && dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        }
    }

    private fun err() = Integer.toHexString(EGL14.eglGetError())
    private fun drainGlErrors() { var n = 0; while (GLES30.glGetError() != GLES30.GL_NO_ERROR && n < 16) n++ }
    private fun fail(why: String) { Log.e(tag, "ANDROID_AG0 EGL_FAIL $why"); Log.i(tag, "ANDROID_AG0 VERDICT=FAIL") }

    /** Render to an offscreen RGBA8 FBO + read one pixel back (the base-gpu Phase-1 present path). */
    private fun probeReadback(): Boolean {
        return try {
            val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            val rbo = IntArray(1); GLES30.glGenRenderbuffers(1, rbo, 0); GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, 16, 16)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rbo[0])
            val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glClearColor(0.25f, 0.5f, 0.75f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val px = java.nio.ByteBuffer.allocateDirect(16 * 16 * 4)
            GLES30.glReadPixels(0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            val r = px.get(0).toInt() and 0xFF
            GLES30.glDeleteFramebuffers(1, fbo, 0); GLES30.glDeleteRenderbuffers(1, rbo, 0)
            complete && GLES30.glGetError() == GLES30.GL_NO_ERROR && r in 0x30..0x60   // ~0.25*255
        } catch (e: Exception) { false }
    }

    /** RGBA16UI 2D-array, one layer attached to an FBO — color-renderable? (117HD's integer texture-array atlas.) */
    private fun probeRgba16uiArray(): Boolean {
        return try {
            drainGlErrors()
            val tex = IntArray(1); GLES30.glGenTextures(1, tex, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[0])
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RGBA16UI, 16, 16, 2)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) { GLES30.glDeleteTextures(1, tex, 0); return false }
            val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, tex[0], 0, 0)
            val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glDeleteFramebuffers(1, fbo, 0); GLES30.glDeleteTextures(1, tex, 0)
            ok
        } catch (e: Exception) { false }
    }

    private fun probeDepthRenderbuffer(fmt: Int): Boolean {
        return try {
            drainGlErrors()
            val rbo = IntArray(1); GLES30.glGenRenderbuffers(1, rbo, 0); GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, fmt, 16, 16)
            val ok = GLES30.glGetError() == GLES30.GL_NO_ERROR
            GLES30.glDeleteRenderbuffers(1, rbo, 0)
            ok
        } catch (e: Exception) { false }
    }
}
