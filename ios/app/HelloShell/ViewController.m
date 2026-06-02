#import "ViewController.h"
#import "JVMLauncher.h"
#import <unistd.h>
#import <fcntl.h>

@interface ViewController () <UITextFieldDelegate>
{
    // Game-canvas centres of each tab stone (updated each chrome refresh), used to proxy a tap onto the live
    // desktop stone. A C array, so it lives as an ivar rather than a property.
    CGPoint _tabCentres[14];
}
@property (nonatomic, strong) UITextView *console;
@property (nonatomic, strong) UIButton *launchButton;
@property (nonatomic, strong) UIButton *canaryButton;
@property (nonatomic, strong) UIButton *keyboardButton;
// W3 hybrid-chrome device de-risk: read the MobileChromeBridge snapshot + proxy a tab switch via the proven
// JavaBridge.onClick, logging a md5-gateable verdict. The seam proof before native rails are drawn.
@property (nonatomic, strong) UIButton *chromeButton;
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
// W3 hybrid-chrome native tab rails (cover-in-place first pass): 14 opaque native buttons drawn over the live
// desktop tab stones (positions/active state from the MobileChromeBridge snapshot each tick); a tap proxies an
// onClick onto the stone centre. A ▦ toggle shows/hides them for instant A/B fallback.
@property (nonatomic, strong) NSMutableArray<UIButton *> *tabButtons;
@property (nonatomic, strong) UIView *tabRailBg;   // opaque rail behind the edge-split icon grid
// Pre-composited tab images (stone background + centred icon) built once from the bundled OSRS sprite assets
// (stone-inactive/active + icon-0..13). Index = tab index; active/inactive variants. Authentic, crisp, no
// crop fragility.
@property (nonatomic, strong) NSMutableArray<UIImage *> *tabImgInactive;
@property (nonatomic, strong) NSMutableArray<UIImage *> *tabImgActive;
// Opaque covers over the desktop tab stones (the bottom double-UI). The stones stay clickable underneath (the
// proxy click reaches them), the cover just hides them — hiding the widget would kill the proxy target.
@property (nonatomic, strong) NSMutableArray<UIView *> *stoneCovers;
@property (nonatomic, strong) UIButton *chromeToggle;
@property (nonatomic, assign) BOOL chromeVisible;
@end

// AWT virtual keycodes for the non-text keys we synthesize (java.awt.event.KeyEvent.VK_*).
static const int AWT_VK_BACK_SPACE = 8;
static const int AWT_VK_ENTER      = 10;
// Zero-width sentinel so a backspace on an "empty" field still fires shouldChangeCharactersInRange.
static NSString * const kKbSentinel = @"​";
// Width (points) of the native tab-rail strip reserved at each side edge. The game view is inset by this on
// both sides, so the docked side panel (at the game-canvas edge) ends INBOARD of the screen edge and the
// native tabs fill the strips flush against it — the official mobile arrangement, with no widget moving.
static const CGFloat kTabStrip = 46.0;

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

    // Native tab rails (edge-split, mobile arrangement): a 2-column icon grid pinned to the right edge of the
    // game view. The opaque rail background sits behind the icon buttons; each icon is blitted from the live
    // desktop stone's sprite region, and a tap proxies a click onto the real stone centre (so the native
    // position is decoupled from the proxy target — no widget is moved/hidden). Subviews of the game view
    // (keyboard-button pattern: each consumes its own tap, the rest drives the game gestures).
    self.tabRailBg = [[UIView alloc] init];
    self.tabRailBg.hidden = YES;
    self.tabRailBg.backgroundColor = [UIColor colorWithWhite:0.04 alpha:0.92];
    self.tabRailBg.layer.cornerRadius = 10;
    self.tabRailBg.userInteractionEnabled = NO;   // never eats taps; the buttons on top do
    [self.screen addSubview:self.tabRailBg];

    self.tabButtons = [NSMutableArray arrayWithCapacity:14];
    for (int i = 0; i < 14; i++) {
        UIButton *tb = [UIButton buttonWithType:UIButtonTypeCustom];
        tb.tag = i;
        tb.hidden = YES;
        tb.imageView.contentMode = UIViewContentModeScaleAspectFit;
        tb.imageView.layer.magnificationFilter = kCAFilterNearest;   // crisp pixel-art upscale
        tb.contentEdgeInsets = UIEdgeInsetsZero;
        tb.clipsToBounds = NO;
        [tb addTarget:self action:@selector(onTabButton:) forControlEvents:UIControlEventTouchUpInside];
        [self.view addSubview:tb];   // in the side strips, outside the (inset) game view
        [self.tabButtons addObject:tb];
    }
    // Opaque covers over the desktop stones (subviews of the game view, above the blit; non-interactive so the
    // Java proxy click still reaches the real stone beneath).
    self.stoneCovers = [NSMutableArray arrayWithCapacity:14];
    for (int i = 0; i < 14; i++) {
        UIView *cv = [[UIView alloc] init];
        cv.hidden = YES;
        cv.userInteractionEnabled = NO;
        cv.backgroundColor = [UIColor blackColor];
        [self.screen addSubview:cv];
        [self.stoneCovers addObject:cv];
    }
    [self buildTabAssets];
    self.chromeVisible = YES;

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
    // Accessory bar — sits ABOVE the soft keyboard (which covers the bottom-left ⌨ button), so there is
    // always a reachable way to send a chat line and to dismiss the keyboard.
    UIToolbar *bar = [[UIToolbar alloc] initWithFrame:CGRectMake(0, 0, 320, 44)];
    bar.barStyle = UIBarStyleBlack;
    bar.translucent = YES;
    UIBarButtonItem *flex = [[UIBarButtonItem alloc]
        initWithBarButtonSystemItem:UIBarButtonSystemItemFlexibleSpace target:nil action:nil];
    UIBarButtonItem *send = [[UIBarButtonItem alloc]
        initWithTitle:@"Send ⏎" style:UIBarButtonItemStylePlain target:self action:@selector(onKbSend)];
    UIBarButtonItem *hide = [[UIBarButtonItem alloc]
        initWithTitle:@"Hide ⌨︎" style:UIBarButtonItemStyleDone target:self action:@selector(onKbHide)];
    bar.items = @[flex, send, hide];
    [bar sizeToFit];
    self.kbField.inputAccessoryView = bar;
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

    self.chromeButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.chromeButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.chromeButton setTitle:@"Chrome test (read+switch)" forState:UIControlStateNormal];
    self.chromeButton.titleLabel.font = [UIFont systemFontOfSize:15];
    [self.chromeButton addTarget:self action:@selector(onChromeTest)
                forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.chromeButton];

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

    // Chrome rails toggle — always on top, left of the ≡ toggle. Shows/hides the native tab rails (A/B).
    self.chromeToggle = [UIButton buttonWithType:UIButtonTypeSystem];
    self.chromeToggle.translatesAutoresizingMaskIntoConstraints = NO;
    [self.chromeToggle setTitle:@"▦" forState:UIControlStateNormal];
    self.chromeToggle.titleLabel.font = [UIFont boldSystemFontOfSize:22];
    self.chromeToggle.tintColor = [UIColor whiteColor];
    self.chromeToggle.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.5];
    self.chromeToggle.layer.cornerRadius = 8;
    [self.chromeToggle addTarget:self action:@selector(onToggleChrome)
                forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.chromeToggle];

    UILayoutGuide *g = self.view.safeAreaLayoutGuide;
    UILayoutGuide *sg = self.screen.safeAreaLayoutGuide;
    UILayoutGuide *p = self.debugPanel.layoutMarginsGuide;
    [NSLayoutConstraint activateConstraints:@[
        // Game fills the SAFE AREA (not edge-to-edge): edge-to-edge pushed the OSRS corner UI (minimap,
        // inventory, chat) under the rounded corners / dynamic island / home indicator. Filling the safe
        // area keeps everything on-screen; the render aspect is computed to match it (onLaunch) so there
        // are no internal letterbox bars either.
        [self.screen.topAnchor constraintEqualToAnchor:g.topAnchor],
        [self.screen.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:kTabStrip],
        [self.screen.trailingAnchor constraintEqualToAnchor:g.trailingAnchor constant:-kTabStrip],
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

        // Chrome rails toggle, just left of the ≡ toggle.
        [self.chromeToggle.topAnchor constraintEqualToAnchor:g.topAnchor constant:8],
        [self.chromeToggle.trailingAnchor constraintEqualToAnchor:self.debugToggle.leadingAnchor constant:-8],
        [self.chromeToggle.widthAnchor constraintEqualToConstant:44],
        [self.chromeToggle.heightAnchor constraintEqualToConstant:36],

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
        [self.chromeButton.topAnchor constraintEqualToAnchor:self.launchButton.bottomAnchor constant:6],
        [self.chromeButton.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.console.topAnchor constraintEqualToAnchor:self.chromeButton.bottomAnchor constant:6],
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

#pragma mark - W3 native tab rails (cover-in-place)

// Map a game-canvas rect to the screen image view's coordinate space, honouring the ScaleAspectFit letterbox
// (the inverse of mapPoint:). self.screen.image's size IS the game canvas, so this places a native rail
// exactly over the live desktop widget.
- (CGRect)viewRectForGameX:(int)gx y:(int)gy w:(int)gw h:(int)gh {
    UIImage *img = self.screen.image;
    if (img == nil) return CGRectZero;
    CGFloat iw = img.size.width, ih = img.size.height;
    if (iw <= 0 || ih <= 0) return CGRectZero;
    CGRect b = self.screen.bounds;
    CGFloat scale = MIN(b.size.width / iw, b.size.height / ih);
    CGFloat ox = (b.size.width - iw * scale) * 0.5;
    CGFloat oy = (b.size.height - ih * scale) * 0.5;
    return CGRectMake(gx * scale + ox, gy * scale + oy, gw * scale, gh * scale);
}

// Load a bundled tab sprite asset at native (1x) scale.
- (UIImage *)loadAsset:(NSString *)name {
    NSString *p = [[NSBundle mainBundle] pathForResource:name ofType:@"png"];
    return p ? [UIImage imageWithContentsOfFile:p] : nil;
}

// Composite an icon centred on a stone background into one tab image (nearest-neighbour, native px).
- (UIImage *)compositeStone:(UIImage *)stone icon:(UIImage *)icon {
    if (stone == nil) return icon;
    CGSize sz = stone.size;
    UIGraphicsImageRendererFormat *fmt = [UIGraphicsImageRendererFormat preferredFormat];
    fmt.opaque = NO;
    fmt.scale = 1.0;
    UIGraphicsImageRenderer *r = [[UIGraphicsImageRenderer alloc] initWithSize:sz format:fmt];
    return [r imageWithActions:^(UIGraphicsImageRendererContext *ctx) {
        CGContextSetInterpolationQuality(ctx.CGContext, kCGInterpolationNone);
        [stone drawInRect:CGRectMake(0, 0, sz.width, sz.height)];
        if (icon) {
            CGFloat ix = floor((sz.width - icon.size.width) / 2.0);
            CGFloat iy = floor((sz.height - icon.size.height) / 2.0);
            [icon drawInRect:CGRectMake(ix, iy, icon.size.width, icon.size.height)];
        }
    }];
}

// Build the 14 active + 14 inactive tab images once from the bundled OSRS sprites (stone-inactive/active +
// icon-0..13). Authentic feathered stones with the real tab glyph — no crop-from-frame fragility.
- (void)buildTabAssets {
    UIImage *stoneI = [self loadAsset:@"stone-inactive"];
    UIImage *stoneA = [self loadAsset:@"stone-active"];
    self.tabImgInactive = [NSMutableArray arrayWithCapacity:14];
    self.tabImgActive = [NSMutableArray arrayWithCapacity:14];
    for (int i = 0; i < 14; i++) {
        UIImage *icon = [self loadAsset:[NSString stringWithFormat:@"icon-%d", i]];
        self.tabImgInactive[i] = [self compositeStone:stoneI icon:icon] ?: [NSNull null];
        self.tabImgActive[i]   = [self compositeStone:stoneA icon:icon] ?: [NSNull null];
    }
    [self appendLine:[NSString stringWithFormat:@"[chrome] tab assets built (stones %@/%@)",
                      stoneI ? @"ok" : @"MISSING", stoneA ? @"ok" : @"MISSING"]];
}

// Lay out the native tab rails from the read-only chrome snapshot — EDGE-SPLIT (mobile) arrangement, matching
// the official OSRS-Mobile app: two SINGLE-column vertical rails, one on each screen edge (tab indices 0–6 on
// the left, 7–13 on the right — the desktop's own STONE grouping). Each cell is an individual semi-transparent
// rounded square with the icon blitted from the live desktop stone; the active tab (TOPLEVEL_PANEL) is
// accented. A tap proxies a click onto the REAL stone centre (stored in _tabCentres), so the native rail's
// position is independent of the desktop stone — no game widget is moved or hidden. Hides everything when not
// logged in / no tabs.
- (void)updateChrome {
    NSString *snap = [JVMLauncher chromeSnapshot];
    if (snap.length == 0) return;
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                     options:0 error:nil];
    self.tabRailBg.hidden = YES;   // mobile cells are individually styled; no solid rail strip behind them
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"loggedIn"] boolValue]) {
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;
        return;
    }
    int active = [d[@"activeTab"] intValue];
    NSArray *tabs = [d[@"tabs"] isKindOfClass:[NSArray class]] ? d[@"tabs"] : @[];

    // Split present tabs into a left column (idx 0–6) and a right column (idx 7–13), in tab-index order, and
    // refresh each present tab's proxy-target centre.
    NSMutableArray<NSNumber *> *left = [NSMutableArray array];
    NSMutableArray<NSNumber *> *right = [NSMutableArray array];
    for (NSUInteger i = 0; i < self.tabButtons.count; i++) {
        id t = i < tabs.count ? tabs[i] : nil;
        if (![t isKindOfClass:[NSArray class]] || [(NSArray *)t count] < 4) {
            self.tabButtons[i].hidden = YES;
            self.stoneCovers[i].hidden = YES;
            continue;
        }
        NSArray *bb = (NSArray *)t;
        int x = [bb[0] intValue], y = [bb[1] intValue], w = [bb[2] intValue], h = [bb[3] intValue];
        _tabCentres[i] = CGPointMake(x + w / 2.0, y + h / 2.0);
        // Cover the desktop stone at its live position (mapped game->view), so the bottom double-UI is hidden
        // while the stone stays clickable underneath for the proxy. Inset out by 1px to cover the edges.
        CGRect cv = [self viewRectForGameX:x y:y w:w h:h];
        self.stoneCovers[i].frame = CGRectInset(cv, -1, -1);
        self.stoneCovers[i].hidden = NO;
        // Columns flipped: the gameplay tabs (idx 0–6: combat/stats/inventory/...) go to the RIGHT strip
        // (flush against the side panel they open), the social/system tabs (idx 7–13) to the LEFT.
        [(i <= 6 ? right : left) addObject:@(i)];
    }

    const CGFloat cell = 38.0, gap = 5.0;
    // The columns live in the side strips (self.view coords), flanking the inset game view. The game canvas's
    // docked side panel ends at the game view's right edge — i.e. flush against the right strip — so the menu
    // sits flush against the tabs with no overlap.
    CGRect gf = self.screen.frame;   // inset game view, in self.view coords
    CGRect strip = CGRectMake(0, gf.origin.y, gf.origin.x, gf.size.height);   // left strip extent (for y span)
    CGFloat leftX  = gf.origin.x - kTabStrip + (kTabStrip - cell) / 2.0;
    CGFloat rightX = gf.origin.x + gf.size.width + (kTabStrip - cell) / 2.0;
    [self layoutColumn:left  x:leftX  cell:cell gap:gap tabs:tabs active:active inBounds:strip];
    [self layoutColumn:right x:rightX cell:cell gap:gap tabs:tabs active:active inBounds:strip];
}

// Place one edge column of tab cells, vertically centred in the game view.
- (void)layoutColumn:(NSArray<NSNumber *> *)col x:(CGFloat)x cell:(CGFloat)cell gap:(CGFloat)gap
                tabs:(NSArray *)tabs active:(int)active inBounds:(CGRect)sb {
    NSUInteger n = col.count;
    if (n == 0) return;
    CGFloat totalH = n * cell + (n - 1) * gap;
    // Centre the column within the strip's actual vertical extent (origin.y..origin.y+height).
    CGFloat y0 = sb.origin.y + MAX(0.0, (sb.size.height - totalH) * 0.5);
    for (NSUInteger k = 0; k < n; k++) {
        NSUInteger i = col[k].unsignedIntegerValue;
        UIButton *btn = self.tabButtons[i];
        NSArray *bb = tabs[i];
        int bx = [bb[0] intValue], by = [bb[1] intValue], bw = [bb[2] intValue], bh = [bb[3] intValue];
        (void)bx; (void)by; (void)bw; (void)bh;   // bounds drive the proxy centre (set above), not the icon
        btn.frame = CGRectMake(x, y0 + k * (cell + gap), cell, cell);
        BOOL isActive = ((int)i == active);
        // Authentic composited tab image (bundled OSRS stone + icon). Active = red stone, inactive = grey.
        NSArray<UIImage *> *imgs = isActive ? self.tabImgActive : self.tabImgInactive;
        UIImage *img = (i < (int)imgs.count && [imgs[i] isKindOfClass:[UIImage class]]) ? imgs[i] : nil;
        [btn setImage:img forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor clearColor];
        btn.layer.borderWidth = 0.0;
        btn.alpha = 1.0;   // active vs inactive carried by the red/grey stone itself
        btn.hidden = NO;
    }
}

// A native tab tap -> proxy a left click on the live desktop stone centre (the device-proven switch primitive,
// JavaBridge.onClick). The button consumed the tap, so it never leaks to the game gestures beneath.
- (void)onTabButton:(UIButton *)b {
    if (![JVMLauncher bridgeReady]) return;
    NSInteger i = b.tag;
    if (i < 0 || i >= 14) return;
    CGPoint c = _tabCentres[i];
    // PRECISE click (no snap): the world viewport is full-screen, so a normal onClick would snap a tab-stone
    // click onto a nearby world entity instead of hitting the stone.
    [JVMLauncher postClickPreciseX:(int)lround(c.x) y:(int)lround(c.y) button:1];
    [self appendLine:[NSString stringWithFormat:@"[chrome] tab %ld -> proxy click %d,%d",
                      (long)i, (int)lround(c.x), (int)lround(c.y)]];
}

- (void)onToggleChrome {
    self.chromeVisible = !self.chromeVisible;
    if (!self.chromeVisible) {
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;   // reveal the real stones in the fallback view
        self.tabRailBg.hidden = YES;
    }
    [self appendLine:[NSString stringWithFormat:@"[chrome] rails %@", self.chromeVisible ? @"ON" : @"OFF"]];
}

// W3 hybrid-chrome device de-risk: prove the native side can (a) READ the MobileChromeBridge snapshot on
// device and (b) SWITCH the active tab by proxying a click on a live tab-stone centre through the proven
// JavaBridge.onClick — the cover-and-proxy seam, before any native rails are drawn. Emits a single
// bash-parseable verdict line to hello-shell.log (md5-gated off-device).
- (void)onChromeTest {
    if (![JVMLauncher bridgeReady]) { [self appendLine:@"[chrome-dev] bridge not ready (log in first)"]; return; }
    NSString *snap = [JVMLauncher chromeSnapshot];
    if (snap.length == 0) { [self appendLine:@"[chrome-dev] snapshot nil/empty"]; return; }
    // Log a trimmed copy so the device snapshot bounds are recoverable for building the rails next.
    NSString *trim = snap.length > 600 ? [snap substringToIndex:600] : snap;
    [self appendLine:[NSString stringWithFormat:@"[chrome-dev] snapshot=%@", trim]];

    NSError *err = nil;
    id obj = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                             options:0 error:&err];
    if (![obj isKindOfClass:[NSDictionary class]]) {
        [self appendLine:[NSString stringWithFormat:@"[chrome-dev] parse failed: %@", err.localizedDescription]];
        return;
    }
    NSDictionary *d = (NSDictionary *)obj;
    if (![d[@"loggedIn"] boolValue]) { [self appendLine:@"[chrome-dev] not LOGGED_IN yet — log in then retry"]; return; }
    int activeBefore = [d[@"activeTab"] intValue];
    NSArray *tabs = [d[@"tabs"] isKindOfClass:[NSArray class]] ? d[@"tabs"] : @[];

    // Pick a target: the first present tab stone (non-null bounds) whose index != the active tab.
    int target = -1, tcx = 0, tcy = 0, present = 0;
    for (NSUInteger i = 0; i < tabs.count; i++) {
        id t = tabs[i];
        if (![t isKindOfClass:[NSArray class]] || [(NSArray *)t count] < 4) continue;
        present++;
        if (target < 0 && (int)i != activeBefore) {
            NSArray *b = (NSArray *)t;
            target = (int)i;
            tcx = [b[0] intValue] + [b[2] intValue] / 2;
            tcy = [b[1] intValue] + [b[3] intValue] / 2;
        }
    }
    if (target < 0) { [self appendLine:[NSString stringWithFormat:@"[chrome-dev] no switchable tab (present=%d active=%d)", present, activeBefore]]; return; }

    [self appendLine:[NSString stringWithFormat:@"[chrome-dev] read=ok present=%d activeBefore=%d target=%d centre=%d,%d -> proxy click",
                      present, activeBefore, target, tcx, tcy]];
    [JVMLauncher postClickX:tcx y:tcy button:1];

    // Re-read after the engine has had a few ticks to switch + the bridge to re-snapshot, then verdict.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.6 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        NSString *snap2 = [JVMLauncher chromeSnapshot];
        NSDictionary *d2 = [snap2 isKindOfClass:[NSString class]]
            ? [NSJSONSerialization JSONObjectWithData:[snap2 dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil]
            : nil;
        int activeAfter = [d2 isKindOfClass:[NSDictionary class]] ? [d2[@"activeTab"] intValue] : -999;
        BOOL switched = (activeAfter == target);
        [self appendLine:[NSString stringWithFormat:
            @"[chrome-dev] CHROME_DEVICE read=ok present=%d activeBefore=%d target=%d activeAfter=%d switched=%@",
            present, activeBefore, target, activeAfter, switched ? @"yes" : @"no"]];
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
    // The game view is inset by kTabStrip on each side (native tab rails fill the strips), so the render
    // aspect must match the INSET width to avoid internal letterbox bars.
    CGFloat sw = b.size.width - ins.left - ins.right - 2 * kTabStrip;
    CGFloat sh = b.size.height - ins.top - ins.bottom;
    CGFloat aspect = (sh > 0 ? sw / sh : 2.0);
    int renderH = 600;
    int renderW = (int)lround(renderH * aspect);
    launcher.resizeSpec = [NSString stringWithFormat:@"%dx%d", renderW, renderH];
    [self appendLine:[NSString stringWithFormat:@"[shell] inset-area %.0fx%.0f aspect %.3f -> render %@",
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

    // Refresh the native tab rails from the chrome snapshot (~10 Hz — positions/active state rarely change,
    // and the JSON read is cheap). Skipped entirely when the rails are toggled off.
    if (self.chromeVisible && self.framesShown % 6 == 0) {
        [self updateChrome];
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
        // A camera drag moves the world, not the pointer — so clear any frozen hover first, otherwise
        // scenery rotating under the stale cursor lights up and the next walk-tap mis-clicks it.
        [JVMLauncher postClearHover];
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
        [JVMLauncher postClearHover];   // zoom moves the world too; don't leave a stale hover behind
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

- (void)onKbSend {
    [JVMLauncher postKeyStroke:AWT_VK_ENTER];
    [self appendLine:@"[shell] key enter (bar)"];
}

- (void)onKbHide {
    [self.kbField resignFirstResponder];
    [self appendLine:@"[shell] keyboard hidden (bar)"];
}

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
