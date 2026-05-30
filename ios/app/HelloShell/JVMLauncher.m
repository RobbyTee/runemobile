#import "JVMLauncher.h"
#import <dlfcn.h>
#import <signal.h>
#import <pthread.h>
#import <unistd.h>
#import <sys/mman.h>
#import <mach/mach.h>
#import <libkern/OSCacheControl.h>

// --- csops / CS_DEBUGGED (private; same probe Amethyst/StikDebug rely on) -----
extern int csops(pid_t pid, unsigned int ops, void *useraddr, size_t usersize);
#ifndef CS_OPS_STATUS
#define CS_OPS_STATUS 0
#endif
#ifndef CS_DEBUGGED
#define CS_DEBUGGED 0x10000000
#endif

// --- JLI_Launch signature (jni-free; jboolean=u8, jint=int) -------------------
typedef unsigned char jli_bool;
typedef int           jli_int;
typedef jli_int (*JLI_Launch_t)(int argc, char **argv,
                                int jargc, const char **jargv,
                                int appclassc, const char **appclassv,
                                const char *fullversion, const char *dotversion,
                                const char *pname, const char *lname,
                                jli_bool javaargs, jli_bool cpwildcard,
                                jli_bool javaw, jli_int ergo);

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

- (int)launchHelloWithLog:(JVMLogSink)log {
    NSString *javaHome = [JVMLauncher javaHome];
    if (!javaHome) { log(@"[jvm] FATAL: embedded jre not found in bundle"); return -10; }
    log([NSString stringWithFormat:@"[jvm] JAVA_HOME = %@", javaHome]);

    NSString *libjli = [javaHome stringByAppendingPathComponent:@"lib/libjli.dylib"];
    NSString *jarPath = [[NSBundle mainBundle] pathForResource:@"Hello" ofType:@"jar"];
    if (!jarPath) { log(@"[jvm] FATAL: Hello.jar not found in bundle"); return -11; }

    // JAVA_HOME must be set so HotSpot finds its modules/libs.
    setenv("JAVA_HOME", javaHome.UTF8String, 1);

    // The iOS JRE launcher otherwise runs main() on a second thread and tries to own
    // the GUI main thread by creating a UIApplication -> "There can only be one
    // UIApplication instance" (we already have one). libjli skips that whole dance if
    // this env var is merely present (getenv != NULL); confirmed in libjli disasm.
    setenv("HACK_IGNORE_START_ON_FIRST_THREAD", "1", 1);

    // the W1-audit Claim A is an EXPERIMENT: try the clean dlopen first. If iOS
    // library validation rejects the re-signed (or ad-hoc) dylib, dlerror()
    // tells us the bypass is mandatory.
    log([NSString stringWithFormat:@"[jvm] dlopen %@", libjli]);
    void *h = dlopen(libjli.UTF8String, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        log([NSString stringWithFormat:@"[jvm] FATAL dlopen(libjli): %s", dlerror()]);
        log(@"[jvm] -> library validation likely rejected it; dyld bypass needed (Claim A REFUTED)");
        return -12;
    }
    JLI_Launch_t JLI_Launch = (JLI_Launch_t)dlsym(h, "JLI_Launch");
    if (!JLI_Launch) {
        log([NSString stringWithFormat:@"[jvm] FATAL dlsym(JLI_Launch): %s", dlerror()]);
        return -13;
    }
    log(@"[jvm] JLI_Launch resolved");

    NSString *frameworks = [[NSBundle mainBundle].bundlePath stringByAppendingPathComponent:@"Frameworks"];

    // argv[0] must be <javaHome>/bin/java (the W1-audit Claim C) so JLI derives paths.
    NSMutableArray<NSString *> *args = [NSMutableArray array];
    [args addObject:[javaHome stringByAppendingPathComponent:@"bin/java"]];

    // iOS-26 code cache (Amethyst jre21 patch) + experimental gate.
    [args addObject:@"-XX:+UnlockExperimentalVMOptions"];
    [args addObject:@"-XX:+MirrorMappedCodeCache"];
    [args addObject:@"-XX:+DisablePrimordialThreadGuardPages"];
    // Free cert has no extended-virtual-addressing -> shrink the heap VA reqs.
    [args addObject:@"-XX:-UseCompressedClassPointers"];
    // Bound the code cache so the brk #0x69 RX reservation stays small (Hello needs
    // little; the StikDebug script allocates the requested size via _M …,rx).
    [args addObject:@"-XX:ReservedCodeCacheSize=64m"];

    [args addObject:[NSString stringWithFormat:@"-Djava.home=%@", javaHome]];
    [args addObject:[NSString stringWithFormat:@"-Djava.library.path=%@", frameworks]];
    [args addObject:@"-cp"];
    [args addObject:jarPath];
    [args addObject:@"Hello"];

    int argc = (int)args.count;
    char **argv = (char **)calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        argv[i] = strdup(args[i].UTF8String);
        log([NSString stringWithFormat:@"[jvm] argv[%d] = %@", i, args[i]]);
    }

    resetSignalHandlers();
    log(@"[jvm] calling JLI_Launch (HotSpot generates its template interpreter now)…");

    int rc = JLI_Launch(argc, argv,
                        0, NULL,
                        0, NULL,
                        "21.0.8", "21.0.8",
                        "java", "openjdk",
                        (jli_bool)0,   // javaargs
                        (jli_bool)1,   // cpwildcard
                        (jli_bool)0,   // javaw
                        (jli_int)0);   // ergo

    log([NSString stringWithFormat:@"[jvm] JLI_Launch returned %d", rc]);
    return rc;
}

@end
