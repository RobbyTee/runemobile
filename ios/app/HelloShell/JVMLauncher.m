#import "JVMLauncher.h"
#import <dlfcn.h>
#import <signal.h>
#import <pthread.h>
#import <unistd.h>
#import <sys/mman.h>
#import <mach/mach.h>
#import <os/proc.h>            // os_proc_available_memory() — jetsam headroom (login-crash diagnostics)
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
static jmethodID gMidInteractSeq = NULL;  // getInteractTapSeq()I — native haptic cue (optional; tolerate older jar)
static jmethodID gMidTouch = NULL, gMidClick = NULL, gMidClickPrecise = NULL, gMidMove = NULL;
static jmethodID gMidWheel = NULL, gMidCamera = NULL, gMidText = NULL, gMidKey = NULL;
static jmethodID gMidInputOpen = NULL, gMidClearHover = NULL;
static jmethodID gMidMenuSelect = NULL;  // selectMenuPoint(II)V — menu row select + hover-clear (optional)

// W3 hybrid-chrome read surface — a SEPARATE facade (MobileChromeBridge) resolved lazily on the main thread.
static jclass    gChromeClass = NULL;   // global ref to net.runelite.client.headless.MobileChromeBridge
static jmethodID gMidChromeSnap = NULL; // getSnapshot()Ljava/lang/String;

// W3 touch-targeting read surface — the MenuPeekBridge facade, resolved lazily on the main thread. The native
// press-and-peek UI polls getSnapshot() (the menu the engine computed for the finger streamed via onMove).
static jclass    gMenuPeekClass = NULL; // global ref to net.runelite.client.headless.MenuPeekBridge
static jmethodID gMidMenuPeekSnap = NULL; // getSnapshot()Ljava/lang/String;

// Phase-6 plugin/config control plane — the MobileUiBridge facade, resolved lazily on the main thread.
static jclass    gMuiClass = NULL;      // global ref to net.runelite.client.headless.MobileUiBridge
static jmethodID gMuiList = NULL;       // listPlugins()Ljava/lang/String;
static jmethodID gMuiGetCfg = NULL;     // getPluginConfig(Ljava/lang/String;)Ljava/lang/String;
static jmethodID gMuiSetEnabled = NULL; // setPluginEnabled(Ljava/lang/String;Z)Ljava/lang/String;
static jmethodID gMuiSetCfg = NULL;     // setConfig(3x String)Ljava/lang/String;
static jmethodID gMuiResetCfg = NULL;   // resetConfig(2x String)Ljava/lang/String;
static jmethodID gMuiSeq = NULL;        // getMobileUiSeq()J
static jmethodID gMuiPoll = NULL;       // pollMobileUiEvents(J)Ljava/lang/String;
static jmethodID gMuiProcessed = NULL;  // getProcessedWriteId()J
static jmethodID gMuiFlush = NULL;      // flushConfig()Ljava/lang/String;
static jmethodID gMuiHubManifest = NULL;  // hubManifest()Ljava/lang/String;
static jmethodID gMuiHubInstall = NULL;   // hubInstall(Ljava/lang/String;)Ljava/lang/String;
static jmethodID gMuiHubUninstall = NULL; // hubUninstall(Ljava/lang/String;)Ljava/lang/String;
static jmethodID gMuiListPanels = NULL;   // listPanels()Ljava/lang/String;
static jmethodID gMuiOpenPanel = NULL;    // openPanel(I)Ljava/lang/String;
static jmethodID gMuiClosePanel = NULL;   // closePanel()Ljava/lang/String;
static jmethodID gMuiPanelW = NULL;       // getPanelWidth()I
static jmethodID gMuiPanelH = NULL;       // getPanelHeight()I
static jmethodID gMuiPanelSeq = NULL;     // getPanelFrameSeq()I
static jmethodID gMuiPanelPixels = NULL;  // getPanelPixels()[I
static jmethodID gMuiPanelClick = NULL;   // panelClick(II)Ljava/lang/String;

// Native Quest Helper reflow — the QuestHelperBridge facade (reflection into the external plugin).
static jclass    gQhClass = NULL;       // global ref to net.runelite.client.headless.QuestHelperBridge
static jmethodID gQhAvailable = NULL;   // isAvailable()Z
static jmethodID gQhList = NULL;        // getQuestListJson()Ljava/lang/String;
static jmethodID gQhActive = NULL;      // getQuestActiveJson()Ljava/lang/String;
static jmethodID gQhStart = NULL;       // startQuest(Ljava/lang/String;)V
static jmethodID gQhStop = NULL;        // stopQuest()V
static jmethodID gQhSetOpen = NULL;     // setUiOpen(Z)V

// Native World Switcher reflow — the WorldSwitcherBridge facade (direct WorldService reads, reflected hop).
static jclass    gWsClass = NULL;       // global ref to net.runelite.client.headless.WorldSwitcherBridge
static jmethodID gWsAvailable = NULL;   // isAvailable()Z
static jmethodID gWsList = NULL;        // getWorldListJson()Ljava/lang/String;
static jmethodID gWsHop = NULL;         // hop(I)V
static jmethodID gWsSetOpen = NULL;     // setUiOpen(Z)V

// ---- W3 plugin-config de-risk (verdict #7, NATIVE-ORIGIN leg) — STRIP AFTER DEVICE CONFIRM ----------
// Proves the half the desktop smoke can't: that the OBJC side builds the correct signed decimal of
// Color.getRGB(). The trap — an opaque color's getRGB() is a NEGATIVE int (top alpha bit set), and
// ColorUtil.fromString uses Integer.decode, which REJECTS 0xFFxxxxxx hex — so the control must send the
// signed decimal, not hex. We write the agreed opaque color 0xFF123456 to the throwaway sentinel group;
// MobileUiSmokeRunner reads it back through the typed proxy path and emits MUI_RT_NATIVE_COLOR_OK.
// Gated like the smoke flags below — flip to YES (+ re-add the 3 smoke opts) to re-run the device confirm.
// Device confirm md5-GREEN 2026-06-01 (MUI_RT_NATIVE_COLOR_OK=true): ObjC signed-decimal getRGB round-trips.
static const BOOL kMuiNativeColorProbe = NO;

static void runMuiNativeColorProbe(JVMLogSink log) {
    // 0xFF123456 == new Color(0x12,0x34,0x56,255).getRGB() — agreed with MobileUiSmokeRunner.nativeColorReadback.
    int32_t argb = (int32_t)(((uint32_t)0xFFu << 24) | ((uint32_t)0x12u << 16) | ((uint32_t)0x34u << 8) | (uint32_t)0x56u);
    NSString *decimal = [NSString stringWithFormat:@"%d", (int)argb];   // the signed decimal the control must send
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        JavaVM *vm = atomic_load(&gVM);
        if (!vm) return;
        JNIEnv *e = NULL;   // own daemon attach — the worker thread is blocked in callMain(DeviceHeadlessBoot)
        if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&e, NULL) != JNI_OK || !e) return;
        jclass cls = (*e)->FindClass(e, "net/runelite/client/headless/MobileUiBridge"); // on the system classpath
        if (!cls || (*e)->ExceptionCheck(e)) { (*e)->ExceptionClear(e); return; }
        jmethodID set = (*e)->GetStaticMethodID(e, cls, "setConfig",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        if (!set) { (*e)->ExceptionClear(e); return; }
        jstring jg = (*e)->NewStringUTF(e, "mobileuismoke");
        jstring jk = (*e)->NewStringUTF(e, "nativecolor");
        jstring jv = (*e)->NewStringUTF(e, decimal.UTF8String);
        BOOL logged = NO;
        // Retry until the facade is wired (config/clientThread non-null) so the write lands; same value each
        // time, so post-landing writes are harmless no-ops. Stops at ~60s or when the VM goes away.
        for (int i = 0; i < 30 && atomic_load(&gVM); i++) {
            jobject r = (*e)->CallStaticObjectMethod(e, cls, set, jg, jk, jv);
            if ((*e)->ExceptionCheck(e)) { (*e)->ExceptionClear(e); }
            if (r) (*e)->DeleteLocalRef(e, r);
            if (!logged) { log([NSString stringWithFormat:@"[mui] NATIVE_RT_COLOR_WRITE=%@", decimal]); logged = YES; }
            usleep(2000 * 1000);   // 2s
        }
        (*e)->DeleteLocalRef(e, jg); (*e)->DeleteLocalRef(e, jk); (*e)->DeleteLocalRef(e, jv);
    });
}

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
    gMidClickPrecise = (*env)->GetStaticMethodID(env, global, "onClickPrecise", "(III)V");
    gMidMove   = (*env)->GetStaticMethodID(env, global, "onMove", "(II)V");
    gMidWheel  = (*env)->GetStaticMethodID(env, global, "onWheel", "(III)V");
    gMidCamera = (*env)->GetStaticMethodID(env, global, "onCameraDrag", "(II)V");
    gMidText   = (*env)->GetStaticMethodID(env, global, "onText", "(Ljava/lang/String;)V");
    gMidKey    = (*env)->GetStaticMethodID(env, global, "onKeyStroke", "(I)V");
    gMidInputOpen = (*env)->GetStaticMethodID(env, global, "isTextInputOpen", "()Z");
    gMidClearHover = (*env)->GetStaticMethodID(env, global, "clearHover", "()V");
    if (!gMidW || !gMidH || !gMidSeq || !gMidPixels || !gMidTouch || !gMidClick || !gMidClickPrecise || !gMidMove
        || !gMidWheel || !gMidCamera || !gMidText || !gMidKey || !gMidInputOpen || !gMidClearHover) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);   // don't leak the class ref on the retry path
        return NO;                              // retry next tick (the class may load just after bringup)
    }
    // Optional (not in the mandatory set above, so an older jar without it still boots the bridge).
    gMidInteractSeq = (*env)->GetStaticMethodID(env, global, "getInteractTapSeq", "()I");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); gMidInteractSeq = NULL; }
    gMidMenuSelect = (*env)->GetStaticMethodID(env, global, "selectMenuPoint", "(II)V");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); gMidMenuSelect = NULL; }
    gBridgeClass = global;   // publish only once fully resolved
    return YES;
}

// Resolve + cache the MobileChromeBridge facade on the calling (main) thread. Reuses the JavaBridge attach
// (gMainEnv), so call only after ensureBridge() has run. Returns YES once usable.
static BOOL ensureChrome(void) {
    if (gChromeClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;   // shares the main thread's attached env
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/MobileChromeBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    jmethodID snap = (*env)->GetStaticMethodID(env, global, "getSnapshot", "()Ljava/lang/String;");
    if (!snap) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gMidChromeSnap = snap;
    gChromeClass = global;   // publish only once fully resolved
    return YES;
}

// Resolve + cache the MenuPeekBridge facade on the calling (main) thread. Shares the JavaBridge attach.
static BOOL ensureMenuPeek(void) {
    if (gMenuPeekClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;   // shares the main thread's attached env
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/MenuPeekBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    jmethodID snap = (*env)->GetStaticMethodID(env, global, "getSnapshot", "()Ljava/lang/String;");
    if (!snap) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gMidMenuPeekSnap = snap;
    gMenuPeekClass = global;   // publish only once fully resolved
    return YES;
}

// Resolve + cache the HudDataBridge facade (W3 Feature-A native HUD data). Shares the JavaBridge attach.
static jclass    gHudClass = NULL;
static jmethodID gMidHudSnap = NULL;   // getSnapshot()Ljava/lang/String;
static jmethodID gMidHudSeq = NULL;    // getSeq()I
static BOOL ensureHud(void) {
    if (gHudClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;   // shares the main thread's attached env
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/HudDataBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    jmethodID snap = (*env)->GetStaticMethodID(env, global, "getSnapshot", "()Ljava/lang/String;");
    jmethodID sq = (*env)->GetStaticMethodID(env, global, "getSeq", "()I");
    if (!snap || !sq) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gMidHudSnap = snap;
    gMidHudSeq = sq;
    gHudClass = global;   // publish only once fully resolved
    return YES;
}

// Resolve + cache the HudVeneerBridge facade (W3 A3 engine-HUD suppression). Shares the JavaBridge attach.
static jclass    gVeneerClass = NULL;
static jmethodID gMidSetChatHidden = NULL;   // setChatHidden(Z)V
static BOOL ensureVeneer(void) {
    if (gVeneerClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/HudVeneerBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    jmethodID m = (*env)->GetStaticMethodID(env, global, "setChatHidden", "(Z)V");
    if (!m) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gMidSetChatHidden = m;
    gVeneerClass = global;
    return YES;
}

// Resolve + cache the MobileUiBridge facade on the calling (main) thread. Shares the JavaBridge attach.
static BOOL ensureMui(void) {
    if (gMuiClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/MobileUiBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    gMuiList       = (*env)->GetStaticMethodID(env, global, "listPlugins", "()Ljava/lang/String;");
    gMuiGetCfg     = (*env)->GetStaticMethodID(env, global, "getPluginConfig", "(Ljava/lang/String;)Ljava/lang/String;");
    gMuiSetEnabled = (*env)->GetStaticMethodID(env, global, "setPluginEnabled", "(Ljava/lang/String;Z)Ljava/lang/String;");
    gMuiSetCfg     = (*env)->GetStaticMethodID(env, global, "setConfig", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    gMuiResetCfg   = (*env)->GetStaticMethodID(env, global, "resetConfig", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    gMuiSeq        = (*env)->GetStaticMethodID(env, global, "getMobileUiSeq", "()J");
    gMuiPoll       = (*env)->GetStaticMethodID(env, global, "pollMobileUiEvents", "(J)Ljava/lang/String;");
    gMuiProcessed  = (*env)->GetStaticMethodID(env, global, "getProcessedWriteId", "()J");
    gMuiFlush      = (*env)->GetStaticMethodID(env, global, "flushConfig", "()Ljava/lang/String;");
    gMuiHubManifest  = (*env)->GetStaticMethodID(env, global, "hubManifest", "()Ljava/lang/String;");
    gMuiHubInstall   = (*env)->GetStaticMethodID(env, global, "hubInstall", "(Ljava/lang/String;)Ljava/lang/String;");
    gMuiHubUninstall = (*env)->GetStaticMethodID(env, global, "hubUninstall", "(Ljava/lang/String;)Ljava/lang/String;");
    gMuiListPanels   = (*env)->GetStaticMethodID(env, global, "listPanels", "()Ljava/lang/String;");
    gMuiOpenPanel    = (*env)->GetStaticMethodID(env, global, "openPanel", "(I)Ljava/lang/String;");
    gMuiClosePanel   = (*env)->GetStaticMethodID(env, global, "closePanel", "()Ljava/lang/String;");
    gMuiPanelW       = (*env)->GetStaticMethodID(env, global, "getPanelWidth", "()I");
    gMuiPanelH       = (*env)->GetStaticMethodID(env, global, "getPanelHeight", "()I");
    gMuiPanelSeq     = (*env)->GetStaticMethodID(env, global, "getPanelFrameSeq", "()I");
    gMuiPanelPixels  = (*env)->GetStaticMethodID(env, global, "getPanelPixels", "()[I");
    gMuiPanelClick   = (*env)->GetStaticMethodID(env, global, "panelClick", "(II)Ljava/lang/String;");
    if (!gMuiList || !gMuiGetCfg || !gMuiSetEnabled || !gMuiSetCfg || !gMuiResetCfg
        || !gMuiSeq || !gMuiPoll || !gMuiProcessed || !gMuiFlush
        || !gMuiHubManifest || !gMuiHubInstall || !gMuiHubUninstall
        || !gMuiListPanels || !gMuiOpenPanel || !gMuiClosePanel || !gMuiPanelW || !gMuiPanelH
        || !gMuiPanelSeq || !gMuiPanelPixels || !gMuiPanelClick) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gMuiClass = global;   // publish only once fully resolved
    return YES;
}

static BOOL ensureQuest(void) {
    if (gQhClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/QuestHelperBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    gQhAvailable = (*env)->GetStaticMethodID(env, global, "isAvailable", "()Z");
    gQhList      = (*env)->GetStaticMethodID(env, global, "getQuestListJson", "()Ljava/lang/String;");
    gQhActive    = (*env)->GetStaticMethodID(env, global, "getQuestActiveJson", "()Ljava/lang/String;");
    gQhStart     = (*env)->GetStaticMethodID(env, global, "startQuest", "(Ljava/lang/String;)V");
    gQhStop      = (*env)->GetStaticMethodID(env, global, "stopQuest", "()V");
    gQhSetOpen   = (*env)->GetStaticMethodID(env, global, "setUiOpen", "(Z)V");
    if (!gQhAvailable || !gQhList || !gQhActive || !gQhStart || !gQhStop || !gQhSetOpen) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gQhClass = global;
    return YES;
}

static BOOL ensureWorld(void) {
    if (gWsClass) return YES;
    if (!ensureBridge() || !gMainEnv) return NO;
    JNIEnv *env = gMainEnv;
    jclass local = (*env)->FindClass(env, "net/runelite/client/headless/WorldSwitcherBridge");
    if (!local || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return NO;
    gWsAvailable = (*env)->GetStaticMethodID(env, global, "isAvailable", "()Z");
    gWsList      = (*env)->GetStaticMethodID(env, global, "getWorldListJson", "()Ljava/lang/String;");
    gWsHop       = (*env)->GetStaticMethodID(env, global, "hop", "(I)V");
    gWsSetOpen   = (*env)->GetStaticMethodID(env, global, "setUiOpen", "(Z)V");
    if (!gWsAvailable || !gWsList || !gWsHop || !gWsSetOpen) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, global);
        return NO;
    }
    gWsClass = global;
    return YES;
}

// Pull a Java String return into an NSString (and free the local ref). Returns nil on null/exception.
static NSString *jstringToNS(JNIEnv *env, jstring js) {
    if (!js || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
    NSString *out = utf ? [NSString stringWithUTF8String:utf] : nil;
    if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
    (*env)->DeleteLocalRef(env, js);
    return out;
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

// ───────────────────── Same-session login-crash diagnostics (Phase 6) — STRIP AFTER DIAGNOSIS ─────────────────────
// Symptom: opening the Jagex login WKWebView earlier in the SAME app session crashes the JVM natively
// right after RuneLite init; a force-quit + relaunch before Start JVM reaches the game cleanly. The two
// obvious causes are ALREADY defended (resetSignalHandlers() wipes WebKit's POSIX SIGSEGV/SIGBUS handlers;
// the §6.2 clear drops the task EXC_BAD_ACCESS Mach port), so this dumps the *actual* runtime state to the
// crash-safe stderr fd (Documents/jvm-stdio.log — dup2'd UNBUFFERED, so it survives even a silent jetsam
// kill). Run twice and diff: (A) fresh launch → Start JVM (clean baseline) vs (B) fresh launch → Log in →
// Start JVM (the crash case). What differs pins the cause:
//   • a non-null BAD_ACCESS port (task or thread) in B → WebKit's JavaScriptCore is stealing HotSpot's
//     null-check/safepoint faults (exception-port path) → the thread-level clear below should fix it;
//   • a "custom" SIGSEGV handler surviving into B → a POSIX-handler shadow we're not resetting;
//   • avail-memory collapsing toward 0 (memwatch) before B dies with no SIGSEGV → jetsam (WebKit helper
//     processes) → the web-view teardown (login VC) is the lever, not the ports.
static void dumpFaultState(const char *when) {
    exception_mask_t      masks[EXC_TYPES_COUNT];
    mach_port_t           ports[EXC_TYPES_COUNT];
    exception_behavior_t  behs[EXC_TYPES_COUNT];
    thread_state_flavor_t flav[EXC_TYPES_COUNT];
    mach_msg_type_number_t n = 0;

    kern_return_t kr = task_get_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS,
                                                masks, &n, ports, behs, flav);
    fprintf(stderr, "[faultdiag:%s] task BAD_ACCESS kr=%d n=%u", when, kr, n);
    for (mach_msg_type_number_t i = 0; i < n; i++) {
        fprintf(stderr, " {mask=0x%x port=0x%x beh=0x%x flav=%d}", masks[i], ports[i], behs[i], flav[i]);
        if (ports[i] != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), ports[i]);
    }
    fprintf(stderr, "\n");

    // BREAKPOINT is the mask that matters for HotSpot's JIT-mirror brk #0x69 — if WebKit displaced the
    // StikDebug debugger here, the brk is never serviced. BAD_INSTRUCTION for completeness (JSC claims it).
    n = 0;
    kr = task_get_exception_ports(mach_task_self(), EXC_MASK_BREAKPOINT, masks, &n, ports, behs, flav);
    fprintf(stderr, "[faultdiag:%s] task BREAKPOINT kr=%d n=%u", when, kr, n);
    for (mach_msg_type_number_t i = 0; i < n; i++) {
        fprintf(stderr, " {mask=0x%x port=0x%x beh=0x%x flav=%d}", masks[i], ports[i], behs[i], flav[i]);
        if (ports[i] != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), ports[i]);
    }
    fprintf(stderr, "\n");
    n = 0;
    kr = task_get_exception_ports(mach_task_self(), EXC_MASK_BAD_INSTRUCTION, masks, &n, ports, behs, flav);
    fprintf(stderr, "[faultdiag:%s] task BAD_INSTRUCTION kr=%d n=%u", when, kr, n);
    for (mach_msg_type_number_t i = 0; i < n; i++) {
        fprintf(stderr, " {mask=0x%x port=0x%x beh=0x%x flav=%d}", masks[i], ports[i], behs[i], flav[i]);
        if (ports[i] != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), ports[i]);
    }
    fprintf(stderr, "\n");

    thread_t th = mach_thread_self();
    n = 0;
    kr = thread_get_exception_ports(th, EXC_MASK_BAD_ACCESS, masks, &n, ports, behs, flav);
    fprintf(stderr, "[faultdiag:%s] thread BAD_ACCESS kr=%d n=%u", when, kr, n);
    for (mach_msg_type_number_t i = 0; i < n; i++) {
        fprintf(stderr, " {mask=0x%x port=0x%x beh=0x%x flav=%d}", masks[i], ports[i], behs[i], flav[i]);
        if (ports[i] != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), ports[i]);
    }
    fprintf(stderr, "\n");
    mach_port_deallocate(mach_task_self(), th);

    int sg[] = { SIGSEGV, SIGBUS, SIGILL, SIGTRAP };
    const char *nm[] = { "SIGSEGV", "SIGBUS", "SIGILL", "SIGTRAP" };
    for (size_t i = 0; i < sizeof(sg) / sizeof(sg[0]); i++) {
        struct sigaction sa; memset(&sa, 0, sizeof(sa));
        if (sigaction(sg[i], NULL, &sa) != 0) continue;
        void *h = (sa.sa_flags & SA_SIGINFO) ? (void *)sa.sa_sigaction : (void *)sa.sa_handler;
        const char *tag = h == (void *)SIG_DFL ? "DFL" : h == (void *)SIG_IGN ? "IGN" : "custom";
        fprintf(stderr, "[faultdiag:%s] %s=%p(%s) flags=0x%x onstack=%d\n",
                when, nm[i], h, tag, sa.sa_flags, (sa.sa_flags & SA_ONSTACK) ? 1 : 0);
    }

    size_t avail = os_proc_available_memory();
    task_vm_info_data_t vmi; mach_msg_type_number_t c = TASK_VM_INFO_COUNT; uint64_t fp = 0;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vmi, &c) == KERN_SUCCESS) fp = vmi.phys_footprint;
    fprintf(stderr, "[faultdiag:%s] mem avail=%.1fMB footprint=%.1fMB\n", when, avail / 1048576.0, fp / 1048576.0);
}

// Sample jetsam headroom every 500 ms across RuneLite init (the crash window), to the crash-safe fd. If a
// post-login launch is OOM/jetsam-killed, the last line shows avail collapsing toward 0 right before death
// — the signature that separates a memory kill from a SIGSEGV (exception-port/handler) kill.
static void *memWatchdog(void *unused) {
    (void)unused;
    pthread_setname_np("mem-watchdog");
    for (int i = 0; i < 60; i++) {
        size_t avail = os_proc_available_memory();
        task_vm_info_data_t vmi; mach_msg_type_number_t c = TASK_VM_INFO_COUNT; uint64_t fp = 0;
        if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vmi, &c) == KERN_SUCCESS) fp = vmi.phys_footprint;
        fprintf(stderr, "[memwatch t=%4.1fs] avail=%.1fMB footprint=%.1fMB\n", i * 0.5, avail / 1048576.0, fp / 1048576.0);
        usleep(500000);
    }
    return NULL;
}
static void startMemWatchdog(void) {
    pthread_t t; if (pthread_create(&t, NULL, memWatchdog, NULL) == 0) pthread_detach(t);
}

// Same-session login fix: if WebKit (resident from the login web view) displaced the StikDebug debugger on
// the task EXC_BREAKPOINT port, HotSpot's JIT-mirror brk #0x69 is delivered to WebKit instead of the
// debugger → never serviced → fatal SIGTRAP in CodeCache::initialize. The debugger still holds the
// EXC_BAD_ACCESS port (it claims EXC_MASK_ALL on one handler), so copy that handler back onto
// EXC_BREAKPOINT. Call BEFORE the §6.2 BAD_ACCESS clear (which needs the debugger's port still in place).
// No-op on a clean launch — the debugger owns both already.
static void restoreBreakpointPortToDebugger(JVMLogSink log) {
    exception_mask_t      m[EXC_TYPES_COUNT];
    mach_port_t           p[EXC_TYPES_COUNT];
    exception_behavior_t  b[EXC_TYPES_COUNT];
    thread_state_flavor_t f[EXC_TYPES_COUNT];
    mach_msg_type_number_t n = 0;
    if (task_get_exception_ports(mach_task_self(), EXC_MASK_BAD_ACCESS, m, &n, p, b, f) != KERN_SUCCESS || n == 0) {
        log(@"[jvm] bp-restore: no BAD_ACCESS handler (debugger not attached?) — skipping");
        return;
    }
    mach_port_t dbg = p[0]; exception_behavior_t dbgBeh = b[0]; thread_state_flavor_t dbgFlav = f[0];

    mach_msg_type_number_t nb = 0;
    exception_mask_t      bm[EXC_TYPES_COUNT];
    mach_port_t           bp[EXC_TYPES_COUNT];
    exception_behavior_t  bb[EXC_TYPES_COUNT];
    thread_state_flavor_t bf[EXC_TYPES_COUNT];
    task_get_exception_ports(mach_task_self(), EXC_MASK_BREAKPOINT, bm, &nb, bp, bb, bf);
    mach_port_t cur = nb ? bp[0] : MACH_PORT_NULL;

    if (cur != dbg) {
        kern_return_t kr = task_set_exception_ports(mach_task_self(), EXC_MASK_BREAKPOINT, dbg, dbgBeh, dbgFlav);
        log([NSString stringWithFormat:@"[jvm] bp-restore: BREAKPOINT 0x%x != debugger 0x%x -> repointed kr=%d (beh=0x%x flav=%d)",
             cur, dbg, kr, dbgBeh, dbgFlav]);
    } else {
        log([NSString stringWithFormat:@"[jvm] bp-restore: BREAKPOINT already debugger's (0x%x) — no-op", dbg]);
    }
    for (mach_msg_type_number_t i = 0; i < n;  i++) if (p[i])  mach_port_deallocate(mach_task_self(), p[i]);
    for (mach_msg_type_number_t i = 0; i < nb; i++) if (bp[i]) mach_port_deallocate(mach_task_self(), bp[i]);
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
        @"-Drunelite.mobileui=true",                 // Phase-6 (W3): arm the MobileUiBridge control-plane facade
                                                     // (plugin/config DTOs + chat-input tick snapshot)
        @"-Drunelite.mobilechrome=true",             // W3 hybrid-chrome: arm the read-only MobileChromeBridge
                                                     // snapshot (tab/panel/minimap/orb/chat/viewport bounds).
                                                     // NOT the .probe gate — that mutates and is desktop-only.
        @"-Drunelite.menupeek=true",                 // W3 touch-targeting: arm the read-only MenuPeekBridge
                                                     // (native right-click menu data source). NOT .probe (desktop-only).
        @"-Drunelite.menupeek.maxEntries=14",        // publish enough display rows for the native menu (default 10)
        @"-Drunelite.huddata=true",                  // W3 Feature-A: arm the read-only HudDataBridge snapshot
                                                     // (chat lines / orb values / minimap positions for the veneers)
        @"-Drunelite.hudveneer=true",                // W3 Feature-A A3: arm the HudVeneerBridge (engine-HUD
                                                     // suppression; native sets which elements to hide, e.g. chat)
        @"-Drunelite.hub=true",                      // plugin hub: drop --safe-mode so external plugins load
                                                     // (device-de-risked 2026-06-02). Also disables the
                                                     // headless-irrelevant GPU plugin.
        @"-Drunelite.pluginhub.version=1.12.27",     // our 1.12.28-SNAPSHOT has no hub manifest (404); 1.12.27
                                                     // is the adjacent released API the hub serves plugins for.
        // NOTE: -Drunelite.mobilechrome.repos was tried and REVERTED — repositioned stones over the world are
        // occluded by the 3D viewport (only the mobile 601 toplevel draws tabs above it). Native rails it is.
        // Phase-6 STEP-0 device smoke confirmed GREEN (2026-06-01): lanes separate (config on client thread,
        // toggle on EDT), overlay registers headless (Ground Items 92->146), config persists across launches,
        // PluginManager EDT asserts live. To re-run the on-device self-test, re-add:
        //   @"-Drunelite.mobileui.smoke=true", @"-ea:net.runelite.client.plugins.PluginManager",
        // W3 plugin-config device confirms — all md5-GREEN 2026-06-01, flags stripped:
        //   typed round-trip (verdict #7) + bridge hardening (event/ack + getPluginConfig completeness +
        //   reset-default), both via the MobileUiSmokeRunner bytes. To re-run the self-test, re-add:
        //   @"-Drunelite.mobileui.smoke=true", @"-ea:net.runelite.client.plugins.PluginManager",
        //   (and -Drunelite.mobileui.nativeprobe + kMuiNativeColorProbe=YES for the native-origin color leg)
        // PLUGIN-HUB device de-risk — md5-GREEN 2026-06-02 (manifest=1700, nail-beast-sfx downloaded +
        // classloaded + JIT-ran + started under the mirror + showed in the panel, clean uninstall). Flags
        // stripped; to re-run the probe re-add:
        //   @"-Drunelite.mobileui.smoke=true", @"-ea:net.runelite.client.plugins.PluginManager",
        //   @"-Drunelite.mobileui.hubprobe=true", @"-Drunelite.hub=true", @"-Drunelite.pluginhub.version=1.12.27",
        // NOTE: -Drunelite.hub also drops safe-mode (enables external plugins) — the real hub UI will pass it.
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

    // Full-screen landscape: after login the headless client resizes its CTC canvas to this WxH (the
    // gamepack switches to resizable layout and fills it). Login stays at 765x503 (autologin coords).
    if (self.resizeSpec.length) {
        [opts addObject:[NSString stringWithFormat:@"-Drunelite.headless.resize=%@", self.resizeSpec]];
        log([NSString stringWithFormat:@"[jvm] full-screen render target = %@", self.resizeSpec]);
    }

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
    dumpFaultState("pre-clear");   // STRIP AFTER DIAGNOSIS — state inherited from this (maybe WebKit-poisoned) session

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
    //
    // SAME-SESSION LOGIN FIX: when the login WKWebView is resident, WebKit displaces the debugger on the
    // task BREAKPOINT port → the brk #0x69 is never serviced → SIGTRAP in CodeCache::initialize. Re-point
    // BREAKPOINT back at the debugger (which still owns BAD_ACCESS) BEFORE we clear BAD_ACCESS below.
    restoreBreakpointPortToDebugger(log);

    kern_return_t kr = task_set_exception_ports(
        mach_task_self(), EXC_MASK_BAD_ACCESS, MACH_PORT_NULL,
        EXCEPTION_DEFAULT, MACHINE_THREAD_STATE);
    log([NSString stringWithFormat:@"[jvm] cleared EXC_MASK_BAD_ACCESS port (Amethyst parity) kr=%d", kr]);

    // Belt-and-braces for the post-login case: a task-level clear leaves THREAD-level handlers intact, so
    // also drop any bad-access port on this jvm-main thread. No-op on a clean launch; the fix if the
    // diagnostics show WebKit's JavaScriptCore left a thread-level port. (STRIP/keep per diagnosis.)
    thread_t jvmThread = mach_thread_self();
    kern_return_t krt = thread_set_exception_ports(jvmThread, EXC_MASK_BAD_ACCESS, MACH_PORT_NULL,
                                                   EXCEPTION_DEFAULT, MACHINE_THREAD_STATE);
    mach_port_deallocate(mach_task_self(), jvmThread);
    log([NSString stringWithFormat:@"[jvm] cleared thread EXC_MASK_BAD_ACCESS port krt=%d", krt]);

    dumpFaultState("post-clear");   // STRIP AFTER DIAGNOSIS — did the clears stick?
    startMemWatchdog();             // STRIP AFTER DIAGNOSIS — sample jetsam headroom across RuneLite init

    log(@"[jvm] JNI_CreateJavaVM (HotSpot generates its template interpreter now)…");

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = JNI_CreateJavaVM(&vm, (void **)&env, &vmArgs);
    if (rc != JNI_OK || !vm || !env) { log([NSString stringWithFormat:@"[jvm] FATAL JNI_CreateJavaVM rc=%d", rc]); return -14; }
    // Publish the VM so the main thread's live-blit + touch bridge can attach into it (atomic store —
    // the main thread loads it with atomic_load in ensureBridge).
    atomic_store(&gVM, vm);
    log(@"[jvm] JavaVM created — HotSpot is up. Running probes…");
    dumpFaultState("post-createvm");   // STRIP AFTER DIAGNOSIS — HotSpot's own SIGSEGV handler is installed now

    if (kMuiNativeColorProbe) { runMuiNativeColorProbe(log); }  // STRIP AFTER DEVICE CONFIRM

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

// Monotonic counter that advances each time an in-game left tap resolved to an interactable (hit or snap),
// not a bare walk. The shell polls this and fires a light haptic when it changes. 0 if the jar predates it.
+ (int)interactTapSeq {
    if (!ensureBridge() || !gMidInteractSeq) return 0;
    JNIEnv *env = gMainEnv;
    jint v = (*env)->CallStaticIntMethod(env, gBridgeClass, gMidInteractSeq);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return (int)v;
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

+ (void)postClickPreciseX:(int)x y:(int)y button:(int)button {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidClickPrecise, (jint)x, (jint)y, (jint)button);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

// Select an open-menu row at (x,y) AND clear the parked hover afterwards (avoids the "cursor stuck on the
// stairs" bug). Falls back to a plain precise click if the jar predates selectMenuPoint.
+ (void)postMenuSelectX:(int)x y:(int)y {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    if (gMidMenuSelect) {
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidMenuSelect, (jint)x, (jint)y);
    } else {
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidClickPrecise, (jint)x, (jint)y, (jint)1);
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postMoveX:(int)x y:(int)y {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidMove, (jint)x, (jint)y);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postWheelX:(int)x y:(int)y rotation:(int)rotation {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidWheel, (jint)x, (jint)y, (jint)rotation);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postCameraDx:(int)dx dy:(int)dy {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidCamera, (jint)dx, (jint)dy);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postClearHover {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidClearHover);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (void)postText:(NSString *)text {
    if (!ensureBridge() || text.length == 0) return;
    JNIEnv *env = gMainEnv;
    jstring js = (*env)->NewStringUTF(env, text.UTF8String);
    if (js) {
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidText, js);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
        (*env)->DeleteLocalRef(env, js);
    }
}

+ (void)postKeyStroke:(int)awtKeyCode {
    if (!ensureBridge()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gBridgeClass, gMidKey, (jint)awtKeyCode);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

+ (BOOL)textInputOpen {
    if (!ensureBridge()) return NO;
    JNIEnv *env = gMainEnv;
    jboolean r = (*env)->CallStaticBooleanMethod(env, gBridgeClass, gMidInputOpen);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    return r == JNI_TRUE;
}

+ (NSString *)chromeSnapshot {
    if (!ensureChrome()) return nil;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gChromeClass, gMidChromeSnap);
    if (!js || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
    NSString *out = utf ? [NSString stringWithUTF8String:utf] : nil;
    if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
    (*env)->DeleteLocalRef(env, js);
    return out;
}

+ (NSString *)menuPeekSnapshot {
    if (!ensureMenuPeek()) return nil;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMenuPeekClass, gMidMenuPeekSnap);
    if (!js || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
    NSString *out = utf ? [NSString stringWithUTF8String:utf] : nil;
    if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
    (*env)->DeleteLocalRef(env, js);
    return out;
}

+ (NSString *)hudSnapshot {
    if (!ensureHud()) return nil;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gHudClass, gMidHudSnap);
    if (!js || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
    NSString *out = utf ? [NSString stringWithUTF8String:utf] : nil;
    if (utf) (*env)->ReleaseStringUTFChars(env, js, utf);
    (*env)->DeleteLocalRef(env, js);
    return out;
}

+ (int)hudSeq {
    if (!ensureHud()) return 0;
    JNIEnv *env = gMainEnv;
    jint s = (*env)->CallStaticIntMethod(env, gHudClass, gMidHudSeq);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return (int)s;
}

+ (void)setEngineChatHidden:(BOOL)hidden {
    if (!ensureVeneer()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gVeneerClass, gMidSetChatHidden, (jboolean)(hidden ? JNI_TRUE : JNI_FALSE));
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); }
}

// ---- Phase 6 (W3): plugin-management + config panel facade wrappers --------------------------------

+ (NSString *)mobilePlugins {
    if (!ensureMui()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiList));
}

+ (NSString *)mobilePluginConfig:(NSString *)group {
    if (!ensureMui() || group == nil) return nil;
    JNIEnv *env = gMainEnv;
    jstring jg = (*env)->NewStringUTF(env, group.UTF8String);
    NSString *out = jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiGetCfg, jg));
    (*env)->DeleteLocalRef(env, jg);
    return out;
}

+ (NSString *)mobileSetPluginEnabled:(NSString *)className enabled:(BOOL)enabled {
    if (!ensureMui() || className == nil) return nil;
    JNIEnv *env = gMainEnv;
    jstring jc = (*env)->NewStringUTF(env, className.UTF8String);
    NSString *out = jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiSetEnabled,
                                                                             jc, (jboolean)(enabled ? JNI_TRUE : JNI_FALSE)));
    (*env)->DeleteLocalRef(env, jc);
    return out;
}

+ (NSString *)mobileSetConfigGroup:(NSString *)group key:(NSString *)key value:(NSString *)value {
    if (!ensureMui() || group == nil || key == nil || value == nil) return nil;
    JNIEnv *env = gMainEnv;
    jstring jg = (*env)->NewStringUTF(env, group.UTF8String);
    jstring jk = (*env)->NewStringUTF(env, key.UTF8String);
    jstring jv = (*env)->NewStringUTF(env, value.UTF8String);
    NSString *out = jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiSetCfg, jg, jk, jv));
    (*env)->DeleteLocalRef(env, jg); (*env)->DeleteLocalRef(env, jk); (*env)->DeleteLocalRef(env, jv);
    return out;
}

+ (NSString *)mobileResetConfigGroup:(NSString *)group key:(NSString *)key {
    if (!ensureMui() || group == nil || key == nil) return nil;
    JNIEnv *env = gMainEnv;
    jstring jg = (*env)->NewStringUTF(env, group.UTF8String);
    jstring jk = (*env)->NewStringUTF(env, key.UTF8String);
    NSString *out = jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiResetCfg, jg, jk));
    (*env)->DeleteLocalRef(env, jg); (*env)->DeleteLocalRef(env, jk);
    return out;
}

+ (long long)mobileUiSeq {
    if (!ensureMui()) return 0;
    JNIEnv *env = gMainEnv;
    jlong v = (*env)->CallStaticLongMethod(env, gMuiClass, gMuiSeq);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return (long long)v;
}

+ (NSString *)mobilePollEventsSince:(long long)sinceSeq {
    if (!ensureMui()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiPoll, (jlong)sinceSeq));
}

+ (long long)mobileProcessedWriteId {
    if (!ensureMui()) return 0;
    JNIEnv *env = gMainEnv;
    jlong v = (*env)->CallStaticLongMethod(env, gMuiClass, gMuiProcessed);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return (long long)v;
}

+ (void)mobileFlushConfig {
    if (!ensureMui()) return;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiFlush);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (js) (*env)->DeleteLocalRef(env, js);
}

+ (NSString *)mobileHubManifest {
    if (!ensureMui()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiHubManifest));
}

+ (NSString *)mobileHubInstall:(NSString *)internalName uninstall:(BOOL)uninstall {
    if (!ensureMui() || internalName == nil) return nil;
    JNIEnv *env = gMainEnv;
    jstring jn = (*env)->NewStringUTF(env, internalName.UTF8String);
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass,
                                                         uninstall ? gMuiHubUninstall : gMuiHubInstall, jn);
    NSString *out = jstringToNS(env, js);
    (*env)->DeleteLocalRef(env, jn);
    return out;
}

// ---- Plugin side-panels (toolbar + embedded-Swing panel surface) -----------------------------------

+ (NSString *)mobileListPanels {
    if (!ensureMui()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiListPanels));
}

+ (void)mobileOpenPanel:(int)index {
    if (!ensureMui()) return;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiOpenPanel, (jint)index);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (js) (*env)->DeleteLocalRef(env, js);
}

+ (void)mobileClosePanel {
    if (!ensureMui()) return;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiClosePanel);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (js) (*env)->DeleteLocalRef(env, js);
}

+ (int)mobilePanelFrameSeq {
    if (!ensureMui()) return 0;
    JNIEnv *env = gMainEnv;
    jint v = (*env)->CallStaticIntMethod(env, gMuiClass, gMuiPanelSeq);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return (int)v;
}

+ (void)mobilePanelClickX:(int)x y:(int)y {
    if (!ensureMui()) return;
    JNIEnv *env = gMainEnv;
    jstring js = (jstring)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiPanelClick, (jint)x, (jint)y);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return; }
    if (js) (*env)->DeleteLocalRef(env, js);
}

// Pull the active panel's full ARGB image (PANEL_W x panelH). Opaque dark-theme pixels, so the same
// alpha-skip/little-endian setup as latestFrame works.
+ (UIImage *)mobilePanelImage {
    if (!ensureMui()) return nil;
    JNIEnv *env = gMainEnv;
    jint w = (*env)->CallStaticIntMethod(env, gMuiClass, gMuiPanelW);
    jint h = (*env)->CallStaticIntMethod(env, gMuiClass, gMuiPanelH);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    if (w <= 0 || h <= 0) return nil;
    jintArray arr = (jintArray)(*env)->CallStaticObjectMethod(env, gMuiClass, gMuiPanelPixels);
    if (!arr || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return nil; }
    jsize len = (*env)->GetArrayLength(env, arr);
    NSUInteger need = (NSUInteger)w * (NSUInteger)h;
    // Validate against a torn read: the EDT repaint publishes w/h then pixels (Java side), so a frame caught
    // mid-swap can have a pixel array that doesn't match w*h — skip it (the next tick is consistent).
    if ((NSUInteger)len < need) { (*env)->DeleteLocalRef(env, arr); return nil; }
    uint32_t *buf = (uint32_t *)malloc(need * sizeof(uint32_t));
    if (!buf) { (*env)->DeleteLocalRef(env, arr); return nil; }
    (*env)->GetIntArrayRegion(env, arr, 0, (jsize)need, (jint *)buf);
    (*env)->DeleteLocalRef(env, arr);
    CFDataRef data = CFDataCreate(NULL, (const UInt8 *)buf, (CFIndex)(need * sizeof(uint32_t)));
    free(buf);
    CGDataProviderRef provider = CGDataProviderCreateWithCFData(data);
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGBitmapInfo info = (CGBitmapInfo)(kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little);
    CGImageRef cg = CGImageCreate((size_t)w, (size_t)h, 8, 32, (size_t)w * 4, cs, info,
                                  provider, NULL, false, kCGRenderingIntentDefault);
    UIImage *img = cg ? [UIImage imageWithCGImage:cg] : nil;
    if (cg) CGImageRelease(cg);
    CGColorSpaceRelease(cs);
    CGDataProviderRelease(provider);
    CFRelease(data);
    return img;
}

// ---- Native Quest Helper reflow (QuestHelperBridge) ------------------------------------------------

+ (BOOL)questAvailable {
    if (!ensureQuest()) return NO;
    JNIEnv *env = gMainEnv;
    jboolean v = (*env)->CallStaticBooleanMethod(env, gQhClass, gQhAvailable);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    return v ? YES : NO;
}

+ (NSString *)questListJson {
    if (!ensureQuest()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gQhClass, gQhList));
}

+ (NSString *)questActiveJson {
    if (!ensureQuest()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gQhClass, gQhActive));
}

+ (void)questStart:(NSString *)name {
    if (!ensureQuest() || name == nil) return;
    JNIEnv *env = gMainEnv;
    jstring js = (*env)->NewStringUTF(env, name.UTF8String);
    (*env)->CallStaticVoidMethod(env, gQhClass, gQhStart, js);
    if (js) (*env)->DeleteLocalRef(env, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

+ (void)questStop {
    if (!ensureQuest()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gQhClass, gQhStop);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

+ (void)questSetUiOpen:(BOOL)open {
    if (!ensureQuest()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gQhClass, gQhSetOpen, (jboolean)(open ? 1 : 0));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

// ---- Native World Switcher reflow (WorldSwitcherBridge) --------------------------------------------

+ (BOOL)worldAvailable {
    if (!ensureWorld()) return NO;
    JNIEnv *env = gMainEnv;
    jboolean v = (*env)->CallStaticBooleanMethod(env, gWsClass, gWsAvailable);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return NO; }
    return v ? YES : NO;
}

+ (NSString *)worldListJson {
    if (!ensureWorld()) return nil;
    JNIEnv *env = gMainEnv;
    return jstringToNS(env, (jstring)(*env)->CallStaticObjectMethod(env, gWsClass, gWsList));
}

+ (void)worldHop:(int)worldId {
    if (!ensureWorld()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gWsClass, gWsHop, (jint)worldId);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

+ (void)worldSetUiOpen:(BOOL)open {
    if (!ensureWorld()) return;
    JNIEnv *env = gMainEnv;
    (*env)->CallStaticVoidMethod(env, gWsClass, gWsSetOpen, (jboolean)(open ? 1 : 0));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

@end
