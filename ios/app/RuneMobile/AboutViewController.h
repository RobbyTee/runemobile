#import <UIKit/UIKit.h>

// The on-device identity/disclosure surface (iOS parity with Android's AboutScreen): a full-screen modal that
// states the honest unofficial / not-affiliated / account-ban-risk framing plus open-source attribution, so the
// shipped app never implies a Jagex-official client. Reachable from the plugin panel's title row (ⓘ button).
@interface AboutViewController : UIViewController
@end
