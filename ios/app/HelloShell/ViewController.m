#import "ViewController.h"
#import "JVMLauncher.h"
#import <unistd.h>
#import <fcntl.h>

@interface ViewController () <UITextFieldDelegate>
@property (nonatomic, strong) UITextView *console;
@property (nonatomic, strong) UIButton *launchButton;
@property (nonatomic, strong) UIButton *canaryButton;
@property (nonatomic, strong) UIButton *keyboardButton;
// Collapsible debug overlay: a small always-on-top toggle shows/hides a panel holding the status,
// launch/canary buttons and the log console, so the game stays full-screen.
@property (nonatomic, strong) UIView *debugPanel;
@property (nonatomic, strong) UIButton *debugToggle;
// Hidden capture field: becomes first responder to raise the soft keyboard; its change/return callbacks
// are translated into AWT KeyEvents. It never actually holds text (a zero-width sentinel keeps backspace
// firing, and every change returns NO).
@property (nonatomic, strong) UITextField *kbField;
@property (nonatomic, strong) UILabel *statusLabel;
@property (nonatomic, strong) NSTimer *jitTimer;
@property (nonatomic, strong) NSFileHandle *logFile;
@property (nonatomic, assign) int jvmFd;   // raw fd for crash-proof libjvm stdout/stderr
@property (nonatomic, assign) BOOL launched;

// W3 native shell: keep the process alive + show frames. The JVM ran fine on device but iOS
// jetsam'd the (idle-UI) process within ~250 ms — before the engine drew its first frame. Two
// protections: (1) a UIBackgroundTask assertion so the OS treats the JVM run as important work and
// won't suspend/kill it; (2) an ACTIVE CADisplayLink render loop that polls the capture dir and
// blits the newest frame-*.png to an on-screen image view — keeping the app in the foreground
// "actively rendering" jetsam band AND showing the live login screen (the start of the real UI).
@property (nonatomic, strong) UIImageView *screen;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) UIBackgroundTaskIdentifier bgTask;
@property (nonatomic, copy)   NSString *captureDir;
@property (nonatomic, copy)   NSString *lastFrameName;
@property (nonatomic, assign) int framesShown;
// Drag-to-select right-click menu: while a long-press is held, the menu is open and the finger's
// position tracks/highlights an entry; release selects it (or cancels, off the entries).
@property (nonatomic, assign) BOOL menuOpen;
@property (nonatomic, assign) int  lastMenuX;
@property (nonatomic, assign) int  lastMenuY;
// Pinch-zoom: accumulate the scale change into whole wheel notches.
@property (nonatomic, assign) CGFloat pinchPrevScale;
@property (nonatomic, assign) CGFloat wheelAccum;
// Contextual keyboard: previous gamepack text-input state, for edge-triggered auto raise/dismiss.
@property (nonatomic, assign) BOOL prevInputOpen;
@end

// AWT virtual keycodes for the non-text keys we synthesize (java.awt.event.KeyEvent.VK_*).
static const int AWT_VK_BACK_SPACE = 8;
static const int AWT_VK_ENTER      = 10;
// Zero-width sentinel so a backspace on an "empty" field still fires shouldChangeCharactersInRange.
static NSString * const kKbSentinel = @"​";

@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    self.bgTask = UIBackgroundTaskInvalid;
    [self buildUI];
    [self openLogFile];
    [self appendLine:@"HelloShell — W1 minimal JVM shell"];
    [self appendLine:[NSString stringWithFormat:@"jre: %@",
                      [JVMLauncher javaHome] ?: @"(MISSING from bundle!)"]];
    [self appendLine:@"Waiting for StikDebug attach (CS_DEBUGGED)…"];
    [self appendLine:@"Assign the iOS-26 JIT script to this app in StikDebug, then attach."];
    [self startJitPolling];
}

- (void)buildUI {
    // (0) Game screen — the BOTTOM layer, filling the entire screen edge-to-edge (full-screen landscape).
    self.screen = [[UIImageView alloc] init];
    self.screen.translatesAutoresizingMaskIntoConstraints = NO;
    self.screen.backgroundColor = [UIColor colorWithWhite:0.06 alpha:1.0];
    self.screen.contentMode = UIViewContentModeScaleAspectFit;   // render aspect == screen aspect -> fills
    self.screen.layer.magnificationFilter = kCAFilterLinear;     // smooth when scaled to native res
    self.screen.userInteractionEnabled = YES;                    // receive the game gestures
    [self.view addSubview:self.screen];

    // Phase 5 control scheme on the game view. UIKit gesture recognizers own classification: tap = left
    // click (walk/interact/menu-entry), long-press = drag-to-select menu, one-finger drag = camera rotate,
    // pinch = zoom. Mutual-exclusivity disambiguates the one-finger trio.
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(onTap:)];
    [self.screen addGestureRecognizer:tap];
    UILongPressGestureRecognizer *longPress = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onLongPress:)];
    longPress.minimumPressDuration = 0.4;
    [self.screen addGestureRecognizer:longPress];
    UIPanGestureRecognizer *camPan = [[UIPanGestureRecognizer alloc]
        initWithTarget:self action:@selector(onCameraPan:)];
    camPan.minimumNumberOfTouches = 1;
    camPan.maximumNumberOfTouches = 1;
    [self.screen addGestureRecognizer:camPan];
    UIPinchGestureRecognizer *pinch = [[UIPinchGestureRecognizer alloc]
        initWithTarget:self action:@selector(onPinch:)];
    [self.screen addGestureRecognizer:pinch];

    // In-game keyboard control, bottom-left of the game's safe area (by the chat box). A button subview
    // consumes its own taps, so it never leaks a walk-here click to the gestures beneath.
    self.keyboardButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.keyboardButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.keyboardButton setTitle:@"⌨︎" forState:UIControlStateNormal];
    self.keyboardButton.titleLabel.font = [UIFont systemFontOfSize:22];
    self.keyboardButton.tintColor = [UIColor whiteColor];
    self.keyboardButton.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.45];
    self.keyboardButton.layer.cornerRadius = 8;
    [self.keyboardButton addTarget:self action:@selector(onToggleKeyboard)
                  forControlEvents:UIControlEventTouchUpInside];
    [self.screen addSubview:self.keyboardButton];

    // Hidden capture field — placed offscreen (not hidden, so it can still become first responder).
    self.kbField = [[UITextField alloc] initWithFrame:CGRectMake(-200, -200, 10, 10)];
    self.kbField.delegate = self;
    self.kbField.autocorrectionType = UITextAutocorrectionTypeNo;          // raw game input
    self.kbField.autocapitalizationType = UITextAutocapitalizationTypeNone;
    self.kbField.spellCheckingType = UITextSpellCheckingTypeNo;
    self.kbField.smartQuotesType = UITextSmartQuotesTypeNo;
    self.kbField.smartDashesType = UITextSmartDashesTypeNo;
    self.kbField.smartInsertDeleteType = UITextSmartInsertDeleteTypeNo;
    self.kbField.keyboardType = UIKeyboardTypeASCIICapable;
    self.kbField.returnKeyType = UIReturnKeyDefault;
    [self.view addSubview:self.kbField];

    // (1) Debug panel — overlay ABOVE the game (so the launch button is tappable; the old bug was the
    // full-screen game view covering it), translucent so the game shows behind. Collapsed by the ≡ toggle.
    self.debugPanel = [[UIView alloc] init];
    self.debugPanel.translatesAutoresizingMaskIntoConstraints = NO;
    self.debugPanel.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.82];
    self.debugPanel.layer.cornerRadius = 10;
    self.debugPanel.clipsToBounds = YES;
    [self.view addSubview:self.debugPanel];

    self.statusLabel = [[UILabel alloc] init];
    self.statusLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.statusLabel.textColor = [UIColor systemYellowColor];
    self.statusLabel.font = [UIFont boldSystemFontOfSize:14];
    self.statusLabel.text = @"JIT: waiting…";
    [self.debugPanel addSubview:self.statusLabel];

    self.launchButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.launchButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.launchButton setTitle:@"Run AWT activation (§6.0)" forState:UIControlStateNormal];
    self.launchButton.titleLabel.font = [UIFont boldSystemFontOfSize:17];
    self.launchButton.enabled = NO;
    [self.launchButton addTarget:self action:@selector(onLaunch)
                forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.launchButton];

    self.canaryButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.canaryButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.canaryButton setTitle:@"RWX canary" forState:UIControlStateNormal];
    [self.canaryButton addTarget:self action:@selector(onCanary)
                forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.canaryButton];

    self.console = [[UITextView alloc] init];
    self.console.translatesAutoresizingMaskIntoConstraints = NO;
    self.console.editable = NO;
    self.console.backgroundColor = [UIColor clearColor];
    self.console.textColor = [UIColor greenColor];
    self.console.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightRegular];
    [self.debugPanel addSubview:self.console];

    // (2) Debug toggle — always on top (added last), top-right. Expands/collapses the debug panel so the
    // game can stay full-screen.
    self.debugToggle = [UIButton buttonWithType:UIButtonTypeSystem];
    self.debugToggle.translatesAutoresizingMaskIntoConstraints = NO;
    [self.debugToggle setTitle:@"≡" forState:UIControlStateNormal];
    self.debugToggle.titleLabel.font = [UIFont boldSystemFontOfSize:22];
    self.debugToggle.tintColor = [UIColor whiteColor];
    self.debugToggle.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.5];
    self.debugToggle.layer.cornerRadius = 8;
    [self.debugToggle addTarget:self action:@selector(onToggleDebug)
               forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.debugToggle];

    UILayoutGuide *g = self.view.safeAreaLayoutGuide;
    UILayoutGuide *sg = self.screen.safeAreaLayoutGuide;
    UILayoutGuide *p = self.debugPanel.layoutMarginsGuide;
    [NSLayoutConstraint activateConstraints:@[
        // Game fills the SAFE AREA (not edge-to-edge): edge-to-edge pushed the OSRS corner UI (minimap,
        // inventory, chat) under the rounded corners / dynamic island / home indicator. Filling the safe
        // area keeps everything on-screen; the render aspect is computed to match it (onLaunch) so there
        // are no internal letterbox bars either.
        [self.screen.topAnchor constraintEqualToAnchor:g.topAnchor],
        [self.screen.leadingAnchor constraintEqualToAnchor:g.leadingAnchor],
        [self.screen.trailingAnchor constraintEqualToAnchor:g.trailingAnchor],
        [self.screen.bottomAnchor constraintEqualToAnchor:g.bottomAnchor],

        // In-game keyboard control, bottom-left of the game's safe area.
        [self.keyboardButton.bottomAnchor constraintEqualToAnchor:sg.bottomAnchor constant:-8],
        [self.keyboardButton.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:8],
        [self.keyboardButton.widthAnchor constraintEqualToConstant:44],
        [self.keyboardButton.heightAnchor constraintEqualToConstant:36],

        // Debug toggle, top-right safe area.
        [self.debugToggle.topAnchor constraintEqualToAnchor:g.topAnchor constant:8],
        [self.debugToggle.trailingAnchor constraintEqualToAnchor:g.trailingAnchor constant:-8],
        [self.debugToggle.widthAnchor constraintEqualToConstant:44],
        [self.debugToggle.heightAnchor constraintEqualToConstant:36],

        // Debug panel — left ~62% of the safe area, full height, below the toggle row.
        [self.debugPanel.topAnchor constraintEqualToAnchor:g.topAnchor constant:8],
        [self.debugPanel.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:8],
        [self.debugPanel.bottomAnchor constraintEqualToAnchor:g.bottomAnchor constant:-8],
        [self.debugPanel.widthAnchor constraintEqualToAnchor:g.widthAnchor multiplier:0.62],

        // Panel contents.
        [self.statusLabel.topAnchor constraintEqualToAnchor:p.topAnchor],
        [self.statusLabel.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.statusLabel.trailingAnchor constraintEqualToAnchor:p.trailingAnchor],
        [self.launchButton.topAnchor constraintEqualToAnchor:self.statusLabel.bottomAnchor constant:6],
        [self.launchButton.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.canaryButton.centerYAnchor constraintEqualToAnchor:self.launchButton.centerYAnchor],
        [self.canaryButton.leadingAnchor constraintEqualToAnchor:self.launchButton.trailingAnchor constant:24],
        [self.console.topAnchor constraintEqualToAnchor:self.launchButton.bottomAnchor constant:6],
        [self.console.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.console.trailingAnchor constraintEqualToAnchor:p.trailingAnchor],
        [self.console.bottomAnchor constraintEqualToAnchor:p.bottomAnchor],
    ]];
}

- (void)onToggleDebug {
    self.debugPanel.hidden = !self.debugPanel.hidden;
}

#pragma mark - Logging

- (void)openLogFile {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"hello-shell.log"];
    [[NSFileManager defaultManager] createFileAtPath:path contents:nil attributes:nil];
    self.logFile = [NSFileHandle fileHandleForWritingAtPath:path];
    // Separate raw file for libjvm stdout/stderr, written synchronously (survives a
    // crash) so the [JIT26] mapping trace + the Hello verdict are always retrievable.
    NSString *jpath = [docs stringByAppendingPathComponent:@"jvm-stdio.log"];
    self.jvmFd = open(jpath.UTF8String, O_WRONLY | O_CREAT | O_TRUNC, 0644);
}

- (void)appendLine:(NSString *)line {
    NSString *out = [line stringByAppendingString:@"\n"];
    // Serialize ALL writes (console + file) on the main queue. The stdout-capture
    // reader runs on a background thread; writing self.logFile from two threads is a
    // data race that silently drops output after JLI_Launch. Funnel both here.
    dispatch_async(dispatch_get_main_queue(), ^{
        self.console.text = [self.console.text stringByAppendingString:out];
        if (self.console.text.length > 0) {
            [self.console scrollRangeToVisible:NSMakeRange(self.console.text.length - 1, 1)];
        }
        [self.logFile writeData:[out dataUsingEncoding:NSUTF8StringEncoding]];
        @try { [self.logFile synchronizeFile]; } @catch (__unused NSException *e) {}
    });
}

// Send HotSpot's + System.out's stdout/stderr STRAIGHT to the crash-proof file fd,
// UNBUFFERED. The earlier pipe approach lost everything on a hard crash: stdout to a
// pipe is fully buffered, so the [JIT26] trace stayed in libc's buffer and the async
// reader never drained before SIGBUS. Direct dup2 + _IONBF = every printf hits disk
// immediately, so jvm-stdio.log captures the [JIT26] mapping trace and the verdict
// even when the VM aborts. (On-screen no longer shows JVM output; it's in the file.)
- (void)captureStdio {
    if (self.jvmFd >= 0) {
        dup2(self.jvmFd, STDOUT_FILENO);
        dup2(self.jvmFd, STDERR_FILENO);
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    [self appendLine:@"(JVM stdout/stderr -> Documents/jvm-stdio.log)"];
}

#pragma mark - JIT gate (the W1-audit Claim E)

- (void)startJitPolling {
    self.jitTimer = [NSTimer scheduledTimerWithTimeInterval:0.2 repeats:YES block:^(NSTimer *t) {
        BOOL attached = [JVMLauncher isDebuggerAttached];
        self.statusLabel.text = attached ? @"JIT: CS_DEBUGGED ✓ (ready)" : @"JIT: waiting…";
        self.statusLabel.textColor = attached ? [UIColor systemGreenColor] : [UIColor systemYellowColor];
        if (attached && !self.launchButton.enabled && !self.launched) {
            self.launchButton.enabled = YES;
            [self appendLine:@"StikDebug attached — CS_DEBUGGED set. Tap Start JVM."];
        }
    }];
}

#pragma mark - Actions

- (void)onCanary {
    JVMLogSink log = ^(NSString *line) { [self appendLine:line]; };
    [self appendLine:@"--- RWX canary ---"];
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        [JVMLauncher runRwxCanary:log];
    });
}

- (void)onLaunch {
    if (self.launched) return;
    self.launched = YES;
    self.launchButton.enabled = NO;
    [self.jitTimer invalidate];
    [self captureStdio];

    // Full-screen landscape: collapse the debug panel so the game owns the whole screen. The ≡ toggle
    // re-opens it (logs/canary), and logs still go to Documents/hello-shell.log; the ⌨ control stays.
    self.debugPanel.hidden = YES;

    // (1) Background-task assertion: tell iOS this is important in-progress work so it won't
    // suspend/kill the process while the JVM runs off-main. (The device jetsam'd the idle-UI
    // process within ~250 ms before; this is the primary longevity fix.)
    self.bgTask = [[UIApplication sharedApplication]
        beginBackgroundTaskWithName:@"jvm-run" expirationHandler:^{
            [self appendLine:@"[shell] background task expiring"];
            [self endBgTask];
        }];
    [self appendLine:[NSString stringWithFormat:@"[shell] bgTask=%lu", (unsigned long)self.bgTask]];

    // (2) Active render loop: poll the capture dir and blit the newest frame every screen refresh.
    // Keeps the app foreground-active+rendering (top jetsam band) AND shows the login screen live.
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    self.captureDir = [docs stringByAppendingPathComponent:@"rl-capture"];
    self.framesShown = 0;
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(onDisplayTick)];
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];

    JVMLogSink log = ^(NSString *line) { [self appendLine:line]; };
    [self appendLine:@"--- launching JVM on worker thread ---"];

    JVMLauncher *launcher = [[JVMLauncher alloc] init];
    // Compute the render resolution to MATCH the SAFE-AREA aspect (the game view fills the safe area), so
    // the blit fills it with no letterbox and nothing clipped under the island/corners/home-indicator.
    // Render height ~600 (a comfortable in-game UI scale); width follows the aspect.
    CGRect b = self.view.bounds;
    UIEdgeInsets ins = self.view.safeAreaInsets;
    CGFloat sw = b.size.width - ins.left - ins.right;
    CGFloat sh = b.size.height - ins.top - ins.bottom;
    CGFloat aspect = (sh > 0 ? sw / sh : 2.0);
    int renderH = 600;
    int renderW = (int)lround(renderH * aspect);
    launcher.resizeSpec = [NSString stringWithFormat:@"%dx%d", renderW, renderH];
    [self appendLine:[NSString stringWithFormat:@"[shell] safe-area %.0fx%.0f aspect %.3f -> render %@",
                      sw, sh, aspect, launcher.resizeSpec]];

    NSThread *t = [[NSThread alloc] initWithBlock:^{
        [launcher runProbesWithLog:log];
        // VM lifecycle done; let the render loop drain a beat, then release the assertion.
        dispatch_async(dispatch_get_main_queue(), ^{
            [self appendLine:@"[shell] JVM thread returned"];
            [self endBgTask];
        });
    }];
    t.stackSize = 16 * 1024 * 1024;
    t.name = @"jvm-main";
    [t start];
}

// Called every screen refresh once the JVM is running. Pulls the engine's latest composited frame
// straight from the buffer via reverse JNI (JavaBridge.getFramePixels) and blits it — no PNG round-trip,
// no disk, no capture budget, so it's smooth and runs for the whole session. Also keeps the app
// foreground-active+rendering (the jetsam-protection that the §6.2 work established).
- (void)onDisplayTick {
    UIImage *img = [JVMLauncher latestFrame];
    if (img == nil) return;   // VM not up yet / no frame drawn yet
    self.screen.image = img;
    self.framesShown++;
    // Light heartbeat (~once/sec at 60 Hz) so the log shows the live blit running without flooding it.
    if (self.framesShown == 1 || self.framesShown % 60 == 0) {
        [self appendLine:[NSString stringWithFormat:@"[shell] live blit frame #%d (%.0fx%.0f)",
                          self.framesShown, img.size.width, img.size.height]];
    }

    // Contextual keyboard: when the gamepack opens a text field (search / PM / enter-amount), raise the
    // soft keyboard automatically; dismiss it when the field closes. Edge-triggered (only on a change) so
    // it never fights a keyboard the user opened manually for chat. Polled ~10 Hz to keep it cheap.
    if (self.framesShown % 6 == 0) {
        BOOL wants = [JVMLauncher textInputOpen];
        if (wants != self.prevInputOpen) {
            self.prevInputOpen = wants;
            if (wants && ![self.kbField isFirstResponder]) {
                self.kbField.text = kKbSentinel;
                [self.kbField becomeFirstResponder];
                [self appendLine:@"[shell] game opened a text field -> keyboard up"];
            } else if (!wants && [self.kbField isFirstResponder]) {
                [self.kbField resignFirstResponder];
                [self appendLine:@"[shell] game closed the text field -> keyboard down"];
            }
        }
    }
}

#pragma mark - Touch -> AWT bridge (Phase 5 control scheme)

// Map a point (in the screen image view's coordinate space) to gamepack-canvas pixels, honouring the
// ScaleAspectFit letterbox. Returns NO if the point is in the letterbox margin (outside the game frame).
- (BOOL)mapPoint:(CGPoint)p toGameX:(int *)gx toGameY:(int *)gy {
    UIImage *img = self.screen.image;
    if (img == nil) return NO;
    CGFloat iw = img.size.width, ih = img.size.height;
    if (iw <= 0 || ih <= 0) return NO;
    CGRect b = self.screen.bounds;
    CGFloat scale = MIN(b.size.width / iw, b.size.height / ih);   // ScaleAspectFit
    CGFloat dispW = iw * scale, dispH = ih * scale;
    CGFloat ox = (b.size.width - dispW) * 0.5, oy = (b.size.height - dispH) * 0.5;
    CGFloat fx = (p.x - ox) / scale, fy = (p.y - oy) / scale;
    if (fx < 0 || fy < 0 || fx >= iw || fy >= ih) return NO;   // tapped the letterbox, not the game
    *gx = (int)fx;
    *gy = (int)fy;
    return YES;
}

// tap -> left click (walk / interact / select a menu entry).
- (void)onTap:(UITapGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    int gx = 0, gy = 0;
    if (![self mapPoint:[g locationInView:self.screen] toGameX:&gx toGameY:&gy]) return;
    [JVMLauncher postClickX:gx y:gy button:1];
    [self appendLine:[NSString stringWithFormat:@"[shell] tap -> click(L) game %d,%d", gx, gy]];
}

// long-press -> drag-to-select right-click menu (the official OSRS-mobile feel). One continuous gesture
// drives the menu's whole lifecycle:
//   .began   -> right-click at the press point: opens the menu (right-mouse-down equivalent).
//   .changed -> move the pointer (no button held) to the finger: the gamepack highlights the entry the
//               finger is over, so sliding down walks the highlight through the options.
//   .ended   -> left-click at the release point: selects the highlighted entry. Releasing OFF the entries
//               lands a click outside the menu bounds, which the gamepack treats as a cancel/close.
- (void)onLongPress:(UILongPressGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    int gx = 0, gy = 0;
    BOOL inside = [self mapPoint:[g locationInView:self.screen] toGameX:&gx toGameY:&gy];
    if (inside) { self.lastMenuX = gx; self.lastMenuY = gy; }

    switch (g.state) {
        case UIGestureRecognizerStateBegan:
            if (!inside) return;                 // started in the letterbox — ignore
            [JVMLauncher postClickX:gx y:gy button:3];
            self.menuOpen = YES;
            [self appendLine:[NSString stringWithFormat:@"[shell] long-press -> menu open(R) game %d,%d", gx, gy]];
            break;

        case UIGestureRecognizerStateChanged:
            if (!self.menuOpen) return;
            // Track the finger to highlight the entry under it (use the last in-frame point if the finger
            // has slid into the letterbox, so the highlight sticks to the nearest edge entry).
            [JVMLauncher postMoveX:self.lastMenuX y:self.lastMenuY];
            break;

        case UIGestureRecognizerStateEnded:
            if (self.menuOpen) {
                // Select at the release point (or the last in-frame point). On an entry -> selects; off
                // the entries -> a click outside the menu, which the gamepack reads as cancel/close.
                [JVMLauncher postClickX:self.lastMenuX y:self.lastMenuY button:1];
                [self appendLine:[NSString stringWithFormat:@"[shell] menu select(L) game %d,%d",
                                  self.lastMenuX, self.lastMenuY]];
            }
            self.menuOpen = NO;
            break;

        case UIGestureRecognizerStateCancelled:
        case UIGestureRecognizerStateFailed:
            self.menuOpen = NO;
            break;

        default:
            break;
    }
}

// one-finger drag -> rotate the camera. Feed incremental pixel deltas (reset the recognizer's translation
// each step) to the bridge, which applies them to the camera yaw/pitch targets directly. The menuOpen
// guard is belt-and-suspenders: if a long-press already claimed the touch, never also rotate.
- (void)onCameraPan:(UIPanGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady] || self.menuOpen) return;
    if (g.state == UIGestureRecognizerStateBegan) {
        [g setTranslation:CGPointZero inView:self.screen];
        [self appendLine:@"[shell] camera pan begin (1-finger)"];
        return;
    }
    if (g.state == UIGestureRecognizerStateEnded || g.state == UIGestureRecognizerStateCancelled) {
        [self appendLine:@"[shell] camera pan end"];
        return;
    }
    if (g.state != UIGestureRecognizerStateChanged) return;
    CGPoint d = [g translationInView:self.screen];
    [g setTranslation:CGPointZero inView:self.screen];   // make the next delta incremental
    if (d.x == 0 && d.y == 0) return;
    [JVMLauncher postCameraDx:(int)lround(d.x) dy:(int)lround(d.y)];
}

// pinch -> zoom via the mouse wheel. Accumulate the scale change into whole wheel notches; pinch out
// (scale grows) = zoom in = negative rotation. Centroid is the wheel point so the gamepack zooms about it.
- (void)onPinch:(UIPinchGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    if (g.state == UIGestureRecognizerStateBegan) {
        self.pinchPrevScale = g.scale;
        self.wheelAccum = 0;
        return;
    }
    if (g.state != UIGestureRecognizerStateChanged) return;
    const CGFloat WHEEL_SENS = 12.0;   // notches per unit of scale change (tune by feel)
    CGFloat step = g.scale - self.pinchPrevScale;
    self.pinchPrevScale = g.scale;
    self.wheelAccum += -step * WHEEL_SENS;   // scale up -> zoom in -> negative rotation
    int notches = (int)(self.wheelAccum > 0 ? floor(self.wheelAccum) : ceil(self.wheelAccum));
    if (notches == 0) return;
    self.wheelAccum -= notches;
    int gx = 0, gy = 0;
    if (![self mapPoint:[g locationInView:self.screen] toGameX:&gx toGameY:&gy]) { gx = 382; gy = 251; }
    [JVMLauncher postWheelX:gx y:gy rotation:notches];
    [self appendLine:[NSString stringWithFormat:@"[shell] pinch -> wheel rot=%d at %d,%d", notches, gx, gy]];
}

#pragma mark - Keyboard -> KeyEvent bridge (Phase 5)

- (void)onToggleKeyboard {
    if ([self.kbField isFirstResponder]) {
        [self.kbField resignFirstResponder];
        [self appendLine:@"[shell] keyboard hidden"];
    } else {
        self.kbField.text = kKbSentinel;   // reset the sentinel each time it opens
        [self.kbField becomeFirstResponder];
        [self appendLine:@"[shell] keyboard shown (type -> game)"];
    }
}

// Every edit is intercepted and forwarded as KeyEvents; the field itself never changes (return NO), so it
// always holds just the sentinel.
- (BOOL)textField:(UITextField *)tf shouldChangeCharactersInRange:(NSRange)range replacementString:(NSString *)string {
    if (string.length > 0) {
        [JVMLauncher postText:string];
        [self appendLine:[NSString stringWithFormat:@"[shell] key text '%@'", string]];
    } else if (range.length > 0) {
        for (NSUInteger i = 0; i < range.length; i++) {
            [JVMLauncher postKeyStroke:AWT_VK_BACK_SPACE];
        }
        [self appendLine:@"[shell] key backspace"];
    }
    return NO;   // keep the field at the sentinel — we only capture, never store
}

- (BOOL)textFieldShouldReturn:(UITextField *)tf {
    [JVMLauncher postKeyStroke:AWT_VK_ENTER];
    [self appendLine:@"[shell] key enter"];
    return NO;   // keep the keyboard up for the next line
}

- (void)endBgTask {
    if (self.bgTask != UIBackgroundTaskInvalid) {
        [[UIApplication sharedApplication] endBackgroundTask:self.bgTask];
        self.bgTask = UIBackgroundTaskInvalid;
    }
}

@end
