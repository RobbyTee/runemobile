#import "PluginPanelsViewController.h"
#import "JVMLauncher.h"

// Right-edge drawer / dock geometry. Desktop-faithful: a vertical icon rail pinned to the far-right edge, the
// rendered panel content opening to its left. The panel's natural 1:1 width is ~PluginPanel.PANEL_WIDTH
// supersampled then shown at device scale (≈322pt on a 3x screen); size the content column to fit it snug.
static const CGFloat kRailWidth = 38.0;       // narrow enough to sit in the outer screen-edge strip
static const CGFloat kDrawerWidth = 392.0;   // modal drawer: content(322) + rail + insets/gap
static const CGFloat kDrawerHInset = 10.0;
static const CGFloat kTitleBarHeight = 44.0;
static const CGFloat kRailButtonH = 40.0;     // smaller icons so the top/bottom clear the screen curve
// Docked geometry. The native tab stones sit INSIDE the safe area (the game is inset by the tab strip), so the
// outer sliver between them and the screen edge is free — put the plugin rail there, to the RIGHT of the tabs.
static const CGFloat kRailTopInset = 22.0;    // pull in from the rounded corners so icons aren't clipped
static const CGFloat kRailBottomInset = 22.0;
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

// Native, reflowed Quest Helper screen (data via QuestHelperBridge). Defined at the bottom of this file.
@interface QuestHelperViewController : UIViewController
@end

@interface PluginPanelsViewController () <UIScrollViewDelegate>
@property (nonatomic, assign) CGSize lastPanelImgSize;     // re-layout only on size change (preserve zoom)
@property (nonatomic, strong) UIScrollView *toolbar;       // legacy: horizontal text-pill strip
@property (nonatomic, strong) UIScrollView *rail;          // vertical plugin-icon rail (right edge)
@property (nonatomic, strong) NSLayoutConstraint *railHeight; // sized to content, clamped to the clear band
@property (nonatomic, strong) UIView *panelContainer;      // docked: collapsible inline panel column
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

    self.rail = [[UIScrollView alloc] init];
    self.rail.translatesAutoresizingMaskIntoConstraints = NO;
    self.rail.showsVerticalScrollIndicator = NO;
    self.rail.backgroundColor = [UIColor colorWithRed:0.08 green:0.07 blue:0.055 alpha:0.94];
    self.rail.layer.cornerRadius = 8;
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
        // Right rail: in the outer strip at the very screen edge, to the RIGHT of the native tab stones (which
        // end at the safe-area edge). Vertically centred, clamped to the safe-area height, scrolls if needed.
        [self.rail.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-2],
        [self.rail.topAnchor constraintGreaterThanOrEqualToAnchor:sg.topAnchor constant:kRailTopInset],
        [self.rail.bottomAnchor constraintLessThanOrEqualToAnchor:sg.bottomAnchor constant:-kRailBottomInset],
        [self.rail.widthAnchor constraintEqualToConstant:kRailWidth],
        railCenter,
        self.railHeight,

        // Inline panel column: left of the rail (over the game world, not the native tabs), full clear height.
        [self.panelContainer.trailingAnchor constraintEqualToAnchor:self.rail.leadingAnchor constant:-6],
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

    // No empty bar before RuneLite has registered any panels.
    self.rail.hidden = (self.panels.count == 0);

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
    if (self.docked) {
        // Quest Helper gets a genuinely native, reflowed screen instead of the rendered image — when the
        // reflection bridge can reach the (external) plugin. Otherwise fall through to the generic surface.
        if (b.tag >= 0 && b.tag < (NSInteger)self.panels.count) {
            NSString *tip = [self.panels[b.tag][@"tooltip"] description];
            if ([tip isEqualToString:@"Quest Helper"] && [JVMLauncher questAvailable]) {
                QuestHelperViewController *qh = [[QuestHelperViewController alloc] init];
                qh.modalPresentationStyle = UIModalPresentationOverFullScreen;
                qh.modalTransitionStyle = UIModalTransitionStyleCrossDissolve;
                [self presentViewController:qh animated:YES completion:nil];
                return;
            }
        }
        if (b.tag == self.currentIndex) {
            [self collapsePanel];              // tapping the active icon closes the panel
            return;
        }
    }
    [self selectIndex:b.tag];
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
    CGPoint loc = [tap locationInView:self.panelView];
    CGFloat s = self.panelNativeW / self.panelView.bounds.size.width;
    int px = (int)lround(loc.x * s);
    int py = (int)lround(loc.y * s);
    [JVMLauncher mobilePanelClickX:px y:py];
}

- (void)onDone {
    [self.refresh invalidate];
    self.refresh = nil;
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
