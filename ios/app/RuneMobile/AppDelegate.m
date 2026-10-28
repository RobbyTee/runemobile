#import "AppDelegate.h"
#import "RMGameSettings.h"
#import "ViewController.h"

@implementation AppDelegate

- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    // Seed the screenshot (📷) control as pinned on a fresh install so manual capture is one tap out of the box,
    // matching Android's always-present camera launcher tile. registerDefaults only supplies this while the key
    // is unset, so anyone who has already customized their pinned controls (or later unpins it) keeps their choice.
    [[NSUserDefaults standardUserDefaults] registerDefaults:@{@"pinnedControls" : @[ @"screenshot" ]}];
    // RMGameSettings reads the phone's orientation when Auto is picked (applyOrientationToWindow:).
    [[UIDevice currentDevice] beginGeneratingDeviceOrientationNotifications];
    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.rootViewController = [[ViewController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}

// Settings > Game settings > Screen: landscape, portrait, or Auto (either). The game resizes its canvas when the window
// turns. This replaces Info.plist's landscape-only list, which still covers the launch screen.
- (UIInterfaceOrientationMask)application:(UIApplication *)application
    supportedInterfaceOrientationsForWindow:(UIWindow *)window {
    return [RMGameSettings orientationMask];
}

@end
