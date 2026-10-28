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
    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.rootViewController = [[ViewController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}

// Settings > Game settings > Screen, for the whole launch: the game's canvas is sized once, when it starts. This
// replaces Info.plist's landscape-only list, which still covers the launch screen.
- (UIInterfaceOrientationMask)application:(UIApplication *)application
    supportedInterfaceOrientationsForWindow:(UIWindow *)window {
    return [RMGameSettings portraitThisLaunch] ? UIInterfaceOrientationMaskPortrait
                                               : UIInterfaceOrientationMaskLandscape;
}

@end
