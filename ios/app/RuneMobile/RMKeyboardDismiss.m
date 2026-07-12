#import "RMKeyboardDismiss.h"

@implementation UITextField (RMKeyboardDismiss)

- (void)rm_installKeyboardDoneBar {
    // Keep the Return key tappable even when the field is empty, so it alone can dismiss.
    self.enablesReturnKeyAutomatically = NO;
    UIToolbar *bar = [[UIToolbar alloc] initWithFrame:CGRectMake(0, 0, 320, 44)];
    UIBarButtonItem *flex = [[UIBarButtonItem alloc]
        initWithBarButtonSystemItem:UIBarButtonSystemItemFlexibleSpace
                             target:nil
                             action:nil];
    UIBarButtonItem *done = [[UIBarButtonItem alloc]
        initWithBarButtonSystemItem:UIBarButtonSystemItemDone
                             target:self
                             action:@selector(resignFirstResponder)];
    bar.items = @[ flex, done ];
    [bar sizeToFit];
    self.inputAccessoryView = bar;
}

@end

@implementation UISearchBar (RMKeyboardDismiss)

- (void)rm_installKeyboardDoneBar {
    // searchTextField is the editable field behind the search bar (iOS 13+); the accessory + return-key
    // flag live on it, not the bar.
    [self.searchTextField rm_installKeyboardDoneBar];
}

@end
