#import <UIKit/UIKit.h>

// Login-UX spike (Phase 6): prove we can mint a Jagex game session ENTIRELY ON THE PHONE — no
// computer-minted tokens.json. Ports the Stage-A "consent" flow of ios/dev-tools/fetch-shield-tokens.sh
// into an embedded WKWebView (the only native unknown; the OAuth protocol is already desktop-proven):
//   1. load the account.jagex.com consent URL, user logs in (+2FA) in-page
//   2. intercept the http://localhost/#id_token=… redirect, pull the id_token
//   3. POST /game-session/v1/sessions {idToken} -> sessionId
//   4. GET  /game-session/v2/accounts -> the character list
// The spike only DISPLAYS the result (id_token len, sessionId len, character names) — it does not yet
// write tokens.json or arm autologin (that's the next step once this is GREEN). No raw token ever
// reaches a log: only lengths + display names are surfaced.
@interface JagexLoginSpikeViewController : UIViewController
// Optional: mirror each (already-redacted) progress line to the parent's on-screen console.
@property (nonatomic, copy) void (^onLog)(NSString *line);
@end
