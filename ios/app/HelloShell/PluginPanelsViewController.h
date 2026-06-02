#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Phase-6 (W3): the native host for RuneLite plugin SIDE-PANELS (XP tracker, GE search, notes, world
/// hopper, etc.). A top toolbar lists the plugins that provide a panel (MobileUiBridge.listPanels); selecting
/// one opens it (openPanel) and the panel's Swing tree is rendered headless to a buffer the shell blits into
/// a scroll view (mobilePanelImage). Taps map back to panel coords and dispatch as Swing clicks
/// (mobilePanelClick) — the embedded-Swing surface, sibling to the game blit.
@interface PluginPanelsViewController : UIViewController
@end

NS_ASSUME_NONNULL_END
