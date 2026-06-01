#import "JVMLauncher.h"
#import <dlfcn.h>
#import <signal.h>
#import <pthread.h>
#import <unistd.h>
#import <sys/mman.h>
#import <mach/mach.h>
#import <libkern/OSCacheControl.h>
#import <stdatomic.h>
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

// ---- Phase 4 (W3) sub-goal B / Phase 5: reverse-JNI bridge state ------------------------------------
// The VM is created on the jvm-main worker thread (runProbesWithLog); the live blit + input handlers run
// on the main/UI thread, so they AttachCurrentThreadAsDaemon and cache the JavaBridge class + method ids
// once. gVM is written by the worker thread and read by the main thread, so it is published atomically
// (a plain static is a C-level data race even where it happens to work on-device).
static _Atomic(JavaVM *) gVM = NULL;  // set right after JNI_CreateJavaVM; cleared before DestroyJavaVM
static JNIEnv   *gMainEnv = NULL;     // the UI thread's attached env (main thread only)
static jclass    gBridgeClass = NULL; // global ref to net.runelite.client.headless.JavaBridge
static jmethodID gMidW = NULL, gMidH = NULL, gMidSeq = NULL, gMidPixels = NULL;
static jmethodID gMidTouch = NULL, gMidClick = NULL;

// Resolve + cache the JavaBridge facade on the calling (main) thread. Returns YES once usable.
static BOOL ensureBridge(void) {
    if (gBridgeClass) return YES;
    JavaVM *vm = atomic_load(&gVM);
    if (!vm) return NO;
    JNIEnv *env = NULL;
    jint r = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_21);
    if (r == JNI_EDETACHED) {
        // Daemon attach: the VM won't block on this (UI) thread at shutdown, so DestroyJavaVM stays clean
        // even though we never explicitly detach the main thread (it outlives the VM teardown call).
        if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK || !env) return NO;
    } else if (r != JNI_OK || !env) {
        return NO;
    }
    gMainEnv = env;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/JavaBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    gMidW      = (*env)->GetStaticMethodID(env, global, "getFrameWidth", "()I");
    gMidH      = (*env)->GetStaticMethodID(env, global, "getFrameHeight", "()I");
    gMidSeq    = (*env)->GetStaticMethodID(env, global, "getFrameSeq", "()I");
    gMidPixels = (*env)->GetStaticMethodID(env, global, "getFramePixels", "()[I");
    gMidTouch  = (*env)->GetStaticMethodID(env, global, "onTouch", "(IIII)V");
    gMidClick  = (*env)->GetStaticMethodID(env, global, "onClick", "(III)V");
    if (!gMidW || !gMidH || !gMidSeq || !gMidPixels || !gMidTouch || !gMidClick) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);   // don't leak the class ref on the retry path
        return NO;                              // retry next tick (the class may load just after bringup)
    }
    gBridgeClass = global;   // publish only once fully resolved
    return YES;
}

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

// Map Documents/tokens.json -> the JX_* env the OSRS gamepack reads when "launched from the Jagex
// launcher". tokens.json is a gitignored secret (throwaway account) pushed into the app container at
// test time (devicectl / Files.app), never bundled into the shared build. Returns YES iff a usable
// session was found (session_id + chosen_account_id), so autologin only arms when we can authenticate.
// Mirrors run-headless-login.sh's jq mapping. Env MUST be set before JNI_CreateJavaVM (HotSpot snapshots
// the environment at startup). Logs redacted prefixes only — never the secret values.
static BOOL loadJagexTokens(NSString *docs, JVMLogSink log) {
    NSString *path = [docs stringByAppendingPathComponent:@"tokens.json"];
    NSData *data = [NSData dataWithContentsOfFile:path];
    if (!data) {
        log(@"[jx] no tokens.json in Documents — autologin DISABLED (login-screen render only)");
        return NO;
    }
    NSError *err = nil;
    id obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:&err];
    if (![obj isKindOfClass:[NSDictionary class]]) {
        log([NSString stringWithFormat:@"[jx] tokens.json parse failed: %@", err.localizedDescription]);
        return NO;
    }
    NSDictionary *t = (NSDictionary *)obj;
    NSString *(^str)(NSString *) = ^NSString *(NSString *k) {
        id v = t[k];
        return [v isKindOfClass:[NSString class]] ? (NSString *)v : nil;
    };
    NSString *session   = str(@"session_id");
    NSString *character = str(@"chosen_account_id");
    NSString *display   = str(@"chosen_display_name");
    NSString *access    = str(@"access_token");
    NSString *refresh   = str(@"refresh_token");
    if (session.length == 0 || character.length == 0) {
        log(@"[jx] tokens.json missing session_id/chosen_account_id — autologin DISABLED");
        return NO;
    }
    setenv("JX_SESSION_ID", session.UTF8String, 1);
    setenv("JX_CHARACTER_ID", character.UTF8String, 1);
    if (display.length)  setenv("JX_DISPLAY_NAME", display.UTF8String, 1);
    if (access.length)   setenv("JX_ACCESS_TOKEN", access.UTF8String, 1);
    if (refresh.length)  setenv("JX_REFRESH_TOKEN", refresh.UTF8String, 1);
    log([NSString stringWithFormat:@"[jx] JX_* set: displayName='%@' characterId=%@ session=%@… access=%@ refresh=%@",
        display ?: @"", character,
        session.length >= 6 ? [session substringToIndex:6] : session,
        access.length ? @"set" : @"empty", refresh.length ? @"set" : @"empty"]);
    return YES;
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
    // Clear stale frames from prior runs: the draw counter resets each launch, so an old run's
    // higher-numbered frame would otherwise win the W3 shell's lexical-newest pick (and disk grows).
    [[NSFileManager defaultManager] removeItemAtPath:captureDir error:nil];
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

    NSMutableArray<NSString *> *opts = [@[
        @"-XX:+UnlockExperimentalVMOptions",
        @"-XX:+MirrorMappedCodeCache",            // iOS-26 code cache (Amethyst jre21 patch)
        @"-XX:+DisablePrimordialThreadGuardPages",
        @"-XX:-UseCompressedClassPointers",       // free cert: no extended-virtual-addressing
        @"-XX:ReservedCodeCacheSize=64m",         // bound the brk #0x69 RX reservation
        // NOTE (UPDATE 14): TieredStopAtLevel=1 + CICompilerCount=1 were tried and REVERTED — they did
        // NOT fix the §6.2 crash, they only MOVED it (code-cache is_unloading → GC heap corruption in
        // copy_to_survivor_space, with the crash reporter itself faulting = broad memory corruption).
        // Root cause is the debugger JIT region's physical pages aliasing the JVM heap, not tier-3
        // density. Full tiered is restored: it has the demonstrated CLEAN boots (UPDATE 9/10), and the
        // crash is probabilistic either way — retry until a boot doesn't collide.
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
        @"-Xmx256m",                              // jetsam hedge: 512m+JIT+native risked an early kill;
                                                  // 256m is ample for the login screen (depends on the
                                                  // increased-memory-limit entitlement)
        [NSString stringWithFormat:@"-Duser.home=%@", docs],                       // -> <docs>/.runelite
        [NSString stringWithFormat:@"-Drunelite.headless.capture.dir=%@", captureDir],
        // Steady capture cadence (the §6.2 TCC fix made the process long-lived, so the old eager
        // interval=2/preLoginMax=6/exit-at-8 burst is no longer needed): grab a fresh login frame
        // ~every 10th draw so the W3 shell shows the LIVE, animated, complete login screen for the
        // whole window, and keep enough budget that it never freezes mid-run.
        @"-Drunelite.bridge=true",                   // enable the reverse-JNI live-blit + touch facade (W3 B)
        @"-Drunelite.headless.capture.interval=10",
        @"-Drunelite.headless.capture.preLoginMax=40",  // login-screen/EULA frames before LOGGED_IN gets its
                                                        // own (MAX_FRAMES) budget; a long LOADING can't starve it
        @"-Dheadless.runSeconds=240",               // wide window: interactive login adds EULA+Play Now clicks,
                                                    // network login, and a first-run cache download before LOGGED_IN
        @"-Dheadless.captureTargetFrames=999",      // don't early-exit; ride the window so the LOGGED_IN frame lands
        [NSString stringWithFormat:@"-Djava.home=%@", javaHome],
        [NSString stringWithFormat:@"-Djava.library.path=%@", frameworks],
        [NSString stringWithFormat:@"-Djava.class.path=%@", classpath],
    ] mutableCopy];

    // Phase 4 sub-goal A — interactive login, purely in-process (no native touch bridge). Map the JX_*
    // launcher handoff from Documents/tokens.json, then arm HeadlessLogin's two-stage click machine
    // (EULA Accept -> Play Now). Only arms when a usable token set is present, so a build with no
    // tokens.json pushed still does the proven §6.2 login-screen render.
    BOOL autologin = loadJagexTokens(docs, log);
    if (autologin) {
        [opts addObjectsFromArray:@[
            @"-Drunelite.headless.autologin=true",
            @"-Drunelite.headless.autologin.eula=true",
            @"-Drunelite.headless.autologin.eula.point=302,312",  // EULA Accept centre (765x503 device frame)
            @"-Drunelite.headless.autologin.eula.loginIndex=12",  // observed EULA sub-state on device
            @"-Drunelite.headless.autologin.point=382,248",       // Play Now centre (desktop-proven)
            @"-Drunelite.headless.autologin.world=301",           // F2P world (throwaway account)
            @"-Drunelite.headless.autologin.worldAddress=oldschool1.runescape.com",
        ]];
        log(@"[jvm] autologin ARMED: EULA 302,312 (idx 12) -> Play Now 382,248, world 301/oldschool1");
    }

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

    // W1 P3 §6.2 — Amethyst app-side JIT parity (the proven path on THIS device).
    // The Amethyst CDN jre21 is byte-identical to ours (size+last-modified match the pin),
    // i.e. Amethyst runs Minecraft's heavier JIT on this SAME libjvm with no crash — so the
    // §6.2 SIGSEGV is NOT a libjvm defect, it's our process setup. JavaLauncher.m clears the
    // task's EXC_BAD_ACCESS Mach exception port (port 0 = default) before bringing up the VM:
    // HotSpot deliberately faults SIGSEGV for implicit null checks / safepoint polls in compiled
    // code and catches them in its OWN handler. With StikDebug attached, that Mach exception is
    // instead delivered to the debugger (and our map-on-fault net can swallow it without
    // re-delivering), so HotSpot's null-check slow path never runs → execution continues with a
    // garbage register → the SEGV_ACCERR we saw in a fresh tier-3 nmethod (x0 = bogus oop).
    // Clearing the bad-access port lets those faults fall through to HotSpot's sigaction handler.
    // brk #0x69 is EXC_BREAKPOINT (a DIFFERENT mask) — left with the debugger, so the 64 MB
    // code-cache reservation is still serviced by runelite-jit.js.
    kern_return_t kr = task_set_exception_ports(
        mach_task_self(), EXC_MASK_BAD_ACCESS, MACH_PORT_NULL,
        EXCEPTION_DEFAULT, MACHINE_THREAD_STATE);
    log([NSString stringWithFormat:@"[jvm] cleared EXC_MASK_BAD_ACCESS port (Amethyst parity) kr=%d", kr]);

    log(@"[jvm] JNI_CreateJavaVM (HotSpot generates its template interpreter now)…");

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = JNI_CreateJavaVM(&vm, (void **)&env, &vmArgs);
    if (rc != JNI_OK || !vm || !env) { log([NSString stringWithFormat:@"[jvm] FATAL JNI_CreateJavaVM rc=%d", rc]); return -14; }
    // Publish the VM so the main thread's live-blit + touch bridge can attach into it (atomic store —
    // the main thread loads it with atomic_load in ensureBridge).
    atomic_store(&gVM, vm);
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

    atomic_store(&gVM, NULL);   // VM going away — stop the bridge before DestroyJavaVM
    (*vm)->DestroyJavaVM(vm);
    log(@"[jvm] DestroyJavaVM done.");
    return 0;
}

// ---- Phase 4 (W3) sub-goal B: reverse-JNI bridge (main-thread API) ---------------------------------

+ (BOOL)bridgeReady {
    return ensureBridge();
}

+ (UIImage *)latestFrame {
    if (!ensureBridge()) return nil;
    JNIEnv *env = gMainEnv;

    // Skip the costly readback + CGImage rebuild when the engine hasn't composited a new frame since the
    // last tick (the display refreshes up to 120 Hz; the engine composites at ~50 fps). Return the cached
    // UIImage so the on-screen view keeps showing the current frame at no per-tick cost.
    static UIImage *cachedImage = nil;
    static jint lastSeq = -1;
    jint seq = (*env)->CallStaticIntMethod(env, gBridgeClass, gMidSeq);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    if (seq == lastSeq && cachedImage != nil) {
        return cachedImage;
    }

    jint w = (*env)->CallStaticIntMethod(env, gBridgeClass, gMidW);
    jint h = (*env)->CallStaticIntMethod(env, gBridgeClass, gMidH);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    if (w <= 0 || h <= 0) return nil;

    jintArray arr = (jintArray)(*env)->CallStaticObjectMethod(env, gBridgeClass, gMidPixels);
    if (!arr || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    jsize len = (*env)->GetArrayLength(env, arr);
    NSUInteger need = (NSUInteger)w * (NSUInteger)h;
    if ((NSUInteger)len < need) { (*env)->DeleteLocalRef(env, arr); return nil; }

    // Reusable native scratch buffer (grows monotonically); copied again into CFData so the CGImage owns
    // stable bytes even as the engine overwrites the Java array on the next frame.
    static uint32_t *buf = NULL;
    static NSUInteger bufCount = 0;
    if (bufCount < need) {
        free(buf);
        buf = (uint32_t *)malloc(need * sizeof(uint32_t));
        bufCount = buf ? need : 0;
    }
    if (!buf) { (*env)->DeleteLocalRef(env, arr); return nil; }
    (*env)->GetIntArrayRegion(env, arr, 0, (jsize)need, (jint *)buf);
    (*env)->DeleteLocalRef(env, arr);

    CFDataRef data = CFDataCreate(NULL, (const UInt8 *)buf, (CFIndex)(need * sizeof(uint32_t)));
    CGDataProviderRef provider = CGDataProviderCreateWithCFData(data);
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    // The engine buffer is TYPE_INT_RGB: packed 0x00RRGGBB ints. On little-endian arm64, reading each
    // 32-bit word native-endian with alpha-skip-first yields [skip],R,G,B — the correct opaque pixel.
    CGBitmapInfo info = (CGBitmapInfo)(kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little);
    CGImageRef cg = CGImageCreate((size_t)w, (size_t)h, 8, 32, (size_t)w * 4, cs, info,
                                  provider, NULL, false, kCGRenderingIntentDefault);
    UIImage *img = cg ? [UIImage imageWithCGImage:cg] : nil;
    if (cg) CGImageRelease(cg);
    CGColorSpaceRelease(cs);
    CGDataProviderRelease(provider);
    CFRelease(data);
    if (img) { cachedImage = img; lastSeq = seq; }   // remember for the skip-unchanged fast path above
    return img;
}

+ (void)postTouchType:(int)type x:(int)x y:(int)y button:(int)button {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidTouch,
                                 (jint)type, (jint)x, (jint)y, (jint)button);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postClickX:(int)x y:(int)y button:(int)button {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidClick, (jint)x, (jint)y, (jint)button);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

@end
