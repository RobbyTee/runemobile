#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// The four haptic triggers, each with its own switch.
typedef NS_ENUM(NSInteger, RMHaptic) {
    RMHapticOp = 0,    // tapping something you can interact with, or picking a menu option
    RMHapticDrag,      // picking up an item to move it
    RMHapticMenuOpen,  // the long-press menu appearing
    RMHapticMenuHover, // sliding over the menu's options
};

/// Posted on the main queue whenever a setting below changes.
extern NSNotificationName const RMGameSettingsDidChangeNotification;

/// The phone-side game settings behind Settings > Game settings. Kept in NSUserDefaults and read live, so a
/// change applies without a restart.
@interface RMGameSettings : NSObject
+ (BOOL)hapticOn:(RMHaptic)h; // default YES
+ (void)setHaptic:(RMHaptic)h on:(BOOL)on;
/// 0 light, 1 medium (default), 2 heavy.
+ (NSInteger)hapticStrength;
+ (void)setHapticStrength:(NSInteger)s;
/// How long a finger has to stay put before the long-press menu opens, in ms (200-1000, default 400).
+ (NSInteger)longPressMs;
+ (void)setLongPressMs:(NSInteger)ms;
/// Camera drag sensitivity, 0.1-4.0 (default 2.0, the drag speed the app has always used).
+ (double)cameraSensitivity;
+ (void)setCameraSensitivity:(double)v;
/// The camera drag multiplier: 1 at the default sensitivity.
+ (double)cameraGain;
@end

/// The native Game settings screen, pushed from Settings.
@interface RMGameSettingsViewController : UITableViewController
@end

NS_ASSUME_NONNULL_END
