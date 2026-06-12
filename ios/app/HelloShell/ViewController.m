#import "ViewController.h"
#import "JVMLauncher.h"
#import "PluginConfigViewController.h"
#import "PluginHubViewController.h"
#import "PluginPanelsViewController.h"
#import "JagexLoginViewController.h"
#import "GLRecon.h"
#import "RouteCBridge.h"            // GPU Route C bridge (backend selected by RC_BACKEND_ANGLE in the build)
#import <QuartzCore/QuartzCore.h>
#if !defined(RC_BACKEND_ANGLE)
#import <OpenGLES/EAGLDrawable.h>   // kEAGLDrawableProperty* (EAGL direct present; not linked in the ANGLE build)
#endif
#import <WebKit/WebKit.h>
#import <CoreText/CoreText.h>
#import <SafariServices/SafariServices.h>
#import <UserNotifications/UserNotifications.h>
#import <Photos/Photos.h>
#import <AVKit/AVKit.h>             // Picture-in-Picture (AVPictureInPictureController + content source)
#import <AVFoundation/AVFoundation.h> // AVSampleBufferDisplayLayer + the background-keepalive audio session
#import <CoreMedia/CoreMedia.h>     // CMSampleBuffer plumbing for the PiP frame feed
#import <unistd.h>
#import <fcntl.h>

@interface ViewController () <UITextFieldDelegate, UITableViewDataSource, UITableViewDelegate, UIGestureRecognizerDelegate, SFSafariViewControllerDelegate, UNUserNotificationCenterDelegate, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate>
{
    // Game-canvas centres of each tab stone (updated each chrome refresh), used to proxy a tap onto the live
    // desktop stone. A C array, so it lives as an ivar rather than a property.
    CGPoint _tabCentres[14];
}
@property (nonatomic, strong) UITextView *console;
@property (nonatomic, strong) UIButton *launchButton;
@property (nonatomic, strong) UIButton *gpuButton;   // GPU Route C: dedicated one-tap button to boot full RuneLite on GPU
@property (nonatomic, strong) UIButton *zoneButton;  // GPU Zone: dedicated one-tap button to boot full RuneLite on 117HD
@property (nonatomic, strong) UIButton *zoneFastButton;  // GPU Zone: 117HD resizable + DIRECT present (no readback)
@property (nonatomic, strong) UIButton *canaryButton;
@property (nonatomic, strong) UIButton *keyboardButton;
@property (nonatomic, strong) UIButton *shiftButton;     // sticky-Shift toggle (holds VK_SHIFT for shift-click)
@property (nonatomic, assign) BOOL shiftHeld;
@property (nonatomic, assign) BOOL notifAuthRequested;   // UNUserNotificationCenter authorization asked once
@property (nonatomic, assign) BOOL safariOpen;           // an SFSafariViewController is currently presented
@property (nonatomic, strong) UIButton *screenshotButton; // 📷 save the live frame to Photos
// W3 hybrid-chrome device de-risk: read the MobileChromeBridge snapshot + proxy a tab switch via the proven
// JavaBridge.onClick, logging a md5-gateable verdict. The seam proof before native rails are drawn.
@property (nonatomic, strong) UIButton *chromeButton;
// On-device Jagex login: opens an embedded OAuth login (WKWebView), mints a session, writes tokens.json.
// Pure native (no JVM/JIT) so it's enabled from launch, not gated on StikDebug attach.
@property (nonatomic, strong) UIButton *loginButton;
// Log out: clears tokens.json (disarms autologin) + purges any persisted Jagex web cookies. Shown only
// when a session is saved.
@property (nonatomic, strong) UIButton *logoutButton;
// Set once the login WKWebView has been shown this session. WebKit then stays permanently resident in the
// process and breaks the JVM's JIT-mirror brk #0x69 (it crashes at code-cache init), so the JVM must be
// launched in a fresh process. Gates onLaunch → a one-tap restart.
@property (nonatomic, assign) BOOL webKitUsedThisSession;
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
@property (nonatomic, assign) BOOL pendingRouteCL1;   // GPU Route C: long-press Start -> boot the L1 kill-test
@property (nonatomic, assign) BOOL pendingRouteCGpu;  // GPU Route C: two-finger long-press -> full RuneLite on GPU
@property (nonatomic, assign) BOOL pendingRouteCZone; // GPU Zone: three-finger long-press -> full RuneLite on 117HD
@property (nonatomic, assign) BOOL pendingRouteCGpuNoResize;  // GPU Route C bisection: skip headless.resize (fixed 765x503)
@property (nonatomic, assign) BOOL pendingRouteCZoneDirect;   // GPU Zone: resizable + DIRECT present (CAEAGLLayer, no readback)

// #1 "Tap to Play" front screen: a polished Jagex-Mobile-style cover shown at cold start INSTEAD of the
// W1 debug panel (which now starts hidden behind the ≡ toggle). The big play button waits on the StikDebug
// attach (isDebuggerAttached / CS_DEBUGGED) — the per-launch attach is immovable, so the screen only
// polishes the post-attach moment. Tap → onLaunch → dismiss. A login row reflects the saved session.
@property (nonatomic, strong) UIView *launchScreen;
@property (nonatomic, strong) UIButton *playButton;
@property (nonatomic, strong) UILabel *playStatusLabel;
@property (nonatomic, strong) UIButton *playLoginButton;

// W3 native shell: keep the process alive + show frames. The JVM ran fine on device but iOS
// jetsam'd the (idle-UI) process within ~250 ms — before the engine drew its first frame. Two
// protections: (1) a UIBackgroundTask assertion so the OS treats the JVM run as important work and
// won't suspend/kill it; (2) an ACTIVE CADisplayLink render loop that polls the capture dir and
// blits the newest frame-*.png to an on-screen image view — keeping the app in the foreground
// "actively rendering" jetsam band AND showing the live login screen (the start of the real UI).
@property (nonatomic, strong) UIImageView *screen;
@property (nonatomic, strong) PluginPanelsViewController *panelDock; // persistent always-on panel dock
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) UIBackgroundTaskIdentifier bgTask;
@property (nonatomic, copy)   NSString *captureDir;
@property (nonatomic, copy)   NSString *lastFrameName;
@property (nonatomic, assign) int framesShown;
// Native mobile right-click menu: a long-press opens the engine menu (right-click), then once that menu is
// reflected in the MenuPeekBridge snapshot we build a big-row native OSRS-styled overlay over it and the
// user taps a row to pick (click-through to the engine row). menuOpen guards camera-pan while it's up.
@property (nonatomic, assign) BOOL menuOpen;
@property (nonatomic, assign) int  lastMenuX;
@property (nonatomic, assign) int  lastMenuY;
@property (nonatomic, assign) BOOL menuBuilding;          // right-clicked; polling for the engine menu snapshot
@property (nonatomic, assign) BOOL menuTouchActive;       // the long-press finger is still down (drag in progress)
@property (nonatomic, assign) NSTimeInterval menuOpenAt;  // when the right-click fired (build timeout)
@property (nonatomic, assign) BOOL menuClosing;           // committed/cancelled; native cover held up until the
                                                          // engine menu actually closes (else it peeks for a frame)
@property (nonatomic, assign) NSTimeInterval menuClosingAt; // when the close click fired (teardown timeout)
@property (nonatomic, strong) UIView *menuContainer;      // the native OSRS-styled menu box
@property (nonatomic, strong) NSMutableArray<UIView *> *menuRows; // the row views (index 0 = top = default)
@property (nonatomic, strong) NSArray *menuRowEntries;    // the entry dicts, for re-rendering a row on hover
@property (nonatomic, assign) int  menuHighlight;         // currently locked-on display row (-1 = none/header)
@property (nonatomic, assign) CGFloat menuHapticY;        // last finger Y a snap-haptic fired at (per-row tick)
@property (nonatomic, assign) CGPoint menuAnchor;         // press point in self.view coords (menu origin)
@property (nonatomic, assign) BOOL menuHandRight;         // pressed on the right half -> open the menu leftward
@property (nonatomic, assign) BOOL   menuGeomValid;       // engine menu rect (below) is live this drag
@property (nonatomic, assign) CGRect menuGeomGame;        // live engine menu bounds, gamepack (765x503) coords
@property (nonatomic, assign) int    menuSelX;            // where commit clicks on release (game coords)
@property (nonatomic, assign) int    menuSelY;
@property (nonatomic, assign) BOOL   menuFingerOnMenu;    // finger over the menu (with grace) -> select; else cancel
@property (nonatomic, strong) UISelectionFeedbackGenerator *menuHaptic; // light tick when the highlight moves
// Interactable-tap feedback: a light haptic when a tap grabs a real interactable (polled via JavaBridge
// interactTapSeq), and a yellow fading click-ring drawn at every tap point (the Jagex-Mobile click marker).
@property (nonatomic, assign) int lastInteractTapSeq;
@property (nonatomic, strong) UIImpactFeedbackGenerator *tapHaptic;
// The click ring is deferred one beat so it can pick the official colour: RED if the tap resolved to an
// interactable (the engine bumped interactTapSeq), YELLOW for a plain walk (no bump within the grace window).
@property (nonatomic, assign) BOOL pendingClick;
@property (nonatomic, assign) CGPoint pendingClickPoint;
@property (nonatomic, assign) NSTimeInterval pendingClickAt;
@property (nonatomic, assign) int emX;                    // live engine-menu geometry (for click-through)
@property (nonatomic, assign) int emY;
@property (nonatomic, assign) int emW;
@property (nonatomic, assign) int emCount;
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

// W3 Feature-A (A3): chat-at-top = the engine's OWN chatbox relocated to the top + flipped transparent
// (ChatRelocateProbe proved it renders there, not occluded). Tabs/scrollback/colors/input stay live since it IS
// the real chat — no native reproduction needed.
@property (nonatomic, assign) BOOL engineChatRelocated;   // one-shot: asked the engine to relocate the HUD (A4)
// Edge-to-edge (A1+A4): the scene fills the whole screen; the engine HUD (chat/minimap/orbs) is relocated INTO
// the safe area so nothing clips under the island/corners/home-indicator. renderW/H = the canvas the engine
// draws (set at launch); chatHitCanvas = the relocated chat tab-row rect in canvas px (onTap treats it as UI).
@property (nonatomic, assign) int renderW;
@property (nonatomic, assign) int renderH;

// GPU Route C direct present: a CAEAGLLayer (EAGL) / CAMetalLayer (ANGLE) the engine renders straight into (no
// readback). nil in CPU/L1 mode. Kept strong so the layer outlives the EGL/EAGL surface that references it.
@property (nonatomic, strong) CAEAGLLayer *glLayer;
@property (nonatomic, strong) CAMetalLayer *metalLayer;
@property (nonatomic, assign) BOOL gpuPresent;        // direct-present active (skip the CPU-frame blit)
@property (nonatomic, assign) BOOL gpuPresentArmed;   // ANGLE: window surface ready; flip gpuPresent on first GPU frame
@property (nonatomic, assign) CGRect chatHitCanvas;
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

// W3 A3 (bigger chat tabs): native UIKit recreation of the engine's chat tab buttons (All/Game/Public/Private/
// Channel/Clan/Trade + Report) drawn LARGER over the relocated engine chat. The engine has no per-widget scale
// and the tab sprites are fixed-size, so we redraw them: each button is backed by the extracted gamepack sprite
// (CHATBOX_BUTTON 3051 / SELECTED 3053 / NEW 3055 / REPORT 3057) with a RuneScape-font label, and a tap proxies
// a precise click onto the live engine button (CONTROLS stays shown underneath, covered by the native row). The
// engine pushes its message area down past the taller row (setEngineChatTabRowHeight). State + click targets
// come from JVMLauncher.chatSnapshot each tick; re-applied so the engine's TOPLEVEL_REDRAW can't undo it.
@property (nonatomic, strong) NSMutableArray<UIButton *> *chatTabButtons;
@property (nonatomic, strong) UIImage *chatBtnImg;      // 3051 — idle tab background
@property (nonatomic, strong) UIImage *chatBtnSelImg;   // 3053 — selected (active) tab
@property (nonatomic, strong) UIImage *chatBtnNewImg;   // 3055 — tab with unread messages
@property (nonatomic, assign) BOOL chatTabsBuilt;
// Finger-scroll the chat: a 1-finger drag that BEGINS inside the chat box scrolls the messages (via synthesized
// mouse-wheel notches to the engine) instead of rotating the camera — only while the chat is open (logged in +
// supported layout). chatScrollCanvas = the chat box rect in engine-canvas px; chatScrollable gates it.
@property (nonatomic, assign) CGRect chatScrollCanvas;
@property (nonatomic, assign) BOOL chatScrollable;
@property (nonatomic, assign) BOOL chatScrolling;       // the current pan gesture is scrolling the chat
@property (nonatomic, assign) CGFloat chatScrollAccum;  // accumulated drag (pt) toward the next wheel notch
// World map (touch — WORLDMAP-MOBILE-PLAN.md Phase 1): while the fullscreen engine world map is open, a
// 1-finger drag pans the MAP (bridge panWorldMap, "grab the map"), taps are precise UI clicks with no
// walk-ring semantics, a tap in the search rect raises the keyboard (the map search doesn't flip INPUT_TYPE),
// the native chrome hides, and a native ✕ in the safe area proxies the bezel-clipped engine close button.
@property (nonatomic, assign) BOOL mapOpen;             // polled from the bridge map snapshot (~10 Hz)
@property (nonatomic, assign) CGRect mapCloseCanvas;    // engine CLOSE rect (canvas px; zero = unknown)
@property (nonatomic, assign) CGRect mapSearchCanvas;   // engine SEARCH rect (canvas px)
@property (nonatomic, assign) CGRect mapWindowCanvas;   // the whole map window rect (canvas px)
@property (nonatomic, assign) CGRect mapKeyCanvas;      // the Key list rect — swipe there scrolls the list
@property (nonatomic, assign) CGRect mapListCanvas;     // the surface dropdown list rect (zero when closed)
@property (nonatomic, strong) NSArray<UIView *> *mapBands; // black bands above/below the scaled map window
@property (nonatomic, assign) BOOL mapListScrolling;    // the current pan gesture scrolls a map list (wheel)
@property (nonatomic, assign) CGPoint mapListWheelPt;   // game point the scroll wheel is parked over
@property (nonatomic, assign) CGFloat mapListScrollAcc; // accumulated drag pt toward the next wheel notch
@property (nonatomic, assign) BOOL mapPanning;          // the current pan gesture is panning the map
@property (nonatomic, assign) CGFloat mapPanAccX;       // sub-px accumulators (screen pt -> whole canvas px)
@property (nonatomic, assign) CGFloat mapPanAccY;
// Instant selected-highlight after a tap: the engine's tab GRAPHIC sprite (which the snapshot reads for the
// selected state) only updates on a redraw, so the highlight lagged until the next game interaction. Optimistically
// mark the tapped tab selected and hold it until the snapshot agrees (or a short timeout). -1 = none.
@property (nonatomic, assign) NSInteger optimisticChatTab;
@property (nonatomic, assign) CFTimeInterval optimisticChatAt;
// Chat collapse (mobile): the currently-active channel (the pill shows it; -1 = unknown), and whether the chat
// message area is collapsed. A quick tap on the pill toggles collapse; the channel stays selected either way.
@property (nonatomic, assign) NSInteger chatActiveTab;
@property (nonatomic, assign) BOOL chatCollapsed;
// Channel-pill redesign: ONE collapsed pill (shows the open channel) that fans the 7 chips out on hold. The 7
// chatTabButtons become display-only fan chips (userInteractionEnabled=NO); the pill's long-press drives the
// whole gesture (drag sideways = switch on lift; pull down off a chip = its filter menu). State here is touched
// only on the main thread (gesture callbacks + the display-tick layout).
@property (nonatomic, strong) UIButton *chatPill;             // the collapsed active-channel button
@property (nonatomic, strong) UILongPressGestureRecognizer *chatPillLP; // the fan/drag driver
@property (nonatomic, strong) UILabel *chatFilterHint;        // the ▾ "pull down to filter" reveal under a chip
@property (nonatomic, assign) BOOL chatFanned;                // chips are fanned out (a gesture is in flight)
@property (nonatomic, assign) BOOL chatFanAnimating;          // mid fan-out/in animation -> don't let the tick reframe
@property (nonatomic, assign) NSInteger chatHoverChip;        // chip under the finger this drag (-1 = none)
@property (nonatomic, assign) NSInteger chatFilterChip;       // chip whose filter menu is open (-1 = SELECT mode)
@property (nonatomic, assign) BOOL chatDragMoved;            // finger left the press point -> a release commits a switch
@property (nonatomic, assign) CGPoint chatDragStart;        // press point (screen), to measure drag distance
@property (nonatomic, assign) CGRect chatPillFrame;           // collapsed pill frame (fan origin), in self.screen
// Fanned-row geometry (self.screen coords), recomputed each tick; a chip's resting frame = (fanRowX + k*fanTabW,
// fanRowY, fanTabW, fanChipH). Used as the drag hit-test target (stable even mid-animation).
@property (nonatomic, assign) CGFloat chatFanRowX;
@property (nonatomic, assign) CGFloat chatFanRowY;
@property (nonatomic, assign) CGFloat chatFanTabW;
@property (nonatomic, assign) CGFloat chatFanChipH;
// Phase-6 native plugin panel: a ⚙ button opens a full-screen modal sheet (scrim eats all touches so nothing
// falls through to the game) listing the plugins (MobileUiBridge.listPlugins) with an enable/disable switch
// and a running/stopped indicator. Refreshed when the bridge's event sequence advances (toggles, conflicts).
@property (nonatomic, strong) UIButton *pluginButton;
@property (nonatomic, strong) UIView *pluginPanel;
@property (nonatomic, strong) UITableView *pluginTable;
@property (nonatomic, strong) NSArray<NSDictionary *> *pluginList;
@property (nonatomic, assign) long long pluginSeq;     // last MobileUiBridge event seq the panel has seen
@property (nonatomic, assign) BOOL pluginPanelOpen;
// className -> CFAbsoluteTime when we first saw it enabled-but-not-active, so a plugin that never reaches
// running (e.g. a native-lib/GPU plugin whose macOS .dylib can't load on iOS) flips from "enabling…" to
// "couldn't start" after a grace period instead of spinning forever.
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *pluginEnabledSince;
// Picture-in-Picture + background keepalive: the game keeps playing after a swipe-home. pipLayer is an
// AVSampleBufferDisplayLayer fed the SAME engine frames as the on-screen blit, parked BEHIND the (opaque)
// game view so it never shows in-app; the system lifts it into the PiP window when the app backgrounds
// (canStartPictureInPictureAutomaticallyFromInline). A silent, mixable AVAudioEngine holds the `audio`
// background mode so the process — JVM, render loop, game socket — keeps running even with PiP dismissed;
// the in-game logout timer (RuneLite, up to 30 min) then decides how long the character stays in.
@property (nonatomic, strong) AVSampleBufferDisplayLayer *pipLayer;
@property (nonatomic, strong) AVPictureInPictureController *pipController;
@property (nonatomic, strong) dispatch_source_t pipBgPump;   // 15 Hz frame pump while backgrounded (the
                                                             // CADisplayLink stops firing there)
@property (nonatomic, assign) int32_t pipFrameSeq;           // last engine frame seq fed to the PiP layer
@property (nonatomic, strong) AVAudioEngine *keepaliveEngine;   // renders silence; holds the audio session
@property (nonatomic, strong) AVAudioSourceNode *keepaliveSource;
@property (nonatomic, assign) UIBackgroundTaskIdentifier bgKeepTask; // per-background grace assertion
@end

// Grace period before an enabled-but-not-active plugin is reported as failed to start.
static const NSTimeInterval kPluginStartGrace = 4.0;

// AWT virtual keycodes for the non-text keys we synthesize (java.awt.event.KeyEvent.VK_*).
static const int AWT_VK_BACK_SPACE = 8;
static const int AWT_VK_ENTER      = 10;
// Zero-width sentinel so a backspace on an "empty" field still fires shouldChangeCharactersInRange.
static NSString * const kKbSentinel = @"​";

// W3 A3/A4: the relocated engine chat's tab row is the top kChatTabRowH px of a kChatW-wide box. Its canvas
// origin moves with the safe-area relocate (self.chatHitCanvas, set in updateHudLayout); these are the fixed
// engine-px dimensions used to treat tab-row taps as UI (precise click, no walk ring).
static const int kChatW = 519;
static const int kChatTabRowH = 23;

// W3 A3 (native chat tabs, matching the official OSRS-mobile app): 7 tabs (NO Report — the official app doesn't
// show it in the tab row), each a stone button with the channel name on top and, for the filterable channels, the
// live filter state ("On"/"Off"/"Filter"/"Hide") below it in the engine's state colour. The 7 tabs span the chat
// width (kChatW), so each is kChatW/7 wide; the row is taller than the bare sprite to fit the two text lines.
static const int kChatTabCount = 7;
static const int kChatTabRowCanvasH = 32;   // 2-line tab height (name + state) in engine-canvas px, ~official
static const int kChatContentCanvasH = 142; // the chat message-area height (engine-canvas px) below the tab row
// Finger-scroll direction: -1 = drag-down scrolls toward OLDER messages (content follows the finger). The drag is
// converted to canvas px and applied to the chat's own scroll layer (no mouse wheel -> no camera zoom). Flip to +1
// if the direction feels inverted.
static const int kChatScrollDir = -1;
static NSString * const kChatTabNames[7] = { @"All", @"Game", @"Public", @"Private", @"Channel", @"Clan", @"Trade" };
// Filterable channels (a right-click filter menu exists): Public/Private/Channel/Clan/Trade. All + Game have
// none, so a pull-down on them is a no-op (no ▾ hint, no menu). Indexes into the 7-tab arrays above.
static const BOOL kChatTabFilterable[7] = { NO, NO, YES, YES, YES, YES, YES };

// Channel-pill redesign tunables (all one-liners to dial in on device).
static const CGFloat kChatHoldDelay     = 0.15;   // press time before the chips fan out (s)
static const CGFloat kChatFanAnim       = 0.18;   // fan out/in spring duration (s)
static const CGFloat kChatFanPopPt      = 0.0;    // extra height (screen pt) a fanned chip gets vs the row (0 = match the original tab size)
static const CGFloat kChatPullDownPt    = 26.0;   // finger travel below a chip's bottom edge that opens its filter
static const CGFloat kChatFilterHintPt  = 10.0;   // finger distance below mid-chip that starts revealing the ▾ hint

// OSRS right-click menu geometry, straight from RuneLite's own hit-test (InteractHighlightOverlay
// .hoveredMenuEntry): the "Choose Option" title strip is 19px, each entry row is 15px. These are fixed
// pixel sizes in the gamepack render space (independent of canvas resolution), so a native row index k maps
// to the engine row centre at (menuX + menuW/2, menuY + 19 + k*15 + 7) for click-through. Display order
// matches MenuPeekBridge: row 0 (top) = the left-click default; the bottom row (index count-1) = "Cancel".
static const int kMenuHeaderPx = 19;
static const int kMenuRowPx    = 15;

// Press-and-hold drag tolerance (gamepack px). Dragging off the menu's SIDE still selects the row at the
// finger's height (generous horizontal grace); lifting clearly ABOVE/BELOW the menu reads as cancel (small
// vertical grace). The cursor is clamped strictly inside the rect regardless, so the menu never auto-closes.
static const CGFloat kMenuGraceX = 60.0;
static const CGFloat kMenuGraceY = 6.0;

// Native overlay sizing (compact — much smaller than the first pass): row height, header strip, box width.
static const CGFloat kMenuRowViewH    = 32.0;
static const CGFloat kMenuHeaderViewH = 22.0;
static const CGFloat kMenuWidth       = 210.0;

@implementation ViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    self.bgTask = UIBackgroundTaskInvalid;
    [self buildUI];
    [self openLogFile];
    self.bgKeepTask = UIBackgroundTaskInvalid;
    // Flush RuneLite config to disk when the app backgrounds (iOS skips RuneLite's own shutdown flush).
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onEnterBackground)
                                                 name:UIApplicationDidEnterBackgroundNotification object:nil];
    // Coming back: stop the background PiP pump (the CADisplayLink takes over) and re-arm the keepalive
    // audio in case the session was interrupted while away.
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onWillEnterForeground)
                                                 name:UIApplicationWillEnterForegroundNotification object:nil];
    [self appendLine:@"HelloShell — W1 minimal JVM shell"];
    [self appendLine:[NSString stringWithFormat:@"jre: %@",
                      [JVMLauncher javaHome] ?: @"(MISSING from bundle!)"]];
    [self appendLine:@"Waiting for StikDebug attach (CS_DEBUGGED)…"];
    [self appendLine:@"Assign the iOS-26 JIT script to this app in StikDebug, then attach."];
    [self startJitPolling];

    // W3 GPU track — B0 GL-tier recon (pure native, no JVM/JIT). Runs once on launch and logs the device's
    // GPU/GL capabilities to the console + hello-shell.log (the go/no-go gate for the GPU renderer effort).
    [GLRecon runWithLog:^(NSString *l) { [self appendLine:l]; }];

    // Persistent plugin-panel dock: an always-on icon rail (right) + icon strip (bottom) over the live game,
    // panels open inline without going through the settings menu. Passthrough overlay (game stays usable); it
    // self-populates once RuneLite registers its panels and tracks plugin toggles.
    self.panelDock = [[PluginPanelsViewController alloc] init];
    self.panelDock.docked = YES;
    [self addChildViewController:self.panelDock];
    self.panelDock.view.frame = self.view.bounds;
    self.panelDock.view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:self.panelDock.view];
    [self.panelDock didMoveToParentViewController:self];

    // #1: the "Tap to Play" cover sits ABOVE everything (game, HUD veneers, panel dock, debug panel) so the
    // cold-start experience is the polished play screen, not the W1 scaffolding. Dismissed on launch.
    [self buildLaunchScreen];
}

#pragma mark - #1 Tap-to-Play front screen

- (void)buildLaunchScreen {
    UIView *cover = [[UIView alloc] initWithFrame:self.view.bounds];
    cover.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    cover.backgroundColor = [UIColor colorWithRed:0.04 green:0.04 blue:0.05 alpha:1.0]; // opaque OSRS-mobile tone
    cover.userInteractionEnabled = YES;   // swallow every touch so nothing reaches the game/HUD beneath
    self.launchScreen = cover;

    // Title — the app identity, top-centred like the Jagex Mobile splash.
    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"RuneLite";
    title.font = [UIFont systemFontOfSize:44 weight:UIFontWeightHeavy];
    title.textColor = [UIColor colorWithRed:0.93 green:0.78 blue:0.36 alpha:1.0]; // OSRS gold
    title.textAlignment = NSTextAlignmentCenter;
    [cover addSubview:title];

    UILabel *subtitle = [[UILabel alloc] init];
    subtitle.translatesAutoresizingMaskIntoConstraints = NO;
    subtitle.text = @"Old School RuneScape";
    subtitle.font = [UIFont systemFontOfSize:15 weight:UIFontWeightMedium];
    subtitle.textColor = [UIColor colorWithWhite:0.7 alpha:1.0];
    subtitle.textAlignment = NSTextAlignmentCenter;
    [cover addSubview:subtitle];

    // Primary play button — disabled (dimmed) until the StikDebug attach flips CS_DEBUGGED. The JIT poller
    // (startJitPolling) enables it; the attach is per-launch and immovable, so this is the gate we polish around.
    UIButton *play = [UIButton buttonWithType:UIButtonTypeSystem];
    play.translatesAutoresizingMaskIntoConstraints = NO;
    [play setTitle:@"Tap to Play" forState:UIControlStateNormal];
    play.titleLabel.font = [UIFont systemFontOfSize:22 weight:UIFontWeightBold];
    [play setTitleColor:[UIColor colorWithRed:0.10 green:0.09 blue:0.05 alpha:1.0] forState:UIControlStateNormal];
    [play setTitleColor:[UIColor colorWithWhite:0.45 alpha:1.0] forState:UIControlStateDisabled];
    play.backgroundColor = [UIColor colorWithRed:0.85 green:0.68 blue:0.26 alpha:1.0];
    play.layer.cornerRadius = 12;
    play.enabled = NO;
    play.alpha = 0.5;
    [play addTarget:self action:@selector(onPlayTapped) forControlEvents:UIControlEventTouchUpInside];
    [cover addSubview:play];
    self.playButton = play;

    // Gated status line under the button: "Attach StikDebug to play…" (amber) → "Ready to play" (green).
    UILabel *status = [[UILabel alloc] init];
    status.translatesAutoresizingMaskIntoConstraints = NO;
    status.text = @"Attach StikDebug to play…";
    status.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    status.textColor = [UIColor systemYellowColor];
    status.textAlignment = NSTextAlignmentCenter;
    status.numberOfLines = 2;
    [cover addSubview:status];
    self.playStatusLabel = status;

    // Login row — reflects the saved session (refreshLaunchLogin keeps the title current). Pure native, so
    // it works before any attach: log in here, then attach + play.
    UIButton *login = [UIButton buttonWithType:UIButtonTypeSystem];
    login.translatesAutoresizingMaskIntoConstraints = NO;
    login.titleLabel.font = [UIFont systemFontOfSize:15 weight:UIFontWeightRegular];
    [login setTitleColor:[UIColor colorWithWhite:0.78 alpha:1.0] forState:UIControlStateNormal];
    [login addTarget:self action:@selector(onLoginWithJagex) forControlEvents:UIControlEventTouchUpInside];
    [cover addSubview:login];
    self.playLoginButton = login;

    // Hidden affordance: a triple-tap on the cover drops to the W1 debug panel (canary / AWT-activation /
    // logs) without launching — the dev escape hatch the audit asked to gate behind a hidden gesture.
    UITapGestureRecognizer *devTap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(onRevealDebug)];
    devTap.numberOfTapsRequired = 3;
    [cover addGestureRecognizer:devTap];

    [self.view addSubview:cover];

    [NSLayoutConstraint activateConstraints:@[
        [title.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [title.bottomAnchor constraintEqualToAnchor:cover.centerYAnchor constant:-40],
        [subtitle.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [subtitle.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:2],

        [play.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [play.topAnchor constraintEqualToAnchor:cover.centerYAnchor constant:24],
        [play.widthAnchor constraintEqualToConstant:240],
        [play.heightAnchor constraintEqualToConstant:56],

        [status.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [status.topAnchor constraintEqualToAnchor:play.bottomAnchor constant:14],
        [status.leadingAnchor constraintGreaterThanOrEqualToAnchor:cover.leadingAnchor constant:20],
        [status.trailingAnchor constraintLessThanOrEqualToAnchor:cover.trailingAnchor constant:-20],

        [login.centerXAnchor constraintEqualToAnchor:cover.centerXAnchor],
        [login.bottomAnchor constraintEqualToAnchor:cover.safeAreaLayoutGuide.bottomAnchor constant:-24],
    ]];

    [self refreshLaunchLogin];
}

// Mirror the saved tokens.json onto the launch-screen login button (same source as refreshLoginButton).
- (void)refreshLaunchLogin {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSData *data = [NSData dataWithContentsOfFile:[docs stringByAppendingPathComponent:@"tokens.json"]];
    id o = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    NSDictionary *t = [o isKindOfClass:[NSDictionary class]] ? o : nil;
    NSString *sid  = [t[@"session_id"] isKindOfClass:[NSString class]] ? t[@"session_id"] : nil;
    NSString *name = [t[@"chosen_display_name"] isKindOfClass:[NSString class]] ? t[@"chosen_display_name"] : nil;
    NSString *title = sid.length
        ? (name.length ? [NSString stringWithFormat:@"Logged in: %@  ·  switch account", name]
                       : @"Logged in  ·  switch account")
        : @"Log in with Jagex";
    [self.playLoginButton setTitle:title forState:UIControlStateNormal];
}

// Hidden dev affordance: dismiss the cover to expose the W1 debug panel (does NOT launch the JVM).
- (void)onRevealDebug {
    self.debugPanel.hidden = NO;
    self.launchScreen.hidden = YES;
    [self.launchScreen removeFromSuperview];
}

- (void)onPlayTapped {
    // If the login web view ran this session, WebKit is resident and the JVM can't start cleanly — onLaunch
    // will prompt a restart instead. Keep the cover up in that case so the user lands back here post-restart.
    // Tap to Play boots the renderer the user SELECTED in the plugins panel, saved in NSUserDefaults
    // "rendererPref" (cpu | gpu | 117hd). Default = cpu (the software renderer — vanilla, always works). The
    // GPU + 117HD plugins are loaded into the panel in every mode (RendererPanelStarter) so they're selectable;
    // flipping one there saves the preference (with a "restart to apply" message), and THIS reads it at launch.
    // base GPU uses the readback present (int[] frames like CPU); 117HD uses the CAEAGLLayer direct present.
    NSString *pref = [[NSUserDefaults standardUserDefaults] stringForKey:@"rendererPref"] ?: @"cpu";
    BOOL wantGpu = [pref isEqualToString:@"gpu"];
    BOOL wantHd = [pref isEqualToString:@"117hd"];
    self.pendingRouteCGpu = wantGpu;
    self.pendingRouteCZone = wantHd;
    self.pendingRouteCZoneDirect = wantHd;
    self.pendingRouteCGpuNoResize = NO;
    self.pendingRouteCL1 = NO;
    [self appendLine:[NSString stringWithFormat:@"[shell] renderer pref = %@", pref]];
    if (self.webKitUsedThisSession) { [self onLaunch]; return; }
    if (!self.launchScreen.hidden) {
        // Fade the cover out, then hand off to the existing JVM launch path.
        [UIView animateWithDuration:0.25 animations:^{ self.launchScreen.alpha = 0.0; }
                         completion:^(BOOL done) {
            self.launchScreen.hidden = YES;
            [self.launchScreen removeFromSuperview];
        }];
    }
    [self onLaunch];
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
    tap.delegate = self;   // never fire a game tap when the touch lands on a native control (chat tab / keyboard)
    [self.screen addGestureRecognizer:tap];
    UILongPressGestureRecognizer *longPress = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onLongPress:)];
    longPress.minimumPressDuration = 0.4;
    longPress.delegate = self;
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

    // Sticky-Shift toggle, next to the keyboard button. Holds VK_SHIFT down via the bridge so the engine's
    // isKeyPressed(KC_SHIFT) path reads true — re-enabling shift-click menu swaps, Shift+right-click marking,
    // and overlay "Configure" on touch (no hardware key). Tap to latch on (highlighted), tap again to release.
    self.shiftButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.shiftButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.shiftButton setTitle:@"⇧" forState:UIControlStateNormal];
    self.shiftButton.titleLabel.font = [UIFont boldSystemFontOfSize:22];
    self.shiftButton.tintColor = [UIColor whiteColor];
    self.shiftButton.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.45];
    self.shiftButton.layer.cornerRadius = 8;
    [self.shiftButton addTarget:self action:@selector(onToggleShift)
               forControlEvents:UIControlEventTouchUpInside];
    [self.screen addSubview:self.shiftButton];

    // Screenshot: save the live composited frame to Photos (the engine's auto-screenshots land in the sandbox
    // dir, unreachable; this is the manual, reachable path — native already has the frame via latestFrame).
    self.screenshotButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.screenshotButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.screenshotButton setTitle:@"📷" forState:UIControlStateNormal];
    self.screenshotButton.titleLabel.font = [UIFont systemFontOfSize:20];
    self.screenshotButton.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.45];
    self.screenshotButton.layer.cornerRadius = 8;
    [self.screenshotButton addTarget:self action:@selector(onScreenshot)
                    forControlEvents:UIControlEventTouchUpInside];
    [self.screen addSubview:self.screenshotButton];

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
        // OSRS dark-UI tone (not stark black) so the covered stones read as game chrome rather than a void.
        cv.backgroundColor = [UIColor colorWithRed:0.12 green:0.105 blue:0.085 alpha:1.0];
        [self.screen addSubview:cv];
        [self.stoneCovers addObject:cv];
    }
    [self buildTabAssets];
    [self registerChatFonts];
    [self buildChatTabs];
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
    self.debugPanel.hidden = YES;   // #1: hidden by default — the ≡ toggle reveals the W1 scaffolding on demand
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
    // GPU Route C dev affordance: LONG-PRESS the launch button to boot the L1 GPU kill-test (routec-l1.jar +
    // RouteC_registerGLNatives + RouteCL1Probe) instead of the full RuneLite boot. A plain tap is unchanged.
    UILongPressGestureRecognizer *l1lp = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onLaunchRouteCL1Gesture:)];
    [self.launchButton addGestureRecognizer:l1lp];
    // TWO-FINGER long-press the launch button to boot the FULL RuneLite in GPU Route C mode (bridge natives
    // registered -> the gpu plugin renders on the device GPU). Distinct from the one-finger L1 gesture.
    UILongPressGestureRecognizer *gpulp = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onLaunchRouteCGpuGesture:)];
    gpulp.numberOfTouchesRequired = 2;
    [self.launchButton addGestureRecognizer:gpulp];
    // THREE-FINGER long-press the launch button to boot the FULL RuneLite in GPU Zone (117HD/RLHD) mode: the
    // staged rlhd jar joins the classpath, the zone facade-delta natives register, and RLHDStarter loads +
    // starts rs117.hd.HdPlugin (the Zone renderer). Distinct from one-finger L1 / two-finger base-GPU.
    UILongPressGestureRecognizer *zonelp = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onLaunchRouteCZoneGesture:)];
    zonelp.numberOfTouchesRequired = 3;
    [self.launchButton addGestureRecognizer:zonelp];
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

    // GPU Route C: dedicated one-tap button to boot full RuneLite on the device GPU (equivalent to the
    // two-finger long-press on the launch button, but unambiguous). Enabled together with the launch button
    // once the JIT debugger is attached.
    self.gpuButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.gpuButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.gpuButton setTitle:@"▶ Run GPU (Route C)" forState:UIControlStateNormal];
    self.gpuButton.titleLabel.font = [UIFont boldSystemFontOfSize:17];
    self.gpuButton.enabled = NO;
    [self.gpuButton addTarget:self action:@selector(onLaunchGpu)
             forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.gpuButton];

    self.zoneButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.zoneButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.zoneButton setTitle:@"▶ 117HD (Zone)" forState:UIControlStateNormal];
    self.zoneButton.titleLabel.font = [UIFont boldSystemFontOfSize:17];
    self.zoneButton.tintColor = [UIColor systemGreenColor];
    self.zoneButton.enabled = NO;
    [self.zoneButton addTarget:self action:@selector(onLaunchZone)
             forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.zoneButton];

    // Fallback: 117HD resizable on the READBACK present (no CAEAGLLayer). The green button defaults to direct
    // present (faster); this is here if direct present ever scans out black or regresses.
    self.zoneFastButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.zoneFastButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.zoneFastButton setTitle:@"117HD readback" forState:UIControlStateNormal];
    self.zoneFastButton.titleLabel.font = [UIFont systemFontOfSize:15];
    self.zoneFastButton.tintColor = [UIColor systemGrayColor];
    self.zoneFastButton.enabled = NO;
    [self.zoneFastButton addTarget:self action:@selector(onLaunchZoneReadback)
             forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.zoneFastButton];

    // "Renderers as plugins": the renderer launch buttons are retired — Tap to Play reads the saved renderer
    // preference and the user selects GPU/117HD from the plugins panel. Hidden (not deleted) so the dev
    // gestures + their constraints stay intact. The L1 kill-test button (if any) is unaffected.
    self.gpuButton.hidden = YES;
    self.zoneButton.hidden = YES;
    self.zoneFastButton.hidden = YES;

    self.loginButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.loginButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.loginButton setTitle:@"Log in with Jagex" forState:UIControlStateNormal];
    self.loginButton.titleLabel.font = [UIFont systemFontOfSize:15];
    [self.loginButton addTarget:self action:@selector(onLoginWithJagex)
               forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.loginButton];

    self.logoutButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.logoutButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.logoutButton setTitle:@"Log out" forState:UIControlStateNormal];
    self.logoutButton.titleLabel.font = [UIFont systemFontOfSize:15];
    self.logoutButton.tintColor = [UIColor systemRedColor];
    self.logoutButton.hidden = YES;   // refreshLoginButton reveals it when a session is saved
    [self.logoutButton addTarget:self action:@selector(onLogout)
                forControlEvents:UIControlEventTouchUpInside];
    [self.debugPanel addSubview:self.logoutButton];

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

    // Plugin/config panel opener — a ⚙ gear, left of the ▦ chrome toggle. Separate from the ≡ debug panel.
    self.pluginButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.pluginButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.pluginButton setTitle:@"⚙" forState:UIControlStateNormal];
    self.pluginButton.titleLabel.font = [UIFont boldSystemFontOfSize:22];
    self.pluginButton.tintColor = [UIColor whiteColor];
    self.pluginButton.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.5];
    self.pluginButton.layer.cornerRadius = 8;
    [self.pluginButton addTarget:self action:@selector(onOpenPluginPanel)
                forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.pluginButton];

    UILayoutGuide *g = self.view.safeAreaLayoutGuide;
    UILayoutGuide *sg = self.screen.safeAreaLayoutGuide;
    UILayoutGuide *p = self.debugPanel.layoutMarginsGuide;
    [NSLayoutConstraint activateConstraints:@[
        // Game fills the WHOLE screen EDGE-TO-EDGE (A1). The OSRS corner HUD (chat/minimap/orbs) used to clip
        // under the island/corners/home-indicator here — that is now fixed by RELOCATING the engine HUD into the
        // safe area (updateHudLayout), so the scene can own every pixel. Render aspect is computed from the full
        // bounds (onLaunch) so there is no internal letterbox.
        [self.screen.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.screen.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.screen.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.screen.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],

        // In-game keyboard control, bottom-left of the safe area.
        [self.keyboardButton.bottomAnchor constraintEqualToAnchor:g.bottomAnchor constant:-8],
        [self.keyboardButton.leadingAnchor constraintEqualToAnchor:g.leadingAnchor constant:8],
        [self.keyboardButton.widthAnchor constraintEqualToConstant:44],
        [self.keyboardButton.heightAnchor constraintEqualToConstant:36],

        // Sticky-Shift toggle — same row, just right of the keyboard button.
        [self.shiftButton.bottomAnchor constraintEqualToAnchor:self.keyboardButton.bottomAnchor],
        [self.shiftButton.leadingAnchor constraintEqualToAnchor:self.keyboardButton.trailingAnchor constant:8],
        [self.shiftButton.widthAnchor constraintEqualToConstant:44],
        [self.shiftButton.heightAnchor constraintEqualToConstant:36],

        // Screenshot button — same row, right of the shift button.
        [self.screenshotButton.bottomAnchor constraintEqualToAnchor:self.keyboardButton.bottomAnchor],
        [self.screenshotButton.leadingAnchor constraintEqualToAnchor:self.shiftButton.trailingAnchor constant:8],
        [self.screenshotButton.widthAnchor constraintEqualToConstant:44],
        [self.screenshotButton.heightAnchor constraintEqualToConstant:36],

        // Utility trio (⚙ ▦ ≡) — moved OUT of the top-right, where it overlapped the engine XP counter + minimap
        // orbs (the XP button lives in the left part of the relocated MAP_CONTAINER cluster, and XP-drop numbers
        // expand left into the trio). Now a vertical stack in the BOTTOM-LEFT above the keyboard button, over the
        // scene and clear of every relocated HUD element (chat top-left, minimap/orbs/XP top-right, inventory
        // bottom-right). Bottom-up order: keyboard, then ⚙, ▦, ≡.
        [self.pluginButton.bottomAnchor constraintEqualToAnchor:self.keyboardButton.topAnchor constant:-8],
        [self.pluginButton.leadingAnchor constraintEqualToAnchor:self.keyboardButton.leadingAnchor],
        [self.pluginButton.widthAnchor constraintEqualToConstant:44],
        [self.pluginButton.heightAnchor constraintEqualToConstant:36],

        [self.chromeToggle.bottomAnchor constraintEqualToAnchor:self.pluginButton.topAnchor constant:-8],
        [self.chromeToggle.leadingAnchor constraintEqualToAnchor:self.keyboardButton.leadingAnchor],
        [self.chromeToggle.widthAnchor constraintEqualToConstant:44],
        [self.chromeToggle.heightAnchor constraintEqualToConstant:36],

        [self.debugToggle.bottomAnchor constraintEqualToAnchor:self.chromeToggle.topAnchor constant:-8],
        [self.debugToggle.leadingAnchor constraintEqualToAnchor:self.keyboardButton.leadingAnchor],
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
        [self.chromeButton.topAnchor constraintEqualToAnchor:self.launchButton.bottomAnchor constant:6],
        [self.chromeButton.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.gpuButton.centerYAnchor constraintEqualToAnchor:self.chromeButton.centerYAnchor],
        [self.gpuButton.leadingAnchor constraintEqualToAnchor:self.chromeButton.trailingAnchor constant:24],
        [self.zoneButton.centerYAnchor constraintEqualToAnchor:self.gpuButton.centerYAnchor],
        [self.zoneButton.leadingAnchor constraintEqualToAnchor:self.gpuButton.trailingAnchor constant:24],
        // ⚡ Fast on its own row (the chrome/gpu/zone row is already full-width).
        [self.zoneFastButton.topAnchor constraintEqualToAnchor:self.chromeButton.bottomAnchor constant:6],
        [self.zoneFastButton.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.loginButton.topAnchor constraintEqualToAnchor:self.zoneFastButton.bottomAnchor constant:6],
        [self.loginButton.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.logoutButton.centerYAnchor constraintEqualToAnchor:self.loginButton.centerYAnchor],
        [self.logoutButton.leadingAnchor constraintEqualToAnchor:self.loginButton.trailingAnchor constant:16],
        [self.console.topAnchor constraintEqualToAnchor:self.loginButton.bottomAnchor constant:6],
        [self.console.leadingAnchor constraintEqualToAnchor:p.leadingAnchor],
        [self.console.trailingAnchor constraintEqualToAnchor:p.trailingAnchor],
        [self.console.bottomAnchor constraintEqualToAnchor:p.bottomAnchor],
    ]];

    [self refreshLoginButton];   // reflect any already-saved session on the button title
}

- (void)onToggleDebug {
    self.debugPanel.hidden = !self.debugPanel.hidden;
}

// On-device Jagex login: present the embedded OAuth login, and on success persist the chosen character to
// tokens.json — exactly the file loadJagexTokens (JVMLauncher) reads to arm autologin. Pure native (no
// JVM), so this works before any StikDebug attach: log in here, then attach + Start JVM to play.
- (void)onLoginWithJagex {
    JagexLoginViewController *vc = [JagexLoginViewController new];
    vc.modalPresentationStyle = UIModalPresentationFullScreen;
    self.webKitUsedThisSession = YES;   // the login web view loads WebKit, which can't be unloaded in-process
    __weak typeof(self) ws = self;
    vc.onLog = ^(NSString *line) { [ws appendLine:[@"[login] " stringByAppendingString:line]]; };
    vc.onComplete = ^(NSString *sessionId, NSString *accountId, NSString *displayName, NSArray *accounts) {
        [ws persistTokensSession:sessionId account:accountId display:displayName accounts:accounts];
        [ws refreshLoginButton];
        // WebKit is now resident → the JVM can't start cleanly in this process. Prompt a one-tap restart
        // into a fresh process; the saved tokens.json means the relaunch goes straight to play, no web view.
        [vc dismissViewControllerAnimated:YES completion:^{ [ws promptRestartToPlay]; }];
    };
    [self presentViewController:vc animated:YES completion:nil];
}

// Write Documents/tokens.json in the shape loadJagexTokens reads. Stage A only → access/refresh empty
// (the gamepack mints its own play token from session_id + chosen_account_id). File-protected at rest;
// never bundled or committed (it's the per-device secret).
- (void)persistTokensSession:(NSString *)sessionId account:(NSString *)accountId
                     display:(NSString *)display accounts:(NSArray *)accounts {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"tokens.json"];
    NSDictionary *t = @{
        @"session_id":          sessionId  ?: @"",
        @"chosen_account_id":   accountId  ?: @"",
        @"chosen_display_name": display    ?: @"",
        @"accounts":            accounts   ?: @[],
        @"access_token":  @"",
        @"refresh_token": @"",
        @"notes": @"Minted on-device by JagexLoginViewController (Stage A). access/refresh empty; the "
                  @"gamepack mints its own play token from session_id + chosen_account_id.",
    };
    NSError *err = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:t options:NSJSONWritingPrettyPrinted error:&err];
    if (!data) {
        [self appendLine:[@"[login] tokens.json encode failed: " stringByAppendingString:err.localizedDescription ?: @"?"]];
        return;
    }
    BOOL ok = [data writeToFile:path
                        options:(NSDataWritingAtomic | NSDataWritingFileProtectionComplete)
                          error:&err];
    [self appendLine:ok
        ? [NSString stringWithFormat:@"[login] tokens.json written — logged in as %@. Attach StikDebug, then Start JVM.",
           display.length ? display : @"(unnamed)"]
        : [NSString stringWithFormat:@"[login] tokens.json write FAILED: %@", err.localizedDescription ?: @"?"]];
}

// Reflect the saved login on the button title so the user can see they're signed in (and re-login if needed).
- (void)refreshLoginButton {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSData *data = [NSData dataWithContentsOfFile:[docs stringByAppendingPathComponent:@"tokens.json"]];
    id o = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    NSDictionary *t = [o isKindOfClass:[NSDictionary class]] ? o : nil;
    NSString *sid  = [t[@"session_id"] isKindOfClass:[NSString class]] ? t[@"session_id"] : nil;
    NSString *name = [t[@"chosen_display_name"] isKindOfClass:[NSString class]] ? t[@"chosen_display_name"] : nil;
    NSString *title = sid.length
        ? (name.length ? [NSString stringWithFormat:@"Logged in: %@  ·  re-login", name] : @"Logged in  ·  re-login")
        : @"Log in with Jagex";
    [self.loginButton setTitle:title forState:UIControlStateNormal];
    self.logoutButton.hidden = (sid.length == 0);
    [self refreshLaunchLogin];   // #1: keep the Tap-to-Play login row in sync
}

// Log out: delete the saved game session (so autologin won't fire) and purge any persisted Jagex web
// cookies from older builds (the login webview itself is now ephemeral, so it leaves none going forward).
- (void)onLogout {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"tokens.json"];
    BOOL existed = [[NSFileManager defaultManager] fileExistsAtPath:path];
    if (existed) [[NSFileManager defaultManager] removeItemAtPath:path error:nil];

    NSSet *types = [NSSet setWithObjects:WKWebsiteDataTypeCookies, WKWebsiteDataTypeLocalStorage,
                    WKWebsiteDataTypeSessionStorage, WKWebsiteDataTypeIndexedDBDatabases, nil];
    WKWebsiteDataStore *ds = [WKWebsiteDataStore defaultDataStore];
    [ds fetchDataRecordsOfTypes:types completionHandler:^(NSArray<WKWebsiteDataRecord *> *records) {
        NSMutableArray *kill = [NSMutableArray array];
        for (WKWebsiteDataRecord *r in records) {
            NSString *n = r.displayName.lowercaseString ?: @"";
            if ([n containsString:@"jagex"] || [n containsString:@"runescape"]) [kill addObject:r];
        }
        if (kill.count) [ds removeDataOfTypes:types forDataRecords:kill completionHandler:^{}];
    }];

    [self refreshLoginButton];
    [self appendLine:existed ? @"[login] logged out — tokens.json cleared; autologin disarmed."
                             : @"[login] already logged out (no tokens.json)."];
}

// After an on-device login the WKWebView leaves WebKit permanently resident in this process, which stops
// StikDebug from servicing HotSpot's JIT-mirror brk #0x69 → the JVM crashes at code-cache init. A fresh
// process has never loaded WebKit, so the reliable path is to restart. iOS can't relaunch an app itself, so
// quit cleanly here; the saved tokens.json means the next launch goes straight to play with no web view
// (no WebKit) and the brk is serviced normally. Only reached after an actual login (fresh login / switch).
- (void)promptRestartToPlay {
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSData *data = [NSData dataWithContentsOfFile:[docs stringByAppendingPathComponent:@"tokens.json"]];
    id o = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    NSString *name = [o isKindOfClass:[NSDictionary class]] ? o[@"chosen_display_name"] : nil;
    NSString *who = name.length ? name : @"your account";
    UIAlertController *a = [UIAlertController
        alertControllerWithTitle:@"Logged in ✓"
                         message:[NSString stringWithFormat:
                            @"Signed in as %@.\n\nRuneLite needs to restart once before you can play. Tap "
                            @"Restart, then reopen RuneLite from your Home Screen — you won't have to log in "
                            @"again.", who]
                  preferredStyle:UIAlertControllerStyleAlert];
    [a addAction:[UIAlertAction actionWithTitle:@"Restart now" style:UIAlertActionStyleDefault handler:^(UIAlertAction *x) {
        exit(0);   // tokens.json is already written atomically; the relaunch reads it and goes straight to play
    }]];
    [a addAction:[UIAlertAction actionWithTitle:@"Not now" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:a animated:YES completion:nil];
}

#pragma mark - Plugin/config panel (Phase 6)

// Build the modal sheet once: a full-screen scrim (eats every touch so nothing reaches the game) holding a
// titled card with a close button and the plugin table. Added on top of all chrome.
- (void)buildPluginPanel {
    if (self.pluginPanel) return;

    self.pluginPanel = [[UIView alloc] initWithFrame:self.view.bounds];
    self.pluginPanel.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    self.pluginPanel.backgroundColor = [UIColor colorWithWhite:0.0 alpha:0.55]; // scrim
    self.pluginPanel.userInteractionEnabled = YES;                              // consume all touches
    self.pluginPanel.hidden = YES;

    UIView *card = [[UIView alloc] init];
    card.translatesAutoresizingMaskIntoConstraints = NO;
    card.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:0.98]; // dark OSRS tone
    card.layer.cornerRadius = 12;
    card.clipsToBounds = YES;
    [self.pluginPanel addSubview:card];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Plugins";
    title.font = [UIFont boldSystemFontOfSize:18];
    title.textColor = [UIColor whiteColor];
    [card addSubview:title];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"Done" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [close addTarget:self action:@selector(onClosePluginPanel) forControlEvents:UIControlEventTouchUpInside];
    [card addSubview:close];

    // Plugin Hub opener — browse/install external plugins (presents PluginHubViewController over the panel).
    UIButton *hub = [UIButton buttonWithType:UIButtonTypeSystem];
    hub.translatesAutoresizingMaskIntoConstraints = NO;
    [hub setTitle:@"＋ Hub" forState:UIControlStateNormal];
    hub.titleLabel.font = [UIFont boldSystemFontOfSize:15];
    hub.tintColor = [UIColor colorWithRed:0.55 green:0.80 blue:1.0 alpha:1.0];
    [hub addTarget:self action:@selector(onOpenHub) forControlEvents:UIControlEventTouchUpInside];
    [card addSubview:hub];

    // Plugin side-panels opener (XP tracker, GE, notes…) — presents PluginPanelsViewController.
    UIButton *panels = [UIButton buttonWithType:UIButtonTypeSystem];
    panels.translatesAutoresizingMaskIntoConstraints = NO;
    [panels setTitle:@"▣ Panels" forState:UIControlStateNormal];
    panels.titleLabel.font = [UIFont boldSystemFontOfSize:15];
    panels.tintColor = [UIColor colorWithRed:0.70 green:0.85 blue:0.55 alpha:1.0];
    [panels addTarget:self action:@selector(onOpenPanels) forControlEvents:UIControlEventTouchUpInside];
    // Long-press = the legacy centered-fullscreen panel surface (instant A/B fallback against the new drawer).
    UILongPressGestureRecognizer *panelsLegacy = [[UILongPressGestureRecognizer alloc] initWithTarget:self action:@selector(onOpenPanelsLegacy:)];
    [panels addGestureRecognizer:panelsLegacy];
    [card addSubview:panels];

    self.pluginTable = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStylePlain];
    self.pluginTable.translatesAutoresizingMaskIntoConstraints = NO;
    self.pluginTable.backgroundColor = [UIColor clearColor];
    self.pluginTable.dataSource = self;
    self.pluginTable.delegate = self;
    self.pluginTable.rowHeight = 56;
    // Drive row interaction with our OWN gesture recognizers, not the table's selection — the table's built-in
    // tap-to-select arbitrates with the scroll view's pan and fired only intermittently. allowsSelection=NO so
    // nothing competes: a tap recognizer (forgiving slop) toggles, a long-press opens config, drags still
    // scroll. cancelsTouchesInView=NO so they never swallow a scroll/other touch.
    self.pluginTable.delaysContentTouches = NO;
    self.pluginTable.allowsSelection = NO;
    [card addSubview:self.pluginTable];

    UITapGestureRecognizer *rowTap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(onPluginRowTap:)];
    rowTap.cancelsTouchesInView = NO;
    [self.pluginTable addGestureRecognizer:rowTap];

    UILongPressGestureRecognizer *cfgLp = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onPluginRowLongPress:)];
    cfgLp.cancelsTouchesInView = NO;
    [self.pluginTable addGestureRecognizer:cfgLp];

    [self.view addSubview:self.pluginPanel];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [card.topAnchor constraintEqualToAnchor:sg.topAnchor constant:10],
        [card.bottomAnchor constraintEqualToAnchor:sg.bottomAnchor constant:-10],
        [card.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:24],
        [card.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-24],

        [title.topAnchor constraintEqualToAnchor:card.topAnchor constant:14],
        [title.leadingAnchor constraintEqualToAnchor:card.leadingAnchor constant:16],
        [close.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:card.trailingAnchor constant:-16],
        [hub.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [hub.trailingAnchor constraintEqualToAnchor:close.leadingAnchor constant:-18],
        [panels.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [panels.trailingAnchor constraintEqualToAnchor:hub.leadingAnchor constant:-18],

        [self.pluginTable.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:10],
        [self.pluginTable.leadingAnchor constraintEqualToAnchor:card.leadingAnchor],
        [self.pluginTable.trailingAnchor constraintEqualToAnchor:card.trailingAnchor],
        [self.pluginTable.bottomAnchor constraintEqualToAnchor:card.bottomAnchor],
    ]];
}

- (void)onOpenPluginPanel {
    [self buildPluginPanel];
    if (!self.pluginEnabledSince) self.pluginEnabledSince = [NSMutableDictionary dictionary];
    [self refreshPluginList];
    self.pluginSeq = [JVMLauncher mobileUiSeq];
    [self.view bringSubviewToFront:self.pluginPanel];
    self.pluginPanel.hidden = NO;
    self.pluginPanelOpen = YES;
    [self appendLine:[NSString stringWithFormat:@"[plugins] panel open (%lu plugins, seq=%lld)",
                      (unsigned long)self.pluginList.count, self.pluginSeq]];
}

- (void)onClosePluginPanel {
    self.pluginPanel.hidden = YES;
    self.pluginPanelOpen = NO;
    // Persist any plugin toggles now — iOS won't run RuneLite's shutdown/periodic flush on close.
    [JVMLauncher mobileFlushConfig];
}

- (void)onOpenHub {
    PluginHubViewController *hubVC = [[PluginHubViewController alloc] init];
    [self presentViewController:hubVC animated:YES completion:nil];
}

- (void)onOpenPanels {
    // Drawer mode: present over the dimmed, still-running game. Close the plugins-management overlay first so
    // what shows behind the dim is the live game (not the opaque plugins card).
    [self onClosePluginPanel];
    PluginPanelsViewController *panelsVC = [[PluginPanelsViewController alloc] init];
    panelsVC.drawerMode = YES;
    panelsVC.modalPresentationStyle = UIModalPresentationOverFullScreen; // keep the game visible behind
    panelsVC.modalTransitionStyle = UIModalTransitionStyleCrossDissolve;
    [self presentViewController:panelsVC animated:YES completion:nil];
}

- (void)onOpenPanelsLegacy:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    PluginPanelsViewController *panelsVC = [[PluginPanelsViewController alloc] init];
    panelsVC.drawerMode = NO;
    panelsVC.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:panelsVC animated:YES completion:nil];
}

// App going to background (user swiped home / to the switcher, before any termination): flush config so
// every edit/toggle made this session survives a relaunch. The one lifecycle hook iOS reliably delivers.
// Then keep the session ALIVE back there: a fresh background-task grace covers the handover, the silent
// audio engine (audio background mode) blocks suspension so the JVM + game socket keep running, and a
// 15 Hz pump keeps feeding live frames to the (auto-started) Picture-in-Picture window.
- (void)onEnterBackground {
    [JVMLauncher mobileFlushConfig];
    if (self.bgKeepTask != UIBackgroundTaskInvalid) {
        [[UIApplication sharedApplication] endBackgroundTask:self.bgKeepTask];
    }
    self.bgKeepTask = [[UIApplication sharedApplication]
        beginBackgroundTaskWithName:@"bg-keepalive" expirationHandler:^{
            if (self.bgKeepTask != UIBackgroundTaskInvalid) {
                [[UIApplication sharedApplication] endBackgroundTask:self.bgKeepTask];
                self.bgKeepTask = UIBackgroundTaskInvalid;
            }
        }];
    if (self.launched) {
        [self startKeepaliveEngine];   // defensive re-arm (a route change can stop a running engine)
        [self startPipBgPump];
    }
    [self appendLine:@"[shell] backgrounded -> flushed config, keepalive armed"];
}

- (void)onWillEnterForeground {
    [self stopPipBgPump];
    if (self.bgKeepTask != UIBackgroundTaskInvalid) {
        [[UIApplication sharedApplication] endBackgroundTask:self.bgKeepTask];
        self.bgKeepTask = UIBackgroundTaskInvalid;
    }
    if (self.launched) [self startKeepaliveEngine];
    [self appendLine:@"[shell] foregrounded -> background pump stopped"];
}

// Read listPlugins() once, parse the JSON array, sort by name, reload the table.
- (void)refreshPluginList {
    NSString *json = [JVMLauncher mobilePlugins];
    NSArray *arr = nil;
    if (json.length) {
        NSError *err = nil;
        id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding]
                                                    options:0 error:&err];
        if ([parsed isKindOfClass:[NSArray class]]) arr = parsed;
    }
    if (arr) {
        self.pluginList = [arr sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
            BOOL pa = [a[@"pinned"] boolValue], pb = [b[@"pinned"] boolValue];
            if (pa != pb) return pa ? NSOrderedAscending : NSOrderedDescending;   // pinned float to the top
            NSString *na = [a[@"name"] description] ?: @"";
            NSString *nb = [b[@"name"] description] ?: @"";
            return [na caseInsensitiveCompare:nb];
        }];
    } else {
        self.pluginList = @[];
    }
    // Maintain the enabled-but-not-active timers: start one when a plugin is first seen pending, clear it
    // once it's running or disabled.
    const CFAbsoluteTime now = CFAbsoluteTimeGetCurrent();
    for (NSDictionary *p in self.pluginList) {
        NSString *cls = [p[@"className"] description];
        if (!cls) continue;
        BOOL pending = [p[@"enabled"] boolValue] && ![p[@"active"] boolValue];
        if (pending) {
            if (!self.pluginEnabledSince[cls]) self.pluginEnabledSince[cls] = @(now);
        } else {
            [self.pluginEnabledSince removeObjectForKey:cls];
        }
    }
    [self.pluginTable reloadData];
}

#pragma mark UITableView

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return (NSInteger)self.pluginList.count;
}

// Tap a plugin row -> toggle it on/off (a forgiving tap recognizer, not the table's flaky selection).
- (void)onPluginRowTap:(UITapGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateEnded) return;
    NSIndexPath *ip = [self.pluginTable indexPathForRowAtPoint:[g locationInView:self.pluginTable]];
    if (ip) [self togglePluginAtRow:ip.row];
}


- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    static NSString *cid = @"pluginCell";
    UITableViewCell *cell = [tableView dequeueReusableCellWithIdentifier:cid];
    if (!cell) {
        cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:cid];
        cell.backgroundColor = [UIColor clearColor];
        cell.textLabel.textColor = [UIColor whiteColor];
        UISwitch *sw = [[UISwitch alloc] init];
        sw.userInteractionEnabled = NO;   // pure indicator — the row tap toggles (reliable big target)
        cell.accessoryView = sw;
    }
    NSDictionary *p = self.pluginList[(NSUInteger)indexPath.row];
    BOOL enabled = [p[@"enabled"] boolValue];
    BOOL active  = [p[@"active"] boolValue];
    BOOL hasConfig = [p[@"hasConfig"] boolValue]
        && [p[@"configGroup"] isKindOfClass:[NSString class]] && [p[@"configGroup"] length] > 0;
    cell.textLabel.text = [p[@"name"] description];
    BOOL pinned = [p[@"pinned"] boolValue];
    cell.imageView.image = pinned ? [UIImage systemImageNamed:@"star.fill"] : nil;
    cell.imageView.tintColor = [UIColor colorWithRed:0.95 green:0.78 blue:0.30 alpha:1.0];
    NSString *status;
    UIColor *statusColor;
    // Renderer plugins (GPU / 117HD): their switch reflects the SAVED launch preference (what will be active next
    // launch), not the live "is it running this session" state — they only activate after a restart.
    NSString *rk = [self rendererKeyForClass:[p[@"className"] description]];
    if (rk) {
        enabled = [[self rendererPref] isEqualToString:rk];
        status = enabled ? @"● selected — restart to apply" : @"○ off";
        statusColor = enabled ? [UIColor colorWithRed:0.45 green:0.85 blue:0.40 alpha:1.0]
                              : [UIColor colorWithWhite:0.6 alpha:1.0];
        cell.detailTextLabel.text = hasConfig ? [status stringByAppendingString:@"   ·  hold to configure"] : status;
        cell.detailTextLabel.textColor = statusColor;
        cell.selectionStyle = UITableViewCellSelectionStyleDefault;
        UISwitch *rsw = (UISwitch *)cell.accessoryView;
        rsw.tag = indexPath.row;
        [rsw setOn:enabled animated:NO];
        return cell;
    }
    if (enabled && active) {
        status = @"● running"; statusColor = [UIColor colorWithRed:0.45 green:0.85 blue:0.40 alpha:1.0];
    } else if (enabled && !active) {
        // Pending until the grace period elapses; after that, treat as failed to start (native-lib/GPU plugins
        // whose macOS .dylib can't load on iOS never reach running).
        NSNumber *since = self.pluginEnabledSince[[p[@"className"] description] ?: @""];
        BOOL graceElapsed = since && (CFAbsoluteTimeGetCurrent() - since.doubleValue) > kPluginStartGrace;
        if (graceElapsed) {
            status = @"✕ couldn't start (incompatible)";
            statusColor = [UIColor colorWithRed:0.95 green:0.45 blue:0.40 alpha:1.0];
        } else {
            status = @"○ enabling…"; statusColor = [UIColor colorWithRed:0.95 green:0.80 blue:0.30 alpha:1.0];
        }
    } else {
        status = @"○ disabled"; statusColor = [UIColor colorWithWhite:0.6 alpha:1.0];
    }
    cell.detailTextLabel.text = hasConfig ? [status stringByAppendingString:@"   ·  hold to configure"] : status;
    cell.detailTextLabel.textColor = statusColor;
    cell.selectionStyle = UITableViewCellSelectionStyleDefault;   // every row taps to toggle
    UISwitch *sw = (UISwitch *)cell.accessoryView;
    sw.tag = indexPath.row;
    [sw setOn:enabled animated:NO];
    return cell;
}

// The two renderer plugins are launch-time on iOS (the GL bridge + present layer are set up at JVM start), so
// they're not hot-swappable like a normal plugin. Their on/off + config live in the panel like any plugin, but
// toggling one saves a launch PREFERENCE (applied on the next launch) instead of a live start/stop.
static NSString * const kRendererGpuClass = @"net.runelite.client.plugins.gpu.GpuPlugin";
static NSString * const kRendererHdClass  = @"rs117.hd.HdPlugin";

// "gpu" / "117hd" for the renderer plugins, nil for any normal plugin.
- (NSString *)rendererKeyForClass:(NSString *)className {
    if ([className isEqualToString:kRendererGpuClass]) return @"gpu";
    if ([className isEqualToString:kRendererHdClass]) return @"117hd";
    return nil;
}

- (NSString *)rendererPref {
    return [[NSUserDefaults standardUserDefaults] stringForKey:@"rendererPref"] ?: @"cpu";
}

// A renderer-plugin row was toggled: select it (or fall back to the software renderer), save the preference,
// and tell the user it applies on the next launch. No live setPluginEnabled.
- (void)toggleRenderer:(NSString *)rendererKey {
    BOOL turnOn = ![[self rendererPref] isEqualToString:rendererKey];   // tapping the selected one turns it off
    NSString *newPref = turnOn ? rendererKey : @"cpu";
    [[NSUserDefaults standardUserDefaults] setObject:newPref forKey:@"rendererPref"];
    NSString *label = [rendererKey isEqualToString:@"117hd"] ? @"117HD" : @"GPU";
    NSString *msg = turnOn
        ? [NSString stringWithFormat:@"%@ will be your renderer the next time you launch.\n\nFully quit RuneLite (swipe it away) and relaunch to apply.", label]
        : @"The software renderer will be used the next time you launch.\n\nFully quit RuneLite (swipe it away) and relaunch to apply.";
    UIAlertController *a = [UIAlertController alertControllerWithTitle:@"Restart to apply"
                                                              message:msg preferredStyle:UIAlertControllerStyleAlert];
    [a addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
    UIViewController *presenter = self.presentedViewController ?: self;
    [presenter presentViewController:a animated:YES completion:nil];
    [self appendLine:[NSString stringWithFormat:@"[renderer] pref -> %@ (restart to apply)", newPref]];
    [self refreshPluginList];   // the row now reflects the saved preference
}

// Toggle a plugin by row: drive MobileUiBridge.setPluginEnabled (EDT lane). The resulting PluginChanged bumps
// the event sequence, so onDisplayTick re-reads the list and the running/stopped indicator settles.
- (void)togglePluginAtRow:(NSInteger)row {
    if (row < 0 || row >= (NSInteger)self.pluginList.count) return;
    NSDictionary *p = self.pluginList[(NSUInteger)row];
    NSString *className = [p[@"className"] description];
    NSString *rk = [self rendererKeyForClass:className];
    if (rk) { [self toggleRenderer:rk]; return; }   // renderer plugins use the launch preference, not a live toggle
    BOOL newOn = ![p[@"enabled"] boolValue];
    NSString *ack = [JVMLauncher mobileSetPluginEnabled:className enabled:newOn];
    // Start/clear the grace timer from the user action (before the next refresh observes it).
    if (className) {
        if (newOn) self.pluginEnabledSince[className] = @(CFAbsoluteTimeGetCurrent());
        else [self.pluginEnabledSince removeObjectForKey:className];
    }
    // Optimistic visual: flip this row's switch now; the next refresh reconciles with the real state.
    UITableViewCell *cell = [self.pluginTable cellForRowAtIndexPath:[NSIndexPath indexPathForRow:row inSection:0]];
    if ([cell.accessoryView isKindOfClass:[UISwitch class]]) {
        [(UISwitch *)cell.accessoryView setOn:newOn animated:YES];
    }
    [self appendLine:[NSString stringWithFormat:@"[plugins] %@ -> %@ : %@",
                      p[@"name"], newOn ? @"enable" : @"disable", ack ?: @"(nil)"]];
}

// Long-press a plugin row -> open its config screen (if it has one). Tap toggles; hold configures.
- (void)onPluginRowLongPress:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    NSIndexPath *ip = [self.pluginTable indexPathForRowAtPoint:[g locationInView:self.pluginTable]];
    if (!ip || ip.row < 0 || ip.row >= (NSInteger)self.pluginList.count) return;
    if (self.presentedViewController) return;   // don't stack/re-present (also guards a re-fired gesture)
    NSDictionary *p = self.pluginList[(NSUInteger)ip.row];
    NSString *name = [p[@"name"] description] ?: @"";
    BOOL hasConfig = [p[@"hasConfig"] boolValue];
    NSString *group = [p[@"configGroup"] isKindOfClass:[NSString class]] ? p[@"configGroup"] : nil;
    BOOL pinned = [p[@"pinned"] boolValue];

    // Long-press menu: pin/unpin (always) + configure (if the plugin has config). Pin floats it to the top of
    // the list and persists via the shared pinnedPlugins CSV. Centered Alert (not an action sheet) so it stays
    // put — an action sheet anchors to the bottom safe-area inset, which the 60fps HUD tick churns landscape.
    UIAlertController *sheet = [UIAlertController alertControllerWithTitle:name message:nil
                                                          preferredStyle:UIAlertControllerStyleAlert];
    [sheet addAction:[UIAlertAction actionWithTitle:(pinned ? @"Unpin" : @"Pin to top")
                                              style:UIAlertActionStyleDefault
                                            handler:^(UIAlertAction *a) {
        [JVMLauncher mobileSetPinned:name pinned:!pinned];
        [self refreshPluginList];
    }]];
    if (hasConfig && group.length > 0) {
        [sheet addAction:[UIAlertAction actionWithTitle:@"Configure" style:UIAlertActionStyleDefault
                                                handler:^(UIAlertAction *a) {
            // 117HD (group "hd"): show ONLY the iOS-ported settings (each safe to setConfig from RLHDStarter).
            // Exposing the rest would recompile an unported shader and stop the plugin. Others show full config.
            NSArray<NSString *> *whitelist = [group isEqualToString:@"hd"] ? @[
                @"sceneResolutionScale", @"antiAliasingMode", @"shadowMode", @"shadowResolution",
                @"enableShadowTransparency", @"dynamicLights", @"tiledLighting", @"normalMapping",
                @"parallaxOcclusionMappingToggle", @"drawDistance",
            ] : nil;
            PluginConfigViewController *cfg = [[PluginConfigViewController alloc] initWithGroup:group
                                                                                    pluginName:name
                                                                                     whitelist:whitelist];
            [self presentViewController:cfg animated:YES completion:nil];
        }]];
    }
    [sheet addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [self presentViewController:sheet animated:YES completion:nil];
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
        // #1: drive the Tap-to-Play cover. Enable the big play button on attach; reflect the gate in its status.
        if (attached) {
            self.playStatusLabel.text = @"Ready to play";
            self.playStatusLabel.textColor = [UIColor systemGreenColor];
            if (!self.playButton.enabled) {
                self.playButton.enabled = YES;
                [UIView animateWithDuration:0.2 animations:^{ self.playButton.alpha = 1.0; }];
            }
        } else {
            self.playStatusLabel.text = @"Attach StikDebug to play…";
            self.playStatusLabel.textColor = [UIColor systemYellowColor];
        }
        if (attached && !self.launchButton.enabled && !self.launched) {
            self.launchButton.enabled = YES;
            self.gpuButton.enabled = YES;
            self.zoneButton.enabled = YES;
            self.zoneFastButton.enabled = YES;
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
    CGFloat iw = img ? img.size.width : 0, ih = img ? img.size.height : 0;
    if ((iw <= 0 || ih <= 0) && self.renderW > 0) { iw = self.renderW; ih = self.renderH; }  // GPU present: no UIImage
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

#pragma mark - W3 A3: enlarged native chat tab buttons

// Register the bundled RuneScape TTFs (the same faithful in-game font RuneLite uses for overlays) so the native
// chat-tab labels render in the authentic OSRS font instead of the system font. Runtime registration — no
// Info.plist UIAppFonts entry needed. PostScript names: "RuneScape", "RuneScapeBold", "RuneScape-Small".
- (void)registerChatFonts {
    for (NSString *n in @[@"runescape", @"runescape_bold", @"runescape_small"]) {
        NSString *p = [[NSBundle mainBundle] pathForResource:n ofType:@"ttf"];
        if (!p) { [self appendLine:[NSString stringWithFormat:@"[chat] font %@ MISSING", n]]; continue; }
        NSData *d = [NSData dataWithContentsOfFile:p];
        if (d.length == 0) continue;
        CGDataProviderRef prov = CGDataProviderCreateWithCFData((__bridge CFDataRef)d);
        if (!prov) continue;
        CGFontRef f = CGFontCreateWithDataProvider(prov);
        if (f) {
            CTFontManagerRegisterGraphicsFont(f, NULL);   // dup-register on relaunch is a harmless no-op
            CGFontRelease(f);
        }
        CGDataProviderRelease(prov);
    }
}

// Build the collapsed channel PILL + the 7 fan-out chips once, as subviews of the game view (above the blit).
// The pill (active channel) is the only interactive view: a quick tap toggles the chat collapse, a hold fans the
// 7 chips out for drag-select / pull-down-to-filter (onChatPillDrag). The chips are display-only
// (userInteractionEnabled=NO) — the pill's long-press tracks the finger across them, so they never intercept
// touches. Sprite-backed two-line RuneScape-font labels (name + filter state) like the official app; hidden until
// the chat is relocated + logged in. updateChatTabs lays them out + states them.
- (void)buildChatTabs {
    self.chatBtnImg    = [self loadAsset:@"chatbtn"];
    self.chatBtnSelImg = [self loadAsset:@"chatbtn-selected"];
    self.chatBtnNewImg = [self loadAsset:@"chatbtn-new"];
    self.chatTabButtons = [NSMutableArray arrayWithCapacity:kChatTabCount];
    for (int i = 0; i < kChatTabCount; i++) {
        UIButton *b = [UIButton buttonWithType:UIButtonTypeCustom];
        b.tag = i;
        b.hidden = YES;
        b.userInteractionEnabled = NO;   // display-only fan chip; the pill's gesture drives selection
        b.adjustsImageWhenHighlighted = NO;
        b.titleLabel.textAlignment = NSTextAlignmentCenter;
        b.titleLabel.numberOfLines = 2;   // channel name + filter state, like the official app
        b.titleLabel.lineBreakMode = NSLineBreakByClipping;
        b.layer.magnificationFilter = kCAFilterLinear;
        [self.screen addSubview:b];
        [self.chatTabButtons addObject:b];
    }

    // The collapsed pill: shows the open channel, and owns the whole gesture.
    UIButton *pill = [UIButton buttonWithType:UIButtonTypeCustom];
    pill.hidden = YES;
    pill.adjustsImageWhenHighlighted = NO;
    pill.titleLabel.textAlignment = NSTextAlignmentCenter;
    pill.titleLabel.numberOfLines = 2;
    pill.titleLabel.lineBreakMode = NSLineBreakByClipping;
    pill.layer.magnificationFilter = kCAFilterLinear;
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc]
        initWithTarget:self action:@selector(onChatPillTap:)];
    UILongPressGestureRecognizer *lp = [[UILongPressGestureRecognizer alloc]
        initWithTarget:self action:@selector(onChatPillDrag:)];
    lp.minimumPressDuration = kChatHoldDelay;
    [tap requireGestureRecognizerToFail:lp];   // a hold fans out; only a quick tap (lp failed) toggles collapse
    [pill addGestureRecognizer:lp];
    [pill addGestureRecognizer:tap];
    [self.screen addSubview:pill];
    self.chatPill = pill;
    self.chatPillLP = lp;

    // The "▾ pull down to filter" reveal that slides under the hovered chip.
    UILabel *hint = [[UILabel alloc] initWithFrame:CGRectZero];
    hint.text = @"▾";
    hint.textAlignment = NSTextAlignmentCenter;
    hint.textColor = [UIColor colorWithRed:1.0 green:0.92 blue:0.35 alpha:1.0];
    hint.hidden = YES;
    hint.userInteractionEnabled = NO;
    [self.screen addSubview:hint];
    self.chatFilterHint = hint;

    self.chatTabsBuilt = YES;
    self.optimisticChatTab = -1;
    self.chatActiveTab = -1;
    self.chatHoverChip = -1;
    self.chatFilterChip = -1;
    [self appendLine:[NSString stringWithFormat:@"[chat] pill + chips built (sprites %@/%@/%@)",
                      self.chatBtnImg ? @"ok" : @"X", self.chatBtnSelImg ? @"ok" : @"X",
                      self.chatBtnNewImg ? @"ok" : @"X"]];
}

// Convert a packed 0xRRGGBB int (the engine widget text colour) to a UIColor; nil for < 0 (unknown).
static UIColor *rgbColor(int rgb) {
    if (rgb < 0) return nil;
    return [UIColor colorWithRed:((rgb >> 16) & 0xFF) / 255.0
                           green:((rgb >> 8) & 0xFF) / 255.0
                            blue:(rgb & 0xFF) / 255.0
                           alpha:1.0];
}

// The native chat tab row height in ENGINE-CANVAS px (screen-scale independent), so it converts cleanly to the
// engine's CHATAREA push-down. Tall enough for the two text lines (name + filter state).
static int chatRowCanvasH(void) {
    return kChatTabRowCanvasH;
}

// Paint one channel button (pill or fan chip) sprite-backed with a two-line RuneScape-font label (name on top,
// filter state below in the engine's state colour). selected -> the gold SELECTED sprite; new -> the unread
// sprite; else the idle sprite. tab = the snapshot entry for channel i (may be nil while collapsed).
- (void)paintChatButton:(UIButton *)b channel:(int)i tab:(NSDictionary *)t selected:(BOOL)selected
               nameFont:(UIFont *)nameFont stateFont:(UIFont *)stateFont {
    BOOL hasNew = [t[@"new"] boolValue];
    UIImage *bg = self.chatBtnImg;
    if (selected) bg = self.chatBtnSelImg;
    else if (hasNew) bg = self.chatBtnNewImg;
    [b setBackgroundImage:bg forState:UIControlStateNormal];

    NSString *name = (i >= 0 && i < kChatTabCount) ? kChatTabNames[i] : @"";
    NSString *filter = [t[@"filter"] isKindOfClass:[NSString class]] ? t[@"filter"] : @"";
    UIColor *nameColor = rgbColor(t ? [t[@"labelColor"] intValue] : -1)
        ?: [UIColor colorWithRed:1.0 green:0.97 blue:0.85 alpha:1.0];
    NSMutableParagraphStyle *ps = [NSMutableParagraphStyle new];
    ps.alignment = NSTextAlignmentCenter;
    ps.lineBreakMode = NSLineBreakByClipping;
    NSMutableAttributedString *title = [[NSMutableAttributedString alloc] initWithString:name
        attributes:@{NSFontAttributeName: nameFont, NSForegroundColorAttributeName: nameColor,
                     NSParagraphStyleAttributeName: ps}];
    if (filter.length) {
        UIColor *fc = rgbColor([t[@"filterColor"] intValue]) ?: [UIColor colorWithRed:0.0 green:0.9 blue:0.0 alpha:1.0];
        [title appendAttributedString:[[NSAttributedString alloc]
            initWithString:[@"\n" stringByAppendingString:filter]
            attributes:@{NSFontAttributeName: stateFont, NSForegroundColorAttributeName: fc,
                         NSParagraphStyleAttributeName: ps}]];
    }
    [b setAttributedTitle:title forState:UIControlStateNormal];
}

// Lay out + state the collapsed pill and the (resting) fan chips from the chat snapshot each tick. Mirrors
// updateChrome: hides everything (revealing the engine's own chat) when the native chrome is toggled off or the
// layout is unsupported (not Classic/164). The pill sits at the chat origin; the fan row spans the chat width
// (kChatW) and is the drag hit-test target. Frame/visibility mutation is skipped mid fan-animation so the
// animation owns the views; content (sprite + label) is always refreshed.
- (void)updateChatTabs {
    if (!self.chatTabsBuilt) return;
    if (self.mapOpen) { [self hideChatTabs]; return; }   // fullscreen world map: the pill would cover it
    if (!self.chromeVisible || self.renderW <= 0) { [self hideChatTabs]; return; }
    NSString *snap = [JVMLauncher chatSnapshot];
    if (snap.length == 0) { [self hideChatTabs]; return; }
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                      options:0 error:nil];
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"loggedIn"] boolValue]) { [self hideChatTabs]; return; }
    // LAYOUT GUARD (same as updateChrome): the native HUD only works in the Classic resizable layout (164).
    if ([d[@"topLevelId"] intValue] != 164) { [self hideChatTabs]; return; }
    NSArray *tabs = [d[@"tabs"] isKindOfClass:[NSArray class]] ? d[@"tabs"] : nil;
    if (tabs.count == 0) { [self hideChatTabs]; return; }
    self.chatScrollable = !self.chatCollapsed;   // chat open + supported -> finger-scroll (off while collapsed)

    CGFloat W = self.view.bounds.size.width;
    CGFloat s = W / (CGFloat)self.renderW;           // canvas px -> screen pt (uniform: render aspect == screen)
    CGFloat rowX = [d[@"chatX"] intValue] * s;
    CGFloat rowY = [d[@"chatY"] intValue] * s;
    CGFloat rowW = kChatW * s;
    CGFloat rowH = kChatTabRowCanvasH * s;
    CGFloat tabW = rowW / kChatTabCount;
    [JVMLauncher setEngineChatTabRowHeight:kChatTabRowCanvasH];   // push messages below the (constant) tab row
    [JVMLauncher setEngineChatAreaWidth:0];                        // chat keeps its natural 519 width

    // Stash the fan geometry (chips are the original tab size + kChatFanPopPt) + the collapsed pill frame. The
    // pill is ONE tab wide so it matches a single original tab (the active channel) instead of an oversized bar.
    self.chatFanRowX = rowX;
    self.chatFanRowY = rowY;
    self.chatFanTabW = tabW;
    self.chatFanChipH = rowH + kChatFanPopPt;
    self.chatPillFrame = CGRectMake(floor(rowX), floor(rowY), ceil(tabW), ceil(rowH));

    CGFloat nameSize = floor(rowH * 0.40);
    CGFloat stateSize = floor(rowH * 0.34);
    UIFont *nameFont = [UIFont fontWithName:@"RuneScape" size:nameSize] ?: [UIFont boldSystemFontOfSize:nameSize];
    UIFont *stateFont = [UIFont fontWithName:@"RuneScape" size:stateSize] ?: [UIFont systemFontOfSize:stateSize];

    // Which channel the snapshot reports selected, reconciled with the optimistic highlight (the engine tab sprite
    // lags a redraw). Persist the last known selection so the pill still names the open channel while collapsed
    // (collapse can invalidate the snapshot's tab bounds/sprite).
    int snapSel = -1;
    for (int j = 0; j < (int)tabs.count && j < kChatTabCount; j++) {
        NSDictionary *tj = [tabs[j] isKindOfClass:[NSDictionary class]] ? tabs[j] : nil;
        if ([tj[@"selected"] boolValue]) { snapSel = j; break; }
    }
    int effSel = snapSel;
    if (self.optimisticChatTab >= 0) {
        if (snapSel == self.optimisticChatTab) self.optimisticChatTab = -1;
        else effSel = (int)self.optimisticChatTab;
    }
    if (effSel >= 0) self.chatActiveTab = effSel;
    int pillSel = (effSel >= 0) ? effSel : (int)self.chatActiveTab;
    if (pillSel < 0) pillSel = 0;   // never seen a selection yet -> default to All

    NSDictionary *pillTab = (pillSel < (int)tabs.count && [tabs[pillSel] isKindOfClass:[NSDictionary class]])
        ? tabs[pillSel] : nil;
    [self paintChatButton:self.chatPill channel:pillSel tab:pillTab selected:YES nameFont:nameFont stateFont:stateFont];

    for (int i = 0; i < kChatTabCount; i++) {
        UIButton *b = self.chatTabButtons[i];
        NSDictionary *t = (i < (int)tabs.count && [tabs[i] isKindOfClass:[NSDictionary class]]) ? tabs[i] : nil;
        [self paintChatButton:b channel:i tab:t selected:(i == effSel)
                     nameFont:nameFont stateFont:stateFont];
    }

    if (self.chatFanAnimating) return;   // the fan animation owns frames/visibility this beat
    if (self.chatFanned) {
        // The live gesture owns chip frames + hover transforms; don't reframe them here (setting .frame on a
        // transformed view fights the hover scale). Just keep the pill INVISIBLE-but-present — hiding it would
        // cancel the in-flight long-press it owns. alpha 0 keeps the recognizer alive.
        self.chatPill.alpha = 0.0;
    } else {
        self.chatPill.frame = self.chatPillFrame;
        self.chatPill.alpha = 1.0;
        self.chatPill.hidden = NO;
        for (UIButton *b in self.chatTabButtons) b.hidden = YES;
        self.chatFilterHint.hidden = YES;
    }
}

- (void)hideChatTabs {
    self.chatScrollable = NO;   // chat not open -> drags fall through to the camera
    self.chatPill.hidden = YES;
    self.chatFilterHint.hidden = YES;
    for (UIButton *b in self.chatTabButtons) b.hidden = YES;
    self.chatFanned = NO;
    self.chatFanAnimating = NO;
    self.chatHoverChip = -1;
    self.chatFilterChip = -1;
    // Restore the engine's own (small) chat tab row + native width whenever the native tabs aren't shown.
    [JVMLauncher setEngineChatTabRowHeight:0];
    [JVMLauncher setEngineChatAreaWidth:0];
}

// The resting (fanned) frame of chip i, in self.screen coords, from the stashed fan geometry.
- (CGRect)chatChipFrameForIndex:(int)i {
    return CGRectMake(floor(self.chatFanRowX + i * self.chatFanTabW), floor(self.chatFanRowY),
                      ceil(self.chatFanTabW), ceil(self.chatFanChipH));
}

// Which fan column the finger is over (0..6), or -1 if clearly off the fan. Accepts a generous vertical band
// BELOW the chips (the pull-down zone) so a finger dragging down to filter still maps to its column.
- (NSInteger)chatChipColumnAtPoint:(CGPoint)p {
    CGFloat rowW = self.chatFanTabW * kChatTabCount;
    if (p.x < self.chatFanRowX || p.x >= self.chatFanRowX + rowW) return -1;
    CGFloat top = self.chatFanRowY - 44.0;
    CGFloat bot = self.chatFanRowY + self.chatFanChipH + kChatPullDownPt + 60.0;
    if (p.y < top || p.y > bot) return -1;
    NSInteger k = (NSInteger)floor((p.x - self.chatFanRowX) / self.chatFanTabW);
    return (k < 0) ? 0 : (k >= kChatTabCount ? kChatTabCount - 1 : k);
}

// Spring the chips out from the pill into the fanned row (or back). Frame/visibility is the animation's during
// the run (updateChatTabs defers via chatFanAnimating).
- (void)fanOutChat {
    if (self.chatFanned) return;
    self.chatFanned = YES;
    self.chatFanAnimating = YES;
    self.chatFilterChip = -1;
    self.chatHoverChip = -1;
    for (int i = 0; i < kChatTabCount; i++) {
        UIButton *c = self.chatTabButtons[i];
        c.transform = CGAffineTransformIdentity;
        c.layer.borderWidth = 0;
        c.frame = self.chatPillFrame;
        c.alpha = 0.0;
        c.hidden = NO;
    }
    [UIView animateWithDuration:kChatFanAnim delay:0 usingSpringWithDamping:0.82 initialSpringVelocity:0.4
                        options:UIViewAnimationOptionCurveEaseOut animations:^{
        for (int i = 0; i < kChatTabCount; i++) {
            self.chatTabButtons[i].frame = [self chatChipFrameForIndex:i];
            self.chatTabButtons[i].alpha = 1.0;
        }
        self.chatPill.alpha = 0.0;
    } completion:^(BOOL finished) {
        self.chatFanAnimating = NO;
        // Leave the pill hidden=NO at alpha 0 — hiding it would cancel the long-press it owns.
    }];
}

- (void)fanInChat {
    if (!self.chatFanned) return;
    self.chatFanAnimating = YES;
    self.chatFilterHint.hidden = YES;
    self.chatPill.hidden = NO;
    self.chatPill.alpha = 0.0;
    // Clear any hover scale/border first — animating .frame on a transformed view is undefined.
    for (UIButton *c in self.chatTabButtons) { c.transform = CGAffineTransformIdentity; c.layer.borderWidth = 0; }
    [UIView animateWithDuration:kChatFanAnim delay:0 options:UIViewAnimationOptionCurveEaseIn animations:^{
        for (int i = 0; i < kChatTabCount; i++) {
            self.chatTabButtons[i].frame = self.chatPillFrame;
            self.chatTabButtons[i].alpha = 0.0;
        }
        self.chatPill.alpha = 1.0;
    } completion:^(BOOL finished) {
        for (UIButton *c in self.chatTabButtons) {
            c.hidden = YES;
            c.transform = CGAffineTransformIdentity;
            c.layer.borderWidth = 0;
        }
        self.chatFanned = NO;
        self.chatFanAnimating = NO;
        self.chatHoverChip = -1;
        self.chatFilterChip = -1;
    }];
}

// Highlight the chip under the finger (scale + white border) and clear the others. -1 clears all. NOT named
// setChatHoverChip: — that's the chatHoverChip property setter, and assigning self.chatHoverChip inside it would
// recurse into itself (stack overflow). This is a distinct method that updates the property + the visuals.
- (void)applyChatHover:(NSInteger)k {
    if (k == self.chatHoverChip) return;
    self.chatHoverChip = k;
    for (int i = 0; i < kChatTabCount; i++) {
        UIButton *c = self.chatTabButtons[i];
        BOOL on = (i == k);
        c.transform = on ? CGAffineTransformMakeScale(1.06, 1.06) : CGAffineTransformIdentity;
        c.layer.borderColor = [UIColor colorWithWhite:1.0 alpha:0.9].CGColor;
        c.layer.borderWidth = on ? 2.0 : 0.0;
        c.layer.cornerRadius = on ? 6.0 : 0.0;
        if (on) [c.superview bringSubviewToFront:c];
    }
}

// Switch the open chat to channel k by replaying its op-script (works on the hidden engine tab); reopen the box
// if it was collapsed, and optimistically show it selected this frame (the engine sprite lags a redraw).
- (void)chatSwitchToChip:(int)k {
    if (k < 0 || k >= kChatTabCount) return;
    if (self.chatCollapsed) {
        self.chatCollapsed = NO;
        [JVMLauncher setEngineChatCollapsed:NO];
    }
    [JVMLauncher switchEngineChatTab:k];
    self.optimisticChatTab = k;
    self.optimisticChatAt = CACurrentMediaTime();
    [self updateChatTabs];
    [self appendLine:[NSString stringWithFormat:@"[chat] switch -> %d (%@)", k, kChatTabNames[k]]];
}

// Position + reveal the ▾ filter hint under the hovered filterable chip as the finger dips toward its bottom.
- (void)updateChatFilterHintForColumn:(NSInteger)k point:(CGPoint)p {
    if (k < 0 || k >= kChatTabCount || !kChatTabFilterable[k] || self.chatFilterChip >= 0) {
        self.chatFilterHint.hidden = YES;
        return;
    }
    CGRect chip = [self chatChipFrameForIndex:(int)k];
    CGFloat mid = CGRectGetMidY(chip);
    CGFloat reveal = (p.y - (mid + kChatFilterHintPt)) / kChatPullDownPt;   // 0 at mid+slop, ~1 at the trigger
    reveal = fmin(fmax(reveal, 0.0), 1.0);
    if (reveal <= 0.01) { self.chatFilterHint.hidden = YES; return; }
    CGFloat hh = 18.0;
    self.chatFilterHint.font = [UIFont systemFontOfSize:16 weight:UIFontWeightBold];
    self.chatFilterHint.frame = CGRectMake(CGRectGetMinX(chip), CGRectGetMaxY(chip) + 2.0,
                                           CGRectGetWidth(chip), hh);
    self.chatFilterHint.alpha = reveal;
    self.chatFilterHint.hidden = NO;
    [self.screen bringSubviewToFront:self.chatFilterHint];
}

// Resolve the live engine chat-button centre (canvas px) for native button tag i, from the chat snapshot bounds.
// Returns NO if the engine button isn't built/visible this frame.
- (BOOL)engineChatButtonCenterForTag:(NSInteger)i x:(int *)cx y:(int *)cy {
    NSString *snap = [JVMLauncher chatSnapshot];
    if (snap.length == 0) return NO;
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                      options:0 error:nil];
    NSArray *tabs = [d isKindOfClass:[NSDictionary class]] ? d[@"tabs"] : nil;
    if (![tabs isKindOfClass:[NSArray class]] || i < 0 || i >= (NSInteger)tabs.count) return NO;
    NSDictionary *t = [tabs[i] isKindOfClass:[NSDictionary class]] ? tabs[i] : nil;
    if (!t) return NO;
    int bx = [t[@"x"] intValue], by = [t[@"y"] intValue], bw = [t[@"w"] intValue], bh = [t[@"h"] intValue];
    if (bw <= 0 || bh <= 0 || bx < 0 || by < 0) return NO;   // engine button not built / not visible this frame
    *cx = bx + bw / 2; *cy = by + bh / 2;
    return YES;
}

// Quick tap on the pill -> toggle the chat message area open/collapsed (the channel stays selected).
- (void)onChatPillTap:(UITapGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    self.chatCollapsed = !self.chatCollapsed;
    [JVMLauncher setEngineChatCollapsed:self.chatCollapsed];
    [self updateChatTabs];
    [self appendLine:[NSString stringWithFormat:@"[chat] pill tap -> %@", self.chatCollapsed ? @"collapse" : @"expand"]];
}

// The driver: hold the pill to fan the channels out, then drag to pick. Horizontal drag = SELECT (lift switches
// to the chip under the finger); pulling DOWN off a filterable chip's bottom = FILTER (open that chip's engine
// filter menu and hand off to the native big-row overlay). Dragging back UP onto the row cancels the filter.
- (void)onChatPillDrag:(UILongPressGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    CGPoint pScreen = [g locationInView:self.screen];
    switch (g.state) {
        case UIGestureRecognizerStateBegan: {
            self.chatDragStart = pScreen;
            self.chatDragMoved = NO;
            [self fanOutChat];
            if (!self.tapHaptic) self.tapHaptic = [[UIImpactFeedbackGenerator alloc] initWithStyle:UIImpactFeedbackStyleLight];
            [self.tapHaptic impactOccurred];
            [self.tapHaptic prepare];
            [self appendLine:@"[chat] pill hold -> fan out"];
            break;
        }
        case UIGestureRecognizerStateChanged: {
            if (!self.chatDragMoved
                && hypot(pScreen.x - self.chatDragStart.x, pScreen.y - self.chatDragStart.y) > 16.0) {
                self.chatDragMoved = YES;   // a real drag began (tracked even mid fan-out) -> a release commits a switch
            }
            if (self.chatFanAnimating) break;   // let the fan settle before tracking hover
            if (self.chatFilterChip >= 0) {
                // FILTER mode: dragging back UP onto the chip row cancels the filter and returns to SELECT.
                CGRect chip = [self chatChipFrameForIndex:(int)self.chatFilterChip];
                if (pScreen.y < CGRectGetMaxY(chip)) {
                    [self cancelMenu];
                    self.chatFilterChip = -1;
                    [self appendLine:@"[chat] pull-up -> filter cancelled, back to select"];
                } else {
                    [self updateMenuHighlightForPoint:pScreen];
                }
                break;
            }
            NSInteger k = [self chatChipColumnAtPoint:pScreen];
            [self applyChatHover:k];
            [self updateChatFilterHintForColumn:k point:pScreen];
            // Pull-down escalation: finger crossed below a filterable chip's bottom by the threshold.
            if (k >= 0 && kChatTabFilterable[k]) {
                CGRect chip = [self chatChipFrameForIndex:(int)k];
                if (pScreen.y > CGRectGetMaxY(chip) + kChatPullDownPt) {
                    // A collapsed chat is SHOVED OFF-SCREEN (not hidden), so the engine filter button is at
                    // canvas y≈3000 — a right-click there opens nothing (the "armed but invisible" filter).
                    // Expand first; the engine restores the widget next tick and the snapshot follows, then a
                    // later .changed event (finger still held below the chip) passes the on-canvas gate below.
                    if (self.chatCollapsed) {
                        self.chatCollapsed = NO;
                        [JVMLauncher setEngineChatCollapsed:NO];
                        [self appendLine:@"[chat] pull-down on collapsed chat -> expand first"];
                    }
                    int cx = 0, cy = 0;
                    if ([self engineChatButtonCenterForTag:k x:&cx y:&cy]
                        && cy < self.renderH) {   // on-canvas = the expand has landed in the snapshot
                        self.chatFilterChip = k;
                        self.chatFilterHint.hidden = YES;
                        [self openEngineMenuAtGameX:cx gameY:cy anchor:pScreen];   // screen coords, like onLongPress
                        if (self.menuHaptic) { [self.menuHaptic selectionChanged]; [self.menuHaptic prepare]; }
                        [self appendLine:[NSString stringWithFormat:@"[chat] pull-down %ld (%@) -> filter menu @%d,%d collapsed=%d",
                                          (long)k, kChatTabNames[k], cx, cy, (int)self.chatCollapsed]];
                    }
                }
            }
            break;
        }
        case UIGestureRecognizerStateEnded: {
            if (self.chatFilterChip >= 0) {
                [self commitMenuSelection];
                self.chatFilterChip = -1;
            } else {
                // Switch to the chip that was HIGHLIGHTED under the finger (what you see is what you get).
                // Re-hit-testing the exact release point was fragile: a finger drifts as it lifts, and the
                // old "release must be above the chip bottom" bound rejected valid releases (worse once the
                // chips matched the shorter original tab height). A switch needs a real drag (chatDragMoved),
                // so a hold-and-release to peek doesn't change channels.
                NSInteger k = self.chatHoverChip;
                if (k < 0) k = [self chatChipColumnAtPoint:pScreen];   // fast flick: hover not tracked yet
                if (self.chatDragMoved && k >= 0 && k != self.chatActiveTab) {
                    [self chatSwitchToChip:(int)k];
                }
            }
            [self fanInChat];
            break;
        }
        case UIGestureRecognizerStateCancelled:
        case UIGestureRecognizerStateFailed:
            if (self.chatFilterChip >= 0) { [self cancelMenu]; self.chatFilterChip = -1; }
            [self fanInChat];
            break;
        default:
            break;
    }
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
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"loggedIn"] boolValue] || self.mapOpen) {
        // (mapOpen: the fullscreen world map owns the screen — the native grid would cover it + steal taps)
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;
        return;
    }
    // LAYOUT GUARD: the native HUD overlay only works in the Classic resizable layout (PRE_EOC = toplevel 164). If
    // the in-game OSRS "resizable layout" setting is switched to Modern (OSRS_STRETCH = 165) or anything else, our
    // tab grid targets widgets that don't exist, so the tab buttons open nothing. Rather than soft-lock the user,
    // hide the native overlay and un-hide the engine's OWN tab stones so the real game HUD renders + is tappable
    // (the user can then switch the layout back). Re-engages automatically once back in toplevel 164.
    int topLevelId = [d[@"topLevelId"] intValue];
    if (topLevelId != 164) {
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;
        [JVMLauncher setEngineStonesHidden:NO];
        if (self.framesShown % 120 == 0) {
            [self appendLine:[NSString stringWithFormat:@"[chrome] unsupported layout topLevel=%d (not 164/Classic) -> native HUD off, engine HUD shown", topLevelId]];
        }
        return;
    }
    [JVMLauncher setEngineStonesHidden:YES];   // supported Classic layout: the native grid replaces the engine stones
    int active = [d[@"activeTab"] intValue];

    // All 14 tabs in ONE 2-column grid at the BOTTOM-RIGHT — the official OSRS-mobile arrangement. The engine
    // stones are HIDDEN (setEngineStonesHidden), so these native buttons ARE the tabs; a tap replays the stone's
    // op script to open the tab (onTabButton) — no cover patch / gray box. TAB_GRID_ORDER maps grid slot ->
    // engine tab index, row-major (slot 0 = top-left, 1 = top-right, …). Tweak it to match the official order.
    // Cells auto-fit the bottom ~56% of the safe height so the grid never collides with the minimap (top-right).
    static const int TAB_GRID_ORDER[14] = { 3, 1,  4, 2,  5, 13,  6, 7,  0, 12,  8, 9,  10, 11 };
    UIEdgeInsets ins = self.view.safeAreaInsets;
    CGFloat W = self.view.bounds.size.width, H = self.view.bounds.size.height;
    const CGFloat gap = 0.0, margin = 6.0;   // NO gap — the stones tile flush, like the official app
    CGFloat availH = (H - ins.top - ins.bottom) * 0.56;
    CGFloat cell = MIN(46.0, availH / 7.0);
    CGFloat gridW = 2 * cell + gap, gridH = 7 * cell + 6 * gap;
    CGFloat gx0 = W - ins.right - margin - gridW;
    CGFloat gy0 = H - ins.bottom - margin - gridH;
    for (int slot = 0; slot < 14; slot++) {
        int i = TAB_GRID_ORDER[slot];
        UIButton *btn = self.tabButtons[i];
        int col = slot % 2, row = slot / 2;
        btn.frame = CGRectMake(gx0 + col * (cell + gap), gy0 + row * (cell + gap), cell, cell);
        BOOL isActive = (i == active);
        // Authentic composited tab image (bundled OSRS stone + icon). Active = red stone, inactive = grey.
        NSArray<UIImage *> *imgs = isActive ? self.tabImgActive : self.tabImgInactive;
        UIImage *img = (i < (int)imgs.count && [imgs[i] isKindOfClass:[UIImage class]]) ? imgs[i] : nil;
        [btn setImage:img forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor clearColor];
        btn.layer.borderWidth = 0.0;
        btn.alpha = 1.0;   // active vs inactive carried by the red/grey stone itself
        btn.hidden = NO;
    }

    // Relocate the engine side panel (the open inventory/skills/etc. "menu") to sit flush LEFT of the tab grid,
    // top-aligned — the official OSRS-mobile arrangement. Convert the grid's screen-point edge to canvas px
    // (canvasPx = pt * renderW/W); the side panel width comes from the chrome snapshot (fixed engine px).
    if (self.renderW > 0 && W > 0) {
        CGFloat k = (CGFloat)self.renderW / W;
        int sideW = 204;   // fallback until the panel is open and reports its real width
        NSArray *sc = [d[@"sideContainer"] isKindOfClass:[NSArray class]] ? d[@"sideContainer"] : nil;
        if (sc.count >= 4 && [sc[2] intValue] > 0) sideW = [sc[2] intValue];
        int sideX = (int)lround(gx0 * k) - sideW;   // right edge flush against the grid's left edge
        // The stones are hidden, so there is no bottom row to avoid — align the panel's top with the grid top.
        int sideY = (int)lround(gy0 * k);
        if (sideX < 0) sideX = 0;
        if (sideY < 0) sideY = 0;
        [JVMLauncher relocateEngineSidePanelX:sideX y:sideY];
    }
}

// A native tab tap -> proxy a left click on the live desktop stone centre (the device-proven switch primitive,
// JavaBridge.onClick). The button consumed the tap, so it never leaks to the game gestures beneath.
- (void)onTabButton:(UIButton *)b {
    if (![JVMLauncher bridgeReady]) return;
    NSInteger i = b.tag;
    if (i < 0 || i >= 14) return;
    // Open the tab by replaying the engine stone's op script (works while the stones are hidden) — no proxy-click
    // on a live stone, so no cover patch / gray box is needed. The button tag is the engine tab index.
    [JVMLauncher openEngineTab:(int)i];
    [self appendLine:[NSString stringWithFormat:@"[chrome] tab %ld -> openTab", (long)i]];
}

- (void)onToggleChrome {
    self.chromeVisible = !self.chromeVisible;
    if (!self.chromeVisible) {
        for (UIButton *btn in self.tabButtons) btn.hidden = YES;
        for (UIView *cv in self.stoneCovers) cv.hidden = YES;   // reveal the real stones in the fallback view
        self.tabRailBg.hidden = YES;
        [self hideChatTabs];   // also restores the engine's own chat row (setEngineChatTabRowHeight:0)
    }
    // (When toggled back ON, updateChatTabs re-applies the enlarged row height each tick.)
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

// GPU Route C — long-press the launch button to route into the L1 kill-test (single fire on press-began).
- (void)onLaunchRouteCL1Gesture:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    if (self.launched) return;
    self.pendingRouteCL1 = YES;
    [self onLaunch];
}

- (void)onLaunchRouteCGpuGesture:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    if (self.launched) return;
    // GPU Route C black-scene bisection: two-finger long-press = GPU in FIXED mode (NO headless.resize, stays
    // 765x503). The CPU path renders the scene fine at the resizable 1304x600 + viewport=0x0 state, while GPU
    // resizable never dispatches the scene render (drawScene=0). Fixed mode isolates whether the resizable
    // viewport/scene-dispatch path is the GPU-broken one. The ▶ Run GPU button stays GPU + resize.
    self.pendingRouteCGpu = YES;
    self.pendingRouteCGpuNoResize = YES;
    [self onLaunch];
}

// "117HD readback" button: 117HD resizable on the proven glReadPixels READBACK present (no CAEAGLLayer) — the
// fallback if the default direct-present path scans out black or regresses on a future change. Same resizable
// canvas/sharpness/chrome as the green button; just the slower present (~15fps vs ~18+).
- (void)onLaunchZoneReadback {
    if (self.launched) return;
    self.pendingRouteCZone = YES;
    [self onLaunch];
}

// THREE-FINGER long-press: boot 117HD/Zone in the FIXED 765x503 fallback (no headless.resize) — the proven
// readback path from Zone bring-up. The one-tap buttons cover resizable readback (green) + resizable direct
// present (⚡); this gesture stays as the fixed-mode escape hatch.
- (void)onLaunchRouteCZoneGesture:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    if (self.launched) return;
    self.pendingRouteCZone = YES;
    self.pendingRouteCGpuNoResize = YES;
    [self onLaunch];
}

// GPU Route C — dedicated one-tap button (same effect as the two-finger long-press, but unambiguous).
- (void)onLaunchGpu {
    if (self.launched) return;
    self.pendingRouteCGpu = YES;
    [self onLaunch];
}

// The DEFAULT 117HD boot (one-tap green button): RESIZABLE + DIRECT present. Resizable = the engine canvas resizes
// to renderW×renderH (~1302x600) after login (sharp + the native Jagex-mobile chrome activates, all gated on
// client.isResized()); direct present = RLHD renders straight into a CAEAGLLayer drawable scanned out by
// presentRenderbuffer (no per-frame glReadPixels readback — device-confirmed faster). The CAEAGLLayer starts hidden
// so the CPU login/loading blit shows during boot and is revealed only once the world is warmed up (no black boot,
// no sky flash). Fallbacks: the "117HD readback" button (resizable readback) and the 3-finger gesture (fixed 765).
- (void)onLaunchZone {
    if (self.launched) return;
    self.pendingRouteCZone = YES;
    self.pendingRouteCZoneDirect = YES;
    [self onLaunch];
}

- (void)onLaunch {
    if (self.launched) return;
    // If the login web view ran this session, WebKit is resident and the JVM would crash at code-cache
    // init (the JIT brk can't be serviced). Require a fresh process first — restart instead of launching.
    if (self.webKitUsedThisSession) { [self promptRestartToPlay]; return; }
    self.launched = YES;
    self.launchButton.enabled = NO;
    self.gpuButton.enabled = NO;
    self.zoneButton.enabled = NO;
    self.zoneFastButton.enabled = NO;
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
    // ProMotion: drive the display at up to 120Hz so the GPU direct-present (synced to vsync) isn't capped at 60.
    // The GPU renders in ~3ms, so there's headroom for 120. (Needs CADisableMinimumFrameDurationOnPhone in Info.plist.)
    if (@available(iOS 15.0, *)) {
        self.displayLink.preferredFrameRateRange = CAFrameRateRangeMake(60.0f, 120.0f, 120.0f);
    }
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];

    JVMLogSink log = ^(NSString *line) { [self appendLine:line]; };
    [self appendLine:@"--- launching JVM on worker thread ---"];

    JVMLauncher *launcher = [[JVMLauncher alloc] init];
    launcher.routecL1Mode = self.pendingRouteCL1;   // GPU Route C L1 kill-test (set by the long-press gesture)
    launcher.routecGpuMode = self.pendingRouteCGpu;  // GPU Route C full path (two-finger long-press)
    launcher.routecZoneMode = self.pendingRouteCZone; // GPU Zone 117HD path (three-finger long-press)
    if (self.pendingRouteCL1) {
        [self appendLine:@"[shell] *** GPU Route C L1 kill-test — booting RouteCL1Probe (not full RuneLite) ***"];
    } else if (self.pendingRouteCZone) {
        [self appendLine:@"[shell] *** GPU Zone (117HD) — booting full RuneLite with RLHD on the GL bridge (device GPU) ***"];
    } else if (self.pendingRouteCGpu) {
        [self appendLine:@"[shell] *** GPU Route C — booting full RuneLite with the GL bridge (device GPU) ***"];
    }
    // Edge-to-edge (A1): render aspect MATCHES THE FULL SCREEN (the game view fills the whole bounds), so the
    // blit fills it with no letterbox. The HUD no longer clips at the edges because it is relocated INTO the
    // safe area (updateHudLayout), not kept at the canvas corners.
    CGRect b = self.view.bounds;
    CGFloat sw = b.size.width;
    CGFloat sh = b.size.height;
    CGFloat aspect = (sh > 0 ? sw / sh : 2.0);
    // Render height is the sharpness<->FPS<->UI-SIZE dial: the engine SOFTWARE-renders at renderW×renderH every
    // frame (no GPU on iOS), so a LOWER renderH = bigger UI on screen. The minimap can't be enlarged on its own
    // (the engine pins its orbs + breaks the terrain raster if the globe is repositioned — see HudVeneerBridge),
    // so we scale the WHOLE HUD up via renderH to make the minimap bigger. 600 was the prior "official-sized"
    // value; 540 enlarges the HUD ~11% (bigger minimap/chat/orbs). Watch the right column (minimap top + native
    // tab grid bottom) for overlap as this drops (480 collided historically) — tune here.
    int renderH = 540;
    int renderW = (int)lround(renderH * aspect);
    self.renderW = renderW;
    self.renderH = renderH;
    if (self.pendingRouteCGpuNoResize) {
        // GPU Route C bisection: leave the canvas at the classic fixed 765x503 (no resizable switch) so we can
        // tell whether the scene render is suppressed by GPU mode itself or only by the resizable-layout path.
        launcher.resizeSpec = nil;
        [self appendLine:@"[shell] *** GPU Route C — FIXED mode (no headless.resize, 765x503 bisection) ***"];
    } else {
        launcher.resizeSpec = [NSString stringWithFormat:@"%dx%d", renderW, renderH];
        [self appendLine:[NSString stringWithFormat:@"[shell] full-screen %.0fx%.0f aspect %.3f -> render %@",
                          sw, sh, aspect, launcher.resizeSpec]];
    }

#if !defined(RC_BACKEND_ANGLE)
    // GPU Route C DIRECT PRESENT (EAGL): render straight into a CAEAGLLayer drawable (no glReadPixels readback — the
    // measured ~16ms/frame bottleneck). A sublayer of the game view, inserted below the chrome subviews, so the
    // existing gestures + native HUD veneers keep working on top. Only for the normal resizable GPU path (the
    // no-resize bisection stays on the readback path). Must be created + sized on main BEFORE the JVM thread starts
    // (the bridge reads this layer from the JVM thread in awtCreateGLContext via renderbufferStorage:fromDrawable:).
    // Fires for the Zone (117HD) direct-present path ONLY. Base GPU intentionally uses the READBACK present (the
    // #else path below) so it publishes int[] frames like the CPU rasterizer — that keeps the GPU plugin
    // toggleable to/from the software renderer at runtime without swapping the present layer. The bridge present
    // path is mode-agnostic (keys off whether a present layer was set).
    if (self.pendingRouteCZoneDirect && !self.pendingRouteCGpuNoResize) {
        [self.view layoutIfNeeded];
        CAEAGLLayer *gl = [CAEAGLLayer layer];
        gl.frame = self.screen.bounds;
        gl.opaque = YES;
        gl.contentsScale = (sh > 0 ? (CGFloat)renderH / sh : 1.0);   // drawable = renderW x renderH (== engine canvas)
        gl.drawableProperties = @{ kEAGLDrawablePropertyRetainedBacking : @NO,
                                   kEAGLDrawablePropertyColorFormat : kEAGLColorFormatRGBA8 };
        // Start HIDDEN + lazily reveal: the CAEAGLLayer is a sublayer of the UIImageView so it draws ABOVE the CPU
        // blit; keeping it hidden lets the CPU login/loading screen show during the ~10s boot, and the present fires
        // into the hidden layer meanwhile. onDisplayTick reveals it (gl.hidden=NO + stop the CPU blit) once the world
        // has warmed up (geometry uploaded, JVMLauncher.sceneWarmedUp) so the first visible frame is the world, not the
        // boot sky. (Armed, not gpuPresent=YES — the switch flips gpuPresent.)
        gl.hidden = YES;
        [self.screen.layer insertSublayer:gl atIndex:0];
        self.glLayer = gl;
        self.gpuPresentArmed = YES;
        self.screen.backgroundColor = [UIColor blackColor];
        [RouteCBridge setPresentLayer:gl];
        [self appendLine:[NSString stringWithFormat:@"[shell] GPU direct-present ARMED (hidden): CAEAGLLayer %.0fx%.0f @%.3f -> drawable %dx%d (no readback)",
                          gl.bounds.size.width, gl.bounds.size.height, gl.contentsScale, renderW, renderH]];
    }
#elif defined(RC_ANGLE_WINDOW_PRESENT)
    // Phase A1 (MetalANGLE) WINDOW-SURFACE DIRECT PRESENT (60fps, present=0.07ms). The GPU renders the scene with
    // ZERO GL errors and eglSwapBuffers presents at 62fps, but on device the CAMetalLayer did not scan out to the
    // display (black screen) — the window-surface present is NOT yet visually confirmed (the gpu-present probe
    // sealed on a glReadPixels self-check, not a screenshot). So this path is GATED OFF by default until the
    // present is debugged; the default ANGLE build uses the proven offscreen+readback path below.
    if (self.pendingRouteCGpu && !self.pendingRouteCGpuNoResize) {
        [self.view layoutIfNeeded];
        CAMetalLayer *ml = [CAMetalLayer layer];
        ml.frame = self.screen.bounds;
        ml.opaque = YES;
        ml.framebufferOnly = NO;
        ml.drawableSize = CGSizeMake(renderW, renderH);
        ml.contentsScale = (sh > 0 ? (CGFloat)renderH / sh : 1.0);
        [self.screen.layer insertSublayer:ml atIndex:0];
        self.metalLayer = ml;
        [RouteCBridge setPresentLayer:ml];
        if ([RouteCBridge prepareWindowSurfaceOnMain]) {
            self.gpuPresentArmed = YES;   // onDisplayTick flips to direct present once the first GPU frame presents
            [self appendLine:[NSString stringWithFormat:@"[shell] GPU (MetalANGLE) direct present ARMED: drawable %dx%d", renderW, renderH]];
        } else {
            [RouteCBridge setPresentLayer:nil];
            [ml removeFromSuperlayer];
            self.metalLayer = nil;
            [self appendLine:@"[shell] GPU (MetalANGLE) window surface FAILED -> offscreen FBO + readback present"];
        }
    } else if (self.pendingRouteCGpu) {
        [self appendLine:@"[shell] GPU (MetalANGLE) — offscreen FBO + readback present (no-resize bisection)"];
    }
#else
    // Phase A1 default: OFFSCREEN FBO + glReadPixels readback present — the PROVEN-DISPLAY path. The GPU-rendered
    // scene is published via JavaBridge.gpuPublish -> latestFrame -> onDisplayTick blit (the exact path that
    // displays the CPU frames), so it is guaranteed to scan out. The readback copy caps it at ~50fps; the 60fps
    // CAMetalLayer window present is gated behind RC_ANGLE_WINDOW_PRESENT until its display is confirmed. No present
    // layer is set -> the bridge runs offscreen + readback.
    if (self.pendingRouteCGpu) {
        [self appendLine:@"[shell] GPU (MetalANGLE) — offscreen FBO + readback present (proven-display path)"];
    }
#endif

    // (3) Background staying-power: the silent keepalive audio session (so iOS never suspends the JVM
    // mid-session) + the Picture-in-Picture controller (auto-pops the live game into a floating window on
    // swipe-home). PiP needs the CPU int[] frame feed, so the 117HD direct-present mode (no readback, GL
    // scans out straight to the layer) runs keepalive-only.
    [self setupBackgroundKeepalive];
    if (self.pendingRouteCZoneDirect && !self.pendingRouteCGpuNoResize) {
        [self appendLine:@"[pip] skipped — 117HD direct-present has no CPU frame feed (keepalive still on)"];
    } else {
        [self setupPictureInPicture];
    }

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

// W3 Feature-A (A1+A3+A4): drive the edge-to-edge HUD. Rather than reproduce the HUD natively, we RELOCATE the
// engine's own pixel-perfect widgets into the safe area while the scene fills the screen edge-to-edge: the chatbox
// to the top-left (transparent OSRS-mobile mode), and the minimap+orbs cluster to the top-right. Both stay the
// real, live engine HUD (chat tabs/scrollback/input + minimap click-to-walk + orb clicks all keep working).
// One-shot once we're logged in; HudVeneerBridge re-applies each frame (survives TOPLEVEL_REDRAW).
// Safe-area insets changed (landscape-left<->right rotation, notch-side flip) — clear the one-shot relocate
// guard so the next updateHudLayout tick (~10Hz) re-anchors chat/minimap to the new safe rect instead of
// leaving them pinned to stale insets.
- (void)viewSafeAreaInsetsDidChange {
    [super viewSafeAreaInsetsDidChange];
    self.engineChatRelocated = NO;
}

- (void)updateHudLayout {
    if (self.engineChatRelocated) return;
    NSString *snap = [JVMLauncher hudSnapshot];
    if (snap.length == 0) return;   // bridge / VM not up yet — retry next tick
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                      options:0 error:nil];
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"loggedIn"] boolValue]) return;
    if (self.renderW <= 0) return;
    self.engineChatRelocated = YES;

    // Safe-area insets are in screen points; convert to canvas px (the engine draws at renderW×renderH, blitted
    // full-screen, so canvasPx = points * renderW/screenW). Keep the relocated HUD inside the safe rect.
    UIEdgeInsets ins = self.view.safeAreaInsets;
    CGFloat sw = self.view.bounds.size.width;
    CGFloat k = (sw > 0) ? (CGFloat)self.renderW / sw : 1.0;
    int topC   = (int)lround(ins.top * k);
    int leftC  = (int)lround(ins.left * k);
    int rightC = (int)lround(ins.right * k);
    const int m = 8;                          // canvas-px margin from the safe edge
    const int MM_W = 211;                     // minimap+orbs cluster width (fixed engine px, from the de-risk)
    // Make the minimap BIGGER: enlarge the engine globe + reposition its chrome (HudVeneerBridge.setMinimapScale).
    // The globe is right-anchored, so it grows left+down within the cluster — the right edge stays at MM_W, so the
    // cluster still aligns to the right safe edge; only the orbs/compass spread further left/down (into the gap
    // between the top-left chat and the top-right minimap). 0/100 = natural size.
    const int kMinimapScalePct = 100;   // 100 = natural (the engine pins the orbs, so a bigger globe can't be wrapped cleanly)

    int chatX = leftC + m;
    int chatY = topC + m;
    int mmX = self.renderW - MM_W - rightC - m;
    int mmY = topC + m;

    // transparent:YES = OSRS-mobile translucent chat (text over the scene, no opaque box) — the official look.
    [JVMLauncher relocateEngineChatX:chatX y:chatY transparent:YES];
    // W3 A3: the native veneer recreates the tab row BIGGER (engine push-down applied per-tick in updateChatTabs).
    [JVMLauncher relocateEngineMinimapX:mmX y:mmY];
    [JVMLauncher setEngineMinimapScale:kMinimapScalePct];   // enlarge the globe + reposition its chrome to hug it
    [JVMLauncher setEngineStonesHidden:YES];   // the native 2-col grid replaces them (no cover patch / gray box)
    // Overlay snap-corner safe frame: plugin overlays snapped to a corner (Quest Helper's top-left guidance
    // panel etc.) clear the curved bezel/notch; the engine also drops the top-left corner below the chat.
    int botC = (int)lround(ins.bottom * k);
    [JVMLauncher setEngineOverlaySafeInsetsL:leftC t:topC r:rightC b:botC];
    // The whole enlarged tab row is UI: a stray tap in it (between native buttons) should be a precise click, not
    // a walk. The native buttons own their taps; this rect just keeps any leak from drawing a walk ring.
    self.chatHitCanvas = CGRectMake(chatX, chatY, kChatW, chatRowCanvasH());
    // The whole chat box (tab row + message area), for finger-scroll capture.
    self.chatScrollCanvas = CGRectMake(chatX, chatY, kChatW, chatRowCanvasH() + kChatContentCanvasH);
    [self appendLine:[NSString stringWithFormat:@"[hud] relocate chat->%d,%d minimap->%d,%d (insets L%d T%d R%d k%.2f)",
                      chatX, chatY, mmX, mmY, leftC, topC, rightC, k]];
}

// World map (touch): poll the bridge map snapshot (~10 Hz). Edge-triggered chrome hide/restore: the engine
// map is FULLSCREEN at our canvas, so the native chrome (chat pill, tab grid via the mapOpen gates in
// updateChatTabs/updateChrome, dock + corner buttons here) would cover it and steal taps. The ✕ proxies the
// engine CLOSE (its widget sits under the curved corner). Rects refresh every poll; visibility flips on edges.
static CGRect mapRectFromArr(NSArray *a) {
    if (![a isKindOfClass:[NSArray class]] || a.count != 4) return CGRectZero;
    return CGRectMake([a[0] doubleValue], [a[1] doubleValue], [a[2] doubleValue], [a[3] doubleValue]);
}

- (void)updateWorldMap {
    NSString *snap = [JVMLauncher mapSnapshot];
    BOOL open = NO;
    CGRect closeR = CGRectZero, searchR = CGRectZero, windowR = CGRectZero;
    if (snap.length) {
        NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                          options:0 error:nil];
        if ([d isKindOfClass:[NSDictionary class]] && [d[@"open"] boolValue]) {
            open = YES;
            closeR = mapRectFromArr(d[@"close"]);
            searchR = mapRectFromArr(d[@"search"]);
            windowR = mapRectFromArr(d[@"window"]);
            self.mapKeyCanvas = mapRectFromArr(d[@"key"]);
            self.mapListCanvas = mapRectFromArr(d[@"maplist"]);
        } else {
            self.mapKeyCanvas = CGRectZero;
            self.mapListCanvas = CGRectZero;
        }
    }
    self.mapCloseCanvas = closeR;
    self.mapSearchCanvas = searchR;
    self.mapWindowCanvas = windowR;
    // Official-mobile look: scale the displayed frame so the engine's (size-capped) map window fills the
    // screen width, black bands above/below. Display-only — input is untouched (gesture locations are read
    // in self.screen's BOUNDS space, which UIKit maps back through the transform; mapPoint works on bounds).
    [self applyMapZoom:open windowCanvas:windowR];
    if (open == self.mapOpen) return;
    self.mapOpen = open;
    // Corner controls that overlap the (centred) map window (the chat/tab-grid veneers hide via their own
    // ticks). The window itself is positioned by the bridge (centred via the WORLDMAP_X/Y varcs) and the
    // scene stays visible around it, like the desktop window — no masks, and the engine's own close X is
    // fully on-screen and tappable.
    self.keyboardButton.hidden = open;
    self.shiftButton.hidden = open;
    self.screenshotButton.hidden = open;
    self.panelDock.view.hidden = open;
    self.debugToggle.hidden = open;
    self.chromeToggle.hidden = open;
    self.pluginButton.hidden = open;
    [self appendLine:open ? @"[map] world map OPEN -> map pan/taps, chrome hidden"
                          : @"[map] world map closed -> chrome restored"];
}

// Scale the game view so the map window fills the full screen width, centred vertically, with black bands
// covering the magnified scene above/below — the official-OSRS-Mobile fullscreen-map look. The engine caps
// the window's real size (probe-settled), so the fill is a DISPLAY transform only: hit-testing stays on the
// engine's true geometry, and touches are unaffected (read in bounds space). The bands sit over the game
// view, so they also swallow stray touches outside the scaled window.
- (void)applyMapZoom:(BOOL)open windowCanvas:(CGRect)wr {
    CGFloat W = self.view.bounds.size.width, H = self.view.bounds.size.height;
    if (!open || wr.size.width <= 0 || self.renderW <= 0 || W <= 0) {
        if (!CGAffineTransformIsIdentity(self.screen.transform)) {
            self.screen.transform = CGAffineTransformIdentity;
            self.screen.layer.magnificationFilter = kCAFilterLinear;
            self.glLayer.magnificationFilter = kCAFilterLinear;
        }
        for (UIView *v in self.mapBands) v.hidden = YES;
        return;
    }
    // Pixel-art crispness: the scaled-up map reads SOFT under bilinear magnification — switch to
    // nearest-neighbour while the map fills the screen (the official app's map is crisp-pixelated too).
    self.screen.layer.magnificationFilter = kCAFilterNearest;
    self.glLayer.magnificationFilter = kCAFilterNearest;
    CGFloat s = W / (CGFloat)self.renderW;   // canvas px -> screen pt (untransformed)
    CGRect r = CGRectMake(wr.origin.x * s, wr.origin.y * s, wr.size.width * s, wr.size.height * s);
    CGFloat k = W / r.size.width;            // fill the full width
    CGAffineTransform t = CGAffineTransformMake(k, 0, 0, k,
                                                k * (W / 2 - CGRectGetMidX(r)),
                                                k * (H / 2 - CGRectGetMidY(r)));
    if (!CGAffineTransformEqualToTransform(self.screen.transform, t)) {
        self.screen.transform = t;
    }
    if (!self.mapBands) {
        NSMutableArray<UIView *> *m = [NSMutableArray arrayWithCapacity:2];
        for (int i = 0; i < 2; i++) {
            UIView *v = [[UIView alloc] initWithFrame:CGRectZero];
            v.backgroundColor = [UIColor blackColor];
            [self.view addSubview:v];
            [m addObject:v];
        }
        self.mapBands = m;
    }
    CGFloat hk = r.size.height * k;          // the scaled window's on-screen height
    CGFloat top = (H - hk) / 2;
    self.mapBands[0].frame = CGRectMake(0, 0, W, MAX(0, top));
    self.mapBands[1].frame = CGRectMake(0, top + hk, W, MAX(0, H - top - hk));
    for (UIView *v in self.mapBands) { v.hidden = NO; [self.view bringSubviewToFront:v]; }
}

// Called every screen refresh once the JVM is running. Pulls the engine's latest composited frame
// straight from the buffer via reverse JNI (JavaBridge.getFramePixels) and blits it — no PNG round-trip,
// no disk, no capture budget, so it's smooth and runs for the whole session. Also keeps the app
// foreground-active+rendering (the jetsam-protection that the §6.2 work established).
- (void)onDisplayTick {
    // Lazy switch to direct present: keep blitting CPU frames (boot/login/loading stay visible) until the GPU has
    // presented a frame AND the world has warmed up (geometry uploaded) — then reveal the GPU layer and stop the CPU
    // blit. The warmup gate (sceneWarmedUp) means the first frame the user sees is the world, not the boot sky.
    if (self.gpuPresentArmed && !self.gpuPresent && [RouteCBridge hasPresentedFrame] && [JVMLauncher sceneWarmedUp]) {
        self.gpuPresent = YES;
        self.glLayer.hidden = NO;                    // EAGL: reveal the drawable (was hidden so the CPU login/loading blit showed)
        self.screen.image = nil;                     // GL presents directly; UIImageView is just a gesture host
        self.screen.backgroundColor = [UIColor blackColor];
        [self teardownPictureInPicture];             // no CPU frame feed in direct-present mode (belt: also skipped at launch)
        [self appendLine:@"[shell] GPU direct present LIVE (world warmed up — layer revealed)"];
    }
    if (self.gpuPresent) {
        // GPU direct-present: the engine renders straight into the CAEAGLLayer and presents on its own thread, so
        // there is no per-tick blit here. Keep the tick alive for the heartbeat + native HUD/chrome refresh below.
        self.framesShown++;
    } else {
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

    // Feed the Picture-in-Picture layer (~15-30 fps: every 4th tick; the copy is skipped inside unless the
    // engine composited a NEW frame). The layer sits behind the opaque game view, so this is invisible in-app —
    // it exists so the system has live video to lift into the PiP window the moment the app backgrounds.
    if (self.pipController && !self.gpuPresent && self.framesShown % 4 == 0) {
        [self pipPumpFrame];
    }

    // Refresh the native tab rails from the chrome snapshot (~10 Hz — positions/active state rarely change,
    // and the JSON read is cheap). Skipped entirely when the rails are toggled off.
    if (self.chromeVisible && self.framesShown % 6 == 0) {
        [self updateChrome];
    }

    // W3 Feature-A (A2.5): refresh the crude native chat-at-top + HUD-status overlay from the HudDataBridge
    // snapshot (~10 Hz). Verifies the device data-flow before the full native chat veneer.
    if (self.framesShown % 6 == 0) {
        [self updateHudLayout];
        [self updateWorldMap];
    }

    // Drain the shared-core notification + outbound-link queues (~2 Hz). Both no-op until something is queued;
    // a fired plugin notification becomes a real OS notification, a plugin "open page" link opens in-app.
    if (self.framesShown % 30 == 0) {
        [self drainNotifications];
        [self drainLinks];
    }

    // W3 A3: refresh the enlarged native chat tab buttons (~10 Hz). Self-gates on chrome toggle + layout; runs
    // every tick (not only when chromeVisible) so it hides itself promptly when the chrome is toggled off.
    if (self.framesShown % 6 == 0) {
        [self updateChatTabs];
    }

    // Plugin panel: while open, re-read the list when the bridge's event sequence advances (a toggle's
    // PluginChanged, or a conflict disabling another plugin) so enabled/active state stays truthful. Cheap:
    // a single long read + a string compare ~10 Hz, only while the modal is up.
    if (self.pluginPanelOpen && self.framesShown % 6 == 0) {
        long long seq = [JVMLauncher mobileUiSeq];
        if (seq != self.pluginSeq) {
            self.pluginSeq = seq;
            [self refreshPluginList];
        } else {
            // No event, but re-render so a pending plugin's "enabling…" flips to "couldn't start" once the
            // time-based grace elapses (the native-lib start failure is async and may emit no event).
            [self.pluginTable reloadData];
        }
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


    // #8: native big-row menu — once the long-press right-click lands in the MenuPeekBridge snapshot (a frame
    // or two later), build the OSRS-styled overlay over the engine's tiny menu. Times out to the engine-menu
    // clamp fallback if the snapshot never arrives.
    if (self.menuOpen && self.menuBuilding && self.menuContainer == nil) {
        if ([self tryBuildNativeMenu]) {
            self.menuBuilding = NO;
        } else if (CACurrentMediaTime() - self.menuOpenAt > 0.6) {
            self.menuBuilding = NO;   // give up; the engine menu + clamp path (updateMenuHighlight) still works
            NSString *snap = [JVMLauncher menuPeekSnapshot];
            [self appendLine:[NSString stringWithFormat:@"[menu-diag] build TIMEOUT @game %d,%d snap=%@",
                              self.lastMenuX, self.lastMenuY,
                              snap.length ? [snap substringToIndex:MIN((NSUInteger)200, snap.length)] : @"(empty)"]];
        }
    }

    // #8 close: after a commit/cancel, hold the native cover up until the engine menu has actually closed (the
    // snapshot's menuOpen flips false), so the briefly-still-open engine menu never peeks out from under it.
    // Timeout at 0.3s in case the snapshot lags, so the cover can't get stuck.
    if (self.menuClosing) {
        BOOL engineClosed = NO;
        NSString *snap = [JVMLauncher menuPeekSnapshot];
        if (snap.length) {
            NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                             options:0 error:nil];
            if ([d isKindOfClass:[NSDictionary class]] && ![d[@"menuOpen"] boolValue]) engineClosed = YES;
        }
        if (engineClosed || CACurrentMediaTime() - self.menuClosingAt > 0.3) {
            [self teardownMenuViews];
        }
    }

    // Interactable-tap feedback: when a tap's CONSUMED click (MenuOptionClicked truth) was a real interaction
    // the bridge bumps the counter — give a light buzz (the "you grabbed something" feel) and draw a RED click
    // ring. A plain walk doesn't bump, so after a grace window we draw a YELLOW ring instead (and stay silent).
    // Matches the official app. The grace covers the engine-resolved tap pipeline: settle gate (a few frames)
    // + click consume next tick + poll latency ≈ 80-160 ms, so 0.25 s keeps walk rings prompt without
    // misclassifying slow interactions as walks.
    int its = [JVMLauncher interactTapSeq];
    if (its != self.lastInteractTapSeq) {
        self.lastInteractTapSeq = its;
        if (!self.tapHaptic) { self.tapHaptic = [[UIImpactFeedbackGenerator alloc] initWithStyle:UIImpactFeedbackStyleLight]; }
        [self.tapHaptic impactOccurred];
        [self.tapHaptic prepare];
        if (self.pendingClick) {
            [self flashClickRingAt:self.pendingClickPoint color:[UIColor colorWithRed:1.0 green:0.2 blue:0.15 alpha:1.0]];
            self.pendingClick = NO;
        }
    }
    if (self.pendingClick && CACurrentMediaTime() - self.pendingClickAt > 0.25) {
        [self flashClickRingAt:self.pendingClickPoint color:[UIColor colorWithRed:1.0 green:0.84 blue:0.0 alpha:1.0]];
        self.pendingClick = NO;
    }
}

#pragma mark - Picture-in-Picture + background keepalive

// The background story, in one place. iOS suspends a backgrounded app within seconds — the JVM freezes,
// the game socket dies, the character is dumped at the login screen. Two cooperating mechanisms keep the
// session alive instead:
//   1. KEEPALIVE — the `audio` background mode (Info.plist) + a silent AVAudioEngine on a mixable playback
//      session. While the engine renders (silence), iOS never suspends the process: the JVM keeps ticking,
//      the connection stays up, and RuneLite's own logout timer (configurable up to 30 min) is what ends
//      the session, exactly like leaving the desktop client unattended.
//   2. PiP — an AVSampleBufferDisplayLayer fed the same engine frames as the on-screen blit. With
//      canStartPictureInPictureAutomaticallyFromInline the system pops it into a floating window on
//      swipe-home, so the game stays VISIBLE while backgrounded. Dismissing the PiP window does not end
//      the session (keepalive holds); reopening the app restores the full UI.
// Foreground feeding rides onDisplayTick; in background the display link stops, so a 15 Hz main-queue
// timer takes over (main-queue so the reverse-JNI calls stay on the thread the bridge env is bound to).

// Silent keepalive audio: a source node renders zeros into a muted mixer. MixWithOthers keeps it polite —
// it neither ducks nor pauses whatever the user is listening to, and (unlike music apps) it keeps playing
// when other audio starts. AVAudioEngine wiring throws ObjC exceptions on failure, so the whole setup is
// fenced; on failure the app just keeps today's suspend-on-background behaviour.
- (void)setupBackgroundKeepalive {
    if (self.keepaliveEngine) return;
    NSError *err = nil;
    AVAudioSession *session = [AVAudioSession sharedInstance];
    if (![session setCategory:AVAudioSessionCategoryPlayback
                  withOptions:AVAudioSessionCategoryOptionMixWithOthers error:&err]) {
        [self appendLine:[NSString stringWithFormat:@"[pip] audio session category FAILED: %@", err.localizedDescription]];
        return;
    }
    if (![session setActive:YES error:&err]) {
        [self appendLine:[NSString stringWithFormat:@"[pip] audio session activate FAILED: %@", err.localizedDescription]];
        return;
    }
    @try {
        AVAudioEngine *eng = [[AVAudioEngine alloc] init];
        AVAudioFormat *fmt = [eng.outputNode inputFormatForBus:0];
        AVAudioSourceNode *src = [[AVAudioSourceNode alloc] initWithFormat:fmt
            renderBlock:^OSStatus(BOOL *isSilence, const AudioTimeStamp *ts,
                                  AVAudioFrameCount frameCount, AudioBufferList *outputData) {
                for (UInt32 i = 0; i < outputData->mNumberBuffers; i++) {
                    if (outputData->mBuffers[i].mData) {
                        memset(outputData->mBuffers[i].mData, 0, outputData->mBuffers[i].mDataByteSize);
                    }
                }
                return noErr;
            }];
        [eng attachNode:src];
        [eng connect:src to:eng.mainMixerNode format:fmt];
        eng.mainMixerNode.outputVolume = 0.0;
        self.keepaliveEngine = eng;
        self.keepaliveSource = src;
    } @catch (NSException *ex) {
        self.keepaliveEngine = nil;
        self.keepaliveSource = nil;
        [self appendLine:[NSString stringWithFormat:@"[pip] keepalive engine wiring FAILED: %@", ex.reason]];
        return;
    }
    // A phone call (or Siri) interrupts the session and stops the engine — restart when it ends, or the
    // next background would suspend the JVM mid-session.
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(onAudioInterruption:)
                                                 name:AVAudioSessionInterruptionNotification object:session];
    [self startKeepaliveEngine];
    [self appendLine:@"[pip] keepalive audio armed (silent, mixable — blocks background suspension)"];
}

- (void)startKeepaliveEngine {
    if (!self.keepaliveEngine || self.keepaliveEngine.isRunning) return;
    [[AVAudioSession sharedInstance] setActive:YES error:nil];
    NSError *err = nil;
    if (![self.keepaliveEngine startAndReturnError:&err]) {
        [self appendLine:[NSString stringWithFormat:@"[pip] keepalive audio start FAILED: %@", err.localizedDescription]];
    }
}

- (void)onAudioInterruption:(NSNotification *)note {
    NSUInteger type = [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
    if (type == AVAudioSessionInterruptionTypeEnded) {
        [self startKeepaliveEngine];
        [self appendLine:@"[pip] audio interruption ended -> keepalive restarted"];
    }
}

// PiP scaffolding: the sample-buffer layer is inserted BELOW the opaque game view (in the hierarchy and
// window-attached, which PiP requires, but never visible in-app — the screen view shows the same frames).
// The system animates it out from the game view's exact rect on swipe-home.
- (void)setupPictureInPicture {
    if (self.pipController) return;
    if (![AVPictureInPictureController isPictureInPictureSupported]) {
        [self appendLine:@"[pip] not supported on this device"];
        return;
    }
    [self.view layoutIfNeeded];
    AVSampleBufferDisplayLayer *layer = [AVSampleBufferDisplayLayer layer];
    layer.frame = self.screen.frame;
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    CMTimebaseRef tb = NULL;
    if (CMTimebaseCreateWithSourceClock(kCFAllocatorDefault, CMClockGetHostTimeClock(), &tb) == noErr && tb) {
        CMTimebaseSetTime(tb, CMClockGetTime(CMClockGetHostTimeClock()));
        CMTimebaseSetRate(tb, 1.0);
        layer.controlTimebase = tb;
        CFRelease(tb);
    }
    [self.view.layer insertSublayer:layer below:self.screen.layer];
    self.pipLayer = layer;
    self.pipFrameSeq = -1;

    AVPictureInPictureControllerContentSource *src = [[AVPictureInPictureControllerContentSource alloc]
        initWithSampleBufferDisplayLayer:layer playbackDelegate:self];
    AVPictureInPictureController *pc = [[AVPictureInPictureController alloc] initWithContentSource:src];
    pc.delegate = self;
    pc.canStartPictureInPictureAutomaticallyFromInline = YES;   // swipe-home -> the game pops into PiP
    self.pipController = pc;
    [self appendLine:@"[pip] armed — backgrounding pops the game into Picture-in-Picture"];
}

- (void)teardownPictureInPicture {
    if (!self.pipController && !self.pipLayer) return;
    [self stopPipBgPump];
    self.pipController = nil;
    [self.pipLayer removeFromSuperlayer];
    self.pipLayer = nil;
}

// Pull the newest engine frame (no-op when nothing new composited) and enqueue it on the PiP layer,
// stamped display-immediately so no timebase juggling is needed for a live feed.
- (void)pipPumpFrame {
    if (!self.pipLayer) return;
    if (self.pipLayer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        [self.pipLayer flush];   // e.g. after a background round-trip; recovers on the next enqueue
    }
    int32_t seq = self.pipFrameSeq;
    CVPixelBufferRef pb = [JVMLauncher copyFramePixelBufferSinceSeq:&seq];
    if (!pb) return;
    self.pipFrameSeq = seq;

    // Format description cached across frames; rebuilt when the canvas dimensions change (resizable render).
    static CMVideoFormatDescriptionRef fmt = NULL;
    if (fmt && !CMVideoFormatDescriptionMatchesImageBuffer(fmt, pb)) { CFRelease(fmt); fmt = NULL; }
    if (!fmt && CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pb, &fmt) != noErr) {
        fmt = NULL;
        CVPixelBufferRelease(pb);
        return;
    }
    CMSampleTimingInfo timing = { .duration = kCMTimeInvalid,
                                  .presentationTimeStamp = CMClockGetTime(CMClockGetHostTimeClock()),
                                  .decodeTimeStamp = kCMTimeInvalid };
    CMSampleBufferRef sb = NULL;
    if (CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, pb, fmt, &timing, &sb) == noErr && sb) {
        CFArrayRef atts = CMSampleBufferGetSampleAttachmentsArray(sb, true);
        if (atts && CFArrayGetCount(atts) > 0) {
            CFMutableDictionaryRef att = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(atts, 0);
            CFDictionarySetValue(att, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
        }
        [self.pipLayer enqueueSampleBuffer:sb];
        CFRelease(sb);
    }
    CVPixelBufferRelease(pb);
}

// Background frame pump: the CADisplayLink stops firing once backgrounded, so a plain 15 Hz main-queue
// timer feeds PiP instead (main queue = the thread the reverse-JNI bridge env is bound to; the run loop
// keeps servicing it because the keepalive audio blocks suspension).
- (void)startPipBgPump {
    if (self.pipBgPump || !self.pipController) return;
    dispatch_source_t t = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
    dispatch_source_set_timer(t, dispatch_time(DISPATCH_TIME_NOW, 0), NSEC_PER_SEC / 15, NSEC_PER_SEC / 100);
    __weak typeof(self) weakSelf = self;
    dispatch_source_set_event_handler(t, ^{ [weakSelf pipPumpFrame]; });
    dispatch_resume(t);
    self.pipBgPump = t;
}

- (void)stopPipBgPump {
    if (!self.pipBgPump) return;
    dispatch_source_cancel(self.pipBgPump);
    self.pipBgPump = nil;
}

// --- AVPictureInPictureSampleBufferPlaybackDelegate: a live game has no pause/seek/duration ------------

- (void)pictureInPictureController:(AVPictureInPictureController *)pc setPlaying:(BOOL)playing {
    // Live feed — the PiP pause button is a no-op (matching a live broadcast).
}

- (CMTimeRange)pictureInPictureControllerTimeRangeForPlayback:(AVPictureInPictureController *)pc {
    // An infinite range renders the PiP window with live-content chrome (no scrubber).
    return CMTimeRangeMake(kCMTimeNegativeInfinity, kCMTimePositiveInfinity);
}

- (BOOL)pictureInPictureControllerIsPlaybackPaused:(AVPictureInPictureController *)pc {
    return NO;
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pc didTransitionToRenderSize:(CMVideoDimensions)newRenderSize {
    // The engine keeps compositing at renderW x renderH; the PiP window scales it. Nothing to do.
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pc skipByInterval:(CMTime)skipInterval completionHandler:(void (^)(void))completionHandler {
    completionHandler();   // live feed — nothing to skip through
}

- (BOOL)pictureInPictureControllerShouldProhibitBackgroundAudioPlayback:(AVPictureInPictureController *)pc {
    return NO;   // the keepalive session must keep running under PiP
}

// --- AVPictureInPictureControllerDelegate ---------------------------------------------------------------

- (void)pictureInPictureControllerDidStartPictureInPicture:(AVPictureInPictureController *)pc {
    [self appendLine:@"[pip] started"];
}

- (void)pictureInPictureControllerDidStopPictureInPicture:(AVPictureInPictureController *)pc {
    [self appendLine:@"[pip] stopped"];
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pc failedToStartPictureInPictureWithError:(NSError *)error {
    [self appendLine:[NSString stringWithFormat:@"[pip] failed to start: %@", error.localizedDescription]];
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pc
    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler:(void (^)(BOOL))completionHandler {
    completionHandler(YES);   // the game UI never left — nothing to restore
}

#pragma mark - Touch -> AWT bridge (Phase 5 control scheme)

// Map a point (in the screen image view's coordinate space) to gamepack-canvas pixels, honouring the
// ScaleAspectFit letterbox. Returns NO if the point is in the letterbox margin (outside the game frame).
- (BOOL)mapPoint:(CGPoint)p toGameX:(int *)gx toGameY:(int *)gy {
    UIImage *img = self.screen.image;
    CGFloat iw = img ? img.size.width : 0, ih = img ? img.size.height : 0;
    if ((iw <= 0 || ih <= 0) && self.renderW > 0) { iw = self.renderW; ih = self.renderH; }  // GPU present: no UIImage
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

// Keep the game-tap / long-press recognizers from firing when the touch lands on a native control over the game
// (the enlarged chat tab buttons, the keyboard button, the side-tab grid). The control handles its own tap, so it
// must not ALSO register as a walk-here click underneath. Touches on the bare game view (self.screen) pass through.
- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gr shouldReceiveTouch:(UITouch *)touch {
    return ![touch.view isKindOfClass:[UIControl class]];
}

// tap -> left click (walk / interact / select a menu entry).
- (void)onTap:(UITapGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    CGPoint vp = [g locationInView:self.view];
    int gx = 0, gy = 0;
    if (![self mapPoint:[g locationInView:self.screen] toGameX:&gx toGameY:&gy]) return;
    // World map open: a tap INSIDE the (centred) map window is a UI click (icons/keys/buttons) — precise,
    // no world-snap, no walk-ring semantics. A tap in the search rect ALSO raises the soft keyboard: the map
    // search doesn't flip INPUT_TYPE (Phase-0 probe), so the auto-raise never fires; typed keys go to the
    // canvas and the focused search field consumes them. Taps OUTSIDE the window fall through to the normal
    // world tap (walk/interact + ring), like the desktop.
    if (self.mapOpen && (self.mapWindowCanvas.size.width <= 0
            || CGRectContainsPoint(self.mapWindowCanvas, CGPointMake(gx, gy)))) {
        if (CGRectGetWidth(self.mapSearchCanvas) > 0
                && CGRectContainsPoint(self.mapSearchCanvas, CGPointMake(gx, gy))
                && ![self.kbField isFirstResponder]) {
            self.kbField.text = kKbSentinel;
            [self.kbField becomeFirstResponder];
            [self appendLine:@"[map] search tap -> keyboard up"];
        }
        [JVMLauncher postClickPreciseX:gx y:gy button:1];
        [self appendLine:[NSString stringWithFormat:@"[map] tap %d,%d (precise)", gx, gy]];
        return;
    }
    // W3 A3: the relocated chat tab-row is UI, not the world. Send a PRECISE click straight to the engine (no
    // world-snap pulling it to a tile/entity) and DON'T draw the walk ring — taps there toggle a chat filter, they
    // don't walk. The rect moves with the safe-area relocate (chatHitCanvas, set in updateHudLayout).
    CGRect ch = self.chatHitCanvas;
    if (ch.size.width > 0 && gx >= ch.origin.x && gx <= ch.origin.x + ch.size.width
            && gy >= ch.origin.y && gy <= ch.origin.y + ch.size.height) {
        [JVMLauncher postClickPreciseX:gx y:gy button:1];
        [self appendLine:[NSString stringWithFormat:@"[shell] chat-tab tap %d,%d (precise, no ring)", gx, gy]];
        return;
    }
    // Defer the ring one beat: onDisplayTick draws it red (interactable) or yellow (walk) once the engine has
    // resolved the tap. Pump the haptic generator so the buzz is low-latency if it turns out to be an interact.
    self.pendingClick = YES;
    self.pendingClickPoint = vp;
    self.pendingClickAt = CACurrentMediaTime();
    if (!self.tapHaptic) { self.tapHaptic = [[UIImpactFeedbackGenerator alloc] initWithStyle:UIImpactFeedbackStyleLight]; }
    [self.tapHaptic prepare];
    [JVMLauncher postClickX:gx y:gy button:1];
    [self appendLine:[NSString stringWithFormat:@"[shell] tap -> click(L) game %d,%d", gx, gy]];
}

// Draw the official-style click marker: a ring (red = interactable, yellow = walk) that expands and fades out
// over ~0.45s at the tap point, then removes itself. Pure native (CAShapeLayer) — no game involvement.
- (void)flashClickRingAt:(CGPoint)p color:(UIColor *)color {
    const CGFloat r0 = 9.0;   // starting radius
    CAShapeLayer *ring = [CAShapeLayer layer];
    ring.frame = CGRectMake(p.x - r0, p.y - r0, r0 * 2, r0 * 2);
    ring.path = [UIBezierPath bezierPathWithOvalInRect:CGRectMake(0, 0, r0 * 2, r0 * 2)].CGPath;
    ring.fillColor = [UIColor clearColor].CGColor;
    ring.strokeColor = color.CGColor;
    ring.lineWidth = 2.5;
    [self.view.layer addSublayer:ring];

    CABasicAnimation *scale = [CABasicAnimation animationWithKeyPath:@"transform.scale"];
    scale.fromValue = @0.5; scale.toValue = @2.2;
    CABasicAnimation *fade = [CABasicAnimation animationWithKeyPath:@"opacity"];
    fade.fromValue = @1.0; fade.toValue = @0.0;
    CAAnimationGroup *grp = [CAAnimationGroup animation];
    grp.animations = @[scale, fade];
    grp.duration = 0.45;
    grp.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut];
    grp.removedOnCompletion = YES;
    [CATransaction begin];
    [CATransaction setCompletionBlock:^{ [ring removeFromSuperlayer]; }];
    [ring addAnimation:grp forKey:@"clickRing"];
    ring.opacity = 0.0;   // end state (the animation drives the visible fade)
    [CATransaction commit];
}

// long-press -> hold-drag-release native menu (the OSRS-mobile feel). One continuous gesture:
//   .began   -> right-click at the press point to open the engine menu; record the press point + which hand
//               (left/right half of the screen) so the native overlay opens to the side away from the hand.
//   .changed -> as the finger slides, lock the highlight onto the row under it (a light haptic tick each time
//               it moves to a new option).
//   .ended   -> release commits the locked-on row via click-through; releasing off the rows cancels.
// The native rows are non-interactive labels — selection is driven entirely by this drag, so there is no
// race with the rows' own touch handling. The overlay is built a frame or two after .began (it waits for the
// engine menu to land in the MenuPeekBridge snapshot), so updateMenuHighlight no-ops until it exists.
// Open the engine right-click menu at game (gx,gy) and arm the native big-row overlay anchored at the finger
// (loc, view coords). Shared by the world long-press (onLongPress) and the chat-tab long-press (filter menu).
- (void)openEngineMenuAtGameX:(int)gx gameY:(int)gy anchor:(CGPoint)loc {
    if (self.menuOpen || self.menuClosing) [self teardownMenuViews];   // re-press: drop a stale/closing menu, open fresh
    self.lastMenuX = gx; self.lastMenuY = gy;
    self.menuAnchor = loc;
    [JVMLauncher postClickX:gx y:gy button:3];      // open the engine's OWN right-click menu at the target
    self.menuOpen = YES;
    self.menuOpenAt = CACurrentMediaTime();
    self.menuGeomValid = NO;                        // no live rect yet; refreshed once the engine builds the menu
    self.menuFingerOnMenu = YES;                    // release-without-moving picks the row at the press point
    self.menuSelX = gx; self.menuSelY = gy;
    self.lastMenuX = gx; self.lastMenuY = gy;
    self.menuBuilding = YES;                         // #8: poll the snapshot in onDisplayTick, then build native rows
    self.menuHighlight = -1;
    self.menuHandRight = (loc.x > self.screen.bounds.size.width * 0.5); // pressed on the right -> open leftward
    self.menuHapticY = loc.y;
    self.menuHaptic = [UISelectionFeedbackGenerator new];
    [self.menuHaptic prepare];
}

#pragma mark - #8 Native big-row menu (over the engine menu; commits via the engine row)

// Poll the MenuPeekBridge snapshot; once the engine menu exists, capture its game-space rect + entries and
// build the native overlay. Returns YES when built (caller clears menuBuilding).
- (BOOL)tryBuildNativeMenu {
    NSString *snap = [JVMLauncher menuPeekSnapshot];
    if (snap.length == 0) return NO;
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                      options:0 error:nil];
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"menuOpen"] boolValue]) return NO;
    NSArray *entries = d[@"entries"];
    if (![entries isKindOfClass:[NSArray class]] || entries.count == 0) return NO;
    int mw = [d[@"menuW"] intValue], mh = [d[@"menuH"] intValue];
    if (mw <= 0 || mh <= 0) return NO;
    self.menuGeomGame = CGRectMake([d[@"menuX"] intValue], [d[@"menuY"] intValue], mw, mh);
    self.menuGeomValid = YES;
    [self buildNativeMenuWithEntries:entries];
    return YES;
}

// Build the opaque OSRS-styled native menu: a "Choose Option" header + one 32pt row per entry (display order,
// index 0 = top = left-click default). The box is sized + positioned to fully COVER the engine's own tiny
// rendered menu so there's no double-menu. Selection is driven by the long-press drag (rows are inert).
- (void)buildNativeMenuWithEntries:(NSArray *)entries {
    [self.menuContainer removeFromSuperview];
    self.menuRowEntries = entries;
    NSInteger count = entries.count;

    // The engine menu rect in view coords — what we must cover.
    CGRect er = [self viewRectForGameX:(int)CGRectGetMinX(self.menuGeomGame)
                                     y:(int)CGRectGetMinY(self.menuGeomGame)
                                     w:(int)CGRectGetWidth(self.menuGeomGame)
                                     h:(int)CGRectGetHeight(self.menuGeomGame)];

    CGFloat boxW = MAX(kMenuWidth, CGRectGetWidth(er) + 16.0);
    CGFloat boxH = kMenuHeaderViewH + count * kMenuRowViewH;

    // Anchor over the engine rect; hand-bias the box away from the pressing thumb, then clamp into the safe area.
    CGFloat ox = CGRectGetMinX(er) - 8.0;
    if (self.menuHandRight) ox = CGRectGetMaxX(er) + 8.0 - boxW;   // right thumb -> open leftward
    CGFloat oy = CGRectGetMinY(er) - 4.0;
    UIEdgeInsets ins = self.view.safeAreaInsets;
    CGFloat minX = ins.left + 6.0;
    CGFloat maxX = self.view.bounds.size.width - ins.right - 6.0 - boxW;
    CGFloat minY = ins.top + 6.0;
    CGFloat maxY = self.view.bounds.size.height - ins.bottom - 6.0 - boxH;
    ox = fmin(fmax(ox, minX), fmax(minX, maxX));
    oy = fmin(fmax(oy, minY), fmax(minY, maxY));

    UIView *box = [[UIView alloc] initWithFrame:CGRectMake(ox, oy, boxW, boxH)];
    box.backgroundColor = [UIColor colorWithRed:0.36 green:0.33 blue:0.27 alpha:1.0]; // OSRS menu body (tan-grey)
    box.layer.borderColor = [UIColor blackColor].CGColor;
    box.layer.borderWidth = 1.0;
    box.userInteractionEnabled = NO;   // the drag drives selection; the rows never handle their own touches
    box.layer.shadowColor = [UIColor blackColor].CGColor;
    box.layer.shadowOpacity = 0.5;
    box.layer.shadowRadius = 6.0;
    box.layer.shadowOffset = CGSizeMake(0, 2);

    UILabel *header = [[UILabel alloc] initWithFrame:CGRectMake(0, 0, boxW, kMenuHeaderViewH)];
    header.backgroundColor = [UIColor colorWithRed:0.24 green:0.21 blue:0.16 alpha:1.0]; // darker title strip
    header.text = @"  Choose Option";
    header.font = [UIFont boldSystemFontOfSize:14];
    header.textColor = [UIColor whiteColor];
    [box addSubview:header];

    self.menuRows = [NSMutableArray arrayWithCapacity:count];
    for (NSInteger k = 0; k < count; k++) {
        NSDictionary *e = [entries[k] isKindOfClass:[NSDictionary class]] ? entries[k] : @{};
        UILabel *row = [[UILabel alloc] initWithFrame:
                        CGRectMake(0, kMenuHeaderViewH + k * kMenuRowViewH, boxW, kMenuRowViewH)];
        row.backgroundColor = [UIColor clearColor];
        row.attributedText = [self menuRowTextForOption:e[@"opt"] target:e[@"tgt"]];
        [box addSubview:row];
        self.menuRows[k] = row;
    }

    [self.view addSubview:box];
    self.menuContainer = box;
    [self appendLine:[NSString stringWithFormat:@"[menu-diag] native rows BUILT n=%ld box=%@ er=%@ viewIdx=%ld/%ld",
                      (long)count, NSStringFromCGRect(box.frame), NSStringFromCGRect(er),
                      (long)[self.view.subviews indexOfObject:box], (long)self.view.subviews.count]];
    self.menuHighlight = -1;
    // Seed the highlight + commit target from the press point so a lift-without-drag picks the row under it.
    [self updateNativeMenuHighlight:self.menuAnchor];
}

// "option target" — option white, target OSRS yellow (tags are already stripped in the snapshot).
- (NSAttributedString *)menuRowTextForOption:(NSString *)opt target:(NSString *)tgt {
    opt = [opt isKindOfClass:[NSString class]] ? opt : @"";
    tgt = [tgt isKindOfClass:[NSString class]] ? tgt : @"";
    UIFont *f = [UIFont systemFontOfSize:16 weight:UIFontWeightSemibold];
    NSMutableAttributedString *s = [[NSMutableAttributedString alloc] initWithString:[@"  " stringByAppendingString:opt]
        attributes:@{NSFontAttributeName: f, NSForegroundColorAttributeName: [UIColor whiteColor]}];
    if (tgt.length) {
        [s appendAttributedString:[[NSAttributedString alloc] initWithString:[@" " stringByAppendingString:tgt]
            attributes:@{NSFontAttributeName: f,
                         NSForegroundColorAttributeName: [UIColor colorWithRed:1.0 green:0.92 blue:0.35 alpha:1.0]}]];
    }
    return s;
}

// Drive the native menu from the drag: highlight the row under the finger, keep the (hidden) engine menu open
// by parking its clamped cursor on the matching engine row, and set the commit target to that row's engine
// centre. Off the rows -> cancel (commit clicks the title strip = close with no action).
- (void)updateNativeMenuHighlight:(CGPoint)pScreen {
    if (!self.menuContainer) return;
    CGPoint c = [self.screen convertPoint:pScreen toView:self.menuContainer];
    CGFloat graceX = 90.0, graceY = 12.0;
    BOOL inX = (c.x > -graceX && c.x < self.menuContainer.bounds.size.width + graceX);
    NSInteger hit = -1;
    for (NSInteger k = 0; k < (NSInteger)self.menuRows.count; k++) {
        CGRect rf = self.menuRows[k].frame;
        CGFloat top = rf.origin.y - (k == 0 ? graceY : 0);
        CGFloat bot = CGRectGetMaxY(rf) + (k == (NSInteger)self.menuRows.count - 1 ? graceY : 0);
        if (inX && c.y >= top && c.y < bot) { hit = k; break; }
    }

    if (hit != self.menuHighlight) {
        for (NSInteger k = 0; k < (NSInteger)self.menuRows.count; k++) {
            self.menuRows[k].backgroundColor = (k == hit)
                ? [UIColor colorWithRed:1.0 green:1.0 blue:1.0 alpha:0.22]   // hovered row highlight
                : [UIColor clearColor];
        }
        if (hit >= 0) { [self.menuHaptic selectionChanged]; [self.menuHaptic prepare]; }
        self.menuHighlight = (int)hit;
    }

    CGRect r = self.menuGeomGame;
    int ex = (int)lround(CGRectGetMidX(r));
    if (hit >= 0) {
        // engine row centre for display index `hit` (fixed gamepack geometry; clamp inside the rect)
        int ey = (int)CGRectGetMinY(r) + kMenuHeaderPx + (int)hit * kMenuRowPx + kMenuRowPx / 2;
        ey = (int)fmin(fmax((CGFloat)ey, CGRectGetMinY(r) + 1), CGRectGetMaxY(r) - 2);
        self.menuFingerOnMenu = YES;
        self.menuSelX = ex; self.menuSelY = ey;
        [JVMLauncher postMoveX:ex y:ey];   // keep the engine highlight in lock-step (menu stays open)
    } else {
        self.menuFingerOnMenu = NO;
        self.menuSelX = ex; self.menuSelY = (int)CGRectGetMinY(r) + 2;   // title strip = clean cancel
        [JVMLauncher postMoveX:self.menuSelX y:self.menuSelY];
    }
}

- (void)onLongPress:(UILongPressGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady]) return;
    CGPoint loc = [g locationInView:self.screen];

    switch (g.state) {
        case UIGestureRecognizerStateBegan: {
            int gx = 0, gy = 0;
            if (![self mapPoint:loc toGameX:&gx toGameY:&gy]) return; // letterbox
            [self openEngineMenuAtGameX:gx gameY:gy anchor:loc];
            [self appendLine:[NSString stringWithFormat:@"[shell] long-press -> engine menu @game %d,%d", gx, gy]];
            break;
        }
        case UIGestureRecognizerStateChanged:
            [self updateMenuHighlightForPoint:loc];   // move the engine pointer to the finger -> highlight
            break;

        case UIGestureRecognizerStateEnded:
            [self commitMenuSelection];               // click under the finger -> select (or close if off-menu)
            break;

        case UIGestureRecognizerStateCancelled:
        case UIGestureRecognizerStateFailed:
            [self cancelMenu];                        // any click closes the open engine menu (camera recovers)
            break;

        default:
            break;
    }
}

#pragma mark - Right-click menu (drives the engine's own menu)

// Pull the live engine right-click menu bounds from the MenuPeekBridge snapshot (gamepack 765x503 coords).
// Sets menuGeomValid + menuGeomGame while the engine reports an open menu; clears valid during the brief build
// window before the menu exists, so the caller falls back to the raw finger point.
- (void)refreshMenuGeometry {
    NSString *snap = [JVMLauncher menuPeekSnapshot];
    if (snap.length == 0) { self.menuGeomValid = NO; return; }
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[snap dataUsingEncoding:NSUTF8StringEncoding]
                                                     options:0 error:nil];
    if (![d isKindOfClass:[NSDictionary class]] || ![d[@"menuOpen"] boolValue]) { self.menuGeomValid = NO; return; }
    int mw = [d[@"menuW"] intValue], mh = [d[@"menuH"] intValue];
    if (mw <= 0 || mh <= 0) { self.menuGeomValid = NO; return; }
    self.menuGeomGame = CGRectMake([d[@"menuX"] intValue], [d[@"menuY"] intValue], mw, mh);
    self.menuGeomValid = YES;
}

// Drive the ENGINE menu's highlight: move the engine pointer to the finger so the engine highlights whichever of
// its OWN rows is under it (exactly like a real mouse). CRUCIAL: the OSRS client auto-closes its right-click menu
// the instant the cursor leaves the menu bounds, so we CLAMP the reported cursor strictly inside the live menu
// rect — the engine never sees it leave, and the menu stays open until the finger lifts. We separately track the
// REAL finger spot so commit can tell select (over the menu) from cancel (dragged off). Haptic ticks per row.
- (void)updateMenuHighlightForPoint:(CGPoint)p {
    if (!self.menuOpen) return;
    if (self.menuContainer) { [self updateNativeMenuHighlight:p]; return; }   // #8: native rows drive selection
    int gx = 0, gy = 0;
    if (![self mapPoint:p toGameX:&gx toGameY:&gy]) return;   // letterbox: keep the menu, ignore this move

    [self refreshMenuGeometry];

    int sx = gx, sy = gy;          // where we park the engine cursor (clamped inside the menu)
    BOOL onMenu = YES;             // does the finger want a row (select) or has it left the menu (cancel)?
    if (self.menuGeomValid) {
        CGRect r = self.menuGeomGame;
        sx = (int)lround(fmin(fmax((CGFloat)gx, CGRectGetMinX(r) + 1), CGRectGetMaxX(r) - 2));
        sy = (int)lround(fmin(fmax((CGFloat)gy, CGRectGetMinY(r) + 1), CGRectGetMaxY(r) - 2));
        onMenu = CGRectContainsPoint(CGRectInset(r, -kMenuGraceX, -kMenuGraceY), CGPointMake(gx, gy));
    }

    self.menuFingerOnMenu = onMenu;
    self.lastMenuX = sx; self.lastMenuY = sy;   // clamped click-through highlight target
    if (onMenu) {
        self.menuSelX = sx; self.menuSelY = sy;                       // release here -> select the row under the finger
    } else if (self.menuGeomValid) {
        CGRect r = self.menuGeomGame;                                 // dragged off -> release clicks the title strip:
        self.menuSelX = (int)lround(CGRectGetMidX(r));                // closes the menu with NO action (clean cancel,
        self.menuSelY = (int)lround(CGRectGetMinY(r)) + 2;            // never a stray world click)
    } else {
        self.menuSelX = gx; self.menuSelY = gy;                       // no geometry yet: fall back to the raw point
    }

    // Only drive the engine cursor once the live menu rect is known: the engine auto-closes its menu the
    // instant the cursor leaves it, and before the geometry lands the clamp can't protect it — a raw move
    // (finger still travelling, e.g. the chat pull-down) yanks the cursor out and silently kills the menu
    // during the build window (the intermittent "haptics but no filter menu" bug).
    if (self.menuGeomValid) [JVMLauncher postMoveX:sx y:sy];   // engine highlights the row under its (clamped) pointer
    if (fabs(p.y - self.menuHapticY) >= 14.0) {
        self.menuHapticY = p.y;
        [self.menuHaptic selectionChanged];
        [self.menuHaptic prepare];
    }
}

// Release: click the resolved target. Over the menu -> the row under the finger (select); dragged off -> the menu's
// title strip (closes with no action = cancel). Either way the menu closes, so camera/world input frees up.
- (void)commitMenuSelection {
    if (!self.menuOpen) { [self teardownMenuViews]; return; }
    [JVMLauncher postMenuSelectX:self.menuSelX y:self.menuSelY];
    [self appendLine:[NSString stringWithFormat:@"[shell] menu %@ @game %d,%d",
                      self.menuFingerOnMenu ? @"select" : @"cancel", self.menuSelX, self.menuSelY]];
    self.menuOpen = NO;
    [self beginMenuClose];   // hold the native cover up until the engine menu actually closes (no peek-through)
}

// Gesture interrupted (system took the touch): close the menu with no action so nothing stays stuck — click the
// title strip if we know the menu rect, else fall back to the last clamped point.
- (void)cancelMenu {
    if (self.menuOpen) {
        if (self.menuGeomValid) {
            CGRect r = self.menuGeomGame;
            [JVMLauncher postMenuSelectX:(int)lround(CGRectGetMidX(r)) y:(int)lround(CGRectGetMinY(r)) + 2];
        } else {
            [JVMLauncher postMenuSelectX:self.lastMenuX y:self.lastMenuY];
        }
        [self appendLine:@"[shell] right-click menu closed"];
    }
    self.menuOpen = NO;
    [self beginMenuClose];   // keep the cover up through the engine-menu close latency
}

// Defer the native-overlay teardown: the engine right-click menu (now composited into the UI in GPU mode) takes a
// game cycle or two to actually close after the commit/cancel click. Removing the native cover immediately exposes
// the engine menu for that gap (the "native menu disappears, native engine menu flashes" artifact). Instead keep
// the cover up and let onDisplayTick tear it down once the MenuPeekBridge snapshot reports the engine menu closed
// (or a short timeout). The container is inert here (menuOpen=NO), so gestures/camera already recovered.
- (void)beginMenuClose {
    if (self.menuContainer == nil) { [self teardownMenuViews]; return; }
    self.menuClosing = YES;
    self.menuClosingAt = CACurrentMediaTime();
}

- (void)teardownMenuViews {
    [self.menuContainer removeFromSuperview];
    self.menuContainer = nil;
    self.menuRows = nil;
    self.menuRowEntries = nil;
    self.menuHighlight = -1;
    self.menuBuilding = NO;
    self.menuClosing = NO;
}

// one-finger drag -> rotate the camera. Feed incremental pixel deltas (reset the recognizer's translation
// each step) to the bridge, which applies them to the camera yaw/pitch targets directly. The menuOpen
// guard is belt-and-suspenders: if a long-press already claimed the touch, never also rotate.
- (void)onCameraPan:(UIPanGestureRecognizer *)g {
    if (![JVMLauncher bridgeReady] || self.menuOpen) return;
    if (g.state == UIGestureRecognizerStateBegan) {
        [g setTranslation:CGPointZero inView:self.screen];
        // World map open: a 1-finger drag that BEGINS inside the Key list or the open surface dropdown
        // SCROLLS that list (wheel events parked over it); inside the map window it pans the MAP (bridge ->
        // map centre). Outside the window it stays a camera drag, like the desktop.
        if (self.mapOpen) {
            int mgx = 0, mgy = 0;
            if ([self mapPoint:[g locationInView:self.screen] toGameX:&mgx toGameY:&mgy]) {
                CGPoint gp = CGPointMake(mgx, mgy);
                if ((self.mapListCanvas.size.width > 0 && CGRectContainsPoint(self.mapListCanvas, gp))
                        || (self.mapKeyCanvas.size.width > 0 && CGRectContainsPoint(self.mapKeyCanvas, gp))) {
                    self.mapListScrolling = YES;
                    self.mapListWheelPt = gp;
                    self.mapListScrollAcc = 0;
                    self.mapPanning = NO;
                    self.chatScrolling = NO;
                    [self appendLine:@"[map] list scroll begin"];
                    return;
                }
                if (self.mapWindowCanvas.size.width <= 0
                        || CGRectContainsPoint(self.mapWindowCanvas, gp)) {
                    self.mapPanning = YES;
                    self.mapListScrolling = NO;
                    self.mapPanAccX = self.mapPanAccY = 0;
                    self.chatScrolling = NO;
                    [self appendLine:@"[map] pan begin"];
                    return;
                }
            }
        }
        self.mapPanning = NO;
        self.mapListScrolling = NO;
        // A drag that STARTS inside the open chat box scrolls the chat (finger-scroll) instead of rotating the
        // camera. Only when the chat is open (chatScrollable); otherwise it's a normal camera drag.
        int gx = 0, gy = 0;
        if (self.chatScrollable && self.chatScrollCanvas.size.width > 0
                && [self mapPoint:[g locationInView:self.screen] toGameX:&gx toGameY:&gy]
                && CGRectContainsPoint(self.chatScrollCanvas, CGPointMake(gx, gy))) {
            self.chatScrolling = YES;
            self.chatScrollAccum = 0;
            [self appendLine:@"[shell] chat scroll begin"];
            return;
        }
        self.chatScrolling = NO;
        // A camera drag moves the world, not the pointer — so clear any frozen hover first, otherwise
        // scenery rotating under the stale cursor lights up and the next walk-tap mis-clicks it.
        [JVMLauncher postClearHover];
        [self appendLine:@"[shell] camera pan begin (1-finger)"];
        return;
    }
    if (g.state == UIGestureRecognizerStateEnded || g.state == UIGestureRecognizerStateCancelled) {
        if (self.mapPanning || self.mapListScrolling) {
            self.mapPanning = NO;
            self.mapListScrolling = NO;
            return;
        }
        if (self.chatScrolling) {
            self.chatScrolling = NO;
            [JVMLauncher postClearHover];   // drop the cursor the wheel parked over the chat
            return;
        }
        [self appendLine:@"[shell] camera pan end"];
        return;
    }
    if (g.state != UIGestureRecognizerStateChanged) return;
    CGPoint d = [g translationInView:self.screen];
    [g setTranslation:CGPointZero inView:self.screen];   // make the next delta incremental
    if (d.x == 0 && d.y == 0) return;
    if (self.mapListScrolling) {
        // Swipe-scroll the Key list / surface dropdown: wheel notches parked over the list (the engine
        // scrolls the hovered scrollable). Content follows the finger: swipe up -> wheel down.
        const CGFloat kMapScrollPtPerNotch = 22.0;
        self.mapListScrollAcc += -d.y / kMapScrollPtPerNotch;
        int notches = (int)(self.mapListScrollAcc > 0 ? floor(self.mapListScrollAcc) : ceil(self.mapListScrollAcc));
        if (notches != 0) {
            self.mapListScrollAcc -= notches;
            [JVMLauncher postWheelX:(int)self.mapListWheelPt.x y:(int)self.mapListWheelPt.y rotation:notches];
        }
        return;
    }
    if (self.mapPanning) {
        // Finger pt -> whole canvas px, sub-px accumulated so slow drags aren't quantised away. The bridge
        // converts px -> map tiles via the live zoom; kMapPanSpeed > 1 = the map travels faster than the
        // finger (user preference — covering Gielinor 1:1 takes too many swipes).
        const CGFloat kMapPanSpeed = 2.0;
        CGFloat W = self.view.bounds.size.width;
        CGFloat k = (self.renderW > 0 && W > 0) ? (CGFloat)self.renderW / W : 1.0;
        k *= kMapPanSpeed;
        self.mapPanAccX += d.x * k;
        self.mapPanAccY += d.y * k;
        int dx = (int)(self.mapPanAccX < 0 ? ceil(self.mapPanAccX) : floor(self.mapPanAccX));
        int dy = (int)(self.mapPanAccY < 0 ? ceil(self.mapPanAccY) : floor(self.mapPanAccY));
        if (dx != 0 || dy != 0) {
            self.mapPanAccX -= dx;
            self.mapPanAccY -= dy;
            [JVMLauncher panEngineMapDx:dx dy:dy];
        }
        return;
    }
    if (self.chatScrolling) {
        [self scrollChatByDragDeltaY:d.y];
        return;
    }
    // Inverted both axes (user preference): swipe direction now drags the world the opposite way (like
    // grabbing the scene), matching the feel they want vs. the previous "push the camera" direction.
    [JVMLauncher postCameraDx:(int)lround(-d.x) dy:(int)lround(-d.y)];
}

// Convert a vertical drag delta (screen pt) into a chat scroll, applied to the chat's OWN scroll layer (not a
// mouse wheel — a wheel over the transparent chat also zooms the camera). The drag is scaled to engine-canvas px
// (1:1 with the finger) and accumulated so sub-pixel drags aren't lost; content follows the finger via kChatScrollDir.
- (void)scrollChatByDragDeltaY:(CGFloat)dy {
    CGFloat W = self.view.bounds.size.width;
    if (self.renderW <= 0 || W <= 0) return;
    CGFloat canvasPerPt = (CGFloat)self.renderW / W;   // screen pt -> engine-canvas px
    self.chatScrollAccum += dy * canvasPerPt;
    int whole = (int)(self.chatScrollAccum < 0 ? ceil(self.chatScrollAccum) : floor(self.chatScrollAccum));
    if (whole == 0) return;
    self.chatScrollAccum -= whole;
    [JVMLauncher scrollEngineChat:(whole * kChatScrollDir)];
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
    if (g.state == UIGestureRecognizerStateEnded || g.state == UIGestureRecognizerStateCancelled) {
        // Each zoom step posts a wheel at the pinch centroid, which re-enters the synthetic cursor THERE (the
        // engine zooms about the live mouse point). Left as-is, the cursor stays parked on whatever is under
        // the fingers — so the engine (and any hover-highlight) keeps picking it after the zoom: the "chop
        // tree box stays up when I zoom in" report. Re-park on lift so hover ends with the gesture.
        [JVMLauncher postClearHover];
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

// Sticky-Shift: latch SHIFT down/up via the bridge so subsequent taps/long-presses are seen by the engine as
// shift-held (shift-click swaps, Shift+right-click marking, overlay "Configure"). Highlighted while latched.
- (void)onToggleShift {
    self.shiftHeld = !self.shiftHeld;
    [JVMLauncher postShiftHeld:self.shiftHeld];
    self.shiftButton.backgroundColor = self.shiftHeld
        ? [UIColor colorWithRed:0.95 green:0.62 blue:0.11 alpha:0.92]   // RuneLite amber = latched on
        : [UIColor colorWithWhite:0.0 alpha:0.45];
    self.shiftButton.tintColor = self.shiftHeld ? [UIColor blackColor] : [UIColor whiteColor];
    [self appendLine:self.shiftHeld ? @"[shell] Shift latched ON" : @"[shell] Shift released"];
}

// Drain the shared-core notification queue -> real OS notifications. Idle/Chat notifications, random events, etc.
// fire here because the sandbox has no tray/taskbar/shell-out. Auth is requested lazily on the first one.
- (void)drainNotifications {
    NSString *n = [JVMLauncher mobileNextNotification];
    if (n.length == 0) return;
    if (!self.notifAuthRequested) {
        self.notifAuthRequested = YES;
        UNUserNotificationCenter *unc = [UNUserNotificationCenter currentNotificationCenter];
        unc.delegate = self;   // so banners show even while foregrounded (the JVM suspends in background)
        [unc requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
            completionHandler:^(BOOL granted, NSError *err) {}];
    }
    unichar sepCh = 1;   // the "titlebody" separator the bridge uses (control char, not a literal)
    NSString *sepStr = [NSString stringWithCharacters:&sepCh length:1];
    while (n.length > 0) {   // drain the whole backlog this tick so a burst doesn't trickle out
        NSRange sep = [n rangeOfString:sepStr];
        NSString *title = sep.location == NSNotFound ? @"RuneLite" : [n substringToIndex:sep.location];
        NSString *body  = sep.location == NSNotFound ? n : [n substringFromIndex:sep.location + 1];
        if (title.length == 0) title = @"RuneLite";
        UNMutableNotificationContent *c = [[UNMutableNotificationContent alloc] init];
        c.title = title;
        c.body = body;
        c.sound = [UNNotificationSound defaultSound];
        UNNotificationRequest *req = [UNNotificationRequest requestWithIdentifier:[[NSUUID UUID] UUIDString]
                                                                         content:c trigger:nil];
        [[UNUserNotificationCenter currentNotificationCenter] addNotificationRequest:req withCompletionHandler:nil];
        n = [JVMLauncher mobileNextNotification];
    }
}

// Drain the shared-core outbound-link queue -> in-app SFSafariViewController. Wiki/HiScore/"open page" links land
// here (the sandbox has no system browser). One at a time; only http(s) is opened.
- (void)drainLinks {
    if (self.safariOpen) return;   // present one; the rest stay queued until it closes
    NSString *urlStr = [JVMLauncher mobileNextLink];
    if (urlStr.length == 0) return;
    NSURL *url = [NSURL URLWithString:urlStr];
    if (url == nil || !([url.scheme isEqualToString:@"https"] || [url.scheme isEqualToString:@"http"])) {
        [self appendLine:[NSString stringWithFormat:@"[shell] skipped non-web link: %@", urlStr]];
        return;
    }
    SFSafariViewController *sf = [[SFSafariViewController alloc] initWithURL:url];
    sf.delegate = self;
    sf.modalPresentationStyle = UIModalPresentationPageSheet;
    self.safariOpen = YES;
    [self presentViewController:sf animated:YES completion:nil];
    [self appendLine:[NSString stringWithFormat:@"[shell] open link: %@", url.absoluteString]];
}

- (void)safariViewControllerDidFinish:(SFSafariViewController *)controller {
    self.safariOpen = NO;
}

// Show notification banners even while the app is foregrounded — the JVM suspends in the background, so a
// fired notification almost always lands while the game is on screen.
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionSound
                      | UNNotificationPresentationOptionList);
}

// Save the live composited frame to Photos. The engine's auto-screenshots write to the sandbox dir (unreachable
// on a phone); this is the manual reachable path — native already holds the frame via latestFrame.
- (void)onScreenshot {
    UIImage *img = [JVMLauncher latestFrame];
    if (img == nil) { [self appendLine:@"[shell] screenshot: no frame yet"]; return; }
    UIImageWriteToSavedPhotosAlbum(img, self, @selector(image:didFinishSavingWithError:contextInfo:), NULL);
}

- (void)image:(UIImage *)image didFinishSavingWithError:(NSError *)error contextInfo:(void *)ctx {
    [self appendLine:error
        ? [NSString stringWithFormat:@"[shell] screenshot save failed: %@", error.localizedDescription]
        : @"[shell] screenshot saved to Photos"];
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
