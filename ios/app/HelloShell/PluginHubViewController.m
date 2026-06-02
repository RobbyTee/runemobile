#import "PluginHubViewController.h"
#import "JVMLauncher.h"

@interface PluginHubViewController () <UITableViewDataSource, UITableViewDelegate, UISearchBarDelegate>
@property (nonatomic, strong) UITableView *table;
@property (nonatomic, strong) UILabel *statusLabel;
@property (nonatomic, strong) UISearchBar *search;
@property (nonatomic, strong) NSArray<NSMutableDictionary *> *all;     // every hub plugin
@property (nonatomic, strong) NSArray<NSMutableDictionary *> *shown;   // filtered view
@property (nonatomic, copy)   NSString *filter;
@property (nonatomic, strong) NSTimer *pollTimer;
@end

@implementation PluginHubViewController

- (instancetype)init {
    if ((self = [super init])) {
        self.modalPresentationStyle = UIModalPresentationFullScreen;
        _all = @[]; _shown = @[]; _filter = @"";
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor colorWithRed:0.10 green:0.09 blue:0.07 alpha:1.0];

    UILabel *title = [[UILabel alloc] init];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Plugin Hub";
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

    self.search = [[UISearchBar alloc] init];
    self.search.translatesAutoresizingMaskIntoConstraints = NO;
    self.search.delegate = self;
    self.search.placeholder = @"Search plugins";
    self.search.searchBarStyle = UISearchBarStyleMinimal;
    self.search.barStyle = UIBarStyleBlack;
    [self.view addSubview:self.search];

    self.statusLabel = [[UILabel alloc] init];
    self.statusLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.statusLabel.textColor = [UIColor colorWithWhite:0.7 alpha:1.0];
    self.statusLabel.font = [UIFont systemFontOfSize:14];
    self.statusLabel.text = @"Loading manifest…";
    [self.view addSubview:self.statusLabel];

    self.table = [[UITableView alloc] initWithFrame:CGRectZero style:UITableViewStylePlain];
    self.table.translatesAutoresizingMaskIntoConstraints = NO;
    self.table.backgroundColor = [UIColor clearColor];
    self.table.dataSource = self;
    self.table.delegate = self;
    self.table.rowHeight = UITableViewAutomaticDimension;
    self.table.estimatedRowHeight = 72;
    self.table.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag;
    [self.view addSubview:self.table];

    UILayoutGuide *sg = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [title.topAnchor constraintEqualToAnchor:sg.topAnchor constant:14],
        [title.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:20],
        [done.centerYAnchor constraintEqualToAnchor:title.centerYAnchor],
        [done.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-20],
        [self.search.topAnchor constraintEqualToAnchor:title.bottomAnchor constant:8],
        [self.search.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:8],
        [self.search.trailingAnchor constraintEqualToAnchor:sg.trailingAnchor constant:-8],
        [self.statusLabel.topAnchor constraintEqualToAnchor:self.search.bottomAnchor constant:4],
        [self.statusLabel.leadingAnchor constraintEqualToAnchor:sg.leadingAnchor constant:20],
        [self.table.topAnchor constraintEqualToAnchor:self.statusLabel.bottomAnchor constant:6],
        [self.table.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.table.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.table.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
    ]];

    // Poll the manifest until it's downloaded (Java fetches it on a background thread).
    self.pollTimer = [NSTimer scheduledTimerWithTimeInterval:1.0 target:self
                                                    selector:@selector(pollManifest) userInfo:nil repeats:YES];
    [self pollManifest];
}

- (void)onDone {
    [self.pollTimer invalidate];
    self.pollTimer = nil;
    [JVMLauncher mobileFlushConfig];   // persist the installed-plugins list
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (void)pollManifest {
    NSString *json = [JVMLauncher mobileHubManifest];
    if (!json.length) { self.statusLabel.text = @"Bridge not ready…"; return; }
    NSError *err = nil;
    id parsed = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding]
                                                options:0 error:&err];
    if (![parsed isKindOfClass:[NSDictionary class]]) return;
    NSString *status = parsed[@"status"];
    if ([status isEqualToString:@"loading"]) { self.statusLabel.text = @"Downloading manifest…"; return; }
    if (![status isEqualToString:@"ok"]) {
        self.statusLabel.text = [NSString stringWithFormat:@"Manifest unavailable (%@)", status];
        [self.pollTimer invalidate]; self.pollTimer = nil;
        return;
    }
    // status ok — load once, stop polling.
    [self.pollTimer invalidate]; self.pollTimer = nil;
    NSArray *plugins = parsed[@"plugins"];
    NSMutableArray *rows = [NSMutableArray array];
    if ([plugins isKindOfClass:[NSArray class]]) {
        for (NSDictionary *p in plugins) [rows addObject:[p mutableCopy]];
        [rows sortUsingComparator:^NSComparisonResult(NSDictionary *a, NSDictionary *b) {
            return [([a[@"displayName"] description] ?: @"") caseInsensitiveCompare:([b[@"displayName"] description] ?: @"")];
        }];
    }
    self.all = rows;
    [self applyFilter];
    self.statusLabel.text = [NSString stringWithFormat:@"%lu plugins", (unsigned long)self.all.count];
}

- (void)applyFilter {
    if (self.filter.length == 0) {
        self.shown = self.all;
    } else {
        NSString *f = self.filter.lowercaseString;
        NSMutableArray *out = [NSMutableArray array];
        for (NSMutableDictionary *p in self.all) {
            NSString *hay = [NSString stringWithFormat:@"%@ %@ %@",
                             p[@"displayName"] ?: @"", p[@"author"] ?: @"", p[@"description"] ?: @""];
            if ([hay.lowercaseString containsString:f]) [out addObject:p];
        }
        self.shown = out;
    }
    [self.table reloadData];
}

#pragma mark search

- (void)searchBar:(UISearchBar *)searchBar textDidChange:(NSString *)searchText {
    self.filter = searchText ?: @"";
    [self applyFilter];
}

- (void)searchBarSearchButtonClicked:(UISearchBar *)searchBar { [searchBar resignFirstResponder]; }

#pragma mark table

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return (NSInteger)self.shown.count;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    static NSString *cid = @"hubCell";
    UITableViewCell *cell = [tableView dequeueReusableCellWithIdentifier:cid];
    if (!cell) {
        cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:cid];
        cell.backgroundColor = [UIColor clearColor];
        cell.textLabel.textColor = [UIColor whiteColor];
        cell.detailTextLabel.textColor = [UIColor colorWithWhite:0.66 alpha:1.0];
        cell.detailTextLabel.numberOfLines = 2;
        cell.selectionStyle = UITableViewCellSelectionStyleNone;
        UIButton *btn = [UIButton buttonWithType:UIButtonTypeSystem];
        btn.frame = CGRectMake(0, 0, 92, 30);
        btn.titleLabel.font = [UIFont boldSystemFontOfSize:14];
        btn.layer.cornerRadius = 7;
        [btn addTarget:self action:@selector(onToggleInstall:) forControlEvents:UIControlEventTouchUpInside];
        cell.accessoryView = btn;
    }
    NSDictionary *p = self.shown[(NSUInteger)indexPath.row];
    NSString *author = [p[@"author"] isKindOfClass:[NSString class]] ? p[@"author"] : @"";
    NSString *desc = [p[@"description"] isKindOfClass:[NSString class]] ? p[@"description"] : @"";
    cell.textLabel.text = [p[@"displayName"] description];
    cell.detailTextLabel.text = author.length ? [NSString stringWithFormat:@"%@ — %@", author, desc] : desc;

    UIButton *btn = (UIButton *)cell.accessoryView;
    btn.tag = indexPath.row;
    BOOL unavailable = [p[@"unavailable"] isKindOfClass:[NSString class]] && [p[@"unavailable"] length] > 0;
    BOOL installed = [p[@"installed"] boolValue];
    if (unavailable) {
        [btn setTitle:@"N/A" forState:UIControlStateNormal];
        btn.enabled = NO;
        btn.backgroundColor = [UIColor clearColor];
        btn.tintColor = [UIColor colorWithWhite:0.5 alpha:1.0];
    } else {
        btn.enabled = YES;
        [btn setTitle:(installed ? @"Remove" : @"Install") forState:UIControlStateNormal];
        btn.backgroundColor = installed ? [UIColor colorWithWhite:1 alpha:0.12]
                                        : [UIColor colorWithRed:0.20 green:0.45 blue:0.22 alpha:1.0];
        btn.tintColor = [UIColor whiteColor];
    }
    return cell;
}

- (void)onToggleInstall:(UIButton *)btn {
    NSInteger row = btn.tag;
    if (row < 0 || row >= (NSInteger)self.shown.count) return;
    NSMutableDictionary *p = self.shown[(NSUInteger)row];
    BOOL installed = [p[@"installed"] boolValue];
    NSString *name = [p[@"internalName"] description];
    NSString *ack = [JVMLauncher mobileHubInstall:name uninstall:installed];
    // optimistic flip; the actual load/unload happens async and shows in the main plugin panel
    p[@"installed"] = @(!installed);
    [self.table reloadRowsAtIndexPaths:@[[NSIndexPath indexPathForRow:row inSection:0]]
                      withRowAnimation:UITableViewRowAnimationNone];
    NSLog(@"[hub] %@ %@ : %@", installed ? @"remove" : @"install", name, ack ?: @"(nil)");
}

@end
