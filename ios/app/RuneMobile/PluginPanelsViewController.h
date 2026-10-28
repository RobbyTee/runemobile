#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Phase-6 (W3): the native host for RuneLite plugin SIDE-PANELS (XP tracker, GE search, notes, world
/// hopper, etc.). A top toolbar lists the plugins that provide a panel (MobileUiBridge.listPanels); selecting
/// one opens it (openPanel) and the panel's Swing tree is rendered headless to a buffer the shell blits into
/// a scroll view (mobilePanelImage). Taps map back to panel coords and dispatch as Swing clicks
/// (mobilePanelClick) — the embedded-Swing surface, sibling to the game blit.
@interface PluginPanelsViewController : UIViewController
/// When YES (and not docked), present as a right-edge tool drawer over the dimmed, still-running game. When NO,
/// the legacy centered-fullscreen layout (A/B fallback). Ignored when `docked`. Defaults to the drawer.
@property (nonatomic, assign) BOOL drawerMode;

/// When YES, run as a PERSISTENT dock hosted as a child on the game screen (not a modal): an always-visible
/// plugin-icon rail on the right edge + an always-visible icon strip across the bottom, with the rendered
/// panel opening inline over the live (still-usable) game. The host adds this as a child view controller and
/// pins its (passthrough) view over the game. Takes precedence over `drawerMode`.
@property (nonatomic, assign) BOOL docked;

/// The right-edge plugin-icon rail is SHOWN by default (the user's primary entry to the plugin side-panels).
/// When YES it's hidden and the inline panel reclaims the rail's edge strip. Setting it re-lays the dock
/// immediately. Toggled from Settings ("Show plugin rail"), persisted in NSUserDefaults `rightBarRailHidden`.
@property (nonatomic, assign) BOOL railHidden;

/// The width of the right-edge strip the shown rail takes, from the screen edge to the rail's leading edge; 0 while
/// it's hidden. The host keeps its right-edge HUD out of it where the safe area doesn't (an iPad has no side insets).
- (CGFloat)railStripWidth;

/// Keep the shown rail between `top` and `bottom`, in points from the view's top: the host's gap between its
/// right-edge HUD, in portrait. 0 and 0 lifts it, leaving only the corners' clearance.
- (void)setRailBandTop:(CGFloat)top bottom:(CGFloat)bottom;

/// Present the native Config Profiles / Infoboxes screens (Settings entry points; the availability of each is
/// pre-checked so these no-op if the bridge isn't wired). These are NOT plugin nav-buttons, so they don't sit on
/// the icon rail — Settings reaches them directly.
- (void)presentConfigProfiles;
- (void)presentInfoboxes;

/// YES while the panel's own soft keyboard (a focused Swing text box) is up — the host gates its game-keyboard
/// auto-poll on this so a game textInputOpen flip doesn't yank the keyboard out from under panel typing (PANEL-1).
- (BOOL)panelKeyboardActive;

#if TARGET_OS_SIMULATOR
/// The simulator screen driver: open the rail panel with this tooltip (NO if there is none), and close it again.
- (BOOL)simOpenPanelNamed:(NSString *)tooltip;
- (BOOL)simPanelOpen;
- (void)simClosePanel;
/// The rail's panels by tooltip, in rail order.
- (NSArray<NSString *> *)simPanelNames;
/// YES while a rendered (Swing) panel is open and its first frame hasn't arrived.
- (BOOL)simPanelLoading;
/// The open rendered panel: "title", "image" ([w, h] of its frame, or null before one arrives), "ink" (the share
/// of the frame's pixels that differ from its corner colour; 0 = one flat colour) and "rect" (window points).
- (NSDictionary *)simPanelInfo;
#endif
@end

NS_ASSUME_NONNULL_END
