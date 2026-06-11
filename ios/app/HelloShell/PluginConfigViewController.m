#import "PluginConfigViewController.h"
#import "JVMLauncher.h"

#pragma mark - Set multi-select chooser (#5)

// A full-screen modal checklist for a Set<Enum> config item: every enum constant is a tappable row with a
// checkmark; Done returns the selected element names so the editor can write them back as a json-array.
@interface MUISetChooser : UIViewController <UITableViewDataSource, UITableViewDelegate>
@end

@implementation MUISetChooser {
    NSString *_title;
    NSArray<NSString *> *_choices;
    NSMutableSet<NSString *> *_selected;
    void (^_onDone)(NSArray<NSString *> *);
    UITableView *_table;
}

- (instancetype)initWithTitle:(NSString *)title choices:(NSArray<NSString *> *)choices
                     selected:(NSArray<NSString *> *)selected onDone:(void (^)(NSArray<NSString *> *))onDone {
    if ((self = [super init])) {
        _title = [title copy];
        _choices = [choices copy] ?: @[];
        _selected = [NSMutableSet setWithArray:(selected ?: @[])];
        _onDone = [onDone copy];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = _title ?: @"Select";
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

    _table = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStyleInsetGrouped];
    _table.translatesAutoresizingMaskIntoConstraints = NO;
    _table.backgroundColor = [UIColor clearColor];
    _table.dataSource = self;
    _table.delegate = self;
    [self.view addSubview:_table];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [title.topAnchor constraintEqualToAnchor:sg.topAnchor constant:14],
        [title.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:20],
        [done.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [done.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-20],
        [_table.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:10],
        [_table.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [_table.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [_table.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
    ]];
}

- (void)onDone {
    // Preserve the choices' declaration order in the written array (stable, matches the enum order).
    NSMutableArray *out = [NSMutableArray array];
    for (NSString *c in _choices) { if ([_selected containsObject:c]) [out addObject:c]; }
    if (_onDone) _onDone(out);
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return (NSInteger)_choices.count;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    UITableViewCell *cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleDefault reuseIdentifier:nil];
    NSString *c = _choices[indexPath.row];
    cell.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.06];
    cell.textLabel.textColor = [UIColor whiteColor];
    cell.textLabel.text = c;
    cell.accessoryType = [_selected containsObject:c]
        ? UITableViewCellAccessoryCheckmark : UITableViewCellAccessoryNone;
    cell.tintColor = [UIColor colorWithRed:1.0 green:0.78 blue:0.20 alpha:1.0];
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    NSString *c = _choices[indexPath.row];
    if ([_selected containsObject:c]) [_selected removeObject:c]; else [_selected addObject:c];
    [tableView reloadRowsAtIndexPaths:@[indexPath] withRowAnimation:UITableViewRowAnimationNone];
}

@end

@interface PluginConfigViewController () <UITableViewDataSource, UITableViewDelegate, UIColorPickerViewControllerDelegate>
@property (nonatomic, copy) NSString *group;
@property (nonatomic, copy) NSString *pluginName;
@property (nonatomic, copy) NSArray<NSString *> *whitelist;   // nil = full config; else only these keys, in order
@property (nonatomic, strong) UITableView *table;
// Sections in declaration order: each @{ @"name": NSString, @"items": NSMutableArray<NSMutableDictionary*> }.
@property (nonatomic, strong) NSMutableArray<NSMutableDictionary *> *sections;
// The item currently being edited via the system color picker (so the delegate callback knows the key/type).
@property (nonatomic, strong) NSMutableDictionary *colorEditItem;
@end

@implementation PluginConfigViewController

- (instancetype)initWithGroup:(NSString *)group pluginName:(NSString *)name {
    return [self initWithGroup:group pluginName:name whitelist:nil];
}

- (instancetype)initWithGroup:(NSString *)group pluginName:(NSString *)name
                    whitelist:(NSArray<NSString *> *)whitelist {
    if ((self = [super init])) {
        _group = [group copy];
        _pluginName = [name copy];
        _whitelist = [whitelist copy];
        _sections = [NSMutableArray array];
        self.modalPresentationStyle = UIModalPresentationFullScreen;
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = self.pluginName ?: @"Configuration";
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

    self.table = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStyleInsetGrouped];
    self.table.translatesAutoresizingMaskIntoConstraints = NO;
    self.table.backgroundColor = [UIColor clearColor];
    self.table.dataSource = self;
    self.table.delegate = self;
    // Deliver touches to the cell controls immediately (snappier), but keep canCancelContentTouches at its
    // default so the list still scrolls normally.
    self.table.delaysContentTouches = NO;
    [self.view addSubview:self.table];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [title.topAnchor constraintEqualToAnchor:sg.topAnchor constant:14],
        [title.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:20],
        [done.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [done.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-20],
        [self.table.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:10],
        [self.table.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.table.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.table.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
    ]];

    [self loadItems];
}

- (void)onDone {
    // Persist edits now — iOS won't run RuneLite's shutdown/periodic flush when the app closes.
    [JVMLauncher mobileFlushConfig];
    [self dismissViewControllerAnimated:YES completion:nil];
}

// Read getPluginConfig(group) and bucket items into sections (declaration order).
- (void)loadItems {
    [self.sections removeAllObjects];
    NSString *json = [JVMLauncher mobilePluginConfig:self.group];
    if (!json.length) { [self.table reloadData]; return; }
    NSError *err = nil;
    id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding]
                                                options:0 error:&err];
    if (![parsed isKindOfClass:[NSDictionary class]]) { [self.table reloadData]; return; }
    NSArray *items = parsed[@"items"];
    if (![items isKindOfClass:[NSArray class]]) { [self.table reloadData]; return; }
    // Curated (whitelist) mode: one flat section, items in whitelist order, skipping anything not present.
    if (self.whitelist) {
        NSMutableArray *picked = [NSMutableArray array];
        for (NSString *wantKey in self.whitelist) {
            for (NSDictionary *it in items) {
                if ([it[@"key"] isEqualToString:wantKey]) { [picked addObject:[it mutableCopy]]; break; }
            }
        }
        [self.sections addObject:[@{ @"name": @"", @"items": picked } mutableCopy]];
        [self.table reloadData];
        return;
    }
    for (NSDictionary *it in items) {
        NSString *sec = [it[@"section"] isKindOfClass:[NSString class]] ? it[@"section"] : @"";
        NSMutableDictionary *bucket = nil;
        for (NSMutableDictionary *s in self.sections) {
            if ([s[@"name"] isEqualToString:sec]) { bucket = s; break; }
        }
        if (!bucket) {
            bucket = [@{ @"name": sec, @"items": [NSMutableArray array] } mutableCopy];
            [self.sections addObject:bucket];
        }
        [bucket[@"items"] addObject:[it mutableCopy]];
    }
    [self.table reloadData];
}

- (NSMutableDictionary *)itemAt:(NSIndexPath *)ip {
    NSMutableArray *items = self.sections[ip.section][@"items"];
    return items[ip.row];
}

#pragma mark - table

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView {
    return (NSInteger)self.sections.count;
}

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return (NSInteger)[self.sections[section][@"items"] count];
}

- (NSString *)tableView:(UITableView *)tableView titleForHeaderInSection:(NSInteger)section {
    NSString *name = self.sections[section][@"name"];
    return name.length ? name : nil;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    NSMutableDictionary *it = [self itemAt:indexPath];
    NSString *type = it[@"type"];

    UITableViewCell *cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleValue1 reuseIdentifier:nil];
    cell.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.06];
    cell.textLabel.textColor = [UIColor whiteColor];
    cell.textLabel.text = it[@"name"];
    cell.textLabel.numberOfLines = 0;
    cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.72 alpha:1.0];
    cell.selectionStyle = UITableViewCellSelectionStyleDefault;

    NSString *value = [it[@"value"] isKindOfClass:[NSString class]] ? it[@"value"] : nil;

    if ([type isEqualToString:@"boolean"]) {
        UISwitch *sw = [[UISwitch alloc] init];
        sw.on = [value isEqualToString:@"true"];
        sw.tag = (indexPath.section << 16) | indexPath.row;
        [sw addTarget:self action:@selector(onSwitch:) forControlEvents:UIControlEventValueChanged];
        cell.accessoryView = sw;
        cell.selectionStyle = UITableViewCellSelectionStyleNone;
    } else if ([type isEqualToString:@"color"]) {
        // Small swatch + the signed value as detail; tap opens the system color picker.
        UIView *swatch = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 26, 26)];
        swatch.backgroundColor = [self colorFromSigned:value] ?: [UIColor clearColor];
        swatch.layer.cornerRadius = 5;
        swatch.layer.borderWidth = 1;
        swatch.layer.borderColor = [UIColor colorWithWhite:1 alpha:0.4].CGColor;
        cell.accessoryView = swatch;
        cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
    } else if ([type isEqualToString:@"int"] || [type isEqualToString:@"double"]
               || [type isEqualToString:@"string"] || [type isEqualToString:@"enum"]) {
        NSString *units = [it[@"units"] isKindOfClass:[NSString class]] ? it[@"units"] : @"";
        cell.detailTextLabel.text = value ? [value stringByAppendingString:units] : @"(default)";
        cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
    } else if ([type isEqualToString:@"dimension"]) {
        // "WIDTHxHEIGHT" (bridge encoding=WxH); tap opens a two-field editor.
        cell.detailTextLabel.text = value.length ? value : @"(default)";
        cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
    } else if ([type isEqualToString:@"set"]) {
        // Set<Enum> (bridge encoding=json-array); show the selected element names; tap opens the multi-select.
        NSArray *sel = [self setValuesFrom:value];
        cell.detailTextLabel.text = sel.count ? [sel componentsJoinedByString:@", "] : @"(none)";
        cell.detailTextLabel.lineBreakMode = NSLineBreakByTruncatingTail;
        cell.accessoryType = UITableViewCellAccessoryDisclosureIndicator;
    } else if ([type isEqualToString:@"notification"]) {
        // Notification config is a compound object; surface its primary control — enabled — as a toggle (the
        // engine checks the per-item enabled before falling back to defaults, so flipping it cleanly turns this
        // notification on/off). Sound/flash/etc. are a future sub-screen; on mobile the banner is delivered
        // regardless of the desktop sound/flash fields.
        UISwitch *sw = [[UISwitch alloc] init];
        sw.on = [self notificationEnabled:value];
        sw.tag = (indexPath.section << 16) | indexPath.row;
        [sw addTarget:self action:@selector(onNotificationSwitch:) forControlEvents:UIControlEventValueChanged];
        cell.accessoryView = sw;
        cell.selectionStyle = UITableViewCellSelectionStyleNone;
    } else {
        // set / keybind / dimension / unknown → read-only for v1. Don't dump raw serialized blobs as the value.
        BOOL looksSerialized = [value hasPrefix:@"{"] || [value hasPrefix:@"["];
        cell.detailTextLabel.text = (value && !looksSerialized) ? value : @"(not editable yet)";
        cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.5 alpha:1.0];
        cell.selectionStyle = UITableViewCellSelectionStyleNone;
    }
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    NSMutableDictionary *it = [self itemAt:indexPath];
    NSString *type = it[@"type"];
    if ([type isEqualToString:@"enum"]) {
        [self editEnum:it at:indexPath];
    } else if ([type isEqualToString:@"color"]) {
        [self editColor:it];
    } else if ([type isEqualToString:@"int"] || [type isEqualToString:@"double"] || [type isEqualToString:@"string"]) {
        [self editText:it type:type at:indexPath];
    } else if ([type isEqualToString:@"dimension"]) {
        [self editDimension:it at:indexPath];
    } else if ([type isEqualToString:@"set"]) {
        [self editSet:it at:indexPath];
    }
}

// Reset-to-default via a trailing swipe on any editable row.
- (UISwipeActionsConfiguration *)tableView:(UITableView *)tableView
    trailingSwipeActionsConfigurationForRowAtIndexPath:(NSIndexPath *)indexPath {
    NSMutableDictionary *it = [self itemAt:indexPath];
    NSString *type = it[@"type"];
    BOOL editable = [@[@"boolean", @"int", @"double", @"string", @"enum", @"color", @"dimension", @"set"]
                     containsObject:type ?: @""];
    if (!editable) return nil;
    __weak typeof(self) ws = self;
    UIContextualAction *reset = [UIContextualAction contextualActionWithStyle:UIContextualActionStyleDestructive
        title:@"Reset" handler:^(UIContextualAction *action, UIView *sourceView, void (^completion)(BOOL)) {
            [JVMLauncher mobileResetConfigGroup:ws.group key:it[@"key"]];
            // re-read the group after the client thread applies the default
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.4 * NSEC_PER_SEC)),
                           dispatch_get_main_queue(), ^{ [ws loadItems]; });
            completion(YES);
        }];
    reset.backgroundColor = [UIColor systemOrangeColor];
    return [UISwipeActionsConfiguration configurationWithActions:@[reset]];
}

#pragma mark - editors

- (void)onSwitch:(UISwitch *)sw {
    NSIndexPath *ip = [NSIndexPath indexPathForRow:(sw.tag & 0xFFFF) inSection:(sw.tag >> 16)];
    NSMutableDictionary *it = [self itemAt:ip];
    [self writeKey:it[@"key"] value:(sw.isOn ? @"true" : @"false") item:it];
}

// The "enabled" field of a serialized Notification config value (default YES when absent/unparseable).
- (BOOL)notificationEnabled:(NSString *)json {
    if (![json isKindOfClass:[NSString class]] || json.length == 0) return YES;
    id obj = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
    if (![obj isKindOfClass:[NSDictionary class]]) return YES;
    id e = ((NSDictionary *)obj)[@"enabled"];
    return e ? [e boolValue] : YES;
}

// Toggle a Notification config item on/off by flipping ONLY its "enabled" field (preserving every other field),
// then writing the re-serialized object back. The engine checks per-item enabled first, so this turns the
// notification on/off without overriding the user's other notification settings.
- (void)onNotificationSwitch:(UISwitch *)sw {
    NSIndexPath *ip = [NSIndexPath indexPathForRow:(sw.tag & 0xFFFF) inSection:(sw.tag >> 16)];
    NSMutableDictionary *it = [self itemAt:ip];
    NSString *cur = [it[@"value"] isKindOfClass:[NSString class]] ? it[@"value"] : @"";
    NSMutableDictionary *obj = nil;
    if (cur.length) {
        id parsed = [NSJSONSerialization JSONObjectWithData:[cur dataUsingEncoding:NSUTF8StringEncoding]
                                                    options:NSJSONReadingMutableContainers error:nil];
        if ([parsed isKindOfClass:[NSDictionary class]]) obj = parsed;
    }
    if (!obj) obj = [NSMutableDictionary dictionary];
    obj[@"enabled"] = @(sw.isOn);
    NSData *out = [NSJSONSerialization dataWithJSONObject:obj options:0 error:nil];
    NSString *json = out ? [[NSString alloc] initWithData:out encoding:NSUTF8StringEncoding] : nil;
    if (json) [self writeKey:it[@"key"] value:json item:it];
}

- (void)editEnum:(NSMutableDictionary *)it at:(NSIndexPath *)ip {
    NSArray *choices = [it[@"choices"] isKindOfClass:[NSArray class]] ? it[@"choices"] : @[];
    UIAlertController *ac = [UIAlertController alertControllerWithTitle:it[@"name"] message:nil
                                                       preferredStyle:UIAlertControllerStyleActionSheet];
    for (NSString *c in choices) {
        [ac addAction:[UIAlertAction actionWithTitle:c style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
            [self writeKey:it[@"key"] value:c item:it];
            [self.table reloadRowsAtIndexPaths:@[ip] withRowAnimation:UITableViewRowAnimationNone];
        }]];
    }
    [ac addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    ac.popoverPresentationController.sourceView = [self.table cellForRowAtIndexPath:ip];
    [self presentViewController:ac animated:YES completion:nil];
}

- (void)editText:(NSMutableDictionary *)it type:(NSString *)type at:(NSIndexPath *)ip {
    UIAlertController *ac = [UIAlertController alertControllerWithTitle:it[@"name"]
        message:it[@"description"] preferredStyle:UIAlertControllerStyleAlert];
    NSString *cur = [it[@"value"] isKindOfClass:[NSString class]] ? it[@"value"] : @"";
    [ac addTextFieldWithConfigurationHandler:^(UITextField *tf) {
        tf.text = cur;
        if ([type isEqualToString:@"int"]) tf.keyboardType = UIKeyboardTypeNumberPad;
        else if ([type isEqualToString:@"double"]) tf.keyboardType = UIKeyboardTypeDecimalPad;
    }];
    [ac addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [ac addAction:[UIAlertAction actionWithTitle:@"Set" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
        NSString *raw = ac.textFields.firstObject.text ?: @"";
        NSString *out = raw;
        if ([type isEqualToString:@"int"]) {
            long long v = [raw longLongValue];
            if (it[@"min"]) v = MAX(v, [it[@"min"] longLongValue]);
            if (it[@"max"]) v = MIN(v, [it[@"max"] longLongValue]);
            out = [NSString stringWithFormat:@"%lld", v];
        } else if ([type isEqualToString:@"double"]) {
            out = [NSString stringWithFormat:@"%g", [raw doubleValue]];
        }
        [self writeKey:it[@"key"] value:out item:it];
        [self.table reloadRowsAtIndexPaths:@[ip] withRowAnimation:UITableViewRowAnimationNone];
    }]];
    [self presentViewController:ac animated:YES completion:nil];
}

// Dimension editor: two number fields -> "WIDTHxHEIGHT" (the bridge's WxH encoding).
- (void)editDimension:(NSMutableDictionary *)it at:(NSIndexPath *)ip {
    NSString *cur = [it[@"value"] isKindOfClass:[NSString class]] ? it[@"value"] : @"";
    NSArray *parts = [cur componentsSeparatedByString:@"x"];
    NSString *cw = parts.count == 2 ? parts[0] : @"";
    NSString *ch = parts.count == 2 ? parts[1] : @"";
    UIAlertController *ac = [UIAlertController alertControllerWithTitle:it[@"name"]
        message:it[@"description"] preferredStyle:UIAlertControllerStyleAlert];
    [ac addTextFieldWithConfigurationHandler:^(UITextField *tf) {
        tf.placeholder = @"Width"; tf.keyboardType = UIKeyboardTypeNumberPad; tf.text = cw;
    }];
    [ac addTextFieldWithConfigurationHandler:^(UITextField *tf) {
        tf.placeholder = @"Height"; tf.keyboardType = UIKeyboardTypeNumberPad; tf.text = ch;
    }];
    [ac addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    [ac addAction:[UIAlertAction actionWithTitle:@"Set" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
        int nw = [ac.textFields[0].text intValue];
        int nh = [ac.textFields[1].text intValue];
        if (nw <= 0 || nh <= 0) return;   // ignore garbage; keep the prior value
        [self writeKey:it[@"key"] value:[NSString stringWithFormat:@"%dx%d", nw, nh] item:it];
        [self.table reloadRowsAtIndexPaths:@[ip] withRowAnimation:UITableViewRowAnimationNone];
    }]];
    [self presentViewController:ac animated:YES completion:nil];
}

// Set<Enum> editor: multi-select checklist -> json-array of element names (the bridge's json-array encoding).
- (void)editSet:(NSMutableDictionary *)it at:(NSIndexPath *)ip {
    NSArray *choices = [it[@"choices"] isKindOfClass:[NSArray class]] ? it[@"choices"] : @[];
    NSArray *selected = [self setValuesFrom:it[@"value"]];
    __weak typeof(self) ws = self;
    MUISetChooser *ch = [[MUISetChooser alloc] initWithTitle:it[@"name"] choices:choices selected:selected
        onDone:^(NSArray<NSString *> *newSel) {
            NSData *d = [NSJSONSerialization dataWithJSONObject:newSel options:0 error:nil];
            NSString *json = d ? [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] : @"[]";
            [ws writeKey:it[@"key"] value:json item:it];
            [ws.table reloadRowsAtIndexPaths:@[ip] withRowAnimation:UITableViewRowAnimationNone];
        }];
    ch.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:ch animated:YES completion:nil];
}

// Parse a Set value (gson json-array of element names) into an ordered string array.
- (NSArray<NSString *> *)setValuesFrom:(id)json {
    if (![json isKindOfClass:[NSString class]] || [(NSString *)json length] == 0) return @[];
    id arr = [NSJSONSerialization JSONObjectWithData:[(NSString *)json dataUsingEncoding:NSUTF8StringEncoding]
                                             options:0 error:nil];
    if (![arr isKindOfClass:[NSArray class]]) return @[];
    NSMutableArray *out = [NSMutableArray array];
    for (id e in arr) { if ([e isKindOfClass:[NSString class]]) [out addObject:e]; }
    return out;
}

- (void)editColor:(NSMutableDictionary *)it {
    self.colorEditItem = it;
    UIColorPickerViewController *cp = [[UIColorPickerViewController alloc] init];
    cp.delegate = self;
    cp.supportsAlpha = [it[@"alpha"] boolValue];
    UIColor *seed = [self colorFromSigned:it[@"value"]];
    if (seed) cp.selectedColor = seed;
    [self presentViewController:cp animated:YES completion:nil];
}

- (void)colorPickerViewControllerDidSelectColor:(UIColorPickerViewController *)viewController {
    NSMutableDictionary *it = self.colorEditItem;
    if (!it) return;
    NSString *dec = [self signedFromColor:viewController.selectedColor alpha:[it[@"alpha"] boolValue]];
    [self writeKey:it[@"key"] value:dec item:it];
}

- (void)colorPickerViewControllerDidFinish:(UIColorPickerViewController *)viewController {
    self.colorEditItem = nil;
    [self.table reloadData];
}

// Write through the proven facade and update the local snapshot so the row reflects it immediately.
- (void)writeKey:(NSString *)key value:(NSString *)value item:(NSMutableDictionary *)it {
    NSString *ack = [JVMLauncher mobileSetConfigGroup:self.group key:key value:value];
    it[@"value"] = value;
    NSLog(@"[plugincfg] %@.%@ = %@ : %@", self.group, key, value, ack ?: @"(nil)");
}

#pragma mark - color helpers (signed-decimal ARGB == ConfigManager's Color contract)

- (UIColor *)colorFromSigned:(id)dec {
    if (![dec isKindOfClass:[NSString class]] || [(NSString *)dec length] == 0) return nil;
    long long v = [(NSString *)dec longLongValue];
    uint32_t argb = (uint32_t)v;
    CGFloat a = ((argb >> 24) & 0xFF) / 255.0;
    CGFloat r = ((argb >> 16) & 0xFF) / 255.0;
    CGFloat g = ((argb >> 8) & 0xFF) / 255.0;
    CGFloat b = (argb & 0xFF) / 255.0;
    return [UIColor colorWithRed:r green:g blue:b alpha:a];
}

- (NSString *)signedFromColor:(UIColor *)c alpha:(BOOL)alpha {
    CGFloat r = 0, g = 0, b = 0, a = 1;
    [c getRed:&r green:&g blue:&b alpha:&a];
    uint32_t A = alpha ? (uint32_t)lround(a * 255.0) : 0xFFu;
    uint32_t argb = (A << 24)
        | (((uint32_t)lround(r * 255.0)) << 16)
        | (((uint32_t)lround(g * 255.0)) << 8)
        | ((uint32_t)lround(b * 255.0));
    return [NSString stringWithFormat:@"%d", (int32_t)argb];  // signed decimal — what ColorUtil.fromString expects
}

@end
