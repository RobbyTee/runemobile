#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Phase-6 (W3): the native replacement for RuneLite's desktop config panel for ONE plugin. Reads the
/// plugin's config descriptor via MobileUiBridge.getPluginConfig (hidden items already filtered, secret
/// values masked) and renders a typed control per item — boolean/int/double/string/enum/color editable,
/// the rest read-only — each writing back through the device-proven MobileUiBridge.setConfig serialization.
/// Presented modally over the plugin list.
@interface PluginConfigViewController : UIViewController

- (instancetype)initWithGroup:(NSString *)group pluginName:(NSString *)name;

@end

NS_ASSUME_NONNULL_END
