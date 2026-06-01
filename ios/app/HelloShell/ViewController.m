#import "ViewController.h"
#import "JVMLauncher.h"
#import <unistd.h>
#import <fcntl.h>

@interface ViewController ()
@property (nonatomic, strong) UITextView *console;
@property (nonatomic, strong) UIButton *launchButton;
@property (nonatomic, strong) UIButton *canaryButton;
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
@end

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
    CGFloat top = 60;
    self.statusLabel = [[UILabel alloc] init];
    self.statusLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.statusLabel.textColor = [UIColor systemYellowColor];
    self.statusLabel.font = [UIFont boldSystemFontOfSize:15];
    self.statusLabel.text = @"JIT: waiting…";
    [self.view addSubview:self.statusLabel];

    self.launchButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.launchButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.launchButton setTitle:@"Run AWT activation (§6.0)" forState:UIControlStateNormal];
    self.launchButton.titleLabel.font = [UIFont boldSystemFontOfSize:17];
    self.launchButton.enabled = NO;
    [self.launchButton addTarget:self action:@selector(onLaunch)
                forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.launchButton];

    self.canaryButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.canaryButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.canaryButton setTitle:@"RWX canary" forState:UIControlStateNormal];
    [self.canaryButton addTarget:self action:@selector(onCanary)
                forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.canaryButton];

    // The game screen: where captured RuneLite frames are blitted live (W3 shell start).
    self.screen = [[UIImageView alloc] init];
    self.screen.translatesAutoresizingMaskIntoConstraints = NO;
    self.screen.backgroundColor = [UIColor colorWithWhite:0.06 alpha:1.0];
    self.screen.contentMode = UIViewContentModeScaleAspectFit;
    self.screen.layer.magnificationFilter = kCAFilterNearest;   // crisp pixel art
    [self.view addSubview:self.screen];

    self.console = [[UITextView alloc] init];
    self.console.translatesAutoresizingMaskIntoConstraints = NO;
    self.console.editable = NO;
    self.console.backgroundColor = [UIColor blackColor];
    self.console.textColor = [UIColor greenColor];
    self.console.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightRegular];
    [self.view addSubview:self.console];

    UILayoutGuide *g = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [self.statusLabel.topAnchor constraintEqualToAnchor:g.topAnchor constant:8],
        [self.statusLabel.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:12],

        [self.launchButton.topAnchor constraintEqualToAnchor:self.statusLabel.bottomAnchor constant:8],
        [self.launchButton.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:12],
        [self.canaryButton.centerYAnchor constraintEqualToAnchor:self.launchButton.centerYAnchor],
        [self.canaryButton.leadingAnchor constraintEqualToAnchor:self.launchButton.trailingAnchor constant:24],

        // Game screen takes the upper ~55% (765x503 aspect renders large); console below it.
        [self.screen.topAnchor constraintEqualToAnchor:self.launchButton.bottomAnchor constant:8],
        [self.screen.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:6],
        [self.screen.trailingAnchor constraintEqualToAnchor:g.trailingAnchor constant:-6],
        [self.screen.heightAnchor constraintEqualToAnchor:g.heightAnchor multiplier:0.55],

        [self.console.topAnchor constraintEqualToAnchor:self.screen.bottomAnchor constant:6],
        [self.console.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:6],
        [self.console.trailingAnchor constraintEqualToAnchor:g.trailingAnchor constant:-6],
        [self.console.bottomAnchor constraintEqualToAnchor:g.bottomAnchor constant:-6],
    ]];
    (void)top;
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
}

#pragma mark - Touch -> AWT bridge (W3 sub-goal B)

// Map a touch point (in the screen image view's coordinate space) to gamepack-canvas pixels, honouring
// the ScaleAspectFit letterbox. Returns NO if the touch is in the letterbox margin (outside the frame).
- (BOOL)mapTouch:(UITouch *)touch toGameX:(int *)gx toGameY:(int *)gy {
    UIImage *img = self.screen.image;
    if (img == nil) return NO;
    CGFloat iw = img.size.width, ih = img.size.height;
    if (iw <= 0 || ih <= 0) return NO;
    CGRect b = self.screen.bounds;
    CGFloat scale = MIN(b.size.width / iw, b.size.height / ih);   // ScaleAspectFit
    CGFloat dispW = iw * scale, dispH = ih * scale;
    CGFloat ox = (b.size.width - dispW) * 0.5, oy = (b.size.height - dispH) * 0.5;
    CGPoint p = [touch locationInView:self.screen];
    CGFloat fx = (p.x - ox) / scale, fy = (p.y - oy) / scale;
    if (fx < 0 || fy < 0 || fx >= iw || fy >= ih) return NO;   // tapped the letterbox, not the game
    *gx = (int)fx;
    *gy = (int)fy;
    return YES;
}

- (void)dispatchTouch:(UITouch *)touch type:(int)type {
    if (![JVMLauncher bridgeReady]) return;
    int gx = 0, gy = 0;
    if (![self mapTouch:touch toGameX:&gx toGameY:&gy]) return;
    [JVMLauncher postTouchType:type x:gx y:gy button:1];
    if (type != 1) {   // log down/up (not every move) so taps are visible in the log
        [self appendLine:[NSString stringWithFormat:@"[shell] touch %@ -> game %d,%d",
                          type == 0 ? @"DOWN" : @"UP", gx, gy]];
    }
}

- (void)touchesBegan:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:touches.anyObject type:0];   // TOUCH_DOWN
}
- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:touches.anyObject type:1];   // TOUCH_MOVE
}
- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:touches.anyObject type:2];   // TOUCH_UP
}
- (void)touchesCancelled:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [self dispatchTouch:touches.anyObject type:2];   // treat cancel as UP so the press is released
}

- (void)endBgTask {
    if (self.bgTask != UIBackgroundTaskInvalid) {
        [[UIApplication sharedApplication] endBackgroundTask:self.bgTask];
        self.bgTask = UIBackgroundTaskInvalid;
    }
}

@end
