#import "RMGameSettings.h"
#import "JVMLauncher.h"

NSNotificationName const RMGameSettingsDidChangeNotification = @"RMGameSettingsDidChangeNotification";

static NSString *const kHapticKeys[4] = {@"haptic.op", @"haptic.drag", @"haptic.menuOpen", @"haptic.menuHover"};
static NSString *const kHapticStrengthKey = @"haptic.strength";
static NSString *const kLongPressKey = @"touch.longPressMs";
static NSString *const kCameraSensitivityKey = @"camera.sensitivity";
static NSString *const kPortraitKey = @"screen.portrait";

static const NSInteger kLongPressMinMs = 200, kLongPressMaxMs = 1000, kLongPressStepMs = 50, kLongPressDefaultMs = 400;
static const NSInteger kDragHoldMaxMs = 150;
static const double kCameraSensMin = 0.1, kCameraSensMax = 4.0, kCameraSensDefault = 2.0;

@implementation RMGameSettings

+ (NSUserDefaults *)defaults {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        [[NSUserDefaults standardUserDefaults] registerDefaults:@{
            kHapticKeys[RMHapticOp] : @YES,
            kHapticKeys[RMHapticDrag] : @YES,
            kHapticKeys[RMHapticMenuOpen] : @YES,
            kHapticKeys[RMHapticMenuHover] : @YES,
            kHapticStrengthKey : @1,
            kLongPressKey : @(kLongPressDefaultMs),
            kCameraSensitivityKey : @(kCameraSensDefault),
            kPortraitKey : @NO,
        }];
    });
    return [NSUserDefaults standardUserDefaults];
}

+ (void)changed {
    [[NSNotificationCenter defaultCenter] postNotificationName:RMGameSettingsDidChangeNotification object:nil];
}

+ (BOOL)hapticOn:(RMHaptic)h {
    if (h < RMHapticOp || h > RMHapticMenuHover)
        return NO;
    return [[self defaults] boolForKey:kHapticKeys[h]];
}

+ (void)setHaptic:(RMHaptic)h on:(BOOL)on {
    if (h < RMHapticOp || h > RMHapticMenuHover)
        return;
    [[self defaults] setBool:on forKey:kHapticKeys[h]];
    [self changed];
}

+ (NSInteger)hapticStrength {
    return MIN(MAX([[self defaults] integerForKey:kHapticStrengthKey], 0), 2);
}

+ (void)setHapticStrength:(NSInteger)s {
    [[self defaults] setInteger:MIN(MAX(s, 0), 2) forKey:kHapticStrengthKey];
    [self changed];
}

+ (NSInteger)longPressMs {
    return MIN(MAX([[self defaults] integerForKey:kLongPressKey], kLongPressMinMs), kLongPressMaxMs);
}

+ (void)setLongPressMs:(NSInteger)ms {
    [[self defaults] setInteger:MIN(MAX(ms, kLongPressMinMs), kLongPressMaxMs) forKey:kLongPressKey];
    [self changed];
}

+ (double)cameraSensitivity {
    return MIN(MAX([[self defaults] doubleForKey:kCameraSensitivityKey], kCameraSensMin), kCameraSensMax);
}

+ (void)setCameraSensitivity:(double)v {
    [[self defaults] setDouble:MIN(MAX(v, kCameraSensMin), kCameraSensMax) forKey:kCameraSensitivityKey];
    [self changed];
}

+ (double)cameraGain {
    return [self cameraSensitivity] / kCameraSensDefault;
}

+ (NSInteger)dragHoldMs {
    return MIN([self longPressMs] * 2 / 3, kDragHoldMaxMs);
}

+ (BOOL)portrait {
    return [[self defaults] boolForKey:kPortraitKey];
}

+ (void)setPortrait:(BOOL)on {
    [[self defaults] setBool:on forKey:kPortraitKey];
    [self changed];
}

+ (BOOL)portraitThisLaunch {
    static BOOL portrait;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        portrait = [self portrait];
    });
    return portrait;
}

@end

#pragma mark - Screen

// Sections, in order. GAME is listed only when the game is running (it reads two RuneLite plugins).
typedef NS_ENUM(NSInteger, RMGsSection) { RMGsScreen = 0,
                                          RMGsTouch,
                                          RMGsGame,
                                          RMGsHaptics };

static NSString *const kHapticTitles[4] = {@"Actions", @"Drag start", @"Menu open", @"Menu rows"};
static NSString *const kHapticDetails[4] = {
    @"Tapping something you can interact with",
    @"Picking up an item to move it",
    @"The long-press menu appearing",
    @"Sliding over the menu's options",
};

// Frame-rate cap choices; 0 is no cap.
static const int kFpsCapChoices[] = {0, 20, 30, 40, 50, 60};

// Slider tags, so one value-changed handler can tell them apart.
enum { kTagLongPress = 1,
       kTagCamera,
       kTagIdle };

@interface RMGameSettingsViewController ()
@property (nonatomic, assign) BOOL gameLoaded; // the game answered with its settings
@property (nonatomic, assign) int fpsCap;      // 0 = no cap
@property (nonatomic, assign) int idleMinutes;
@property (nonatomic, copy) NSArray<NSNumber *> *fpsChoices;
@end

@implementation RMGameSettingsViewController

- (instancetype)init {
    return [super initWithStyle:UITableViewStyleInsetGrouped];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = @"Game settings";
    self.view.backgroundColor = [UIColor colorWithWhite:0.07 alpha:1.0];
    self.tableView.backgroundColor = [UIColor colorWithWhite:0.07 alpha:1.0];
    self.tableView.rowHeight = UITableViewAutomaticDimension;
    self.tableView.estimatedRowHeight = 64;
    [self loadGameSettings];
}

- (void)loadGameSettings {
    NSString *json = [JVMLauncher gameSettings];
    NSData *data = [json dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *d = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    self.gameLoaded = [d isKindOfClass:[NSDictionary class]];
    self.fpsCap = self.gameLoaded ? [d[@"fpsCap"] intValue] : 0;
    self.idleMinutes = self.gameLoaded ? [d[@"idleLogoutMinutes"] intValue] : 5;
    NSMutableArray<NSNumber *> *choices = [NSMutableArray array];
    for (size_t i = 0; i < sizeof(kFpsCapChoices) / sizeof(kFpsCapChoices[0]); i++)
        [choices addObject:@(kFpsCapChoices[i])];
    if (![choices containsObject:@(self.fpsCap)]) {
        [choices addObject:@(self.fpsCap)]; // a cap set from the plugin's own screen
        [choices sortUsingSelector:@selector(compare:)];
    }
    self.fpsChoices = choices;
}

- (UIColor *)accent {
    return [UIColor colorWithRed:0.95 green:0.62 blue:0.11 alpha:1.0];
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tv {
    return 4;
}

- (NSInteger)tableView:(UITableView *)tv numberOfRowsInSection:(NSInteger)s {
    switch (s) {
        case RMGsScreen:
            return 1; // orientation
        case RMGsTouch:
            return 2; // long-press time, camera sensitivity
        case RMGsGame:
            return self.gameLoaded ? 2 : 0; // idle logout, frame-rate cap
        case RMGsHaptics:
            return 5; // four triggers + strength
        default:
            return 0;
    }
}

- (NSString *)tableView:(UITableView *)tv titleForHeaderInSection:(NSInteger)s {
    switch (s) {
        case RMGsScreen:
            return @"SCREEN";
        case RMGsTouch:
            return @"TOUCH";
        case RMGsGame:
            return @"GAME";
        case RMGsHaptics:
            return @"HAPTICS";
        default:
            return nil;
    }
}

- (NSString *)tableView:(UITableView *)tv titleForFooterInSection:(NSInteger)s {
    if (s == RMGsScreen)
        return [RMGameSettings portrait] == [RMGameSettings portraitThisLaunch]
                   ? @"Applies when the app next opens."
                   : @"Close the app and open it again to switch.";
    if (s == RMGsGame && !self.gameLoaded)
        return @"Available once the game is running.";
    return nil;
}

- (UITableViewCell *)baseCell {
    UITableViewCell *cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:nil];
    cell.backgroundColor = [UIColor colorWithWhite:0.13 alpha:1.0];
    cell.textLabel.textColor = [UIColor whiteColor];
    cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.6 alpha:1.0];
    cell.selectionStyle = UITableViewCellSelectionStyleNone;
    return cell;
}

// A title, its current value on the right, and a full-width slider under them.
- (UITableViewCell *)sliderCellTitle:(NSString *)title
                               value:(NSString *)valueText
                                 min:(float)lo
                                 max:(float)hi
                             current:(float)cur
                                 tag:(NSInteger)tag {
    UITableViewCell *cell = [self baseCell];
    UILabel *name = [UILabel new];
    name.text = title;
    name.textColor = [UIColor whiteColor];
    UILabel *value = [UILabel new];
    value.text = valueText;
    value.textColor = [UIColor colorWithWhite:0.6 alpha:1.0];
    value.tag = 100 + tag;
    [value setContentHuggingPriority:UILayoutPriorityRequired forAxis:UILayoutConstraintAxisHorizontal];
    UIStackView *top = [[UIStackView alloc] initWithArrangedSubviews:@[ name, value ]];
    UISlider *slider = [UISlider new];
    slider.minimumValue = lo;
    slider.maximumValue = hi;
    slider.value = cur;
    slider.tag = tag;
    slider.minimumTrackTintColor = [self accent];
    slider.accessibilityLabel = title;
    [slider addTarget:self action:@selector(onSlide:) forControlEvents:UIControlEventValueChanged];
    [slider addTarget:self
                  action:@selector(onSlideDone:)
        forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];
    [self stackInCell:cell views:@[ top, slider ]];
    return cell;
}

// Lay views out top to bottom across the cell's full width.
- (void)stackInCell:(UITableViewCell *)cell views:(NSArray<UIView *> *)views {
    UIStackView *col = [[UIStackView alloc] initWithArrangedSubviews:views];
    col.axis = UILayoutConstraintAxisVertical;
    col.spacing = 6;
    col.translatesAutoresizingMaskIntoConstraints = NO;
    [cell.contentView addSubview:col];
    UILayoutGuide *m = cell.contentView.layoutMarginsGuide;
    [NSLayoutConstraint activateConstraints:@[
        [col.leadingAnchor constraintEqualToAnchor:m.leadingAnchor],
        [col.trailingAnchor constraintEqualToAnchor:m.trailingAnchor],
        [col.topAnchor constraintEqualToAnchor:m.topAnchor],
        [col.bottomAnchor constraintEqualToAnchor:m.bottomAnchor],
    ]];
}

- (UITableViewCell *)tableView:(UITableView *)tv cellForRowAtIndexPath:(NSIndexPath *)ip {
    if (ip.section == RMGsTouch && ip.row == 0) {
        NSInteger ms = [RMGameSettings longPressMs];
        return [self sliderCellTitle:@"Long-press time"
                               value:[NSString stringWithFormat:@"%ld ms", (long)ms]
                                 min:kLongPressMinMs
                                 max:kLongPressMaxMs
                             current:ms
                                 tag:kTagLongPress];
    }
    if (ip.section == RMGsTouch) {
        double v = [RMGameSettings cameraSensitivity];
        return [self sliderCellTitle:@"Camera sensitivity"
                               value:[NSString stringWithFormat:@"%.1f", v]
                                 min:kCameraSensMin
                                 max:kCameraSensMax
                             current:v
                                 tag:kTagCamera];
    }
    if (ip.section == RMGsGame && ip.row == 0) {
        return [self sliderCellTitle:@"Idle logout"
                               value:[NSString stringWithFormat:@"%d min", self.idleMinutes]
                                 min:5
                                 max:30
                             current:self.idleMinutes
                                 tag:kTagIdle];
    }
    UITableViewCell *cell = [self baseCell];
    if (ip.section == RMGsScreen) {
        cell.textLabel.text = @"Orientation";
        UISegmentedControl *seg = [[UISegmentedControl alloc] initWithItems:@[ @"Landscape", @"Portrait" ]];
        seg.selectedSegmentIndex = [RMGameSettings portrait] ? 1 : 0;
        seg.selectedSegmentTintColor = [self accent];
        seg.accessibilityLabel = @"Orientation";
        [seg addTarget:self action:@selector(onOrientation:) forControlEvents:UIControlEventValueChanged];
        [seg sizeToFit];
        cell.accessoryView = seg;
    } else if (ip.section == RMGsGame) {
        // Too many choices to sit beside the title, so they get a line of their own.
        UILabel *name = [UILabel new];
        name.text = @"Frame-rate cap";
        name.textColor = [UIColor whiteColor];
        NSMutableArray<NSString *> *labels = [NSMutableArray array];
        for (NSNumber *n in self.fpsChoices)
            [labels addObject:n.intValue == 0 ? @"Off" : n.stringValue];
        UISegmentedControl *seg = [[UISegmentedControl alloc] initWithItems:labels];
        seg.selectedSegmentIndex = (NSInteger)[self.fpsChoices indexOfObject:@(self.fpsCap)];
        seg.selectedSegmentTintColor = [self accent];
        seg.accessibilityLabel = @"Frame-rate cap";
        [seg addTarget:self action:@selector(onFpsCap:) forControlEvents:UIControlEventValueChanged];
        [self stackInCell:cell views:@[ name, seg ]];
    } else if (ip.row < 4) {
        cell.textLabel.text = kHapticTitles[ip.row];
        cell.detailTextLabel.text = kHapticDetails[ip.row];
        UISwitch *sw = [[UISwitch alloc] init];
        sw.on = [RMGameSettings hapticOn:(RMHaptic)ip.row];
        sw.onTintColor = [self accent];
        sw.tag = ip.row;
        [sw addTarget:self action:@selector(onHapticSwitch:) forControlEvents:UIControlEventValueChanged];
        cell.accessoryView = sw;
    } else {
        cell.textLabel.text = @"Strength";
        UISegmentedControl *seg = [[UISegmentedControl alloc] initWithItems:@[ @"Light", @"Medium", @"Heavy" ]];
        seg.selectedSegmentIndex = [RMGameSettings hapticStrength];
        seg.selectedSegmentTintColor = [self accent];
        [seg addTarget:self action:@selector(onStrength:) forControlEvents:UIControlEventValueChanged];
        [seg sizeToFit];
        cell.accessoryView = seg;
    }
    return cell;
}

// Snap to the setting's step and show it; native settings save as they move.
- (void)onSlide:(UISlider *)s {
    UILabel *value = [s.superview.superview viewWithTag:100 + s.tag];
    if (s.tag == kTagLongPress) {
        NSInteger ms = lround(s.value / kLongPressStepMs) * kLongPressStepMs;
        s.value = ms;
        value.text = [NSString stringWithFormat:@"%ld ms", (long)ms];
        [RMGameSettings setLongPressMs:ms];
    } else if (s.tag == kTagCamera) {
        double v = round(s.value * 10.0) / 10.0;
        s.value = (float)v;
        value.text = [NSString stringWithFormat:@"%.1f", v];
        [RMGameSettings setCameraSensitivity:v];
    } else if (s.tag == kTagIdle) {
        self.idleMinutes = (int)lround(s.value);
        s.value = self.idleMinutes;
        value.text = [NSString stringWithFormat:@"%d min", self.idleMinutes];
    }
}

// The idle logout goes to the game once, when the finger lifts.
- (void)onSlideDone:(UISlider *)s {
    if (s.tag == kTagIdle)
        [JVMLauncher setIdleLogoutMinutes:self.idleMinutes];
}

- (void)onFpsCap:(UISegmentedControl *)seg {
    if (seg.selectedSegmentIndex < 0 || seg.selectedSegmentIndex >= (NSInteger)self.fpsChoices.count)
        return;
    self.fpsCap = self.fpsChoices[seg.selectedSegmentIndex].intValue;
    [JVMLauncher setFpsCap:self.fpsCap];
}

- (void)onOrientation:(UISegmentedControl *)seg {
    [RMGameSettings setPortrait:seg.selectedSegmentIndex == 1];
    // The footer says whether a restart is needed.
    [self.tableView reloadSections:[NSIndexSet indexSetWithIndex:RMGsScreen]
                  withRowAnimation:UITableViewRowAnimationNone];
}

- (void)onHapticSwitch:(UISwitch *)sw {
    [RMGameSettings setHaptic:(RMHaptic)sw.tag on:sw.on];
}

- (void)onStrength:(UISegmentedControl *)seg {
    [RMGameSettings setHapticStrength:seg.selectedSegmentIndex];
    // A sample buzz at the new strength.
    static const UIImpactFeedbackStyle styles[3] = {UIImpactFeedbackStyleLight, UIImpactFeedbackStyleMedium,
                                                    UIImpactFeedbackStyleHeavy};
    [[[UIImpactFeedbackGenerator alloc] initWithStyle:styles[[RMGameSettings hapticStrength]]] impactOccurred];
}

@end
