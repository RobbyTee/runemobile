#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// The native chatbox and the minimap cluster aren't RuneLite overlays (the shell places them), but the user
/// repositions them the same way, in overlay edit-mode. The host (ViewController) provides their canvas rects and
/// applies the moves, so edit-mode can show "Chat" and "Minimap" handles too.
@protocol OverlayEditHudHost <NSObject>
/// The chat box rect in game-canvas px (top-left + size), or {@code CGRectNull} when the chat isn't placeable yet.
- (CGRect)overlayEditChatCanvasRect;
/// Move the chat's top-left to canvas {@code (x,y)} — live, while dragging.
- (void)overlayEditMoveChatToCanvasX:(int)x y:(int)y;
/// Persist the chat's new position (drag ended).
- (void)overlayEditCommitChatMove;
/// The minimap+orbs cluster rect in game-canvas px, or {@code CGRectNull} when it isn't placeable yet.
- (CGRect)overlayEditMinimapCanvasRect;
/// Move the cluster's top-left to canvas {@code (x,y)}, live while dragging.
- (void)overlayEditMoveMinimapToCanvasX:(int)x y:(int)y;
/// Persist the cluster's new position (drag ended).
- (void)overlayEditCommitMinimapMove;
/// Put the cluster back at its automatic top-right spot and forget the saved position.
- (void)overlayEditResetMinimap;
@end

/// Desktop-parity OVL-2/3/5: a touch overlay edit-mode. On desktop you Alt-drag overlays around the canvas and
/// drop them onto snap corners; touch has none of that, so this view (over the live game) draws a draggable
/// labelled handle for every movable overlay (data from OverlayBridge) and writes the new free location — or a
/// snap corner when dropped near an edge — back through the bridge. Each handle also has a reset button.
///
/// Overlay bounds are in game-canvas pixels (renderW x renderH); this view maps them to screen points with the
/// same uniform scale the game blit uses (canvas fills the screen edge-to-edge, so scale = screenW / renderW).
@interface OverlayEditView : UIView

/// Called when edit-mode tears down (Done, or any removal) so the host can clear its strong reference.
@property (nonatomic, copy, nullable) void (^onClose)(void);

/// Optional host for the shell-placed HUD, so the chatbox and minimap show as draggable handles alongside the
/// overlays.
@property (nonatomic, weak, nullable) id<OverlayEditHudHost> hudHost;

/// `frame` should cover the whole game screen. `canvasW`/`canvasH` are the engine's render dimensions (renderW/H).
/// `presenter` is the host VC used to present the per-overlay snap/reset menu (a UIView can't present).
- (instancetype)initWithFrame:(CGRect)frame canvasW:(int)canvasW canvasH:(int)canvasH
                    presenter:(UIViewController *)presenter;

/// Close edit mode: tell the bridge the screen closed and remove self from the hierarchy.
- (void)dismiss;

@end

NS_ASSUME_NONNULL_END
