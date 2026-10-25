#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// The four haptic triggers, each with its own switch.
typedef NS_ENUM(NSInteger, RMHaptic) {
    RMHapticOp = 0,    // tapping something you can interact with, or picking a menu option
    RMHapticDrag,      // picking up an item to move it
    RMHapticMenuOpen,  // the long-press menu appearing
    RMHapticMenuHover, // sliding over the menu's options
};

/// The phone-side game settings behind Settings > Game settings. Kept in NSUserDefaults and read live, so a
/// change applies without a restart.
@interface RMGameSettings : NSObject
+ (BOOL)hapticOn:(RMHaptic)h; // default YES
+ (void)setHaptic:(RMHaptic)h on:(BOOL)on;
/// 0 light, 1 medium (default), 2 heavy.
+ (NSInteger)hapticStrength;
+ (void)setHapticStrength:(NSInteger)s;
@end

/// The native Game settings screen, pushed from Settings.
@interface RMGameSettingsViewController : UITableViewController
@end

NS_ASSUME_NONNULL_END
