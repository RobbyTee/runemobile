// Native JNI Invocation-API bootstrap + render/input host for the embedded HotSpot VM.
//
// The app process is ART's. Here we dlopen the embedded JRE's libjvm.so and call JNI_CreateJavaVM to
// stand up a SECOND, in-process HotSpot VM (the one that runs RuneLite). This mirrors the iOS bootstrap
// (ios/app/HelloShell/JVMLauncher.m) but drops every iOS-JIT workaround: Android grants JIT for free, so
// there is no MirrorMappedCodeCache, no brk #0x69 debugger mirror, no exception-port juggling.
//
// Milestone B host model:
//   * The VM is created on a dedicated, never-exiting native thread (the HotSpot "main" thread). It then
//     runs DeviceHeadlessBoot.main, which boots headless RuneLite under CACIO CTC and keeps the engine
//     running on its own threads. We do NOT DestroyJavaVM — the process owns teardown.
//   * The net.runelite.client.headless.JavaBridge facade (cached as HotSpot global refs at boot) is the
//     one Java seam. A render thread pulls JavaBridge.getFramePixels() each refresh and blits to the
//     SurfaceView's ANativeWindow; UI touches are enqueued (no ART->HotSpot attach) and drained by the
//     render thread, which is the only thread that calls into the bridge.

#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <string>
#include <vector>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <algorithm>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#ifndef JNI_VERSION_21
#define JNI_VERSION_21 0x00150000
#endif

#define LOG_TAG "jvm-boot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

// ---- Process-global VM + bridge state ----------------------------------------------------------------
// g_vm is the single in-process HotSpot VM, published once JNI_CreateJavaVM succeeds. A JNIEnv* is NEVER
// shared across threads; every thread that needs one daemon-attaches itself (see bridgeEnvAttach).
static JavaVM *g_vm = nullptr;

// JavaBridge facade, cached as HotSpot global refs at boot (never FindClass on the hot path).
static jclass    g_bridgeCls = nullptr;
static jmethodID g_midSeq = nullptr, g_midW = nullptr, g_midH = nullptr, g_midPixels = nullptr;
static jmethodID g_midTouch = nullptr, g_midClick = nullptr, g_midCamera = nullptr, g_midClearHover = nullptr;

// ---- Surface / render --------------------------------------------------------------------------------
static std::mutex      g_winLock;
static ANativeWindow  *g_window = nullptr;     // SurfaceView's window (acquired); guarded by g_winLock
static pthread_t       g_renderThread;
static bool            g_renderStarted = false;
static volatile bool   g_renderRun = false;
// Last frame dims the render thread saw — read by the UI thread to map touch coords to game space.
// std::atomic (not plain volatile): cross-thread publication needs real atomics under the C++ memory
// model. Two separate atomics suffice — the login canvas dims are fixed, so a torn {w,h} pair (which
// would only matter mid-resize) can't arise in this scope.
static std::atomic<int> g_lastFrameW{0};
static std::atomic<int> g_lastFrameH{0};

// ---- Input queue (UI thread enqueues; render thread drains and dispatches into the bridge) -----------
enum InputKind { IN_CLICK = 0, IN_TOUCH = 1, IN_CAMERA = 2, IN_CLEARHOVER = 3 };
struct InputEvent { int kind, a, b, c, d; };
static std::mutex             g_inLock;
static std::deque<InputEvent> g_inQueue;

// ---- VM bringup latch (startJvm waits until the worker has created the VM + published the bridge) -----
static std::mutex              g_bootLock;
static std::condition_variable g_bootCv;
static bool                    g_bootDone = false;
static jint                    g_bootRc = -100;

// Heap-owned args passed to the worker thread (the ART JNIEnv can't be used off the calling thread).
struct BootArgs {
    std::string javaHome, libjvmPath, libraryPath, classpath, mainClass;
    std::vector<std::string> extraOpts;
};

// The embedded VM writes System.out/err to fd 1/2, which Android drops. Pipe both into logcat so RuneLite
// (and the Milestone-A Hello) output is visible. Robust against partial setup: closes the spare write fd
// after dup2 and bails cleanly (no reader-less pipe that could block writers) if anything fails.
static int g_stdioReadFd = -1;
static void *pumpStdio(void *) {
    char buf[1024];
    ssize_t n;
    while (true) {
        n = read(g_stdioReadFd, buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            __android_log_print(ANDROID_LOG_INFO, "jvm-stdout", "%s", buf);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    close(g_stdioReadFd);
    g_stdioReadFd = -1;
    return nullptr;
}
static void redirectStdioToLogcat() {
    static bool done = false;
    if (done) return;
    int pipefd[2];
    if (pipe(pipefd) != 0) { LOGE("stdio: pipe failed errno=%d", errno); return; }
    // Save the originals so fd 1/2 can be restored if setup fails partway: a reader-less pipe left on
    // stdout/err would raise SIGPIPE on the next write and can take the process down.
    int oldOut = dup(STDOUT_FILENO);
    int oldErr = dup(STDERR_FILENO);
    auto restoreStdio = [&] {
        if (oldOut >= 0) { dup2(oldOut, STDOUT_FILENO); close(oldOut); oldOut = -1; }
        if (oldErr >= 0) { dup2(oldErr, STDERR_FILENO); close(oldErr); oldErr = -1; }
    };
    if (dup2(pipefd[1], STDOUT_FILENO) < 0 || dup2(pipefd[1], STDERR_FILENO) < 0) {
        LOGE("stdio: dup2 failed errno=%d", errno);
        restoreStdio();
        close(pipefd[0]); close(pipefd[1]);
        return;
    }
    close(pipefd[1]);                 // the dup'd copies on fd 1/2 are the only writers now
    g_stdioReadFd = pipefd[0];
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pthread_t t;
    if (pthread_create(&t, nullptr, pumpStdio, nullptr) != 0) {
        LOGE("stdio: pthread_create failed errno=%d", errno);
        restoreStdio();               // point fd 1/2 back at the originals so writes don't hit a dead pipe
        close(g_stdioReadFd); g_stdioReadFd = -1;
        return;
    }
    pthread_detach(t);
    if (oldOut >= 0) close(oldOut);   // success: the saved originals are no longer needed
    if (oldErr >= 0) close(oldErr);
    done = true;
}

// HotSpot dlopens lib/*.so by absolute path, but the linker resolves each .so's DT_NEEDED siblings
// (libnio.so NEEDs libnet.so, libawt NEEDs libfontmanager, etc.) via the namespace's search paths — which
// DON'T include the JRE lib dir. Preload every lib/*.so with RTLD_GLOBAL up front so those transitive
// NEEDED libs resolve by soname. Multi-pass (loop until no progress) to absorb intra-dir ordering.
static void preloadOneDir(const std::string &libDir) {
    std::vector<std::string> pending;
    DIR *d = opendir(libDir.c_str());
    if (!d) { LOGE("preload: opendir %s failed", libDir.c_str()); return; }
    struct dirent *e;
    while ((e = readdir(d)) != nullptr) {
        std::string name = e->d_name;
        if (name.size() < 4 || name.compare(name.size() - 3, 3, ".so") != 0) continue;
        if (name == "libjvm.so") continue;   // dlopen'd explicitly by absolute path
        pending.push_back(libDir + "/" + name);
    }
    closedir(d);

    while (!pending.empty()) {
        std::vector<std::string> still;
        for (const std::string &path : pending) {
            if (dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL)) continue;
            still.push_back(path);
        }
        if (still.size() == pending.size()) {  // no progress this pass -> unresolvable, report and stop
            for (const std::string &path : still) LOGI("preload: deferred %s (%s)", path.c_str(), dlerror());
            break;
        }
        pending.swap(still);
    }
}
// java.library.path may be a ':'-joined list; preload every directory in it.
static void preloadJreLibs(const std::string &libraryPath) {
    size_t start = 0;
    while (start <= libraryPath.size()) {
        size_t end = libraryPath.find(':', start);
        std::string dir = libraryPath.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (!dir.empty()) preloadOneDir(dir);
        if (end == std::string::npos) break;
        start = end + 1;
    }
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
static std::string toInternal(std::string name) {
    for (char &ch : name) if (ch == '.') ch = '/';
    return name;
}

// GetEnv-or-daemon-attach for the calling thread. Daemon attach means we never need to detach (the VM
// won't block on these threads at shutdown, and the process owns teardown). Returns nullptr on failure.
static JNIEnv *bridgeEnvAttach() {
    if (!g_vm) return nullptr;
    JNIEnv *env = nullptr;
    jint r = g_vm->GetEnv((void **)&env, JNI_VERSION_21);
    if (r == JNI_OK && env) return env;
    if (r == JNI_EDETACHED && g_vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// Resolve + cache the JavaBridge facade as HotSpot global refs. Called once, on the VM's main thread, at
// boot. Returns true once every method id resolved.
static bool publishBridge(JNIEnv *env) {
    jclass local = env->FindClass("net/runelite/client/headless/JavaBridge");
    if (!local || env->ExceptionCheck()) { env->ExceptionClear(); LOGE("bridge: FindClass JavaBridge failed"); return false; }
    jclass global = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    if (!global) return false;
    g_midSeq        = env->GetStaticMethodID(global, "getFrameSeq", "()I");
    g_midW          = env->GetStaticMethodID(global, "getFrameWidth", "()I");
    g_midH          = env->GetStaticMethodID(global, "getFrameHeight", "()I");
    g_midPixels     = env->GetStaticMethodID(global, "getFramePixels", "()[I");
    g_midTouch      = env->GetStaticMethodID(global, "onTouch", "(IIII)V");
    g_midClick      = env->GetStaticMethodID(global, "onClick", "(III)V");
    g_midCamera     = env->GetStaticMethodID(global, "onCameraDrag", "(II)V");
    g_midClearHover = env->GetStaticMethodID(global, "clearHover", "()V");
    if (!g_midSeq || !g_midW || !g_midH || !g_midPixels || !g_midTouch || !g_midClick
        || !g_midCamera || !g_midClearHover) {
        env->ExceptionClear();
        env->DeleteGlobalRef(global);
        LOGE("bridge: a JavaBridge method id failed to resolve");
        return false;
    }
    g_bridgeCls = global;   // publish only once fully resolved
    LOGI("bridge: JavaBridge facade published");
    return true;
}

// Call a no-arg static void method by (FindClass) name. Used to install CACIO CTC before any AWT touch.
static bool callStaticVoidNoArg(JNIEnv *env, const char *cls, const char *method) {
    jclass c = env->FindClass(cls);
    if (!c || env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); LOGE("call: FindClass %s failed", cls); return false; }
    jmethodID m = env->GetStaticMethodID(c, method, "()V");
    if (!m || env->ExceptionCheck()) {
        env->ExceptionDescribe(); env->ExceptionClear(); LOGE("call: no %s.%s()V", cls, method);
        env->DeleteLocalRef(c);
        return false;
    }
    env->CallStaticVoidMethod(c, m);
    bool ok = !env->ExceptionCheck();
    if (!ok) { env->ExceptionDescribe(); env->ExceptionClear(); LOGE("call: %s.%s threw", cls, method); }
    env->DeleteLocalRef(c);
    return ok;
}

// Drain the input queue and dispatch each event into the bridge (called from the render thread, which is
// HotSpot-attached). The UI thread only ever pushes onto the queue, so it never attaches to HotSpot.
static void drainInput(JNIEnv *env) {
    std::deque<InputEvent> batch;
    {
        std::lock_guard<std::mutex> lk(g_inLock);
        batch.swap(g_inQueue);
    }
    for (const InputEvent &ev : batch) {
        switch (ev.kind) {
            case IN_CLICK:      env->CallStaticVoidMethod(g_bridgeCls, g_midClick, ev.a, ev.b, ev.c); break;
            case IN_TOUCH:      env->CallStaticVoidMethod(g_bridgeCls, g_midTouch, ev.a, ev.b, ev.c, ev.d); break;
            case IN_CAMERA:     env->CallStaticVoidMethod(g_bridgeCls, g_midCamera, ev.a, ev.b); break;
            case IN_CLEARHOVER: env->CallStaticVoidMethod(g_bridgeCls, g_midClearHover); break;
            default: break;
        }
        if (env->ExceptionCheck()) {
            LOGE("drainInput: exception from bridge dispatch (kind=%d)", ev.kind);
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
}

// Blit the latest complete JavaBridge frame (TYPE_INT_RGB, 0x00RRGGBB ints) to the SurfaceView window,
// swapping R<->B into RGBA_8888 (the only widely-available ANativeWindow format). Skips the copy when the
// engine hasn't composited a new frame since the last tick.
static void *renderLoop(void *) {
    JNIEnv *env = nullptr;
    if (g_vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK || !env) {
        LOGE("render: attach failed"); return nullptr;
    }
    int lastSeq = -1, geomW = -1, geomH = -1;
    while (g_renderRun) {
        if (g_bridgeCls) drainInput(env);

        ANativeWindow *win = nullptr;
        {
            std::lock_guard<std::mutex> lk(g_winLock);
            win = g_window;
            if (win) ANativeWindow_acquire(win);
        }
        if (!win || !g_bridgeCls) { if (win) ANativeWindow_release(win); usleep(16000); continue; }

        int seq = env->CallStaticIntMethod(g_bridgeCls, g_midSeq);
        if (env->ExceptionCheck()) { env->ExceptionClear(); seq = lastSeq; }
        if (seq != lastSeq) {
            int w = env->CallStaticIntMethod(g_bridgeCls, g_midW);
            int h = env->CallStaticIntMethod(g_bridgeCls, g_midH);
            if (env->ExceptionCheck()) { env->ExceptionClear(); w = h = 0; }
            if (w > 0 && h > 0 && w <= 16384 && h <= 16384) {
                g_lastFrameW.store(w, std::memory_order_relaxed);
                g_lastFrameH.store(h, std::memory_order_relaxed);
                jintArray arr = (jintArray) env->CallStaticObjectMethod(g_bridgeCls, g_midPixels);
                if (env->ExceptionCheck()) { env->ExceptionClear(); arr = nullptr; }
                if (arr) {
                    jsize len = env->GetArrayLength(arr);
                    const int64_t needed = (int64_t) w * h;   // w,h capped above, so this can't overflow
                    if ((int64_t) len >= needed) {
                        if (w != geomW || h != geomH) {
                            ANativeWindow_setBuffersGeometry(win, w, h, WINDOW_FORMAT_RGBA_8888);
                            geomW = w; geomH = h;
                        }
                        ANativeWindow_Buffer wb;
                        if (ANativeWindow_lock(win, &wb, nullptr) == 0) {
                            jint *px = env->GetIntArrayElements(arr, nullptr);
                            if (px) {
                                const int cw = std::min(w, wb.width);
                                const int ch = std::min(h, wb.height);
                                uint32_t *dst = (uint32_t *) wb.bits;
                                for (int y = 0; y < ch; y++) {
                                    uint32_t *drow = dst + (size_t) y * wb.stride;
                                    const jint *srow = px + (size_t) y * w;
                                    for (int x = 0; x < cw; x++) {
                                        uint32_t s = (uint32_t) srow[x];   // 0x00RRGGBB
                                        drow[x] = 0xFF000000u | ((s & 0xFFu) << 16) | (s & 0xFF00u) | ((s >> 16) & 0xFFu);
                                    }
                                }
                                env->ReleaseIntArrayElements(arr, px, JNI_ABORT);
                            }
                            ANativeWindow_unlockAndPost(win);
                        }
                    }
                    env->DeleteLocalRef(arr);
                }
            }
            lastSeq = seq;
        }
        ANativeWindow_release(win);
        usleep(16000);   // ~60 Hz cap; the seq check makes an idle login screen nearly free
    }
    return nullptr;
}

static void startRenderThreadOnce() {
    if (g_renderStarted) return;
    g_renderRun = true;
    if (pthread_create(&g_renderThread, nullptr, renderLoop, nullptr) == 0) {
        g_renderStarted = true;
        LOGI("render: thread started");
    } else {
        g_renderRun = false;
        LOGE("render: pthread_create failed errno=%d", errno);
    }
}

// The VM's main thread: create the VM here and NEVER exit (so HotSpot's main thread stays alive and the VM
// is not torn down). After publishing the bridge it runs DeviceHeadlessBoot.main, which boots headless
// RuneLite and keeps the engine running on its own threads.
static void *jvmThreadMain(void *argp) {
    BootArgs *a = (BootArgs *) argp;

    auto fail = [&](jint rc) {
        { std::lock_guard<std::mutex> lk(g_bootLock); g_bootDone = true; g_bootRc = rc; }
        g_bootCv.notify_all();
        delete a;
        return (void *) nullptr;
    };

    LOGI("dlopen %s", a->libjvmPath.c_str());
    void *h = dlopen(a->libjvmPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!h) { LOGE("dlopen libjvm failed: %s", dlerror()); return fail(-10); }
    auto JNI_CreateJavaVM = (CreateJavaVM_t) dlsym(h, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror()); return fail(-11); }

    preloadJreLibs(a->libraryPath);

    std::vector<std::string> opts;
    opts.push_back("-Djava.class.path=" + a->classpath);
    opts.push_back("-Djava.library.path=" + a->libraryPath);
    opts.push_back("-Djava.home=" + a->javaHome);
    for (const std::string &o : a->extraOpts) opts.push_back(o);

    std::vector<JavaVMOption> vmOpts(opts.size());
    for (size_t i = 0; i < opts.size(); i++) {
        vmOpts[i].optionString = const_cast<char *>(opts[i].c_str());
        vmOpts[i].extraInfo = nullptr;
        LOGI("opt[%zu] = %s", i, opts[i].c_str());
    }
    JavaVMInitArgs vmArgs{};
    vmArgs.version = JNI_VERSION_21;
    vmArgs.nOptions = (jint) vmOpts.size();
    vmArgs.options = vmOpts.data();
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = nullptr;
    JNIEnv *env = nullptr;
    LOGI("JNI_CreateJavaVM…");
    jint rc = JNI_CreateJavaVM(&vm, (void **) &env, &vmArgs);
    if (rc != JNI_OK || !vm || !env) { LOGE("JNI_CreateJavaVM rc=%d", rc); return fail(rc ? rc : -12); }
    g_vm = vm;
    LOGI("HotSpot VM up; activating CACIO CTC before any AWT/bridge class loads");

    // CRITICAL ordering: install CACIO's CTC toolkit BEFORE anything touches java.awt.* — otherwise
    // JavaBridge.<clinit> (it builds a java.awt.Color) drags in java.awt.Toolkit.<clinit>, which on this
    // bionic JRE tries to dlopen the default xawt backend (libawt_xawt.so, absent) and dies. iOS dodges
    // this by running activate() at the very top of DeviceHeadlessBoot.main and resolving the bridge late;
    // we mirror that here so the eager publishBridge below is safe. (DeviceHeadlessBoot.main re-activates —
    // activate() is idempotent.)
    if (!callStaticVoidNoArg(env, "UnsafePokeActivation", "activate")) {
        LOGE("CACIO activation failed; aborting boot (AWT would crash on xawt)");
        return fail(-13);
    }
    LOGI("CACIO CTC active; publishing bridge + starting render");

    if (!publishBridge(env)) {   // JavaBridge is essential for Milestone B — a null bridge is a dead render
        LOGE("bridge publish failed; aborting boot (render + input would be inert)");
        return fail(-14);
    }
    startRenderThreadOnce();

    // Signal startJvm that bringup is complete (VM is up). The worker now continues into the boot main.
    { std::lock_guard<std::mutex> lk(g_bootLock); g_bootDone = true; g_bootRc = JNI_OK; }
    g_bootCv.notify_all();

    const std::string internal = toInternal(a->mainClass);
    jclass cls = env->FindClass(internal.c_str());
    if (!cls || env->ExceptionCheck()) {
        LOGE("FindClass %s failed", internal.c_str()); env->ExceptionDescribe(); env->ExceptionClear();
        delete a; return nullptr;   // VM stays up; bridge/render already live
    }
    jmethodID mid = env->GetStaticMethodID(cls, "main", "([Ljava/lang/String;)V");
    if (!mid || env->ExceptionCheck()) {
        LOGE("no main([String]) on %s", internal.c_str()); env->ExceptionDescribe(); env->ExceptionClear();
        delete a; return nullptr;
    }
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray args = env->NewObjectArray(0, strCls, nullptr);
    LOGI("invoking %s.main", a->mainClass.c_str());
    env->CallStaticVoidMethod(cls, mid, args);
    if (env->ExceptionCheck()) {
        LOGE("%s.main threw", a->mainClass.c_str()); env->ExceptionDescribe(); env->ExceptionClear();
    }
    LOGI("%s.main returned; VM stays alive (engine threads own the run)", a->mainClass.c_str());
    env->DeleteLocalRef(args);   // this thread parks forever below; drop its locals so they don't pin
    env->DeleteLocalRef(strCls);
    env->DeleteLocalRef(cls);
    delete a;
    // Do NOT exit cleanly in a way that detaches the VM main thread: park forever so HotSpot stays up.
    while (true) pause();
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_audaro_helloshell_JvmLauncher_startJvm(
        JNIEnv *artEnv, jobject /*thiz*/,
        jstring jJavaHome, jstring jLibjvmPath, jstring jLibraryPath,
        jstring jClasspath, jstring jMainClass, jobjectArray jExtraOpts) {

    if (g_vm) { LOGI("startJvm: VM already up (ignoring re-entry)"); return JNI_OK; }
    redirectStdioToLogcat();

    auto *a = new BootArgs();
    a->javaHome    = jstr(artEnv, jJavaHome);
    a->libjvmPath  = jstr(artEnv, jLibjvmPath);
    a->libraryPath = jstr(artEnv, jLibraryPath);
    a->classpath   = jstr(artEnv, jClasspath);
    a->mainClass   = jstr(artEnv, jMainClass);
    const jsize nExtra = jExtraOpts ? artEnv->GetArrayLength(jExtraOpts) : 0;
    for (jsize i = 0; i < nExtra; i++) {
        auto s = (jstring) artEnv->GetObjectArrayElement(jExtraOpts, i);
        a->extraOpts.push_back(jstr(artEnv, s));
        artEnv->DeleteLocalRef(s);
    }

    // Reset the bringup latch so the wait below reflects THIS attempt, not a previous failed boot's
    // result (g_bootDone is process-global). startJvm is single-call by construction, but this keeps a
    // retry-after-failure honest instead of immediately returning a stale rc.
    { std::lock_guard<std::mutex> lk(g_bootLock); g_bootDone = false; g_bootRc = -100; }

    pthread_t t;
    if (pthread_create(&t, nullptr, jvmThreadMain, a) != 0) {
        LOGE("startJvm: pthread_create failed errno=%d", errno);
        delete a;
        return -1;
    }
    pthread_detach(t);

    // Block until the worker has created the VM (or failed), so the Kotlin caller gets a real rc.
    std::unique_lock<std::mutex> lk(g_bootLock);
    g_bootCv.wait(lk, [] { return g_bootDone; });
    return g_bootRc;
}

// ---- Surface lifecycle (called from the ART UI thread) -----------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeSetSurface(JNIEnv *env, jobject, jobject surface) {
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    ANativeWindow *old = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_winLock);
        old = g_window;
        g_window = win;   // takes the fromSurface reference
    }
    if (old) ANativeWindow_release(old);
    LOGI("surface %s", win ? "set" : "cleared");
}

// ---- Input ingress (called from the ART UI thread; pushes onto the queue, never attaches to HotSpot) --

static void enqueue(InputEvent ev) {
    std::lock_guard<std::mutex> lk(g_inLock);
    if (g_inQueue.size() < 512) g_inQueue.push_back(ev);   // bound the queue; drop if a stall backs it up
}

extern "C" JNIEXPORT void JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeClick(JNIEnv *, jobject, jint x, jint y, jint button) {
    enqueue({IN_CLICK, x, y, button, 0});
}
extern "C" JNIEXPORT void JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeTouch(JNIEnv *, jobject, jint type, jint x, jint y, jint button) {
    enqueue({IN_TOUCH, type, x, y, button});
}
extern "C" JNIEXPORT void JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeCamera(JNIEnv *, jobject, jint dx, jint dy) {
    enqueue({IN_CAMERA, dx, dy, 0, 0});
}
extern "C" JNIEXPORT void JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeClearHover(JNIEnv *, jobject) {
    enqueue({IN_CLEARHOVER, 0, 0, 0, 0});
}

// Last frame dimensions (so the UI thread can map a screen touch to game-canvas coords). 0 before render.
extern "C" JNIEXPORT jint JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeFrameWidth(JNIEnv *, jobject) { return g_lastFrameW.load(std::memory_order_relaxed); }
extern "C" JNIEXPORT jint JNICALL
Java_dev_audaro_helloshell_JvmLauncher_nativeFrameHeight(JNIEnv *, jobject) { return g_lastFrameH.load(std::memory_order_relaxed); }
