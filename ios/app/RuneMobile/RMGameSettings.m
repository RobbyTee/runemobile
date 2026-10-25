#import "RMGameSettings.h"

static NSString *const kHapticKeys[4] = {@"haptic.op", @"haptic.drag", @"haptic.menuOpen", @"haptic.menuHover"};
static NSString *const kHapticStrengthKey = @"haptic.strength";

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
        }];
    });
    return [NSUserDefaults standardUserDefaults];
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
}

+ (NSInteger)hapticStrength {
    return MIN(MAX([[self defaults] integerForKey:kHapticStrengthKey], 0), 2);
}

+ (void)setHapticStrength:(NSInteger)s {
    [[self defaults] setInteger:MIN(MAX(s, 0), 2) forKey:kHapticStrengthKey];
}

@end

#pragma mark - Screen

static NSString *const kHapticTitles[4] = {@"Actions", @"Drag start", @"Menu open", @"Menu rows"};
static NSString *const kHapticDetails[4] = {
    @"Tapping something you can interact with",
    @"Picking up an item to move it",
    @"The long-press menu appearing",
    @"Sliding over the menu's options",
};

@implementation RMGameSettingsViewController

- (instancetype)init {
    return [super initWithStyle:UITableViewStyleInsetGrouped];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.title = @"Game settings";
    self.view.backgroundColor = [UIColor colorWithWhite:0.07 alpha:1.0];
    self.tableView.backgroundColor = [UIColor colorWithWhite:0.07 alpha:1.0];
}

- (UIColor *)accent {
    return [UIColor colorWithRed:0.95 green:0.62 blue:0.11 alpha:1.0];
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tv {
    return 1;
}

- (NSInteger)tableView:(UITableView *)tv numberOfRowsInSection:(NSInteger)s {
    return 5; // four triggers + strength
}

- (NSString *)tableView:(UITableView *)tv titleForHeaderInSection:(NSInteger)s {
    return @"HAPTICS";
}

- (UITableViewCell *)tableView:(UITableView *)tv cellForRowAtIndexPath:(NSIndexPath *)ip {
    UITableViewCell *cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:nil];
    cell.backgroundColor = [UIColor colorWithWhite:0.13 alpha:1.0];
    cell.textLabel.textColor = [UIColor whiteColor];
    cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.6 alpha:1.0];
    cell.selectionStyle = UITableViewCellSelectionStyleNone;
    if (ip.row < 4) {
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
