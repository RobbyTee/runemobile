#import "PluginPanelsViewController.h"
#import "JVMLauncher.h"

@interface PluginPanelsViewController ()
@property (nonatomic, strong) UIScrollView *toolbar;       // horizontal strip of plugin buttons
@property (nonatomic, strong) UIScrollView *panelScroll;   // vertical scroll of the rendered panel
@property (nonatomic, strong) UIImageView *panelView;
@property (nonatomic, strong) UILabel *emptyLabel;
@property (nonatomic, strong) NSArray<NSDictionary *> *panels;
@property (nonatomic, assign) NSInteger currentIndex;
@property (nonatomic, strong) CADisplayLink *refresh;
@property (nonatomic, assign) int lastSeq;
@property (nonatomic, assign) CGFloat panelNativeW;        // image width in panel-space px (PluginPanel width)
@property (nonatomic, strong) NSMutableArray<UIButton *> *toolbarButtons;
@end

@implementation PluginPanelsViewController

- (instancetype)init {
    if ((self = [super init])) {
        self.modalPresentationStyle = UIModalPresentationFullScreen;
        _currentIndex = -1; _lastSeq = -1; _panelNativeW = 242;
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
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
    self.panelScroll.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0]; // = app bg, so side margins blend
    [self.view addSubview:self.panelScroll];

    self.panelView = [[UIImageView alloc] init];
    self.panelView.userInteractionEnabled = YES;
    self.panelView.contentMode = UIViewContentModeScaleToFill;
    self.panelView.layer.magnificationFilter = kCAFilterTrilinear;
    [self.panelScroll addSubview:self.panelView];
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(onPanelTap:)];
    [self.panelView addGestureRecognizer:tap];

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

    [self buildToolbar];

    self.refresh = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick)];
    self.refresh.preferredFramesPerSecond = 15;
    [self.refresh addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
}

- (void)buildToolbar {
    NSString *json = [JVMLauncher mobileListPanels];
    NSArray *arr = nil;
    if (json.length) {
        id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        if ([parsed isKindOfClass:[NSArray class]]) arr = parsed;
    }
    self.panels = arr ?: @[];
    self.toolbarButtons = [NSMutableArray array];
    CGFloat x = 8;
    const CGFloat bw = 120, bh = 34, gap = 6;
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
        [b addTarget:self action:@selector(onToolbarButton:) forControlEvents:UIControlEventTouchUpInside];
        [self.toolbar addSubview:b];
        [self.toolbarButtons addObject:b];
        x += bw + gap;
    }
    self.toolbar.contentSize = CGSizeMake(x + 2, 44);
    if (self.panels.count > 0) {
        [self selectIndex:0];
    } else {
        self.emptyLabel.text = @"No plugins with panels are enabled.";
    }
}

- (void)onToolbarButton:(UIButton *)b {
    [self selectIndex:b.tag];
}

- (void)selectIndex:(NSInteger)index {
    if (index < 0 || index >= (NSInteger)self.panels.count) return;
    self.currentIndex = index;
    self.lastSeq = -1;                 // force a re-pull
    self.emptyLabel.text = @"";
    for (UIButton *b in self.toolbarButtons) {
        BOOL on = (b.tag == index);
        b.backgroundColor = on ? [UIColor colorWithRed:0.20 green:0.45 blue:0.22 alpha:1.0]
                               : [UIColor colorWithWhite:1 alpha:0.10];
    }
    [JVMLauncher mobileOpenPanel:(int)index];
}

- (void)tick {
    if (self.currentIndex < 0) return;
    int seq = [JVMLauncher mobilePanelFrameSeq];
    if (seq == self.lastSeq) return;
    UIImage *img = [JVMLauncher mobilePanelImage];
    if (!img || img.size.width < 1) return;
    self.lastSeq = seq;
    self.panelNativeW = img.size.width;
    self.panelView.image = img;
    // Don't stretch the narrow panel across the whole landscape width (that blew it up ~8x). Cap the
    // display width and centre it; scroll vertically. The Java side supersamples so it stays crisp.
    CGFloat vw = self.panelScroll.bounds.size.width;
    if (vw < 1) return;
    // Display at device resolution 1:1 (image px / screen scale) so there's NO upscale blur — pixel-crisp.
    // The buffer is supersampled on the Java side; at 4x of 242 that's ~323pt, a comfortable panel width.
    CGFloat oneToOne = img.size.width / UIScreen.mainScreen.scale;
    CGFloat dispW = MIN(vw, oneToOne);
    CGFloat scale = dispW / img.size.width;
    CGFloat dispH = img.size.height * scale;
    CGFloat originX = floor((vw - dispW) / 2.0);
    self.panelView.frame = CGRectMake(originX, 0, dispW, dispH);
    self.panelScroll.contentSize = CGSizeMake(vw, dispH);
}

- (void)onPanelTap:(UITapGestureRecognizer *)tap {
    if (self.currentIndex < 0 || self.panelView.bounds.size.width < 1) return;
    CGPoint loc = [tap locationInView:self.panelView];
    // panelView is the natural panel scaled to its bounds width; invert to panel-space px.
    CGFloat s = self.panelNativeW / self.panelView.bounds.size.width;
    int px = (int)lround(loc.x * s);
    int py = (int)lround(loc.y * s);
    [JVMLauncher mobilePanelClickX:px y:py];
}

- (void)onDone {
    [self.refresh invalidate];
    self.refresh = nil;
    [JVMLauncher mobileClosePanel];
    [JVMLauncher mobileFlushConfig];   // panels often write config (settings, layout)
    [self dismissViewControllerAnimated:YES completion:nil];
}

@end
