#import "JagexLoginSpikeViewController.h"
#import <WebKit/WebKit.h>

// ── Jagex OAuth constants — verbatim from ios/dev-tools/fetch-shield-tokens.sh (Stage A consent flow) ──
// Stage A produces the id_token the game-session API accepts (the launcher client's id_token is rejected
// with INVALID_ID_TOKEN). redirect_uri is a loopback that never loads; we intercept the navigation to it
// and read the id_token out of the URL fragment.
static NSString *const kAuthEndpoint    = @"https://account.jagex.com/oauth2/auth";
static NSString *const kConsentClientId = @"1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
static NSString *const kConsentRedirect = @"http://localhost";
static NSString *const kConsentScope    = @"openid offline";
static NSString *const kSessionsURL     = @"https://auth.runescape.com/game-session/v1/sessions";
static NSString *const kAccountsURL     = @"https://auth.runescape.com/game-session/v2/accounts?fetchMembership=true";
// Cloudflare in front of the game-session API is User-Agent sensitive — match the official Mac launcher.
static NSString *const kLauncherUA =
    @"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
    @"JagexLauncher/2.4.4 Chrome/120.0.0.0 Safari/537.36";

@interface JagexLoginSpikeViewController () <WKNavigationDelegate>
@property (nonatomic, strong) WKWebView *web;
@property (nonatomic, strong) UITextView *logView;
@property (nonatomic, copy)   NSString *nonce;
@property (nonatomic, copy)   NSString *oauthState;
@property (nonatomic, assign) BOOL captured;   // handle the redirect exactly once
@end

@implementation JagexLoginSpikeViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    self.nonce      = [self randomToken];
    self.oauthState = [self randomToken];

    // Top bar with a Close button.
    UIView *bar = [UIView new];
    bar.translatesAutoresizingMaskIntoConstraints = NO;
    bar.backgroundColor = [UIColor colorWithWhite:0.10 alpha:1.0];
    [self.view addSubview:bar];

    UILabel *title = [UILabel new];
    title.translatesAutoresizingMaskIntoConstraints = NO;
    title.text = @"Jagex login (spike)";
    title.textColor = [UIColor whiteColor];
    title.font = [UIFont boldSystemFontOfSize:15];
    [bar addSubview:title];

    UIButton *close = [UIButton buttonWithType:UIButtonTypeSystem];
    close.translatesAutoresizingMaskIntoConstraints = NO;
    [close setTitle:@"Close" forState:UIControlStateNormal];
    [close addTarget:self action:@selector(onClose) forControlEvents:UIControlEventTouchUpInside];
    [bar addSubview:close];

    // Embedded login web view.
    WKWebViewConfiguration *cfg = [WKWebViewConfiguration new];
    self.web = [[WKWebView alloc] initWithFrame:CGRectZero configuration:cfg];
    self.web.translatesAutoresizingMaskIntoConstraints = NO;
    self.web.navigationDelegate = self;
    [self.view addSubview:self.web];

    // Verdict / progress strip.
    self.logView = [UITextView new];
    self.logView.translatesAutoresizingMaskIntoConstraints = NO;
    self.logView.editable = NO;
    self.logView.backgroundColor = [UIColor colorWithWhite:0.05 alpha:1.0];
    self.logView.textColor = [UIColor greenColor];
    self.logView.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightRegular];
    [self.view addSubview:self.logView];

    UILayoutGuide *g = self.view.safeAreaLayoutGuide;
    [NSLayoutConstraint activateConstraints:@[
        [bar.topAnchor constraintEqualToAnchor:g.topAnchor],
        [bar.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [bar.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [bar.heightAnchor constraintEqualToConstant:44],
        [title.leadingAnchor constraintEqualToAnchor:bar.leadingAnchor constant:14],
        [title.centerYAnchor constraintEqualToAnchor:bar.centerYAnchor],
        [close.trailingAnchor constraintEqualToAnchor:bar.trailingAnchor constant:-14],
        [close.centerYAnchor constraintEqualToAnchor:bar.centerYAnchor],

        [self.web.topAnchor constraintEqualToAnchor:bar.bottomAnchor],
        [self.web.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.web.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],

        [self.logView.topAnchor constraintEqualToAnchor:self.web.bottomAnchor],
        [self.logView.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.logView.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [self.logView.bottomAnchor constraintEqualToAnchor:g.bottomAnchor],
        [self.logView.heightAnchor constraintEqualToConstant:150],
    ]];

    [self log:@"Stage A — loading Jagex consent login…"];
    [self loadConsent];
}

- (void)loadConsent {
    NSURLComponents *c = [NSURLComponents componentsWithString:kAuthEndpoint];
    c.queryItems = @[
        [NSURLQueryItem queryItemWithName:@"response_type" value:@"id_token code"], // OIDC hybrid
        [NSURLQueryItem queryItemWithName:@"client_id"     value:kConsentClientId],
        [NSURLQueryItem queryItemWithName:@"redirect_uri"  value:kConsentRedirect],
        [NSURLQueryItem queryItemWithName:@"scope"         value:kConsentScope],
        [NSURLQueryItem queryItemWithName:@"nonce"         value:self.nonce],
        [NSURLQueryItem queryItemWithName:@"state"         value:self.oauthState],
    ];
    [self.web loadRequest:[NSURLRequest requestWithURL:c.URL]];
}

#pragma mark - WKNavigationDelegate

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
                    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    NSURL *url = navigationAction.request.URL;
    // The consent flow redirects to http://localhost/#id_token=…&code=…&state=… — a loopback that won't
    // load. Catch it BEFORE the (doomed) navigation and pull the id_token from the fragment. The decision
    // delegate fires ahead of the network layer, so ATS never blocks the http://localhost we cancel.
    if ([url.host isEqualToString:@"localhost"]) {
        decisionHandler(WKNavigationActionPolicyCancel);
        if (!self.captured) {
            self.captured = YES;
            [self handleConsentRedirect:url];
        }
        return;
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)webView
    didFailProvisionalNavigation:(WKNavigation *)navigation
                       withError:(NSError *)error {
    if (error.code == NSURLErrorCancelled) return;   // our own cancel of the localhost nav
    [self log:[NSString stringWithFormat:@"nav error: %@", error.localizedDescription]];
}

- (void)handleConsentRedirect:(NSURL *)url {
    [self log:@"✓ caught redirect to localhost"];
    NSString *frag = url.fragment ?: @"";
    NSString *idToken = [self valueFor:@"id_token" in:frag];
    if (idToken.length == 0) {
        [self log:[NSString stringWithFormat:@"✗ no id_token in fragment (frag len=%lu)", (unsigned long)frag.length]];
        return;
    }
    [self log:[NSString stringWithFormat:@"✓ id_token captured (len=%lu)", (unsigned long)idToken.length]];
    self.web.hidden = YES;
    [self fetchSessionWithIdToken:idToken];
}

#pragma mark - Game-session API (native URLSession)

- (void)fetchSessionWithIdToken:(NSString *)idToken {
    [self log:@"POST /game-session/v1/sessions …"];
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:kSessionsURL]];
    req.HTTPMethod = @"POST";
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [req setValue:@"application/json" forHTTPHeaderField:@"Accept"];
    [req setValue:kLauncherUA        forHTTPHeaderField:@"User-Agent"];
    req.HTTPBody = [NSJSONSerialization dataWithJSONObject:@{ @"idToken": idToken } options:0 error:nil];

    [[NSURLSession.sharedSession dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (error) { [self log:[NSString stringWithFormat:@"✗ sessions error: %@", error.localizedDescription]]; return; }
            NSInteger code = [(NSHTTPURLResponse *)resp statusCode];
            id obj = data.length ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
            NSString *sessionId = [obj isKindOfClass:NSDictionary.class] ? obj[@"sessionId"] : nil;
            if (sessionId.length == 0) {
                [self log:[NSString stringWithFormat:@"✗ no sessionId (HTTP %ld): %@", (long)code, [self snippet:data]]];
                return;
            }
            [self log:[NSString stringWithFormat:@"✓ sessionId (len=%lu, HTTP %ld)", (unsigned long)sessionId.length, (long)code]];
            [self fetchAccountsWithSession:sessionId];
        });
    }] resume];
}

- (void)fetchAccountsWithSession:(NSString *)sessionId {
    [self log:@"GET /game-session/v2/accounts …"];
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:kAccountsURL]];
    [req setValue:[NSString stringWithFormat:@"Bearer %@", sessionId] forHTTPHeaderField:@"Authorization"];
    [req setValue:@"application/json" forHTTPHeaderField:@"Accept"];
    [req setValue:kLauncherUA        forHTTPHeaderField:@"User-Agent"];

    [[NSURLSession.sharedSession dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *error) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (error) { [self log:[NSString stringWithFormat:@"✗ accounts error: %@", error.localizedDescription]]; return; }
            NSInteger code = [(NSHTTPURLResponse *)resp statusCode];
            id arr = data.length ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
            if (![arr isKindOfClass:NSArray.class]) {
                [self log:[NSString stringWithFormat:@"✗ accounts not an array (HTTP %ld): %@", (long)code, [self snippet:data]]];
                return;
            }
            [self log:[NSString stringWithFormat:@"✓ %lu character(s) (HTTP %ld):", (unsigned long)[arr count], (long)code]];
            for (NSDictionary *a in arr) {
                if (![a isKindOfClass:NSDictionary.class]) continue;
                NSString *name = a[@"displayName"] ?: @"<no displayName>";
                NSString *acc  = a[@"accountId"]   ?: @"?";
                [self log:[NSString stringWithFormat:@"   • %@   (accountId=%@)", name, acc]];
            }
            [self log:@""];
            [self log:@"VERDICT: LOGIN-SPIKE GREEN — the phone minted a session + character list."];
        });
    }] resume];
}

#pragma mark - helpers

// Pull a value out of an x-www-form-urlencoded fragment/query string.
- (NSString *)valueFor:(NSString *)key in:(NSString *)query {
    for (NSString *pair in [query componentsSeparatedByString:@"&"]) {
        NSRange eq = [pair rangeOfString:@"="];
        if (eq.location == NSNotFound) continue;
        if ([[pair substringToIndex:eq.location] isEqualToString:key]) {
            NSString *v = [pair substringFromIndex:eq.location + 1];
            return [v stringByRemovingPercentEncoding] ?: v;
        }
    }
    return nil;
}

- (NSString *)randomToken {
    uint8_t buf[24];
    arc4random_buf(buf, sizeof(buf));
    NSString *s = [[NSData dataWithBytes:buf length:sizeof(buf)] base64EncodedStringWithOptions:0];
    s = [s stringByReplacingOccurrencesOfString:@"+" withString:@"-"];
    s = [s stringByReplacingOccurrencesOfString:@"/" withString:@"_"];
    s = [s stringByReplacingOccurrencesOfString:@"=" withString:@""];
    return s;
}

- (NSString *)snippet:(NSData *)data {
    if (!data.length) return @"(no body)";
    NSString *s = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"(binary)";
    return s.length > 300 ? [[s substringToIndex:300] stringByAppendingString:@"…"] : s;
}

// Only ever receives redacted lines (lengths / display names / verdicts) — never a raw token.
- (void)log:(NSString *)line {
    NSLog(@"[login-spike] %@", line);
    if (self.onLog) self.onLog(line);
    self.logView.text = self.logView.text.length
        ? [NSString stringWithFormat:@"%@\n%@", self.logView.text, line] : line;
    [self.logView scrollRangeToVisible:NSMakeRange(self.logView.text.length, 0)];

    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"login-spike.log"];
    NSString *entry = [line stringByAppendingString:@"\n"];
    NSFileHandle *fh = [NSFileHandle fileHandleForWritingAtPath:path];
    if (!fh) { [entry writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil]; }
    else     { [fh seekToEndOfFile]; [fh writeData:[entry dataUsingEncoding:NSUTF8StringEncoding]]; [fh closeFile]; }
}

- (void)onClose {
    [self dismissViewControllerAnimated:YES completion:nil];
}

@end
