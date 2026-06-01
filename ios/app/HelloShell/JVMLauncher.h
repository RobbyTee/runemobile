#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

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

/// dlopen(libjvm) -> JNI_CreateJavaVM -> run JitProbe, Bench, GlyphSmoke in one VM,
/// then DestroyJavaVM. Blocks until done. MUST be called on a dedicated thread with
/// JIT already live (CS_DEBUGGED + the StikDebug brk #0x69 script). Returns 0 on a
/// clean VM lifecycle, negative on a fatal setup error.
- (int)runProbesWithLog:(JVMLogSink)log;

// ---- Phase 4 (W3) sub-goal B: reverse-JNI bridge (called from the main/UI thread) ------------------

/// True once the VM is up and the JavaBridge class has been resolved — i.e. live blit + touch are usable.
+ (BOOL)bridgeReady;

/// Pull the engine's latest composited frame as a UIImage (reverse JNI -> JavaBridge.getFramePixels).
/// Returns nil before the first draw / if the VM isn't up yet. Call on the main thread.
+ (nullable UIImage *)latestFrame;

/// Post a DRAG phase into the gamepack via JavaBridge.onTouch. `type`: 0=down, 1=move, 2=up;
/// `x`,`y` are gamepack-canvas (765x503) pixels; `button` is 1 (left) or 3 (right). A release does NOT
/// synthesize a click (use postClick for a tap). Call on the main thread. Used by the camera/scroll drag.
+ (void)postTouchType:(int)type x:(int)x y:(int)y button:(int)button;

/// Post a discrete TAP into the gamepack via JavaBridge.onClick (the full press+release+click at one
/// point). `x`,`y` are gamepack-canvas (765x503) pixels; `button` is 1 (left tap / menu-entry select) or
/// 3 (long-press: open the right-click menu). Call on the main thread.
+ (void)postClickX:(int)x y:(int)y button:(int)button;

/// Move the pointer (no button held) into the gamepack via JavaBridge.onMove — a plain MOUSE_MOVED.
/// Used to highlight the entry under the finger while drag-selecting an open right-click menu.
/// `x`,`y` are gamepack-canvas (765x503) pixels. Call on the main thread.
+ (void)postMoveX:(int)x y:(int)y;

/// Scroll the wheel via JavaBridge.onWheel (a MouseWheelEvent). `x`,`y` are gamepack-canvas pixels (the
/// pinch centroid); `rotation` notches: negative = zoom in. Used by pinch-to-zoom. Call on the main thread.
+ (void)postWheelX:(int)x y:(int)y rotation:(int)rotation;

/// Rotate the camera via JavaBridge.onCameraDrag — raw two-finger-pan pixel deltas applied to the camera
/// yaw/pitch targets directly (no middle-mouse). Call on the main thread.
+ (void)postCameraDx:(int)dx dy:(int)dy;

/// Type committed text via JavaBridge.onText (KEY_PRESSED+KEY_TYPED+KEY_RELEASED per char). From the
/// hidden UITextField's change callback. Call on the main thread.
+ (void)postText:(NSString *)text;

/// Press+release one non-text key via JavaBridge.onKeyStroke, by AWT virtual keycode (8=backspace,
/// 10=enter, 27=escape, 9=tab, 37/38/39/40=arrows). Call on the main thread.
+ (void)postKeyStroke:(int)awtKeyCode;

@end

NS_ASSUME_NONNULL_END
