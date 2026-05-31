#import "JVMLauncher.h"
#import <dlfcn.h>
#import <signal.h>
#import <pthread.h>
#import <unistd.h>
#import <sys/mman.h>
#import <mach/mach.h>
#import <libkern/OSCacheControl.h>
#import "jni/jni.h"

// --- csops / CS_DEBUGGED (private; same probe Amethyst/StikDebug rely on) -----
extern int csops(pid_t pid, unsigned int ops, void *useraddr, size_t usersize);
#ifndef CS_OPS_STATUS
#define CS_OPS_STATUS 0
#endif
#ifndef CS_DEBUGGED
#define CS_DEBUGGED 0x10000000
#endif

// We embed via the JNI Invocation API (JNI_CreateJavaVM), NOT JLI_Launch. The iOS
// JRE launcher's first-thread dance (libjli apple_main -> re-invokes our main() ->
// UIApplicationMain -> "There can only be one UIApplication instance") makes
// JLI_Launch unusable from inside an existing UIKit app; JNI_CreateJavaVM creates
// the VM in-process on this thread with no launcher threading.
typedef jint (*CreateJavaVM_t)(JavaVM **pvm, void **penv, void *args);

@implementation JVMLauncher

+ (BOOL)isDebuggerAttached {
    int flags = 0;
    if (csops(getpid(), CS_OPS_STATUS, &flags, sizeof(flags)) != 0) {
        return NO; // EPERM etc. -> treat as not-yet-debugged
    }
    return (flags & CS_DEBUGGED) != 0;
}

+ (int)runRwxCanary:(JVMLogSink)log {
    size_t pg = (size_t)getpagesize();
    void *mem = mmap(NULL, pg, PROT_READ | PROT_WRITE,
                     MAP_ANON | MAP_PRIVATE | MAP_JIT, -1, 0);
    if (mem == MAP_FAILED) {
        // MAP_JIT may be rejected; retry without it.
        mem = mmap(NULL, pg, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);
    }
    if (mem == MAP_FAILED) { log(@"[canary] mmap RW failed"); return -1; }

    // arm64: mov w0, #42 ; ret
    uint32_t code[] = { 0x52800540u, 0xD65F03C0u };
    memcpy(mem, code, sizeof(code));

    if (mprotect(mem, pg, PROT_READ | PROT_EXEC) != 0) {
        log([NSString stringWithFormat:@"[canary] mprotect RX failed errno=%d", errno]);
        munmap(mem, pg);
        return -2;
    }
    sys_icache_invalidate(mem, sizeof(code));   // arm64: flush I-cache for the new code
    log(@"[canary] RX page ready; calling (SIGILL/EXC_BAD_ACCESS here => naive RWX blocked)");

    int (*fn)(void) = (int (*)(void))mem;
    int r = fn();                       // crashes here if RWX exec is disallowed
    log([NSString stringWithFormat:@"[canary] returned %d (expected 42) -> naive RWX WORKS", r]);
    munmap(mem, pg);
    return r;
}

+ (NSString *)javaHome {
    NSString *jh = [[NSBundle mainBundle] pathForResource:@"jre" ofType:nil];
    if (jh) return jh;
    // Folder-reference fallback: <bundle>/jre
    NSString *guess = [[[NSBundle mainBundle] bundlePath] stringByAppendingPathComponent:@"jre"];
    return [[NSFileManager defaultManager] fileExistsAtPath:guess] ? guess : nil;
}

// Reset the signals HotSpot manages to SIG_DFL so its own handlers install
// cleanly (Amethyst does this immediately before JLI_Launch).
static void resetSignalHandlers(void) {
    int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGPIPE, SIGTRAP };
    for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++) {
        signal(sigs[i], SIG_DFL);
    }
}

// Invoke <className>.main(args) on the (already-created) VM. Clears any exception
// so one probe's failure doesn't poison the next.
static void callMain(JNIEnv *env, const char *className, NSArray<NSString *> *args, JVMLogSink log) {
    jclass cls = (*env)->FindClass(env, className);
    if (!cls || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env);
        log([NSString stringWithFormat:@"[jvm] FindClass(%s) failed", className]); return;
    }
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (!mid || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env);
        log([NSString stringWithFormat:@"[jvm] GetStaticMethodID(%s.main) failed", className]); return;
    }
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jsize n = (jsize)args.count;
    jobjectArray jargs = (*env)->NewObjectArray(env, n, strCls, NULL);
    for (jsize i = 0; i < n; i++) {
        jstring s = (*env)->NewStringUTF(env, args[i].UTF8String);
        (*env)->SetObjectArrayElement(env, jargs, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    log([NSString stringWithFormat:@"[jvm] ===== %s.main =====", className]);
    (*env)->CallStaticVoidMethod(env, cls, mid, jargs);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env);
        log([NSString stringWithFormat:@"[jvm] %s threw", className]);
    } else {
        log([NSString stringWithFormat:@"[jvm] %s done", className]);
    }
}

- (int)runProbesWithLog:(JVMLogSink)log {
    NSString *javaHome = [JVMLauncher javaHome];
    if (!javaHome) { log(@"[jvm] FATAL: embedded jre not found in bundle"); return -10; }
    log([NSString stringWithFormat:@"[jvm] JAVA_HOME = %@", javaHome]);

    NSString *jarPath = [[NSBundle mainBundle] pathForResource:@"probes" ofType:@"jar"];
    if (!jarPath) { log(@"[jvm] FATAL: probes.jar not found in bundle"); return -11; }
    NSString *frameworks = [[NSBundle mainBundle].bundlePath stringByAppendingPathComponent:@"Frameworks"];
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *glyphPng = [docs stringByAppendingPathComponent:@"glyph-smoke.png"];

    // §6.2: the shaded RuneLite client jar (all deps) + a writable RuneLite home for the gamepack
    // cache, and the headless capture dir (HeadlessCapture dumps mainBufferProvider PNGs there).
    NSString *shadedPath = [[NSBundle mainBundle] pathForResource:@"client-1.12.28-SNAPSHOT-shaded" ofType:@"jar"];
    NSString *classpath  = shadedPath ? [NSString stringWithFormat:@"%@:%@", shadedPath, jarPath] : jarPath;
    NSString *captureDir = [docs stringByAppendingPathComponent:@"rl-capture"];
    [[NSFileManager defaultManager] createDirectoryAtPath:captureDir withIntermediateDirectories:YES attributes:nil error:nil];
    (void)glyphPng;

    setenv("JAVA_HOME", javaHome.UTF8String, 1);

    // dlopen libjvm directly and use the JNI Invocation API (no launcher). Claim A
    // (re-signed ad-hoc dylibs load via plain dlopen, no dyld bypass) still holds.
    NSString *libjvm = [javaHome stringByAppendingPathComponent:@"lib/server/libjvm.dylib"];
    log([NSString stringWithFormat:@"[jvm] dlopen %@", libjvm]);
    void *h = dlopen(libjvm.UTF8String, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { log([NSString stringWithFormat:@"[jvm] FATAL dlopen(libjvm): %s", dlerror()]); return -12; }
    CreateJavaVM_t JNI_CreateJavaVM = (CreateJavaVM_t)dlsym(h, "JNI_CreateJavaVM");
    if (!JNI_CreateJavaVM) { log([NSString stringWithFormat:@"[jvm] FATAL dlsym(JNI_CreateJavaVM): %s", dlerror()]); return -13; }
    log(@"[jvm] JNI_CreateJavaVM resolved");

    NSArray<NSString *> *opts = @[
        @"-XX:+UnlockExperimentalVMOptions",
        @"-XX:+MirrorMappedCodeCache",            // iOS-26 code cache (Amethyst jre21 patch)
        @"-XX:+DisablePrimordialThreadGuardPages",
        @"-XX:-UseCompressedClassPointers",       // free cert: no extended-virtual-addressing
        @"-XX:ReservedCodeCacheSize=64m",         // bound the brk #0x69 RX reservation
        // W1 P3 §6.0 activation confirm: non-headless + the CACIO JPMS set (same flags the
        // desktop awtsmoke harness uses). HotSpot accepts --add-exports/--add-opens directly
        // as VM options (verified). sun.awt.image is needed by CTC's CTCVolatileSurfaceManager.
        // For the Phase-1 probe set, restore -Djava.awt.headless=true and drop the JPMS block.
        // NOTE (2026-05-31): JIT cannot be disabled on this patched libjvm — both -Xint and
        // -XX:-UseCompiler fail VM init with "Failed setting boot class path". The MirrorMappedCodeCache
        // build requires the JIT mirror active, so the §6.2 fix must repair the mirror, not bypass it.
        @"-Djava.awt.headless=false",
        @"--add-exports=java.desktop/java.awt=ALL-UNNAMED",
        @"--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
        @"--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
        @"--add-exports=java.base/sun.security.action=ALL-UNNAMED",
        @"--add-opens=java.base/java.util=ALL-UNNAMED",
        @"--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        @"--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
        @"--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        // §6.2: heap for the full client + a writable RuneLite home (gamepack cache) + capture dir.
        @"-Xmx512m",                              // depends on the increased-memory-limit entitlement
        [NSString stringWithFormat:@"-Duser.home=%@", docs],                       // -> <docs>/.runelite
        [NSString stringWithFormat:@"-Drunelite.headless.capture.dir=%@", captureDir],
        @"-Drunelite.headless.capture.interval=50",
        @"-Dheadless.runSeconds=200",
        [NSString stringWithFormat:@"-Djava.home=%@", javaHome],
        [NSString stringWithFormat:@"-Djava.library.path=%@", frameworks],
        [NSString stringWithFormat:@"-Djava.class.path=%@", classpath],
    ];

    JavaVMOption *vmopts = (JavaVMOption *)calloc(opts.count, sizeof(JavaVMOption));
    for (NSUInteger i = 0; i < opts.count; i++) {
        vmopts[i].optionString = strdup(opts[i].UTF8String);
        vmopts[i].extraInfo = NULL;
        log([NSString stringWithFormat:@"[jvm] opt[%lu] = %@", (unsigned long)i, opts[i]]);
    }
    JavaVMInitArgs vmArgs;
    vmArgs.version = JNI_VERSION_21;
    vmArgs.nOptions = (jint)opts.count;
    vmArgs.options = vmopts;
    vmArgs.ignoreUnrecognized = JNI_FALSE;

    resetSignalHandlers();
    log(@"[jvm] JNI_CreateJavaVM (HotSpot generates its template interpreter now)…");

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = JNI_CreateJavaVM(&vm, (void **)&env, &vmArgs);
    if (rc != JNI_OK || !vm || !env) { log([NSString stringWithFormat:@"[jvm] FATAL JNI_CreateJavaVM rc=%d", rc]); return -14; }
    log(@"[jvm] JavaVM created — HotSpot is up. Running probes…");

    // W1 P3 §6.1 device smoke: AwtSmoke activates CTC via UnsafePokeActivation (§6.0, device-GREEN),
    // then realizes a Frame+Canvas and asserts Canvas.getGraphics() non-null + CTC pixel/font readback.
    // Greppable verdicts in jvm-stdio.log:
    //   "AWT-ACTIVATION = CTC ACTIVE"  (§6.0 still holds)
    //   "AWT-SMOKE = CANVAS+CTC OK"    -> §6.1 green on device, proceed to the real-RuneLite buffer smoke
    //   "AWT-SMOKE = FAILED"           -> read the per-gate FAIL line (Canvas peer / getGraphics / fonts)
    // The PNG (args[0]) lands in Documents for an eyeball A/B (pull via devicectl).
    // Earlier probe sets: UnsafePokeActivation alone (§6.0); JitProbe/Bench/GlyphSmoke (Phase-1).
    // W1 P3 §6.2: boot the REAL RuneLite headless client under UnsafePoke CTC, capture
    // mainBufferProvider. Greppable verdicts in jvm-stdio.log:
    //   "[boot] graphicsenv = …CTCGraphicsEnvironment" + "[headless] … displayable=true"
    //   "[headless-capture] … nullGraphics=0" + "wrote frame-LOGIN_SCREEN-*.png" -> §6.2 login render OK
    // PNGs land in Documents/rl-capture/. Earlier entrypoints: AwtSmoke (§6.1), UnsafePokeActivation
    // (§6.0), JitProbe/Bench/GlyphSmoke (Phase-1).
    callMain(env, "DeviceHeadlessBoot", @[], log);

    (*vm)->DestroyJavaVM(vm);
    log(@"[jvm] DestroyJavaVM done.");
    return 0;
}

@end
