#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Result/diagnostic callback. `line` is one log line (already newline-free);
/// the UI appends it to the on-screen console and it is also written to
/// Documents/hello-shell.log for retrieval off-device.
typedef void (^JVMLogSink)(NSString *line);

@interface JVMLauncher : NSObject

/// CS_DEBUGGED gate (the W1-audit Claim E): true once StikDebug has attached and the
/// kernel marks this process debugged. Poll this BEFORE warming HotSpot.
+ (BOOL)isDebuggerAttached;

/// Optional RWX canary (the W1-audit Claim E "tiny TXM preflight"). Maps a page,
/// writes `mov w0,#42; ret`, flips it to RX and calls it. Returns 42 on
/// success. NOTE: a plain mprotect RW->RX canary may FALSE-NEGATIVE under the
/// MirrorMappedCodeCache path HotSpot actually uses — treat a crash here as
/// "naive RWX is blocked", not "HotSpot will fail". Diagnostic only.
+ (int)runRwxCanary:(JVMLogSink)log;

/// Resolve the embedded JRE home inside the .app bundle (".../jre").
+ (nullable NSString *)javaHome;

/// dlopen(libjli) -> dlsym(JLI_Launch) -> run `Hello`. Blocks until the JVM
/// exits (Hello returns) or aborts. MUST be called on a dedicated thread with
/// JIT already live. Returns the JLI_Launch exit code (or a negative error).
- (int)launchHelloWithLog:(JVMLogSink)log;

@end

NS_ASSUME_NONNULL_END
