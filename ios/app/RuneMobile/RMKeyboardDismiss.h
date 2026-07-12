#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

// Gives search/text fields an always-available way to close the soft keyboard. Their only built-in
// dismiss is the Return/Search key, which iOS greys out while the field is empty — so a user who opens
// the keyboard without typing (or wants to keep an empty filter) gets stuck with no way to close it.
// Installing this adds a "Done" bar above the keyboard AND re-enables the empty Return key, so the
// keyboard can be dismissed at any time without losing whatever was typed.
@interface UITextField (RMKeyboardDismiss)
- (void)rm_installKeyboardDoneBar;
@end

@interface UISearchBar (RMKeyboardDismiss)
- (void)rm_installKeyboardDoneBar;
@end

NS_ASSUME_NONNULL_END
