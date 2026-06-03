// Native JNI Invocation-API bootstrap for the embedded HotSpot VM.
//
// The app process is ART's. Here we dlopen the embedded JRE's libjvm.so and call JNI_CreateJavaVM to
// stand up a SECOND, in-process HotSpot VM (the one that runs RuneLite). This mirrors the iOS bootstrap
// (ios/app/HelloShell/JVMLauncher.m) but drops every iOS-JIT workaround: Android grants JIT for free, so
// there is no MirrorMappedCodeCache, no brk #0x69 debugger mirror, no exception-port juggling.

#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#ifndef JNI_VERSION_21
#define JNI_VERSION_21 0x00150000
#endif

#define LOG_TAG "jvm-boot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

// The embedded VM writes System.out/err to fd 1/2, which Android drops. Pipe both into logcat so RuneLite
// (and the Milestone-A Hello) output is visible.
static int g_stdioReadFd = -1;

static void *pumpStdio(void *) {
    char buf[1024];
    ssize_t n;
    while ((n = read(g_stdioReadFd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        __android_log_print(ANDROID_LOG_INFO, "jvm-stdout", "%s", buf);
    }
    return nullptr;
}

static void redirectStdioToLogcat() {
    static bool done = false;
    if (done) return;
    done = true;
    int pipefd[2];
    if (pipe(pipefd) != 0) return;
    g_stdioReadFd = pipefd[0];
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pthread_t t;
    pthread_create(&t, nullptr, pumpStdio, nullptr);
    pthread_detach(t);
}

// HotSpot dlopens lib/*.so by absolute path (the data dir is on the linker's permitted_path), but the
// linker resolves each .so's DT_NEEDED siblings (libnio.so NEEDs libnet.so, etc.) via the namespace's
// search paths — which DON'T include the JRE lib dir. Preload every lib/*.so with RTLD_GLOBAL up front so
// those transitive NEEDED libs resolve by soname. Multi-pass to absorb arbitrary intra-dir ordering.
static void preloadJreLibs(const std::string &libDir) {
    std::vector<std::string> pending;
    DIR *d = opendir(libDir.c_str());
    if (!d) { LOGE("preload: opendir %s failed", libDir.c_str()); return; }
    struct dirent * e;
    while ((e = readdir(d)) != nullptr) {
        std::string name = e->d_name;
        if (name.size() < 4 || name.compare(name.size() - 3, 3, ".so") != 0) continue;
        // libjvm.so lives under lib/server and is dlopen'd explicitly; skip any stray top-level copy.
        if (name == "libjvm.so") continue;
        pending.push_back(libDir + "/" + name);
    }
    closedir(d);

    for (int pass = 0; pass < 4 && !pending.empty(); pass++) {
        std::vector<std::string> still;
        for (const std::string &path : pending) {
            if (dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL)) continue;
            still.push_back(path);
        }
        if (still.size() == pending.size()) break; // no progress this pass
        pending.swap(still);
    }
    if (!pending.empty()) {
        for (const std::string &path : pending)
            LOGI("preload: deferred %s (%s)", path.c_str(), dlerror());
    }
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Replace '.' with '/' for FindClass (binary name -> internal name).
static std::string toInternal(std::string name) {
    for (char &ch : name) if (ch == '.') ch = '/';
    return name;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_audaro_helloshell_JvmLauncher_startJvm(
        JNIEnv *artEnv, jobject /*thiz*/,
        jstring jJavaHome, jstring jClasspath, jstring jLibraryPath,
        jstring jMainClass, jobjectArray jExtraOpts) {

    redirectStdioToLogcat();

    const std::string javaHome    = jstr(artEnv, jJavaHome);
    const std::string classpath   = jstr(artEnv, jClasspath);
    const std::string libraryPath = jstr(artEnv, jLibraryPath);
    const std::string mainClass   = jstr(artEnv, jMainClass);

    const std::string libjvmPath = javaHome + "/lib/server/libjvm.so";
    LOGI("dlopen %s", libjvmPath.c_str());
    void *h = dlopen(libjvmPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!h) { LOGE("dlopen libjvm failed: %s", dlerror()); return -10; }

    auto JNI_CreateJavaVM = (CreateJavaVM_t) dlsym(h, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror()); return -11; }

    // Resolve sibling NEEDED libs (libnet.so ← libnio.so, etc.) before any JRE .so is lazy-loaded.
    preloadJreLibs(libraryPath);

    std::vector<std::string> opts;
    opts.push_back("-Djava.class.path=" + classpath);
    opts.push_back("-Djava.library.path=" + libraryPath);
    opts.push_back("-Djava.home=" + javaHome);

    const jsize nExtra = jExtraOpts ? artEnv->GetArrayLength(jExtraOpts) : 0;
    for (jsize i = 0; i < nExtra; i++) {
        auto s = (jstring) artEnv->GetObjectArrayElement(jExtraOpts, i);
        opts.push_back(jstr(artEnv, s));
        artEnv->DeleteLocalRef(s);
    }

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
    if (rc != JNI_OK || !vm || !env) { LOGE("JNI_CreateJavaVM rc=%d", rc); return rc ? rc : -12; }
    LOGI("HotSpot VM up; invoking %s.main", mainClass.c_str());

    const std::string internal = toInternal(mainClass);
    jclass cls = env->FindClass(internal.c_str());
    if (!cls) { LOGE("FindClass %s failed", internal.c_str()); env->ExceptionDescribe(); vm->DestroyJavaVM(); return -13; }

    jmethodID mid = env->GetStaticMethodID(cls, "main", "([Ljava/lang/String;)V");
    if (!mid) { LOGE("no main([String]) on %s", internal.c_str()); env->ExceptionDescribe(); vm->DestroyJavaVM(); return -14; }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray args = env->NewObjectArray(0, strCls, nullptr);
    env->CallStaticVoidMethod(cls, mid, args);
    if (env->ExceptionCheck()) { LOGE("%s.main threw", mainClass.c_str()); env->ExceptionDescribe(); env->ExceptionClear(); }

    LOGI("main returned; DestroyJavaVM");
    vm->DestroyJavaVM();
    return JNI_OK;
}
