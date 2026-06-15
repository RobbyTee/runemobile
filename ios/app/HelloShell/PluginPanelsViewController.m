#import "PluginPanelsViewController.h"
#import "JVMLauncher.h"

// Right-edge drawer / dock geometry. Desktop-faithful: a vertical icon rail pinned to the far-right edge, the
// rendered panel content opening to its left. The panel's natural 1:1 width is ~PluginPanel.PANEL_WIDTH
// supersampled then shown at device scale (≈322pt on a 3x screen); size the content column to fit it snug.
static const CGFloat kRailWidth = 42.0;       // touch-friendly icon column in the outer screen-edge strip
static const CGFloat kDrawerWidth = 392.0;   // modal drawer: content(322) + rail + insets/gap
static const CGFloat kDrawerHInset = 10.0;
static const CGFloat kTitleBarHeight = 44.0;
static const CGFloat kRailButtonH = 44.0;     // mobile touch target per plugin icon
// Docked geometry. The native tab stones sit INSIDE the safe area (the game is inset by the tab strip), so the
// outer sliver between them and the screen edge is free — put the plugin rail there, to the RIGHT of the tabs.
// kRailCornerClear keeps the rail's top + bottom OUT of the iPhone's rounded-corner radius (~55pt) so the curve
// can't clip the top-right / bottom-right of the bar; the rail floats in the flat middle of the right edge.
static const CGFloat kRailCornerClear = 60.0;
static const CGFloat kDockPanelW = 334.0;     // inline panel column width (≈322 content + chrome)

/// Passthrough container for docked mode: touches that don't land on a real subview (rail/strip/panel) fall
/// through to the live game beneath, so the game stays fully usable while the dock is up.
@interface PassthroughView : UIView
@end
@implementation PassthroughView
- (UIView *)hitTest:(CGPoint)point withEvent:(UIEvent *)event {
    UIView *v = [super hitTest:point withEvent:event];
    return v == self ? nil : v;
}
@end

// Native, reflowed plugin screens (data via the bridges). Defined at the bottom of this file.
@interface QuestHelperViewController : UIViewController
@end
@interface WorldSwitcherViewController : UIViewController
@end
@interface XpTrackerViewController : UIViewController
@end

// §3.3 redesign: the labelled/searchable/favourites grid launcher. Defined at the bottom of this file.
@interface MUIPluginGrid : UIViewController
- (instancetype)initWithPanels:(NSArray<NSDictionary *> *)panels onPick:(void (^)(NSString *tooltip))onPick;
@end

@interface PluginPanelsViewController () <UIScrollViewDelegate, UITextFieldDelegate>
@property (nonatomic, strong) UITextField *panelKbField;   // PANEL-1: hidden field mirroring a focused Swing text box
@property (nonatomic, assign) CGSize lastPanelImgSize;     // re-layout only on size change (preserve zoom)
@property (nonatomic, strong) UIScrollView *toolbar;       // legacy: horizontal text-pill strip
@property (nonatomic, strong) UIScrollView *rail;          // vertical plugin-icon rail (right edge)
@property (nonatomic, strong) NSLayoutConstraint *railHeight; // sized to content, clamped to the clear band
@property (nonatomic, strong) UIView *panelContainer;      // docked: collapsible inline panel column
@property (nonatomic, strong) NSLayoutConstraint *panelTrailRail;  // panel trailing -> rail leading (rail shown)
@property (nonatomic, strong) NSLayoutConstraint *panelTrailEdge;  // panel trailing -> screen edge (rail hidden)
@property (nonatomic, strong) UIScrollView *panelScroll;   // vertical scroll of the rendered panel
@property (nonatomic, strong) UIImageView *panelView;
@property (nonatomic, strong) UILabel *emptyLabel;
@property (nonatomic, strong) NSArray<NSDictionary *> *panels;
@property (nonatomic, copy) NSString *panelsSignature;     // detect nav-button changes (toggles/new plugins)
@property (nonatomic, assign) NSInteger currentIndex;
@property (nonatomic, strong) CADisplayLink *refresh;
@property (nonatomic, assign) int lastSeq;
@property (nonatomic, assign) int frameCounter;
@property (nonatomic, assign) CGFloat panelNativeW;
@property (nonatomic, strong) NSMutableArray<UIButton *> *railButtons;
@property (nonatomic, strong) NSMutableArray<UIButton *> *toolbarButtons; // legacy
// Modal-drawer chrome.
@property (nonatomic, strong) UIView *dimView;
@property (nonatomic, strong) UIView *drawer;
@property (nonatomic, strong) UILabel *titleLabel;
@end

@implementation PluginPanelsViewController

- (instancetype)init {
    if ((self = [super init])) {
        _currentIndex = -1; _lastSeq = -1; _panelNativeW = 242; _drawerMode = YES;
    }
    return self;
}

- (void)loadView {
    if (self.docked) {
        self.view = [[PassthroughView alloc] init];   // game-usable overlay
    } else {
        [super loadView];
    }
}

- (void)viewDidLoad {
    [super viewDidLoad];
    if (self.docked) {
        [self setupDocked];
        [self loadPanels];
        [self rebuildSelectors];               // start collapsed; selectors populate when panels appear
    } else if (self.drawerMode) {
        [self setupDrawer];
        [self loadPanels];
        [self buildRail];
        [self startOnFirstPanel];
    } else {
        [self setupLegacy];
        [self loadPanels];
        [self buildLegacyToolbar];
        [self startOnFirstPanel];
    }

    self.refresh = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick)];
    self.refresh.preferredFramesPerSecond = 15;
    [self.refresh addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
}

- (void)startOnFirstPanel {
    if (self.panels.count > 0) {
        [self selectIndex:0];
    } else {
        self.emptyLabel.text = @"No plugins with panels are enabled.";
    }
}

#pragma mark - Docked layout (persistent dock on the game screen)

- (void)setupDocked {
    self.view.backgroundColor = [UIColor clearColor];

    // §3.5 step 3: default to the rail HIDDEN (⊞ FAB grid is the entry); a debug long-press on the FAB flips it.
    NSUserDefaults *ud = [NSUserDefaults standardUserDefaults];
    _railHidden = ([ud objectForKey:@"rightBarRailHidden"] == nil) ? NO : [ud boolForKey:@"rightBarRailHidden"];

    self.rail = [[UIScrollView alloc] init];
    self.rail.translatesAutoresizingMaskIntoConstraints = NO;
    self.rail.showsVerticalScrollIndicator = NO;
    self.rail.backgroundColor = [UIColor colorWithRed:40/255.0 green:40/255.0 blue:40/255.0 alpha:0.96]; // RuneLite DARK_GRAY
    self.rail.layer.cornerRadius = 10;
    [self.view addSubview:self.rail];

    // Collapsible inline panel column (hidden until an icon is tapped). Game stays visible to its left.
    self.panelContainer = [[UIView alloc] init];
    self.panelContainer.translatesAutoresizingMaskIntoConstraints = NO;
    self.panelContainer.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:0.98];
    self.panelContainer.layer.cornerRadius = 8;
    self.panelContainer.clipsToBounds = YES;
    self.panelContainer.hidden = YES;
    [self.view addSubview:self.panelContainer];

    self.titleLabel = [[UILabel alloc] init];
    self.titleLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    self.titleLabel.textColor = [UIColor whiteColor];
    [self.panelContainer addSubview:self.titleLabel];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"✕" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont boldSystemFontOfSize:18];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [close addTarget:self action:@selector(collapsePanel) forControlEvents:UIControlEventTouchUpInside];
    [self.panelContainer addSubview:close];

    UIView *sep = [[UIView alloc] init];
    sep.translatesAutoresizingMaskIntoConstraints = NO;
    sep.backgroundColor = [UIColor colorWithWhite:1 alpha:0.08];
    [self.panelContainer addSubview:sep];

    self.panelScroll = [[UIScrollView alloc] init];
    self.panelScroll.translatesAutoresizingMaskIntoConstraints = NO;
    self.panelScroll.backgroundColor = [UIColor clearColor];
    [self.panelContainer addSubview:self.panelScroll];
    [self buildPanelViewInto:self.panelScroll];

    self.railHeight = [self.rail.heightAnchor constraintEqualToConstant:200];
    self.railHeight.priority = UILayoutPriorityDefaultLow;          // sized to content, clamped by the band
    NSLayoutConstraint *railCenter = [self.rail.centerYAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.centerYAnchor];
    railCenter.priority = UILayoutPriorityDefaultHigh;             // centre in the clear band when it fits

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        // Right rail: floats in the outer strip just off the screen edge, to the RIGHT of the native tab stones.
        // Top/bottom are clamped OUT of the rounded-corner radius (kRailCornerClear, measured from the PHYSICAL
        // edges) so the curve never clips the bar; vertically centred; scrolls if the plugin count overflows.
        [self.rail.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-5],
        [self.rail.topAnchor constraintGreaterThanOrEqualToAnchor:self.view.topAnchor constant:kRailCornerClear],
        [self.rail.bottomAnchor constraintLessThanOrEqualToAnchor:self.view.bottomAnchor constant:-kRailCornerClear],
        [self.rail.widthAnchor constraintEqualToConstant:kRailWidth],
        railCenter,
        self.railHeight,

        // Inline panel column: left of the rail (over the game world, not the native tabs), full clear height.
        // (Trailing is one of two swappable constraints set up below — to the rail when shown, else the edge.)
        [self.panelContainer.topAnchor constraintEqualToAnchor:sg.topAnchor constant:6],
        [self.panelContainer.bottomAnchor constraintEqualToAnchor:sg.bottomAnchor constant:-6],
        [self.panelContainer.widthAnchor constraintEqualToConstant:kDockPanelW],

        [self.titleLabel.leadingAnchor constraintEqualToAnchor:self.panelContainer.leadingAnchor constant:12],
        [self.titleLabel.topAnchor constraintEqualToAnchor:self.panelContainer.topAnchor constant:10],
        [close.centerYAnchor constraintEqualToAnchor:self.titleLabel.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:self.panelContainer.trailingAnchor constant:-12],
        [self.titleLabel.trailingAnchor constraintLessThanOrEqualToAnchor:close.leadingAnchor constant:-8],
        [sep.topAnchor constraintEqualToAnchor:self.panelContainer.topAnchor constant:kTitleBarHeight],
        [sep.leadingAnchor constraintEqualToAnchor:self.panelContainer.leadingAnchor],
        [sep.trailingAnchor constraintEqualToAnchor:self.panelContainer.trailingAnchor],
        [sep.heightAnchor constraintEqualToConstant:1],
        [self.panelScroll.topAnchor constraintEqualToAnchor:sep.bottomAnchor],
        [self.panelScroll.leadingAnchor constraintEqualToAnchor:self.panelContainer.leadingAnchor constant:6],
        [self.panelScroll.trailingAnchor constraintEqualToAnchor:self.panelContainer.trailingAnchor constant:-6],
        [self.panelScroll.bottomAnchor constraintEqualToAnchor:self.panelContainer.bottomAnchor constant:-6],
    ]];

    // Swappable panel trailing: anchored to the rail's leading edge when the rail is shown, else to the screen
    // edge so the panel reclaims the rail strip when the rail is hidden (FAB-only mode). applyRailVisibility picks.
    self.panelTrailRail = [self.panelContainer.trailingAnchor constraintEqualToAnchor:self.rail.leadingAnchor constant:-6];
    self.panelTrailEdge = [self.panelContainer.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-6];
    [self applyRailVisibility];
}

// §3.5 step 3: show the rail only when it's not hidden AND there are panels; otherwise the ⊞ FAB is the entry and
// the inline panel stretches to the screen edge (reclaiming the rail strip). Idempotent; safe to call any time.
- (void)applyRailVisibility {
    BOOL railVisible = !self.railHidden && self.panels.count > 0;
    self.rail.hidden = !railVisible;
    self.panelTrailRail.active = railVisible;
    self.panelTrailEdge.active = !railVisible;
}

- (void)setRailHidden:(BOOL)railHidden {
    _railHidden = railHidden;
    if (self.docked && self.rail) [self applyRailVisibility];
}

// (Re)build the rail + bottom strip from the current panel list. Called on first load and whenever the nav
// buttons change (a plugin toggled on/off, or the client finished registering them after boot).
- (void)rebuildSelectors {
    for (UIButton *b in self.railButtons) [b removeFromSuperview];
    self.railButtons = [NSMutableArray array];

    CGFloat y = 4;
    for (NSUInteger i = 0; i < self.panels.count; i++) {
        UIButton *rb = [self makeIconButton:self.panels[i] index:i];
        rb.frame = CGRectMake(3, y, kRailWidth - 6, kRailButtonH - 4);
        [self.rail addSubview:rb];
        [self.railButtons addObject:rb];
        y += kRailButtonH;
    }
    self.rail.contentSize = CGSizeMake(kRailWidth, y + 4);
    self.railHeight.constant = y + 4;          // sized to content; clamped to the clear band, then scrolls

    // Show the rail only when not hidden (§3.5 step 3) and there are panels; else the ⊞ FAB grid is the entry.
    [self applyRailVisibility];

    if (self.panels.count == 0) {
        [self collapsePanel];
    } else if (self.currentIndex >= (NSInteger)self.panels.count) {
        [self collapsePanel];
    } else if (self.currentIndex >= 0) {
        [self highlightSelectors];             // keep showing the open panel
    }
}

- (UIButton *)makeIconButton:(NSDictionary *)p index:(NSUInteger)i {
    UIButton *b = [UIButton buttonWithType:UIButtonTypeCustom];
    b.layer.cornerRadius = 6;
    b.tag = (NSInteger)i;
    b.adjustsImageWhenHighlighted = NO;
    UIImage *icon = [self decodeIcon:p[@"iconPng"]];
    if (icon) {
        [b setImage:icon forState:UIControlStateNormal];
        b.imageView.contentMode = UIViewContentModeScaleAspectFit;
        b.contentEdgeInsets = UIEdgeInsetsMake(7, 7, 7, 7);
    } else {
        NSString *t = [p[@"tooltip"] description];
        [b setTitle:(t.length ? [[t substringToIndex:1] uppercaseString] : @"?") forState:UIControlStateNormal];
        b.titleLabel.font = [UIFont boldSystemFontOfSize:16];
        [b setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    }
    [b addTarget:self action:@selector(onSelectorButton:) forControlEvents:UIControlEventTouchUpInside];
    return b;
}

- (void)highlightSelectors {
    UIColor *on = [UIColor colorWithRed:0.20 green:0.45 blue:0.22 alpha:1.0];
    for (UIButton *b in self.railButtons) b.backgroundColor = (b.tag == self.currentIndex) ? on : [UIColor clearColor];
}

- (void)collapsePanel {
    self.currentIndex = -1;
    self.panelContainer.hidden = YES;
    [self.panelKbField resignFirstResponder];   // PANEL-1: drop the soft keyboard with the panel
    [self highlightSelectors];
    [JVMLauncher mobileClosePanel];
    [JVMLauncher mobileFlushConfig];
}

#pragma mark - Modal drawer layout (A/B fallback via the plugins menu)

- (void)buildRail {
    self.railButtons = [NSMutableArray array];
    CGFloat y = 4;
    for (NSUInteger i = 0; i < self.panels.count; i++) {
        UIButton *b = [self makeIconButton:self.panels[i] index:i];
        b.frame = CGRectMake(3, y, kRailWidth - 6, kRailButtonH - 4);
        [self.rail addSubview:b];
        [self.railButtons addObject:b];
        y += kRailButtonH;
    }
    self.rail.contentSize = CGSizeMake(kRailWidth, y + 4);
}

- (void)setupDrawer {
    self.view.backgroundColor = [UIColor clearColor];

    self.dimView = [[UIView alloc] init];
    self.dimView.translatesAutoresizingMaskIntoConstraints = NO;
    self.dimView.backgroundColor = [UIColor colorWithWhite:0 alpha:0.45];
    [self.view addSubview:self.dimView];
    UITapGestureRecognizer *dismissTap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onDone)];
    [self.dimView addGestureRecognizer:dismissTap];

    self.drawer = [[UIView alloc] init];
    self.drawer.translatesAutoresizingMaskIntoConstraints = NO;
    self.drawer.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];
    self.drawer.layer.shadowColor = [UIColor blackColor].CGColor;
    self.drawer.layer.shadowOffset = CGSizeMake(-3, 0);
    self.drawer.layer.shadowRadius = 8;
    self.drawer.layer.shadowOpacity = 0.5;
    [self.view addSubview:self.drawer];

    self.rail = [[UIScrollView alloc] init];
    self.rail.translatesAutoresizingMaskIntoConstraints = NO;
    self.rail.showsVerticalScrollIndicator = NO;
    self.rail.backgroundColor = [UIColor colorWithWhite:0 alpha:0.22];
    [self.drawer addSubview:self.rail];

    UIView *railSep = [[UIView alloc] init];
    railSep.translatesAutoresizingMaskIntoConstraints = NO;
    railSep.backgroundColor = [UIColor colorWithWhite:1 alpha:0.08];
    [self.drawer addSubview:railSep];

    self.titleLabel = [[UILabel alloc] init];
    self.titleLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    self.titleLabel.textColor = [UIColor whiteColor];
    self.titleLabel.text = @"Plugin Panels";
    [self.drawer addSubview:self.titleLabel];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"✕" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont boldSystemFontOfSize:18];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [close addTarget:self action:@selector(onDone) forControlEvents:UIControlEventTouchUpInside];
    [self.drawer addSubview:close];

    UIView *titleSep = [[UIView alloc] init];
    titleSep.translatesAutoresizingMaskIntoConstraints = NO;
    titleSep.backgroundColor = [UIColor colorWithWhite:1 alpha:0.08];
    [self.drawer addSubview:titleSep];

    self.panelScroll = [[UIScrollView alloc] init];
    self.panelScroll.translatesAutoresizingMaskIntoConstraints = NO;
    self.panelScroll.backgroundColor = [UIColor clearColor];
    [self.drawer addSubview:self.panelScroll];
    [self buildPanelViewInto:self.panelScroll];

    self.emptyLabel = [[UILabel alloc] init];
    self.emptyLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.emptyLabel.text = @"Loading panels…";
    self.emptyLabel.textColor = [UIColor colorWithWhite:0.7 alpha:1.0];
    self.emptyLabel.textAlignment = NSTextAlignmentCenter;
    self.emptyLabel.numberOfLines = 0;
    [self.drawer addSubview:self.emptyLabel];

    UILayoutGuide *dg = self.drawer.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [self.dimView.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.dimView.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.dimView.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.dimView.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.drawer.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.drawer.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.drawer.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.drawer.widthAnchor constraintEqualToConstant:kDrawerWidth],
        [self.rail.topAnchor constraintEqualToAnchor:dg.topAnchor],
        [self.rail.bottomAnchor constraintEqualToAnchor:self.drawer.bottomAnchor],
        [self.rail.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor],
        [self.rail.widthAnchor constraintEqualToConstant:kRailWidth],
        [railSep.topAnchor constraintEqualToAnchor:self.drawer.topAnchor],
        [railSep.bottomAnchor constraintEqualToAnchor:self.drawer.bottomAnchor],
        [railSep.trailingAnchor constraintEqualToAnchor:self.rail.leadingAnchor],
        [railSep.widthAnchor constraintEqualToConstant:1],
        [self.titleLabel.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:kDrawerHInset],
        [self.titleLabel.topAnchor constraintEqualToAnchor:dg.topAnchor constant:10],
        [close.centerYAnchor constraintEqualToAnchor:self.titleLabel.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:railSep.leadingAnchor constant:-10],
        [self.titleLabel.trailingAnchor constraintLessThanOrEqualToAnchor:close.leadingAnchor constant:-8],
        [titleSep.topAnchor constraintEqualToAnchor:dg.topAnchor constant:kTitleBarHeight],
        [titleSep.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor],
        [titleSep.trailingAnchor constraintEqualToAnchor:railSep.leadingAnchor],
        [titleSep.heightAnchor constraintEqualToConstant:1],
        [self.panelScroll.topAnchor constraintEqualToAnchor:titleSep.bottomAnchor],
        [self.panelScroll.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:kDrawerHInset],
        [self.panelScroll.trailingAnchor constraintEqualToAnchor:railSep.leadingAnchor constant:-6],
        [self.panelScroll.bottomAnchor constraintEqualToAnchor:dg.bottomAnchor constant:-8],
        [self.emptyLabel.centerXAnchor constraintEqualToAnchor:self.panelScroll.centerXAnchor],
        [self.emptyLabel.centerYAnchor constraintEqualToAnchor:self.panelScroll.centerYAnchor],
        [self.emptyLabel.widthAnchor constraintEqualToConstant:kDrawerWidth - kRailWidth - 2 * kDrawerHInset - 16],
    ]];
}

#pragma mark - Legacy layout (centered fullscreen — A/B fallback)

- (void)setupLegacy {
    self.view.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Plugin Panels";
    title.font = [UIFont boldSystemFontOfSize:18];
    title.textColor = [UIColor whiteColor];
    [self.view addSubview:title];

    UIButton *done = [UIButton buttonWithType:UIButtonTypeSystem];
    done.translatesAutoresizingMaskIntoConstraints = NO;
    [done setTitle:@"Done" forState:UIControlStateNormal];
    done.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    done.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [done addTarget:self action:@selector(onDone) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:done];

    self.toolbar = [[UIScrollView alloc] init];
    self.toolbar.translatesAutoresizingMaskIntoConstraints = NO;
    self.toolbar.showsHorizontalScrollIndicator = NO;
    self.toolbar.backgroundColor = [UIColor colorWithWhite:1 alpha:0.05];
    [self.view addSubview:self.toolbar];

    self.panelScroll = [[UIScrollView alloc] init];
    self.panelScroll.translatesAutoresizingMaskIntoConstraints = NO;
    self.panelScroll.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];
    [self.view addSubview:self.panelScroll];
    [self buildPanelViewInto:self.panelScroll];

    self.emptyLabel = [[UILabel alloc] init];
    self.emptyLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.emptyLabel.text = @"Loading panels…";
    self.emptyLabel.textColor = [UIColor colorWithWhite:0.7 alpha:1.0];
    self.emptyLabel.textAlignment = NSTextAlignmentCenter;
    [self.view addSubview:self.emptyLabel];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [title.topAnchor constraintEqualToAnchor:sg.topAnchor constant:14],
        [title.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:20],
        [done.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [done.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-20],
        [self.toolbar.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:8],
        [self.toolbar.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor],
        [self.toolbar.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor],
        [self.toolbar.heightAnchor constraintEqualToConstant:44],
        [self.panelScroll.topAnchor constraintEqualToAnchor:self.toolbar.bottomAnchor constant:6],
        [self.panelScroll.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:8],
        [self.panelScroll.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-8],
        [self.panelScroll.bottomAnchor constraintEqualToAnchor:sg.bottomAnchor constant:-8],
        [self.emptyLabel.centerXAnchor constraintEqualToAnchor:self.panelScroll.centerXAnchor],
        [self.emptyLabel.centerYAnchor constraintEqualToAnchor:self.panelScroll.centerYAnchor],
    ]];
}

- (void)buildLegacyToolbar {
    self.toolbarButtons = [NSMutableArray array];
    CGFloat x = 8;
    const CGFloat bw = 116, bh = 34, gap = 6;
    for (NSUInteger i = 0; i < self.panels.count; i++) {
        UIButton *b = [UIButton buttonWithType:UIButtonTypeSystem];
        b.frame = CGRectMake(x, 5, bw, bh);
        [b setTitle:[self.panels[i][@"tooltip"] description] forState:UIControlStateNormal];
        b.titleLabel.font = [UIFont systemFontOfSize:14];
        b.titleLabel.adjustsFontSizeToFitWidth = YES;
        b.titleLabel.minimumScaleFactor = 0.7;
        b.tintColor = [UIColor whiteColor];
        b.backgroundColor = [UIColor colorWithWhite:1 alpha:0.10];
        b.layer.cornerRadius = 7;
        b.tag = (NSInteger)i;
        [b addTarget:self action:@selector(onSelectorButton:) forControlEvents:UIControlEventTouchUpInside];
        [self.toolbar addSubview:b];
        [self.toolbarButtons addObject:b];
        x += bw + gap;
    }
    self.toolbar.contentSize = CGSizeMake(x + 2, 44);
}

#pragma mark - Shared

- (void)loadPanels {
    NSString *json = [JVMLauncher mobileListPanels] ?: @"[]";
    self.panelsSignature = json;
    NSArray *arr = nil;
    id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    if ([parsed isKindOfClass:[NSArray class]]) arr = parsed;
    self.panels = arr ?: @[];
}

- (void)buildPanelViewInto:(UIScrollView *)scroll {
    self.panelView = [[UIImageView alloc] init];
    self.panelView.userInteractionEnabled = YES;
    self.panelView.contentMode = UIViewContentModeScaleToFill;
    self.panelView.layer.magnificationFilter = kCAFilterTrilinear;
    [scroll addSubview:self.panelView];
    // Pinch-to-zoom + pan: the rendered desktop panel is dense, so let the user zoom in to read any part. The
    // buffer is supersampled, so zooming in stays crisp. viewForZooming returns panelView (its only subview).
    scroll.delegate = self;
    scroll.minimumZoomScale = 1.0;
    scroll.maximumZoomScale = 4.0;
    scroll.bouncesZoom = YES;
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onPanelTap:)];
    [self.panelView addGestureRecognizer:tap];
    // PANEL-2: long-press = a right-click (BUTTON3) into the panel's Swing tree (context handlers, e.g. reset).
    UILongPressGestureRecognizer *lp = [[UILongPressGestureRecognizer alloc] initWithTarget:self
                                                                                     action:@selector(onPanelLongPress:)];
    [self.panelView addGestureRecognizer:lp];

    // PANEL-1: a hidden text field. When a panel tap focuses a Swing text box, it becomes first responder (raising
    // the soft keyboard) and mirrors its text into the Swing field on every change.
    if (!self.panelKbField) {
        self.panelKbField = [[UITextField alloc] initWithFrame:CGRectMake(-300, -300, 10, 10)];
        self.panelKbField.delegate = self;
        self.panelKbField.autocorrectionType = UITextAutocorrectionTypeNo;
        self.panelKbField.autocapitalizationType = UITextAutocapitalizationTypeNone;
        self.panelKbField.spellCheckingType = UITextSpellCheckingTypeNo;
        self.panelKbField.smartQuotesType = UITextSmartQuotesTypeNo;
        self.panelKbField.smartDashesType = UITextSmartDashesTypeNo;
        self.panelKbField.smartInsertDeleteType = UITextSmartInsertDeleteTypeNo;
        self.panelKbField.keyboardType = UIKeyboardTypeASCIICapable;
        self.panelKbField.keyboardAppearance = UIKeyboardAppearanceDark;
        self.panelKbField.returnKeyType = UIReturnKeyDone;
        [self.panelKbField addTarget:self action:@selector(onPanelKbChanged)
                    forControlEvents:UIControlEventEditingChanged];
        [self.view addSubview:self.panelKbField];
    }
}

// Buffer-pixel coords for a gesture on panelView (shared by tap + long-press).
- (CGPoint)panelBufferPointFor:(UIGestureRecognizer *)g {
    CGPoint loc = [g locationInView:self.panelView];
    CGFloat s = self.panelNativeW / self.panelView.bounds.size.width;
    return CGPointMake(lround(loc.x * s), lround(loc.y * s));
}

- (void)onPanelLongPress:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    if (self.currentIndex < 0 || self.panelView.bounds.size.width < 1) return;
    CGPoint p = [self panelBufferPointFor:g];
    [JVMLauncher mobilePanelLongPressX:(int)p.x y:(int)p.y];
}

- (BOOL)panelKeyboardActive {
    return self.panelKbField.isFirstResponder;
}

// The hidden field changed: mirror its whole text into the focused Swing field (live-filter search boxes react).
- (void)onPanelKbChanged {
    [JVMLauncher mobileSetPanelText:(self.panelKbField.text ?: @"")];
}

- (BOOL)textFieldShouldReturn:(UITextField *)textField {
    [JVMLauncher mobilePanelKey:10];   // VK_ENTER — submit (search fields that act on Enter)
    [textField resignFirstResponder];
    return NO;
}

- (UIView *)viewForZoomingInScrollView:(UIScrollView *)scrollView {
    return self.panelView;
}

- (nullable UIImage *)decodeIcon:(id)base64 {
    if (![base64 isKindOfClass:[NSString class]] || [(NSString *)base64 length] == 0) return nil;
    NSData *d = [[NSData alloc] initWithBase64EncodedString:base64 options:0];
    return d ? [UIImage imageWithData:d] : nil;
}

- (void)onSelectorButton:(UIButton *)b {
    [self openPanelAtIndex:b.tag fromGrid:NO];
}

// §3.3: the FAB-launched grid. Reuses the live panel list (label + icon + pinned) and routes a tile back through
// the same openPanelAtIndex fork (native Quest Helper / World Switcher, else the rendered panel).
- (void)presentGridLauncher {
    if (self.presentedViewController) return;
    __weak typeof(self) ws = self;
    MUIPluginGrid *grid = [[MUIPluginGrid alloc] initWithPanels:(self.panels ?: @[]) onPick:^(NSString *tooltip) {
        NSInteger idx = [ws indexForPanelTooltip:tooltip];
        if (idx >= 0) [ws openPanelAtIndex:idx fromGrid:YES];
    }];
    if (@available(iOS 15.0, *)) {
        grid.modalPresentationStyle = UIModalPresentationPageSheet;
        UISheetPresentationController *sheet = grid.sheetPresentationController;
        sheet.detents = @[UISheetPresentationControllerDetent.mediumDetent,
                          UISheetPresentationControllerDetent.largeDetent];
        sheet.prefersGrabberVisible = YES;
    } else {
        grid.modalPresentationStyle = UIModalPresentationFormSheet;
    }
    [self presentViewController:grid animated:YES completion:nil];
}

// Open the panel at `index`: route Quest Helper / World Switcher to their native reflowed screens (when the
// reflection bridge can reach the plugin), else open the generic rendered surface. Shared by the rail buttons
// and the grid launcher (§3.3). `fromGrid` skips the tap-active-icon-to-close shortcut (the grid always opens).
- (void)openPanelAtIndex:(NSInteger)index fromGrid:(BOOL)fromGrid {
    if (index < 0 || index >= (NSInteger)self.panels.count) return;
    NSString *tip = [self.panels[index][@"tooltip"] description];
    // The native reflowed Quest Helper / World Switcher screens are docked-mode only (the grid only runs docked).
    // The drawer/legacy A-B fallback modes keep showing the rendered panel, matching the prior onSelectorButton.
    if (self.docked) {
        if ([tip isEqualToString:@"Quest Helper"] && [JVMLauncher questAvailable]) {
            QuestHelperViewController *qh = [[QuestHelperViewController alloc] init];
            qh.modalPresentationStyle = UIModalPresentationOverFullScreen;
            qh.modalTransitionStyle = UIModalTransitionStyleCrossDissolve;
            [self presentViewController:qh animated:YES completion:nil];
            return;
        }
        if ([tip isEqualToString:@"World Switcher"] && [JVMLauncher worldAvailable]) {
            WorldSwitcherViewController *ws = [[WorldSwitcherViewController alloc] init];
            ws.modalPresentationStyle = UIModalPresentationOverFullScreen;
            ws.modalTransitionStyle = UIModalTransitionStyleCrossDissolve;
            [self presentViewController:ws animated:YES completion:nil];
            return;
        }
        if ([tip isEqualToString:@"XP Tracker"] && [JVMLauncher xpTrackerAvailable]) {
            XpTrackerViewController *xp = [[XpTrackerViewController alloc] init];
            xp.modalPresentationStyle = UIModalPresentationOverFullScreen;
            xp.modalTransitionStyle = UIModalTransitionStyleCrossDissolve;
            [self presentViewController:xp animated:YES completion:nil];
            return;
        }
        if (!fromGrid && index == self.currentIndex) {
            [self collapsePanel];          // tapping the active rail icon closes the panel
            return;
        }
    }
    [self selectIndex:index];
}

// Live array position of the panel with this tooltip, or -1. The grid snapshots the panel list, so it resolves
// its pick by stable name (the dock's tick may have rebuilt self.panels between present and pick).
- (NSInteger)indexForPanelTooltip:(NSString *)tooltip {
    for (NSUInteger i = 0; i < self.panels.count; i++) {
        if ([[self.panels[i][@"tooltip"] description] isEqualToString:tooltip]) return (NSInteger)i;
    }
    return -1;
}

- (void)selectIndex:(NSInteger)index {
    if (index < 0 || index >= (NSInteger)self.panels.count) return;
    self.currentIndex = index;
    self.lastSeq = -1;                         // force a re-pull
    self.lastPanelImgSize = CGSizeZero;        // re-layout + reset zoom for the newly selected panel
    if (self.emptyLabel) self.emptyLabel.text = @"";
    self.titleLabel.text = [self.panels[index][@"tooltip"] description];
    if (self.docked) {
        self.panelContainer.hidden = NO;
        [self highlightSelectors];
    } else {
        UIColor *on = [UIColor colorWithRed:0.20 green:0.45 blue:0.22 alpha:1.0];
        UIColor *off = self.drawerMode ? [UIColor clearColor] : [UIColor colorWithWhite:1 alpha:0.10];
        for (UIButton *b in self.railButtons)    b.backgroundColor = (b.tag == index) ? on : off;
        for (UIButton *b in self.toolbarButtons) b.backgroundColor = (b.tag == index) ? on : off;
    }
    [JVMLauncher mobileOpenPanel:(int)index];
}

- (void)tick {
    // Docked: cheaply poll the panel list every ~1.5s so the rail/strip populate once the client is up and
    // track plugin toggles. (The list is empty for a while after launch.)
    if (self.docked && (++self.frameCounter % 22 == 0)) {
        NSString *json = [JVMLauncher mobileListPanels] ?: @"[]";
        if (![json isEqualToString:self.panelsSignature]) {
            self.panelsSignature = json;
            NSArray *arr = nil;
            id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
            if ([parsed isKindOfClass:[NSArray class]]) arr = parsed;
            self.panels = arr ?: @[];
            [self rebuildSelectors];
        }
    }

    if (self.currentIndex < 0) return;
    if (self.docked && self.panelContainer.hidden) return;
    int seq = [JVMLauncher mobilePanelFrameSeq];
    if (seq == self.lastSeq) return;
    UIImage *img = [JVMLauncher mobilePanelImage];
    if (!img || img.size.width < 1) return;
    self.lastSeq = seq;
    self.panelNativeW = img.size.width;
    self.panelView.image = img;                 // refresh content every frame (live updates)
    // Only (re)compute the base layout when the buffer SIZE changes — re-laying out every repaint would reset
    // the user's pinch-zoom/scroll. Same panel keeps the same buffer dimensions across repaints.
    if (CGSizeEqualToSize(img.size, self.lastPanelImgSize)) return;
    self.lastPanelImgSize = img.size;
    CGFloat vw = self.panelScroll.bounds.size.width;
    if (vw < 1) { self.lastPanelImgSize = CGSizeZero; return; }
    CGFloat oneToOne = img.size.width / UIScreen.mainScreen.scale;
    // Dock: fill the panel column at a consistent width regardless of the buffer's supersample scale (so a
    // tall panel rendered at a lower scale doesn't display tiny). Legacy fullscreen: cap at 1:1 to avoid
    // blowing a 242-wide panel across the whole screen. Either way, pinch-zoom reads the detail.
    CGFloat dispW = self.docked ? vw : MIN(vw, oneToOne);
    CGFloat scale = dispW / img.size.width;
    CGFloat dispH = img.size.height * scale;
    self.panelScroll.zoomScale = 1.0;           // new panel/size → reset zoom
    self.panelView.frame = CGRectMake(0, 0, dispW, dispH);
    self.panelScroll.contentSize = CGSizeMake(dispW, dispH);
    // Centre horizontally when the panel is narrower than the viewport (legacy fullscreen); 0 in the dock.
    CGFloat sideInset = MAX(0.0, floor((vw - dispW) / 2.0));
    self.panelScroll.contentInset = UIEdgeInsetsMake(0, sideInset, 0, sideInset);
}

- (void)onPanelTap:(UITapGestureRecognizer *)tap {
    if (self.currentIndex < 0 || self.panelView.bounds.size.width < 1) return;
    CGPoint p = [self panelBufferPointFor:tap];
    [JVMLauncher mobilePanelClickX:(int)p.x y:(int)p.y];
    // PANEL-1: the click dispatch is async on the Swing EDT (which is also supersampling the panel every ~500ms),
    // so poll a few times before deciding — a single fixed delay can race the busy EDT and miss the focus.
    [self pollPanelKeyboard:0 forIndex:self.currentIndex];
}

// Up to 5 polls (~80ms apart) of whether the last panel tap focused a text field: raise+prefill the keyboard on
// the first true; if all are false, resign once. Bails if the panel was switched/closed mid-poll.
- (void)pollPanelKeyboard:(int)attempt forIndex:(NSInteger)index {
    if (self.currentIndex != index || self.currentIndex < 0) return;   // panel switched/closed → abandon
    if ([JVMLauncher mobilePanelTextActive]) {
        self.panelKbField.text = [JVMLauncher mobilePanelText] ?: @"";
        if (![self.panelKbField isFirstResponder]) [self.panelKbField becomeFirstResponder];
        return;
    }
    if (attempt >= 4) {
        if ([self.panelKbField isFirstResponder]) [self.panelKbField resignFirstResponder];
        return;
    }
    __weak typeof(self) ws = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.08 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [ws pollPanelKeyboard:attempt + 1 forIndex:index];
    });
}

- (void)onDone {
    [self.refresh invalidate];
    self.refresh = nil;
    [self.panelKbField resignFirstResponder];
    [JVMLauncher mobileClosePanel];
    [JVMLauncher mobileFlushConfig];
    [self dismissViewControllerAnimated:YES completion:nil];
}

@end

#pragma mark - Native Quest Helper (reflowed)

// A genuinely native, reflowed Quest Helper: a searchable/filterable quest list (status pills) that starts a
// helper on tap, plus a "Current quest" view showing the active quest's current-step text and requirement
// checklist. Data comes from QuestHelperBridge (reflection into the external plugin) via JVMLauncher; the
// in-world step arrows still come from the plugin's own overlays on the game canvas.
@interface QuestHelperViewController () <UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate>
@property (nonatomic, strong) UIView *drawer;
@property (nonatomic, strong) UISegmentedControl *modeSeg;     // Browse | Current
@property (nonatomic, strong) UIView *browseView;
@property (nonatomic, strong) UIView *currentView;
@property (nonatomic, strong) UISearchBar *search;
@property (nonatomic, strong) UISegmentedControl *statusSeg;   // All | In progress | Not started | Done
@property (nonatomic, strong) UITableView *table;
@property (nonatomic, strong) UILabel *currentNameLabel;
@property (nonatomic, strong) UITextView *detailText;
@property (nonatomic, strong) UIButton *stopButton;
@property (nonatomic, strong) NSArray<NSDictionary *> *allQuests;
@property (nonatomic, strong) NSArray<NSDictionary *> *filtered;
@property (nonatomic, copy)   NSString *lastListSig;
@property (nonatomic, strong) NSDictionary *activeDict;
@property (nonatomic, strong) NSTimer *timer;
@property (nonatomic, assign) BOOL didInitialMode;
@end

@implementation QuestHelperViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor clearColor];
    [JVMLauncher questSetUiOpen:YES];

    UIView *dim = [[UIView alloc] initWithFrame:self.view.bounds];
    dim.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    dim.backgroundColor = [UIColor colorWithWhite:0 alpha:0.45];
    [self.view addSubview:dim];
    [dim addGestureRecognizer:[[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onDone)]];

    self.drawer = [[UIView alloc] init];
    self.drawer.translatesAutoresizingMaskIntoConstraints = NO;
    self.drawer.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];
    [self.view addSubview:self.drawer];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Quest Helper";
    title.font = [UIFont boldSystemFontOfSize:18];
    title.textColor = [UIColor whiteColor];
    [self.drawer addSubview:title];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"✕" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont boldSystemFontOfSize:20];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [close addTarget:self action:@selector(onDone) forControlEvents:UIControlEventTouchUpInside];
    [self.drawer addSubview:close];

    self.modeSeg = [[UISegmentedControl alloc] initWithItems:@[@"Browse", @"Current quest"]];
    self.modeSeg.translatesAutoresizingMaskIntoConstraints = NO;
    self.modeSeg.selectedSegmentIndex = 0;
    [self.modeSeg addTarget:self action:@selector(onModeChanged) forControlEvents:UIControlEventValueChanged];
    [self.drawer addSubview:self.modeSeg];

    // ---- Browse view ----
    self.browseView = [[UIView alloc] init];
    self.browseView.translatesAutoresizingMaskIntoConstraints = NO;
    [self.drawer addSubview:self.browseView];

    self.search = [[UISearchBar alloc] init];
    self.search.translatesAutoresizingMaskIntoConstraints = NO;
    self.search.placeholder = @"Search quests";
    self.search.delegate = self;
    self.search.barStyle = UIBarStyleBlack;
    self.search.searchBarStyle = UISearchBarStyleMinimal;
    [self.browseView addSubview:self.search];

    self.statusSeg = [[UISegmentedControl alloc] initWithItems:@[@"All", @"In progress", @"Not started", @"Done"]];
    self.statusSeg.translatesAutoresizingMaskIntoConstraints = NO;
    self.statusSeg.selectedSegmentIndex = 0;
    [self.statusSeg addTarget:self action:@selector(applyFilter) forControlEvents:UIControlEventValueChanged];
    [self.browseView addSubview:self.statusSeg];

    self.table = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStylePlain];
    self.table.translatesAutoresizingMaskIntoConstraints = NO;
    self.table.backgroundColor = [UIColor clearColor];
    self.table.dataSource = self;
    self.table.delegate = self;
    self.table.rowHeight = 56;
    [self.browseView addSubview:self.table];

    // ---- Current view ----
    self.currentView = [[UIView alloc] init];
    self.currentView.translatesAutoresizingMaskIntoConstraints = NO;
    self.currentView.hidden = YES;
    [self.drawer addSubview:self.currentView];

    self.currentNameLabel = [[UILabel alloc] init];
    self.currentNameLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.currentNameLabel.font = [UIFont boldSystemFontOfSize:17];
    self.currentNameLabel.textColor = [UIColor colorWithRed:1.0 green:0.82 blue:0.40 alpha:1.0];
    self.currentNameLabel.numberOfLines = 0;
    [self.currentView addSubview:self.currentNameLabel];

    self.detailText = [[UITextView alloc] init];
    self.detailText.translatesAutoresizingMaskIntoConstraints = NO;
    self.detailText.editable = NO;
    self.detailText.backgroundColor = [UIColor clearColor];
    self.detailText.textColor = [UIColor colorWithWhite:0.92 alpha:1.0];
    self.detailText.font = [UIFont systemFontOfSize:15];
    [self.currentView addSubview:self.detailText];

    self.stopButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.stopButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.stopButton setTitle:@"Stop helper" forState:UIControlStateNormal];
    self.stopButton.tintColor = [UIColor colorWithRed:1.0 green:0.5 blue:0.45 alpha:1.0];
    self.stopButton.titleLabel.font = [UIFont boldSystemFontOfSize:15];
    [self.stopButton addTarget:self action:@selector(onStop) forControlEvents:UIControlEventTouchUpInside];
    [self.currentView addSubview:self.stopButton];

    UILayoutGuide *dg = self.drawer.safeAreaLayoutGuide;
    CGFloat drawerW = MIN(460.0, self.view.bounds.size.width * 0.55);
    [NSLayoutConstraint activateConstraints:@[
        [self.drawer.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.drawer.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.drawer.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.drawer.widthAnchor constraintEqualToConstant:drawerW],

        [title.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:16],
        [title.topAnchor constraintEqualToAnchor:dg.topAnchor constant:12],
        [close.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-16],

        [self.modeSeg.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:12],
        [self.modeSeg.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:14],
        [self.modeSeg.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-14],

        [self.browseView.topAnchor constraintEqualToAnchor:self.modeSeg.bottomAnchor constant:8],
        [self.browseView.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor],
        [self.browseView.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor],
        [self.browseView.bottomAnchor constraintEqualToAnchor:self.drawer.bottomAnchor],

        [self.search.topAnchor constraintEqualToAnchor:self.browseView.topAnchor],
        [self.search.leadingAnchor constraintEqualToAnchor:self.browseView.leadingAnchor constant:8],
        [self.search.trailingAnchor constraintEqualToAnchor:self.browseView.trailingAnchor constant:-8],
        [self.statusSeg.topAnchor constraintEqualToAnchor:self.search.bottomAnchor constant:4],
        [self.statusSeg.leadingAnchor constraintEqualToAnchor:self.browseView.leadingAnchor constant:14],
        [self.statusSeg.trailingAnchor constraintEqualToAnchor:self.browseView.trailingAnchor constant:-14],
        [self.table.topAnchor constraintEqualToAnchor:self.statusSeg.bottomAnchor constant:8],
        [self.table.leadingAnchor constraintEqualToAnchor:self.browseView.leadingAnchor],
        [self.table.trailingAnchor constraintEqualToAnchor:self.browseView.trailingAnchor],
        [self.table.bottomAnchor constraintEqualToAnchor:self.browseView.safeAreaLayoutGuide.bottomAnchor],

        [self.currentView.topAnchor constraintEqualToAnchor:self.modeSeg.bottomAnchor constant:12],
        [self.currentView.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:16],
        [self.currentView.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-16],
        [self.currentView.bottomAnchor constraintEqualToAnchor:dg.bottomAnchor constant:-12],

        [self.currentNameLabel.topAnchor constraintEqualToAnchor:self.currentView.topAnchor],
        [self.currentNameLabel.leadingAnchor constraintEqualToAnchor:self.currentView.leadingAnchor],
        [self.currentNameLabel.trailingAnchor constraintEqualToAnchor:self.currentView.trailingAnchor],
        [self.detailText.topAnchor constraintEqualToAnchor:self.currentNameLabel.bottomAnchor constant:8],
        [self.detailText.leadingAnchor constraintEqualToAnchor:self.currentView.leadingAnchor],
        [self.detailText.trailingAnchor constraintEqualToAnchor:self.currentView.trailingAnchor],
        [self.detailText.bottomAnchor constraintEqualToAnchor:self.stopButton.topAnchor constant:-8],
        [self.stopButton.leadingAnchor constraintEqualToAnchor:self.currentView.leadingAnchor],
        [self.stopButton.bottomAnchor constraintEqualToAnchor:self.currentView.bottomAnchor],
    ]];

    [self refresh];
    self.timer = [NSTimer scheduledTimerWithTimeInterval:1.0 target:self selector:@selector(refresh) userInfo:nil repeats:YES];
}

- (void)onModeChanged {
    BOOL browse = (self.modeSeg.selectedSegmentIndex == 0);
    self.browseView.hidden = !browse;
    self.currentView.hidden = browse;
}

- (void)refresh {
    // Quest list (reload table only when the snapshot actually changed, to avoid clobbering scroll/typing).
    NSString *lj = [JVMLauncher questListJson] ?: @"{}";
    if (![lj isEqualToString:self.lastListSig]) {
        self.lastListSig = lj;
        NSDictionary *d = [self parseObj:lj];
        id quests = d[@"quests"];
        self.allQuests = [quests isKindOfClass:[NSArray class]] ? quests : @[];
        [self applyFilter];
    }
    // Active quest.
    self.activeDict = [self parseObj:[JVMLauncher questActiveJson] ?: @"{}"];
    [self updateCurrent];
    if (!self.didInitialMode) {
        self.didInitialMode = YES;
        if ([self.activeDict[@"active"] boolValue]) {
            self.modeSeg.selectedSegmentIndex = 1;
            [self onModeChanged];
        }
    }
}

- (NSDictionary *)parseObj:(NSString *)json {
    id o = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    return [o isKindOfClass:[NSDictionary class]] ? o : @{};
}

- (void)applyFilter {
    NSString *q = self.search.text.lowercaseString;
    NSInteger seg = self.statusSeg.selectedSegmentIndex;
    NSMutableArray *out = [NSMutableArray array];
    for (NSDictionary *quest in self.allQuests) {
        NSString *name = [quest[@"name"] description];
        if (q.length && [name.lowercaseString rangeOfString:q].location == NSNotFound) continue;
        NSString *state = [quest[@"state"] description];
        BOOL ok = YES;
        if (seg == 1) ok = [state isEqualToString:@"IN_PROGRESS"];
        else if (seg == 2) ok = [state isEqualToString:@"NOT_STARTED"];
        else if (seg == 3) ok = [state isEqualToString:@"FINISHED"];
        if (ok) [out addObject:quest];
    }
    self.filtered = out;
    [self.table reloadData];
}

- (void)updateCurrent {
    BOOL active = [self.activeDict[@"active"] boolValue];
    if (!active) {
        self.currentNameLabel.text = @"No quest selected";
        self.detailText.text = @"Pick a quest in Browse and tap it to start the helper. In-world step arrows still show on the game screen.";
        self.stopButton.hidden = YES;
        return;
    }
    self.stopButton.hidden = NO;
    self.currentNameLabel.text = [self.activeDict[@"name"] description];
    NSMutableString *s = [NSMutableString string];
    for (id line in (self.activeDict[@"steps"] ?: @[])) {
        if (s.length) [s appendString:@"\n\n"];
        [s appendString:[line description]];
    }
    NSArray *items = self.activeDict[@"items"];
    if ([items isKindOfClass:[NSArray class]] && items.count) {
        [s appendString:@"\n\n— Items —"];
        for (id it in items) [s appendFormat:@"\n• %@", [it description]];
    }
    NSArray *gen = self.activeDict[@"general"];
    if ([gen isKindOfClass:[NSArray class]] && gen.count) {
        [s appendString:@"\n\n— Requirements —"];
        for (id g in gen) [s appendFormat:@"\n• %@", [g description]];
    }
    if (s.length == 0) [s appendString:@"(no current step text)"];
    self.detailText.text = s;
}

- (void)onStop {
    [JVMLauncher questStop];
}

- (void)onDone {
    [self.timer invalidate];
    self.timer = nil;
    [JVMLauncher questSetUiOpen:NO];
    [self dismissViewControllerAnimated:YES completion:nil];
}

#pragma mark UITableView

- (NSInteger)tableView:(UITableView *)tv numberOfRowsInSection:(NSInteger)s {
    return self.filtered.count;
}

- (UITableViewCell *)tableView:(UITableView *)tv cellForRowAtIndexPath:(NSIndexPath *)ip {
    UITableViewCell *cell = [tv dequeueReusableCellWithIdentifier:@"q"];
    if (!cell) {
        cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:@"q"];
        cell.backgroundColor = [UIColor clearColor];
    }
    NSDictionary *quest = self.filtered[ip.row];
    cell.textLabel.text = [quest[@"name"] description];
    cell.textLabel.textColor = [UIColor whiteColor];
    NSString *diff = [[quest[@"difficulty"] description] stringByReplacingOccurrencesOfString:@"_" withString:@" "];
    cell.detailTextLabel.text = diff.capitalizedString;
    cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.6 alpha:1.0];

    NSString *state = [quest[@"state"] description];
    UILabel *pill = [[UILabel alloc] initWithFrame:CGRectMake(0, 0, 96, 22)];
    pill.font = [UIFont boldSystemFontOfSize:11];
    pill.textAlignment = NSTextAlignmentCenter;
    pill.layer.cornerRadius = 11;
    pill.clipsToBounds = YES;
    pill.textColor = [UIColor blackColor];
    if ([state isEqualToString:@"FINISHED"]) {
        pill.text = @"Done"; pill.backgroundColor = [UIColor colorWithRed:0.45 green:0.78 blue:0.45 alpha:1.0];
    } else if ([state isEqualToString:@"IN_PROGRESS"]) {
        pill.text = @"In progress"; pill.backgroundColor = [UIColor colorWithRed:0.95 green:0.80 blue:0.35 alpha:1.0];
    } else {
        pill.text = @"Not started"; pill.backgroundColor = [UIColor colorWithWhite:0.55 alpha:1.0];
    }
    cell.accessoryView = pill;
    return cell;
}

- (void)tableView:(UITableView *)tv didSelectRowAtIndexPath:(NSIndexPath *)ip {
    [tv deselectRowAtIndexPath:ip animated:YES];
    NSString *name = [self.filtered[ip.row][@"name"] description];
    [JVMLauncher questStart:name];
    self.modeSeg.selectedSegmentIndex = 1;
    [self onModeChanged];
}

#pragma mark UISearchBar

- (void)searchBar:(UISearchBar *)sb textDidChange:(NSString *)text { [self applyFilter]; }
- (void)searchBarSearchButtonClicked:(UISearchBar *)sb { [sb resignFirstResponder]; }

@end

#pragma mark - Native World Switcher (reflowed)

// Native World Switcher: a live, filterable world list (members/F2P/PVP, player counts, current-world
// highlight) driven by WorldService via WorldSwitcherBridge; tapping a world hops to it (client thread).
@interface WorldSwitcherViewController () <UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate>
@property (nonatomic, strong) UIView *drawer;
@property (nonatomic, strong) UISearchBar *search;
@property (nonatomic, strong) UISegmentedControl *filterSeg;   // All | Members | Free | PVP
@property (nonatomic, strong) UIButton *sortRegionButton;      // WH-4/WH-5: sort + region menu
@property (nonatomic, strong) UITableView *table;
@property (nonatomic, strong) NSArray<NSDictionary *> *allWorlds;
@property (nonatomic, strong) NSArray<NSDictionary *> *filtered;
@property (nonatomic, assign) NSInteger current;
@property (nonatomic, assign) NSInteger sortMode;     // 0 World · 1 Players · 2 Activity (WH-4)
@property (nonatomic, assign) NSInteger regionCode;   // -1 All · else the location code 0/1/3/7 (WH-5)
@property (nonatomic, strong) NSTimer *timer;
@end

@implementation WorldSwitcherViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor clearColor];
    [JVMLauncher worldSetUiOpen:YES];

    UIView *dim = [[UIView alloc] initWithFrame:self.view.bounds];
    dim.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    dim.backgroundColor = [UIColor colorWithWhite:0 alpha:0.45];
    [self.view addSubview:dim];
    [dim addGestureRecognizer:[[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onDone)]];

    self.drawer = [[UIView alloc] init];
    self.drawer.translatesAutoresizingMaskIntoConstraints = NO;
    self.drawer.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];
    [self.view addSubview:self.drawer];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"World Switcher";
    title.font = [UIFont boldSystemFontOfSize:18];
    title.textColor = [UIColor whiteColor];
    [self.drawer addSubview:title];

    self.regionCode = -1;   // WH-5: all regions by default

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"✕" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont boldSystemFontOfSize:20];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [close addTarget:self action:@selector(onDone) forControlEvents:UIControlEventTouchUpInside];
    [self.drawer addSubview:close];

    // WH-3 quick-hop: previous / next world (the plugin's signature keybind action, as on-screen buttons).
    UIButton *hopPrev = [UIButton buttonWithType:UIButtonTypeSystem];
    hopPrev.translatesAutoresizingMaskIntoConstraints = NO;
    [hopPrev setTitle:@"◀" forState:UIControlStateNormal];
    hopPrev.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    hopPrev.tintColor = [UIColor whiteColor];
    [hopPrev addTarget:self action:@selector(onHopPrev) forControlEvents:UIControlEventTouchUpInside];
    [self.drawer addSubview:hopPrev];

    UIButton *hopNext = [UIButton buttonWithType:UIButtonTypeSystem];
    hopNext.translatesAutoresizingMaskIntoConstraints = NO;
    [hopNext setTitle:@"▶" forState:UIControlStateNormal];
    hopNext.titleLabel.font = [UIFont boldSystemFontOfSize:16];
    hopNext.tintColor = [UIColor whiteColor];
    [hopNext addTarget:self action:@selector(onHopNext) forControlEvents:UIControlEventTouchUpInside];
    [self.drawer addSubview:hopNext];

    // WH-4/WH-5: a sort + region menu (kept compact so the ~52%-width drawer stays uncluttered).
    self.sortRegionButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.sortRegionButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.sortRegionButton setTitle:@"⇅" forState:UIControlStateNormal];
    self.sortRegionButton.titleLabel.font = [UIFont boldSystemFontOfSize:18];
    self.sortRegionButton.tintColor = [UIColor whiteColor];
    self.sortRegionButton.showsMenuAsPrimaryAction = YES;
    [self.drawer addSubview:self.sortRegionButton];
    [self rebuildSortRegionMenu];

    self.search = [[UISearchBar alloc] init];
    self.search.translatesAutoresizingMaskIntoConstraints = NO;
    self.search.placeholder = @"World number or activity";
    self.search.delegate = self;
    self.search.searchBarStyle = UISearchBarStyleMinimal;
    self.search.keyboardType = UIKeyboardTypeNumbersAndPunctuation;
    [self.drawer addSubview:self.search];

    self.filterSeg = [[UISegmentedControl alloc] initWithItems:@[@"All", @"Members", @"Free", @"PVP"]];
    self.filterSeg.translatesAutoresizingMaskIntoConstraints = NO;
    self.filterSeg.selectedSegmentIndex = 0;
    [self.filterSeg addTarget:self action:@selector(applyFilter) forControlEvents:UIControlEventValueChanged];
    [self.drawer addSubview:self.filterSeg];

    self.table = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStylePlain];
    self.table.translatesAutoresizingMaskIntoConstraints = NO;
    self.table.backgroundColor = [UIColor clearColor];
    self.table.dataSource = self;
    self.table.delegate = self;
    self.table.rowHeight = 52;
    [self.drawer addSubview:self.table];

    UILayoutGuide *dg = self.drawer.safeAreaLayoutGuide;
    CGFloat drawerW = MIN(440.0, self.view.bounds.size.width * 0.52);
    [NSLayoutConstraint activateConstraints:@[
        [self.drawer.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.drawer.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.drawer.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.drawer.widthAnchor constraintEqualToConstant:drawerW],

        [title.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:16],
        [title.topAnchor constraintEqualToAnchor:dg.topAnchor constant:12],
        [close.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-16],
        // Header buttons, right-to-left: ✕ … ▶ ◀ ⇅
        [hopNext.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [hopNext.trailingAnchor constraintEqualToAnchor:close.leadingAnchor constant:-14],
        [hopPrev.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [hopPrev.trailingAnchor constraintEqualToAnchor:hopNext.leadingAnchor constant:-10],
        [self.sortRegionButton.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [self.sortRegionButton.trailingAnchor constraintEqualToAnchor:hopPrev.leadingAnchor constant:-14],

        [self.search.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:8],
        [self.search.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:6],
        [self.search.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-6],
        [self.filterSeg.topAnchor constraintEqualToAnchor:self.search.bottomAnchor constant:4],
        [self.filterSeg.leadingAnchor constraintEqualToAnchor:dg.leadingAnchor constant:14],
        [self.filterSeg.trailingAnchor constraintEqualToAnchor:dg.trailingAnchor constant:-14],
        [self.table.topAnchor constraintEqualToAnchor:self.filterSeg.bottomAnchor constant:8],
        [self.table.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor],
        [self.table.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor],
        [self.table.bottomAnchor constraintEqualToAnchor:dg.bottomAnchor],
    ]];

    [self refresh];
    self.timer = [NSTimer scheduledTimerWithTimeInterval:1.5 target:self selector:@selector(refresh) userInfo:nil repeats:YES];
}

- (void)refresh {
    NSString *json = [JVMLauncher worldListJson] ?: @"{}";
    id o = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    NSDictionary *d = [o isKindOfClass:[NSDictionary class]] ? o : @{};
    self.current = [d[@"current"] integerValue];
    id worlds = d[@"worlds"];
    self.allWorlds = [worlds isKindOfClass:[NSArray class]] ? worlds : @[];
    [self applyFilter];
}

- (void)applyFilter {
    NSString *q = self.search.text;
    NSInteger seg = self.filterSeg.selectedSegmentIndex;
    NSMutableArray *out = [NSMutableArray array];
    for (NSDictionary *w in self.allWorlds) {
        BOOL members = [w[@"members"] boolValue];
        BOOL pvp = [w[@"pvp"] boolValue];
        if (seg == 1 && !members) continue;
        if (seg == 2 && members) continue;
        if (seg == 3 && !pvp) continue;
        if (self.regionCode >= 0 && [w[@"location"] integerValue] != self.regionCode) continue;   // WH-5
        if (q.length) {
            NSString *idStr = [NSString stringWithFormat:@"%@", w[@"id"]];
            NSString *act = [[w[@"activity"] description] lowercaseString];
            if ([idStr rangeOfString:q].location == NSNotFound &&
                [act rangeOfString:q.lowercaseString].location == NSNotFound) continue;
        }
        [out addObject:w];
    }
    // WH-1 favourites float to the top, then WH-4 sort (World / Players desc / Activity) within each group.
    NSInteger mode = self.sortMode;
    [out sortUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
        BOOL fa = [a[@"favourite"] boolValue], fb = [b[@"favourite"] boolValue];
        if (fa != fb) return fa ? NSOrderedAscending : NSOrderedDescending;
        if (mode == 1) {   // Players — busiest first
            NSInteger pa = [a[@"players"] integerValue], pb = [b[@"players"] integerValue];
            if (pa != pb) return pa > pb ? NSOrderedAscending : NSOrderedDescending;
        } else if (mode == 2) {   // Activity (alpha), blanks last
            NSString *aa = [a[@"activity"] description] ?: @"", *ab = [b[@"activity"] description] ?: @"";
            if (aa.length == 0 && ab.length) return NSOrderedDescending;
            if (ab.length == 0 && aa.length) return NSOrderedAscending;
            NSComparisonResult r = [aa caseInsensitiveCompare:ab];
            if (r != NSOrderedSame) return r;
        }
        NSInteger ia = [a[@"id"] integerValue], ib = [b[@"id"] integerValue];   // tie-break / World mode
        return ia < ib ? NSOrderedAscending : (ia > ib ? NSOrderedDescending : NSOrderedSame);
    }];
    self.filtered = out;
    [self.table reloadData];
}

// WH-4/WH-5: the sort + region menu (checkmark reflects the current choice).
- (void)rebuildSortRegionMenu {
    __weak typeof(self) ws = self;
    UIAction *(^sortAction)(NSString *, NSInteger) = ^UIAction *(NSString *t, NSInteger m) {
        UIAction *a = [UIAction actionWithTitle:t image:nil identifier:nil handler:^(UIAction *act) {
            ws.sortMode = m; [ws rebuildSortRegionMenu]; [ws applyFilter];
        }];
        a.state = (ws.sortMode == m) ? UIMenuElementStateOn : UIMenuElementStateOff;
        return a;
    };
    UIMenu *sort = [UIMenu menuWithTitle:@"Sort by" image:nil identifier:nil
                                 options:UIMenuOptionsDisplayInline
                                children:@[sortAction(@"World", 0), sortAction(@"Players", 1), sortAction(@"Activity", 2)]];
    UIAction *(^regionAction)(NSString *, NSInteger) = ^UIAction *(NSString *t, NSInteger code) {
        UIAction *a = [UIAction actionWithTitle:t image:nil identifier:nil handler:^(UIAction *act) {
            ws.regionCode = code; [ws rebuildSortRegionMenu]; [ws applyFilter];
        }];
        a.state = (ws.regionCode == code) ? UIMenuElementStateOn : UIMenuElementStateOff;
        return a;
    };
    UIMenu *region = [UIMenu menuWithTitle:@"Region" image:nil identifier:nil
                                   options:UIMenuOptionsDisplayInline
                                  children:@[regionAction(@"All regions", -1), regionAction(@"US", 0),
                                             regionAction(@"UK", 1), regionAction(@"AUS", 3), regionAction(@"GER", 7)]];
    self.sortRegionButton.menu = [UIMenu menuWithTitle:@"" children:@[sort, region]];
}

- (void)onHopPrev { [JVMLauncher worldHopDirection:YES]; }
- (void)onHopNext { [JVMLauncher worldHopDirection:NO]; }

- (NSString *)locationLabel:(NSInteger)loc {
    switch (loc) {
        case 0: return @"US";
        case 1: return @"UK";
        case 3: return @"AUS";
        case 7: return @"GER";
        default: return @"";
    }
}

- (void)onDone {
    [self.timer invalidate];
    self.timer = nil;
    [JVMLauncher worldSetUiOpen:NO];
    [self dismissViewControllerAnimated:YES completion:nil];
}

#pragma mark UITableView

- (NSInteger)tableView:(UITableView *)tv numberOfRowsInSection:(NSInteger)s { return self.filtered.count; }

- (UITableViewCell *)tableView:(UITableView *)tv cellForRowAtIndexPath:(NSIndexPath *)ip {
    UITableViewCell *cell = [tv dequeueReusableCellWithIdentifier:@"w"];
    if (!cell) {
        cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:@"w"];
        cell.backgroundColor = [UIColor clearColor];
    }
    NSDictionary *w = self.filtered[ip.row];
    NSInteger wid = [w[@"id"] integerValue];
    BOOL isCurrent = (wid == self.current);
    NSString *act = [w[@"activity"] description];
    cell.textLabel.text = [NSString stringWithFormat:@"World %ld%@", (long)wid, isCurrent ? @"  • current" : @""];
    cell.textLabel.textColor = isCurrent ? [UIColor colorWithRed:0.45 green:0.85 blue:0.45 alpha:1.0] : [UIColor whiteColor];

    NSMutableArray *tags = [NSMutableArray array];
    [tags addObject:[w[@"members"] boolValue] ? @"Members" : @"Free"];
    NSString *locL = [self locationLabel:[w[@"location"] integerValue]];
    if (locL.length) [tags addObject:locL];
    if ([w[@"pvp"] boolValue]) [tags addObject:@"PVP"];
    if ([w[@"highRisk"] boolValue]) [tags addObject:@"High risk"];
    NSString *sub = [tags componentsJoinedByString:@" · "];
    if (act.length) sub = [NSString stringWithFormat:@"%@ — %@", sub, act];
    cell.detailTextLabel.text = sub;
    cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.6 alpha:1.0];

    NSInteger players = [w[@"players"] integerValue];
    BOOL fav = [w[@"favourite"] boolValue];
    // Accessory: a ★ favourite toggle (WH-1) + the player count. The star's tag carries the world id.
    UIView *acc = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 92, 28)];
    UIButton *star = [UIButton buttonWithType:UIButtonTypeSystem];
    star.frame = CGRectMake(0, 0, 32, 28);
    [star setTitle:(fav ? @"★" : @"☆") forState:UIControlStateNormal];
    star.titleLabel.font = [UIFont systemFontOfSize:18];
    star.tintColor = fav ? [UIColor colorWithRed:0.95 green:0.78 blue:0.30 alpha:1.0] : [UIColor colorWithWhite:0.55 alpha:1.0];
    star.tag = wid;
    [star addTarget:self action:@selector(onToggleFavourite:) forControlEvents:UIControlEventTouchUpInside];
    [acc addSubview:star];
    UILabel *pc = [[UILabel alloc] initWithFrame:CGRectMake(34, 4, 58, 20)];
    pc.font = [UIFont systemFontOfSize:13];
    pc.textAlignment = NSTextAlignmentRight;
    pc.textColor = [UIColor colorWithWhite:0.75 alpha:1.0];
    pc.text = players >= 0 ? [NSString stringWithFormat:@"%ld", (long)players] : @"—";
    [acc addSubview:pc];
    cell.accessoryView = acc;
    return cell;
}

// WH-1: toggle a world's favourite (the star button), then re-pull so it floats to the top.
- (void)onToggleFavourite:(UIButton *)star {
    NSInteger wid = star.tag;
    BOOL nowFav = NO;
    for (NSDictionary *w in self.allWorlds) {
        if ([w[@"id"] integerValue] == wid) { nowFav = ![w[@"favourite"] boolValue]; break; }
    }
    [JVMLauncher worldSetFavourite:(int)wid favourite:nowFav];
    // Optimistic: flip the local snapshot so the star + sort update before the next bridge poll lands.
    NSMutableArray *updated = [NSMutableArray array];
    for (NSDictionary *w in self.allWorlds) {
        if ([w[@"id"] integerValue] == wid) {
            NSMutableDictionary *m = [w mutableCopy]; m[@"favourite"] = @(nowFav); [updated addObject:m];
        } else { [updated addObject:w]; }
    }
    self.allWorlds = updated;
    [self applyFilter];
}

- (void)tableView:(UITableView *)tv didSelectRowAtIndexPath:(NSIndexPath *)ip {
    [tv deselectRowAtIndexPath:ip animated:YES];
    NSInteger wid = [self.filtered[ip.row][@"id"] integerValue];
    [JVMLauncher worldHop:(int)wid];
}

#pragma mark UISearchBar

- (void)searchBar:(UISearchBar *)sb textDidChange:(NSString *)text { [self applyFilter]; }
- (void)searchBarSearchButtonClicked:(UISearchBar *)sb { [sb resignFirstResponder]; }

@end

#pragma mark - Grid launcher tile + VC (§3.3 redesign)

// One labelled plugin tile: the plugin icon over its name, with a small ★ when pinned (a favourite).
@interface MUIGridTile : UICollectionViewCell
@property (nonatomic, strong) UIImageView *icon;
@property (nonatomic, strong) UILabel *name;
@property (nonatomic, strong) UILabel *star;
@end

@implementation MUIGridTile
- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) {
        self.contentView.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.06];
        self.contentView.layer.cornerRadius = 10;
        self.contentView.clipsToBounds = YES;

        _icon = [[UIImageView alloc] init];
        _icon.translatesAutoresizingMaskIntoConstraints = NO;
        _icon.contentMode = UIViewContentModeScaleAspectFit;
        [self.contentView addSubview:_icon];

        _name = [[UILabel alloc] init];
        _name.translatesAutoresizingMaskIntoConstraints = NO;
        _name.font = [UIFont systemFontOfSize:11];
        _name.textColor = [UIColor whiteColor];
        _name.textAlignment = NSTextAlignmentCenter;
        _name.numberOfLines = 2;
        [self.contentView addSubview:_name];

        _star = [[UILabel alloc] init];
        _star.translatesAutoresizingMaskIntoConstraints = NO;
        _star.text = @"★";
        _star.font = [UIFont systemFontOfSize:12];
        _star.textColor = [UIColor colorWithRed:0.95 green:0.78 blue:0.30 alpha:1.0];
        _star.hidden = YES;
        [self.contentView addSubview:_star];

        [NSLayoutConstraint activateConstraints:@[
            [_icon.topAnchor constraintEqualToAnchor:self.contentView.topAnchor constant:10],
            [_icon.centerXAnchor constraintEqualToAnchor:self.contentView.centerXAnchor],
            [_icon.widthAnchor constraintEqualToConstant:30],
            [_icon.heightAnchor constraintEqualToConstant:30],
            [_name.topAnchor constraintEqualToAnchor:_icon.bottomAnchor constant:6],
            [_name.leadingAnchor constraintEqualToAnchor:self.contentView.leadingAnchor constant:4],
            [_name.trailingAnchor constraintEqualToAnchor:self.contentView.trailingAnchor constant:-4],
            [_star.topAnchor constraintEqualToAnchor:self.contentView.topAnchor constant:5],
            [_star.trailingAnchor constraintEqualToAnchor:self.contentView.trailingAnchor constant:-6],
        ]];
    }
    return self;
}
@end

#pragma mark - Native XP Tracker panel (RuneLite charcoal style, real session data)

// RuneLite's actual colour scheme + the bundled RuneScape font + square corners (no rounding), per the design.
static UIFont *XpRsFont(CGFloat size) { return [UIFont fontWithName:@"RuneScape" size:size] ?: [UIFont systemFontOfSize:size]; }
static UIColor *XpGray(CGFloat w) { return [UIColor colorWithWhite:w alpha:1.0]; }
// RuneLite XP-tracker formatting: < 10K shows comma-grouped; else 1-decimal K / M (matches the desktop panel).
static NSString *XpFmt(long n) {
    if (n < 10000) {
        NSNumberFormatter *f = [[NSNumberFormatter alloc] init];
        f.numberStyle = NSNumberFormatterDecimalStyle;
        return [f stringFromNumber:@(n)] ?: [NSString stringWithFormat:@"%ld", n];
    }
    if (n < 1000000) return [NSString stringWithFormat:@"%.1fK", n / 1000.0];
    return [NSString stringWithFormat:@"%.1fM", n / 1000000.0];
}

@interface XpTrackerViewController ()
@property (nonatomic, strong) UIView *drawer;
@property (nonatomic, strong) UILabel *headerLabel;
@property (nonatomic, strong) UIStackView *stack;
@property (nonatomic, strong) UILabel *emptyLabel;
@property (nonatomic, strong) NSTimer *timer;
@property (nonatomic, copy) NSString *lastJson;
@end

@implementation XpTrackerViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor clearColor];

    UIView *dim = [[UIView alloc] initWithFrame:self.view.bounds];
    dim.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    dim.backgroundColor = [UIColor colorWithWhite:0 alpha:0.45];
    [self.view addSubview:dim];
    [dim addGestureRecognizer:[[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onDone)]];

    self.drawer = [[UIView alloc] init];
    self.drawer.translatesAutoresizingMaskIntoConstraints = NO;
    self.drawer.backgroundColor = [UIColor colorWithRed:40/255.0 green:40/255.0 blue:40/255.0 alpha:1.0]; // DARK_GRAY
    [self.view addSubview:self.drawer];

    UIView *headerBar = [[UIView alloc] init];
    headerBar.translatesAutoresizingMaskIntoConstraints = NO;
    headerBar.backgroundColor = [UIColor colorWithRed:30/255.0 green:30/255.0 blue:30/255.0 alpha:1.0]; // DARKER_GRAY
    [self.drawer addSubview:headerBar];

    self.headerLabel = [[UILabel alloc] init];
    self.headerLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.headerLabel.font = XpRsFont(15);
    self.headerLabel.textColor = XpGray(0.72);
    self.headerLabel.text = @"XP tracker";
    [headerBar addSubview:self.headerLabel];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"✕" forState:UIControlStateNormal];
    close.titleLabel.font = [UIFont systemFontOfSize:17];
    close.tintColor = [UIColor colorWithRed:1.0 green:0.596 blue:0.122 alpha:1.0]; // BRAND_ORANGE
    [close addTarget:self action:@selector(onDone) forControlEvents:UIControlEventTouchUpInside];
    [headerBar addSubview:close];

    UIScrollView *scroll = [[UIScrollView alloc] init];
    scroll.translatesAutoresizingMaskIntoConstraints = NO;
    scroll.backgroundColor = [UIColor clearColor];
    scroll.alwaysBounceVertical = YES;
    [self.drawer addSubview:scroll];

    self.stack = [[UIStackView alloc] init];
    self.stack.axis = UILayoutConstraintAxisVertical;
    self.stack.spacing = 6;
    self.stack.translatesAutoresizingMaskIntoConstraints = NO;
    [scroll addSubview:self.stack];

    self.emptyLabel = [[UILabel alloc] init];
    self.emptyLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.emptyLabel.font = XpRsFont(15);
    self.emptyLabel.textColor = XpGray(0.55);
    self.emptyLabel.numberOfLines = 0;
    self.emptyLabel.textAlignment = NSTextAlignmentCenter;
    self.emptyLabel.text = @"Start training a skill to track XP.";
    self.emptyLabel.hidden = YES;
    [self.drawer addSubview:self.emptyLabel];

    CGFloat drawerW = MIN(440.0, self.view.bounds.size.width * 0.52);
    UILayoutGuide *dg = self.drawer.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [self.drawer.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.drawer.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.drawer.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.drawer.widthAnchor constraintEqualToConstant:drawerW],

        [headerBar.topAnchor constraintEqualToAnchor:dg.topAnchor],
        [headerBar.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor],
        [headerBar.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor],
        [headerBar.heightAnchor constraintEqualToConstant:34],
        [self.headerLabel.leadingAnchor constraintEqualToAnchor:headerBar.leadingAnchor constant:10],
        [self.headerLabel.centerYAnchor constraintEqualToAnchor:headerBar.centerYAnchor],
        [self.headerLabel.trailingAnchor constraintLessThanOrEqualToAnchor:close.leadingAnchor constant:-8],
        [close.trailingAnchor constraintEqualToAnchor:headerBar.trailingAnchor constant:-10],
        [close.centerYAnchor constraintEqualToAnchor:headerBar.centerYAnchor],

        [scroll.topAnchor constraintEqualToAnchor:headerBar.bottomAnchor],
        [scroll.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor],
        [scroll.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor],
        [scroll.bottomAnchor constraintEqualToAnchor:dg.bottomAnchor],

        [self.stack.topAnchor constraintEqualToAnchor:scroll.topAnchor constant:6],
        [self.stack.leadingAnchor constraintEqualToAnchor:scroll.leadingAnchor constant:6],
        [self.stack.trailingAnchor constraintEqualToAnchor:scroll.trailingAnchor constant:-6],
        [self.stack.bottomAnchor constraintEqualToAnchor:scroll.bottomAnchor constant:-6],
        [self.stack.widthAnchor constraintEqualToAnchor:scroll.widthAnchor constant:-12],

        [self.emptyLabel.topAnchor constraintEqualToAnchor:headerBar.bottomAnchor constant:40],
        [self.emptyLabel.leadingAnchor constraintEqualToAnchor:self.drawer.leadingAnchor constant:20],
        [self.emptyLabel.trailingAnchor constraintEqualToAnchor:self.drawer.trailingAnchor constant:-20],
    ]];

    [self refresh];
    self.timer = [NSTimer scheduledTimerWithTimeInterval:2.0 target:self selector:@selector(refresh) userInfo:nil repeats:YES];
}

- (void)onDone {
    [self.timer invalidate]; self.timer = nil;
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    [self.timer invalidate]; self.timer = nil;
}

- (void)refresh {
    NSString *json = [JVMLauncher xpTrackerJson] ?: @"{}";
    if (self.lastJson && [json isEqualToString:self.lastJson]) return;   // unchanged -> keep the built boxes
    self.lastJson = json;
    NSDictionary *d = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    if (![d isKindOfClass:[NSDictionary class]]) d = @{};
    long tg = [d[@"totalGained"] longLongValue];
    long th = [d[@"totalHr"] longLongValue];
    self.headerLabel.text = [NSString stringWithFormat:@"Gained: %@   ·   Per hour: %@", XpFmt(tg), XpFmt(th)];

    NSArray *skills = [d[@"skills"] isKindOfClass:[NSArray class]] ? d[@"skills"] : @[];
    for (UIView *v in [self.stack.arrangedSubviews copy]) { [self.stack removeArrangedSubview:v]; [v removeFromSuperview]; }
    for (NSDictionary *s in skills) {
        if (![s isKindOfClass:[NSDictionary class]]) continue;
        [self.stack addArrangedSubview:[self skillBox:s]];
    }
    self.emptyLabel.hidden = (skills.count > 0);
}

- (UILabel *)statLabel:(NSString *)text right:(BOOL)right {
    UILabel *l = [[UILabel alloc] init];
    l.font = XpRsFont(14);
    l.textColor = XpGray(0.62);
    l.text = text;
    l.textAlignment = right ? NSTextAlignmentRight : NSTextAlignmentLeft;
    return l;
}

- (UIView *)skillBox:(NSDictionary *)s {
    long gained = [s[@"gained"] longLongValue], hr = [s[@"hr"] longLongValue];
    long left = [s[@"left"] longLongValue], actions = [s[@"actions"] longLongValue];

    UIStackView *box = [[UIStackView alloc] init];
    box.axis = UILayoutConstraintAxisVertical;
    box.spacing = 6;
    box.layoutMarginsRelativeArrangement = YES;
    box.layoutMargins = UIEdgeInsetsMake(7, 8, 7, 8);
    box.backgroundColor = [UIColor colorWithRed:43/255.0 green:43/255.0 blue:43/255.0 alpha:1.0];

    UIStackView *topRow = [[UIStackView alloc] init];
    topRow.axis = UILayoutConstraintAxisHorizontal;
    topRow.spacing = 8;
    topRow.alignment = UIStackViewAlignmentCenter;

    UIImageView *icon = [[UIImageView alloc] init];
    icon.contentMode = UIViewContentModeScaleAspectFit;
    icon.translatesAutoresizingMaskIntoConstraints = NO;
    [icon.widthAnchor constraintEqualToConstant:24].active = YES;
    [icon.heightAnchor constraintEqualToConstant:24].active = YES;
    NSString *b64 = [s[@"icon"] isKindOfClass:[NSString class]] ? s[@"icon"] : nil;
    if (b64.length) {
        NSData *data = [[NSData alloc] initWithBase64EncodedString:b64 options:0];
        if (data) icon.image = [UIImage imageWithData:data];
    }
    [topRow addArrangedSubview:icon];

    UIStackView *leftCol = [[UIStackView alloc] init];
    leftCol.axis = UILayoutConstraintAxisVertical; leftCol.spacing = 2;
    [leftCol addArrangedSubview:[self statLabel:[NSString stringWithFormat:@"XP gained: %@", XpFmt(gained)] right:NO]];
    [leftCol addArrangedSubview:[self statLabel:[NSString stringWithFormat:@"XP/hour: %@", XpFmt(hr)] right:NO]];

    UIStackView *rightCol = [[UIStackView alloc] init];
    rightCol.axis = UILayoutConstraintAxisVertical; rightCol.spacing = 2;
    [rightCol addArrangedSubview:[self statLabel:[NSString stringWithFormat:@"XP left: %@", XpFmt(left)] right:YES]];
    [rightCol addArrangedSubview:[self statLabel:[NSString stringWithFormat:@"Actions: %@", XpFmt(actions)] right:YES]];

    UIStackView *cols = [[UIStackView alloc] init];
    cols.axis = UILayoutConstraintAxisHorizontal; cols.distribution = UIStackViewDistributionFillEqually; cols.spacing = 6;
    [cols addArrangedSubview:leftCol];
    [cols addArrangedSubview:rightCol];
    [topRow addArrangedSubview:cols];

    [box addArrangedSubview:topRow];
    [box addArrangedSubview:[self barStart:[s[@"startLevel"] intValue] end:[s[@"endLevel"] intValue] progress:[s[@"progress"] intValue]]];
    return box;
}

- (UIView *)barStart:(int)start end:(int)end progress:(int)pct {
    if (pct < 0) pct = 0; if (pct > 100) pct = 100;
    UIView *bar = [[UIView alloc] init];
    bar.translatesAutoresizingMaskIntoConstraints = NO;
    bar.backgroundColor = [UIColor colorWithRed:20/255.0 green:20/255.0 blue:20/255.0 alpha:1.0];
    [bar.heightAnchor constraintEqualToConstant:16].active = YES;

    UIView *fill = [[UIView alloc] init];
    fill.translatesAutoresizingMaskIntoConstraints = NO;
    fill.backgroundColor = [UIColor colorWithRed:79/255.0 green:122/255.0 blue:35/255.0 alpha:1.0]; // progress green
    [bar addSubview:fill];
    [NSLayoutConstraint activateConstraints:@[
        [fill.leadingAnchor constraintEqualToAnchor:bar.leadingAnchor],
        [fill.topAnchor constraintEqualToAnchor:bar.topAnchor],
        [fill.bottomAnchor constraintEqualToAnchor:bar.bottomAnchor],
        [fill.widthAnchor constraintEqualToAnchor:bar.widthAnchor multiplier:(pct / 100.0)],
    ]];

    UILabel *(^lvl)(NSString *) = ^UILabel *(NSString *t) {
        UILabel *l = [[UILabel alloc] init];
        l.translatesAutoresizingMaskIntoConstraints = NO;
        l.font = XpRsFont(14);
        l.textColor = [UIColor whiteColor];
        l.text = t;
        return l;
    };
    UILabel *lv1 = lvl([NSString stringWithFormat:@"Lvl %d", start]);
    UILabel *pc = lvl([NSString stringWithFormat:@"%d%%", pct]);
    UILabel *lv2 = lvl([NSString stringWithFormat:@"Lvl %d", end]);
    [bar addSubview:lv1]; [bar addSubview:pc]; [bar addSubview:lv2];
    [NSLayoutConstraint activateConstraints:@[
        [lv1.leadingAnchor constraintEqualToAnchor:bar.leadingAnchor constant:6],
        [lv1.centerYAnchor constraintEqualToAnchor:bar.centerYAnchor],
        [pc.centerXAnchor constraintEqualToAnchor:bar.centerXAnchor],
        [pc.centerYAnchor constraintEqualToAnchor:bar.centerYAnchor],
        [lv2.trailingAnchor constraintEqualToAnchor:bar.trailingAnchor constant:-6],
        [lv2.centerYAnchor constraintEqualToAnchor:bar.centerYAnchor],
    ]];
    return bar;
}

@end

@interface MUIPluginGrid () <UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UISearchBarDelegate>
@end

@implementation MUIPluginGrid {
    NSArray<NSDictionary *> *_all;       // every panel ({index,tooltip,name,iconPng,pinned})
    NSMutableArray<NSDictionary *> *_shown; // filtered + favourites-first
    NSString *_filter;
    void (^_onPick)(NSString *);
    UICollectionView *_grid;
    UISearchBar *_search;
}

- (instancetype)initWithPanels:(NSArray<NSDictionary *> *)panels onPick:(void (^)(NSString *))onPick {
    if ((self = [super init])) {
        _all = [panels copy] ?: @[];
        _onPick = [onPick copy];
        _filter = @"";
        _shown = [NSMutableArray array];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Plugins";
    title.font = [UIFont boldSystemFontOfSize:18];
    title.textColor = [UIColor whiteColor];
    [self.view addSubview:title];

    _search = [[UISearchBar alloc] init];
    _search.translatesAutoresizingMaskIntoConstraints = NO;
    _search.delegate = self;
    _search.placeholder = @"Search plugins";
    _search.searchBarStyle = UISearchBarStyleMinimal;
    _search.barStyle = UIBarStyleBlack;
    _search.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    [self.view addSubview:_search];

    UICollectionViewFlowLayout *layout = [[UICollectionViewFlowLayout alloc] init];
    layout.minimumInteritemSpacing = 10;
    layout.minimumLineSpacing = 14;
    layout.sectionInset = UIEdgeInsetsMake(6, 16, 24, 16);
    _grid = [[UICollectionView alloc] initWithFrame:CGRectZero collectionViewLayout:layout];
    _grid.backgroundColor = [UIColor clearColor];
    _grid.dataSource = self;
    _grid.delegate = self;
    _grid.alwaysBounceVertical = YES;
    _grid.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag;
    [_grid registerClass:[MUIGridTile class] forCellWithReuseIdentifier:@"tile"];
    [self.view addSubview:_grid];

    UILongPressGestureRecognizer *lp = [[UILongPressGestureRecognizer alloc] initWithTarget:self
                                                                                     action:@selector(onTileLongPress:)];
    [_grid addGestureRecognizer:lp];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [title.topAnchor constraintEqualToAnchor:self.view.topAnchor constant:16],
        [title.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:20],
        [_search.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:6],
        [_search.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:8],
        [_search.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-8],
        [_grid.topAnchor constraintEqualToAnchor:_search.bottomAnchor constant:2],
        [_grid.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [_grid.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [_grid.bottomAnchor constraintEqualToAnchor:sg.bottomAnchor],
    ]];
    [self applyFilter];
}

- (void)applyFilter {
    NSArray *base = _all;
    if (_filter.length) {
        NSMutableArray *f = [NSMutableArray array];
        for (NSDictionary *p in _all) {
            NSString *n = [[p[@"tooltip"] description] lowercaseString] ?: @"";
            if ([n containsString:_filter]) [f addObject:p];
        }
        base = f;
    }
    [_shown setArray:[base sortedArrayUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
        BOOL pa = [a[@"pinned"] boolValue], pb = [b[@"pinned"] boolValue];
        if (pa != pb) return pa ? NSOrderedAscending : NSOrderedDescending;   // favourites float to the top
        return [[a[@"tooltip"] description] caseInsensitiveCompare:([b[@"tooltip"] description] ?: @"")];
    }]];
    [_grid reloadData];
}

- (nullable UIImage *)decodeIcon:(id)base64 {
    if (![base64 isKindOfClass:[NSString class]] || [(NSString *)base64 length] == 0) return nil;
    NSData *d = [[NSData alloc] initWithBase64EncodedString:base64 options:0];
    return d ? [UIImage imageWithData:d] : nil;
}

#pragma mark search

- (void)searchBar:(UISearchBar *)searchBar textDidChange:(NSString *)searchText {
    _filter = [searchText lowercaseString];
    [self applyFilter];
}
- (void)searchBarSearchButtonClicked:(UISearchBar *)searchBar { [searchBar resignFirstResponder]; }

#pragma mark grid

- (NSInteger)collectionView:(UICollectionView *)cv numberOfItemsInSection:(NSInteger)section {
    return (NSInteger)_shown.count;
}

- (UICollectionViewCell *)collectionView:(UICollectionView *)cv cellForItemAtIndexPath:(NSIndexPath *)ip {
    MUIGridTile *cell = [cv dequeueReusableCellWithReuseIdentifier:@"tile" forIndexPath:ip];
    NSDictionary *p = _shown[(NSUInteger)ip.item];
    cell.name.text = [p[@"tooltip"] description];
    UIImage *icon = [self decodeIcon:p[@"iconPng"]];
    cell.icon.image = icon;
    cell.icon.hidden = (icon == nil);
    cell.star.hidden = ![p[@"pinned"] boolValue];
    return cell;
}

- (CGSize)collectionView:(UICollectionView *)cv layout:(UICollectionViewLayout *)layout
  sizeForItemAtIndexPath:(NSIndexPath *)ip {
    CGFloat avail = cv.bounds.size.width - 32;   // section insets
    NSInteger cols = MAX(2, (NSInteger)floor(avail / 110.0));   // ~100pt tiles, more columns in landscape
    CGFloat w = floor((avail - (cols - 1) * 10.0) / cols);
    return CGSizeMake(w, 84);
}

- (void)collectionView:(UICollectionView *)cv didSelectItemAtIndexPath:(NSIndexPath *)ip {
    NSDictionary *p = _shown[(NSUInteger)ip.item];
    NSString *tooltip = [p[@"tooltip"] description] ?: @"";
    void (^pick)(NSString *) = _onPick;
    [self dismissViewControllerAnimated:YES completion:^{ if (pick) pick(tooltip); }];
}

// Long-press a tile = pin/unpin (favourite). Reuses the shared pinnedPlugins CSV via mobileSetPinned.
- (void)onTileLongPress:(UILongPressGestureRecognizer *)g {
    if (g.state != UIGestureRecognizerStateBegan) return;
    NSIndexPath *ip = [_grid indexPathForItemAtPoint:[g locationInView:_grid]];
    if (!ip || ip.item < 0 || ip.item >= (NSInteger)_shown.count) return;
    NSDictionary *p = _shown[(NSUInteger)ip.item];
    NSString *name = [p[@"tooltip"] description] ?: @"";
    BOOL pinned = [p[@"pinned"] boolValue];
    [JVMLauncher mobileSetPinned:name pinned:!pinned];
    // Reflect immediately: flip the pinned flag in the local snapshot and re-sort (the dock reconciles on its poll).
    NSMutableArray *updated = [NSMutableArray array];
    for (NSDictionary *q in _all) {
        if ([[q[@"tooltip"] description] isEqualToString:name]) {
            NSMutableDictionary *m = [q mutableCopy];
            m[@"pinned"] = @(!pinned);
            [updated addObject:m];
        } else {
            [updated addObject:q];
        }
    }
    _all = updated;
    [self applyFilter];
}

@end
