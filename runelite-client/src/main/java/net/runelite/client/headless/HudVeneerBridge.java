/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.headless;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * W3 Feature-A (A3) — controls the engine chatbox so the chat-at-top look matches the OSRS Mobile reference.
 * A desktop probe ({@code ChatRelocateProbe}) proved the engine's own chatbox can be {@code setForcedPosition}'d
 * to the top and STILL renders there (not occluded by the 3D viewport — unlike tab stones). So instead of
 * reproducing the chat natively, we relocate the engine's pixel-perfect chat (tabs / scrollback / colours /
 * input all stay live, since it IS the real chat) and flip it to OSRS transparent-chat mode.
 *
 * <p>The desired state is set from the native shell (reverse JNI) and re-applied each frame on the client thread
 * (the engine rebuilds the toplevel on {@code TOPLEVEL_REDRAW}). Two modes: {@link #relocateChat} (move the
 * chatbox + optional transparency) or {@link #setChatHidden} (hide it outright). Inert unless
 * {@code -Drunelite.hudveneer=true}.
 */
@Slf4j
public final class HudVeneerBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.hudveneer");

	private static final int CONTROLS_H = 23;   // the chat tab-row (CONTROLS) height, from the layout dump

	// W3 A3 (native chat tabs): the 7 chat tab buttons of the CONTROLS row in display order. The gamepack lays
	// each button out as a parent widget whose children are GRAPHIC = id+1 (the tab-background sprite), TEXT =
	// id+2 (the channel label) and FILTER = id+3 (the live "On"/"Off"/"Filter"/"Hide" sub-label) — except CHAT_ALL
	// which has no filter. The native veneer recreates these BIGGER (the engine has no per-widget scale and the
	// sprites are fixed-size), so this bridge publishes their live canvas geometry + state each frame and the
	// native side draws scaled UIKit buttons over them, proxying a precise click onto the live engine button.
	private static final int[] CHAT_TAB_IDS = {
		InterfaceID.Chatbox.CHAT_ALL, InterfaceID.Chatbox.CHAT_GAME, InterfaceID.Chatbox.CHAT_PUBLIC,
		InterfaceID.Chatbox.CHAT_PRIVATE, InterfaceID.Chatbox.CHAT_FRIENDSCHAT, InterfaceID.Chatbox.CHAT_CLAN,
		InterfaceID.Chatbox.CHAT_TRADE, InterfaceID.Chatbox.REPORTABUSE,
	};
	private static final String[] CHAT_TAB_NAMES = { "All", "Game", "Public", "Private", "Channel", "Clan", "Trade", "Report" };
	private static final int SPR_CHATBTN_SELECTED = 3053;   // SpriteID.CHATBOX_BUTTON_SELECTED — active tab
	private static final int SPR_CHATBTN_NEW = 3055;        // SpriteID.CHATBOX_BUTTON_NEW_MESSAGES — unread tab

	// Desired state, set from native, applied on the client thread. chatX < 0 = not relocating.
	private static volatile int chatX = -1;
	private static volatile int chatY = -1;
	private static volatile boolean transparentChat;
	private static volatile boolean hideChat;

	// Minimap+orbs cluster (MAP_CONTAINER) relocate. minimapX < 0 = not relocating. Proven by MinimapRelocateProbe:
	// MAP_CONTAINER is root-anchored (setForcedPosition is ~canvas-absolute) and holds both the minimap globe and
	// the orbs as children, so one relocate moves the whole cluster — it renders at the new spot (not occluded) and
	// stays click-to-walk + orb-clickable (it IS the live engine HUD). This is A4 (minimap) + A5 (orbs) in one move.
	private static volatile int minimapX = -1;
	private static volatile int minimapY = -1;

	// Side panel (SIDE_CONTAINER = the inventory / equipment / spellbook / etc. panel) relocate. sideX < 0 = off.
	// Same root-anchored relocate as the minimap; edge-to-edge needs it pulled inward so it doesn't clip under the
	// notch. Best-effort (only shows when a tab is open); independently toggleable so a misbehave can be isolated.
	private static volatile int sideX = -1;
	private static volatile int sideY = -1;

	// Minimap ENLARGE (make the top-right globe bigger). 0 = natural size. Resizing the draw-area widget (MINIMAP)
	// makes the engine render a bigger globe (terrain + dots scale, click-to-walk follows for free since
	// Perspective.localToMinimap reads that widget's centre/size). The globe is RIGHT+TOP anchored so it grows
	// left+down about its fixed top-right corner; the surrounding chrome (compass / orbs / world-map / wiki / store)
	// are separate fixed widgets that DON'T scale, so we reposition them about that same corner to keep them hugging
	// the bigger globe, and hide the now-mismatched frame ring. Driven from native (setMinimapScale) or, off-device,
	// the -Drunelite.hudveneer.test.minimapScalePct prop.
	private static volatile int minimapScalePct;

	// Tab stones. The native 2-col tab grid replaces them, so we HIDE all 14 engine stones (removes the gray cover
	// patch the old proxy approach needed) and switch tabs by REPLAYING the stone's own op script — which works
	// even with the stone hidden (TabScriptProbe: replay + worksHidden GREEN). pendingOpenTab is a one-shot action
	// applied on the client thread (where runScript is legal).
	private static volatile boolean hideStones;
	private static volatile int pendingOpenTab = -1;

	// Native chat-tab veneer (A3 bigger buttons). chatTabRowH = the canvas-px height the native (enlarged) tab row
	// occupies; the message area (CHATAREA) is pushed down past it instead of the engine's 23px CONTROLS row. 0 =
	// fall back to CONTROLS_H. chatAreaW = the canvas-px width the native row spans, so the message area is widened
	// to ALIGN with the bigger buttons (0 = the engine default 519). chatSnapshotJson = the per-frame chat-tab
	// geometry/state the native side reads.
	private static volatile int chatTabRowH;
	private static volatile int chatAreaW;
	private static volatile String chatSnapshotJson = "{\"loggedIn\":false}";

	// Native chat-tab SWITCH: which tab (0..7 index into CHAT_TAB_IDS) the native veneer asked to open. The engine
	// tab sprites are hidden (so they don't peek under the native row), which makes them unclickable — so switching
	// REPLAYS the tab's op-script instead of clicking, the same way the bottom tab-grid drives the hidden stones.
	// One-shot, applied on the client thread in tick(). -1 = nothing pending.
	private static volatile int pendingChatTab = -1;


	// Chat COLLAPSE: when the user taps the already-active tab, the chat box hides (mobile behaviour); a tap on any
	// tab reopens it. Driven from native (setChatCollapsed). While collapsed the engine CHAT_CONTAINER is hidden.
	private static volatile boolean chatCollapsed;

	// Finger-scroll: accumulated chat scroll delta (canvas px) requested from native, applied on the client thread
	// in tick() via the chatbox's own scroll layer/scrollbar — NOT via a mouse wheel (the wheel also zooms the
	// camera because the transparent chat sits over the viewport). +delta = toward newer (bottom).
	private static final AtomicInteger pendingChatScroll = new AtomicInteger(0);

	private static final int CHAT_DEFAULT_W = 519;       // the engine's native chatbox width (from the layout dump)
	// Collapse = shove the live chatbox this far below its anchor (off-canvas) instead of hiding it, so its
	// history + selected channel survive and reopening is instant. Far enough to clear any stretched canvas.
	private static final int CHAT_COLLAPSE_OFFSCREEN_DY = 3000;
	private static final int ORIG_CHAT_CONTENT_H = 142;  // the natural message-area height (container 165 − CONTROLS 23)

	// Desktop off-device test harness: when -Drunelite.hudveneer.test=true, auto-apply the same chat veneer the
	// native shell drives (relocate + enlarged-tab geometry) once logged in, so the headless capture renders the
	// real chatbox geometry to PNG on the Mac (no device / StikDebug). Values default to the device's (render
	// 1304x600 → chat at 93,8, 32px tab row, natural width); overridable via -Drunelite.hudveneer.test.* props.
	private static final boolean TEST_APPLY = Boolean.getBoolean("runelite.hudveneer.test");
	private static boolean testApplied;

	private HudVeneerBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Native: move the engine chatbox to {@code (x,y)} (top-left of the screen) and optionally flip it to OSRS
	 *  transparent-chat mode. {@code x < 0} stops relocating. */
	public static void relocateChat(int x, int y, boolean transparent)
	{
		chatX = x;
		chatY = y;
		transparentChat = transparent;
	}

	/** Native: hide the engine chatbox outright (used when the native veneer fully replaces it). */
	public static void setChatHidden(boolean hidden)
	{
		hideChat = hidden;
	}

	/** Native: relocate the engine minimap+orbs cluster ({@code MAP_CONTAINER}) to canvas {@code (x,y)}.
	 *  {@code x < 0} stops relocating. The cluster stays the live engine HUD — minimap clicks still walk and orb
	 *  clicks still fire (A4 + A5 in one relocate; MinimapRelocateProbe confirmed render + click-to-walk). */
	public static void relocateMinimap(int x, int y)
	{
		minimapX = x;
		minimapY = y;
	}

	/** Native: enlarge the engine minimap to {@code scalePct}% of its natural size (e.g. 130 = 30% bigger).
	 *  {@code 0} (or 100) = natural size. The globe + its chrome are re-laid each frame in {@link #tick}; the cluster
	 *  stays the live engine HUD, so click-to-walk + orb clicks keep working. */
	public static void setMinimapScale(int scalePct)
	{
		minimapScalePct = scalePct <= 0 ? 0 : scalePct;
	}

	/** Native: relocate the engine side panel ({@code SIDE_CONTAINER}) to canvas {@code (x,y)}. {@code x < 0} stops.
	 *  Same root-anchored relocate as the minimap; pulls the inventory/equipment panel inward for edge-to-edge so it
	 *  doesn't clip under the notch. The panel only exists while a tab is open, so this is a no-op until then. */
	public static void relocateSidePanel(int x, int y)
	{
		sideX = x;
		sideY = y;
	}

	/** Native: hide (or show) all 14 engine tab stones. With the native 2-col tab grid driving tabs via
	 *  {@link #openTab}, the real stones are hidden so there is no leftover stone row / cover patch. Re-applied
	 *  each frame (the engine rebuilds the toplevel on TOPLEVEL_REDRAW). */
	public static void setStonesHidden(boolean hidden)
	{
		hideStones = hidden;
	}

	/** Native: open the side-panel tab with the given index (0..13) by replaying that stone's op script — works
	 *  even while the stones are hidden. One-shot; applied on the client thread in {@link #tick}. */
	public static void openTab(int tabIndex)
	{
		pendingOpenTab = tabIndex;
	}

	/** Native: the canvas-px height the enlarged native chat-tab row occupies, so the message area is pushed down
	 *  past it (the engine's own CONTROLS row is only 23px). {@code 0} restores the default 23px offset. */
	public static void setChatTabRowHeight(int canvasPx)
	{
		chatTabRowH = canvasPx;
	}

	/** Native: the canvas-px width the enlarged native chat-tab row spans, so the message area is widened to ALIGN
	 *  with the bigger buttons (the engine chatbox is fixed at 519). {@code 0} restores the default 519 width. */
	public static void setChatAreaWidth(int canvasPx)
	{
		chatAreaW = canvasPx;
	}

	/** Native: the live chat-tab geometry/state snapshot (escaped JSON) for the enlarged native tab veneer —
	 *  per-tab canvas bounds, selected/new-message state, and the live filter sub-label + colour. Read lock-free
	 *  on the JNI thread; published on the client thread in {@link #tick}. */
	public static String getChatSnapshot()
	{
		return chatSnapshotJson;
	}

	/** Native: scroll the chat by {@code deltaCanvasPx} (positive = toward newer/bottom). Accumulated and applied
	 *  on the client thread in {@link #tick} via the chatbox scroll layer — no mouse wheel, so the camera doesn't
	 *  zoom (the transparent chat overlays the viewport, where a wheel would zoom). */
	public static void scrollChat(int deltaCanvasPx)
	{
		pendingChatScroll.addAndGet(deltaCanvasPx);
	}

	/** Native: switch the chat to tab {@code index} (0..7 into the 7 native tabs + Report) by REPLAYING that tab's
	 *  op-script on the client thread — works even though the engine tab is hidden (a click wouldn't). One-shot. */
	public static void switchChatTab(int index)
	{
		pendingChatTab = index;
	}

	/** Native: collapse (hide) or expand the chat box. Tapping the active tab collapses; tapping any tab expands. */
	public static void setChatCollapsed(boolean collapsed)
	{
		chatCollapsed = collapsed;
	}

	/** Re-apply the requested chatbox state each frame. Client thread. */
	public static void tick(Client c)
	{
		if (!ENABLED || c == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		try
		{
			if (!c.isResized())
			{
				return;   // native veneers + transparent chat are resizable-mode only
			}
			if (TEST_APPLY && !testApplied)
			{
				// Off-device capture: drive the chat veneer ourselves (the native shell would on device). The
				// in-world "Welcome to RuneScape" news overlay is dismissed by HeadlessLogin so the chat is visible.
				testApplied = true;
				relocateChat(Integer.getInteger("runelite.hudveneer.test.chatX", 93),
					Integer.getInteger("runelite.hudveneer.test.chatY", 8),
					!"false".equals(System.getProperty("runelite.hudveneer.test.transparent")));
				setChatTabRowHeight(Integer.getInteger("runelite.hudveneer.test.rowH", 32));
				setChatAreaWidth(Integer.getInteger("runelite.hudveneer.test.areaW", 0));
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;

			// The minimap-orb cluster's ORBS layer is built, but in this headless boot the engine never opens the
			// Orbs interface (group 160) INTO that layer — so the HP/prayer/run/spec orbs + world-map/wiki/store
			// icons never render (verified: the ORBS layer widget exists and is un-hidden, but every Orbs.* child
			// resolves null). Open it ourselves once the layer exists; the interface's own onload binds it to the
			// live orb varplayers, so it renders + updates like a normal client. No-op once the orbs are present.
			ensureOrbsLoaded(c, preEoc);

			final int chatId = preEoc ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;

			// Chat box: keep the engine chatbox FULLY ALIVE at all times (never hidden). To "collapse" (the user
			// tapped the active tab) we shove the live widget far off-screen; reopening just moves it back — instant
			// and full, with NO interface teardown, tab-selection loss, or rebuild. (Hiding the container tore the
			// chat down, so reopen showed an empty box until a second tap.) The native tab row stays put because
			// buildChatSnapshot reports the configured chatX/chatY, not the widget's moved position.
			if (chatX >= 0)
			{
				final int rowH = chatTabRowH > 0 ? chatTabRowH : CONTROLS_H;
				final int rowW = chatAreaW > 0 ? chatAreaW : CHAT_DEFAULT_W;
				final int drawY = chatCollapsed ? chatY + CHAT_COLLAPSE_OFFSCREEN_DY : chatY;
				final Widget w = c.getWidget(chatId);
				if (w != null)
				{
					if (w.isHidden())
					{
						w.setHidden(false);   // safety: we never hide it now, but restore if something else did
					}
					w.setForcedPosition(chatX, drawY);
					// Widen the chat CONTAINER to the native row width so its children (the message area) can fill it.
					if (w.getOriginalWidth() != rowW)
					{
						w.setOriginalWidth(rowW);
					}
					w.revalidate();
				}
				// OSRS-Mobile tabs-on-top: the desktop chat stacks the tab row (CONTROLS, 23px) UNDER the message
				// area (CHATAREA, 142px). Flip it — force the tab row to the chat's top and push the messages down
				// by the tab height — so the layout reads tabs -> messages -> input like the mobile reference. When
				// the native veneer enlarges the tabs it reports its taller row height; push CHATAREA past THAT so
				// the scrollback clears the bigger buttons (the engine's own 23px CONTROLS stays put underneath,
				// covered by the native row, so a native tab tap can still proxy a precise click onto it).
				// setForcedPosition is RELATIVE TO THE PARENT, and the chat container is already at (chatX,chatY),
				// so position the children at (0, …) — using (chatX,chatY) double-offsets them right+down (the
				// misalignment + bottom clip). CONTROLS at the container's top-left.
				forcePos(c, InterfaceID.Chatbox.CONTROLS, 0, 0);
				// Hide the engine's own tab sprites so they don't peek under the native 7-tab row (the engine lays 8
				// tabs, incl. Report, across the same width, so they don't line up). Switching is NOT a proxy-click any
				// more (the sprite IS the click target, and a hidden widget isn't clickable) — instead the native tap
				// REPLAYS the tab's op-script ({@link #switchChatTab}), the same trick the bottom tab-grid uses for the
				// hidden tab stones (the widget still RESOLVES + its script runs while hidden). Gated on the native row
				// being active; un-hides when the native chrome is toggled off so the engine row shows.
				setEngineChatTabsHidden(c, chatTabRowH > 0);
				// Apply a pending tab switch (op-replay sets the channel; switchChatTabNow rebuilds the lines).
				final int switchTab = pendingChatTab;
				if (switchTab >= 0)
				{
					pendingChatTab = -1;
					switchChatTabNow(c, switchTab);
				}
				// Resize + reposition the message area so it ALIGNS with the bigger native row (width = rowW) and
				// FITS below it without clipping the newest line. Deterministic: read the chat container's real
				// height, then pin CHATAREA to ABSOLUTE width=rowW and height=(containerH - rowH - margin) at
				// container-relative (0, rowH). The display/scroll children are revalidated so they widen too.
				sizeChatArea(c, chatId, rowW, rowH);
				if (transparentChat)
				{
					// Deterministic transparency: hide the brown chatbox background sprites directly (the
					// CHATBOX_TRANSPARENCY varbit alone doesn't re-render an already-built chat). Re-applied each
					// frame. The text + tab buttons stay; only the opaque box behind them goes.
					hide(c, InterfaceID.Chatbox.CHAT_BACKGROUND);
					hide(c, InterfaceID.Chatbox.CONTROLS_BACKGROUND);
					hide(c, InterfaceID.Chatbox.CONTROLS_BACKGROUND_GRAPHIC);
					if (c.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) != 1)
					{
						c.setVarbit(VarbitID.CHATBOX_TRANSPARENCY, 1);
					}
				}
				else
				{
					// OPAQUE mode (debug aid: makes the chat box dimensions visible). Keep the brown background
					// shown and widen it to the native row width so the visible box matches the message area.
					if (c.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) != 0)
					{
						c.setVarbit(VarbitID.CHATBOX_TRANSPARENCY, 0);
					}
					final Widget bg = c.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);
					if (bg != null && bg.isHidden())
					{
						bg.setHidden(false);
					}
					forceWidth(c, InterfaceID.Chatbox.CHAT_BACKGROUND, rowW, 0);
				}
				buildChatSnapshot(c);
				// Apply any finger-scroll requested from native (client thread — runScript is legal here).
				final int scrollDelta = pendingChatScroll.getAndSet(0);
				if (scrollDelta != 0)
				{
					applyChatScroll(c, scrollDelta);
				}
				dumpChatLayoutOnce(c, chatId);
			}
			else if (hideChat)
			{
				final Widget w = c.getWidget(chatId);
				if (w != null && !w.isHidden())
				{
					w.setHidden(true);
				}
			}

			// Minimap+orbs cluster relocate (independent of chat). Force MAP_CONTAINER each frame so it survives
			// TOPLEVEL_REDRAW; revalidate cascades to the globe + orb children.
			if (minimapX >= 0)
			{
				final int containerId = preEoc
					? InterfaceID.ToplevelPreEoc.MAP_CONTAINER : InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER;
				final Widget w = c.getWidget(containerId);
				if (w != null)
				{
					w.setForcedPosition(minimapX, minimapY);
					w.revalidate();
				}
			}

			// Minimap ENLARGE: resize the globe + reposition its chrome so the whole cluster reads as a bigger
			// minimap. Off-device the scale comes from the test prop; on device it's driven by native setMinimapScale.
			if (TEST_APPLY)
			{
				final int p = Integer.getInteger("runelite.hudveneer.test.minimapScalePct", 0);
				if (p > 0)
				{
					minimapScalePct = p;
				}
			}
			if (minimapScalePct > 0)
			{
				applyMinimapScale(c, preEoc, minimapScalePct);
			}

			// Side panel relocate (independent). No-op until a tab is open (SIDE_CONTAINER then becomes non-null).
			if (sideX >= 0)
			{
				final int sideId = preEoc
					? InterfaceID.ToplevelPreEoc.SIDE_CONTAINER : InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER;
				final Widget w = c.getWidget(sideId);
				if (w != null)
				{
					w.setForcedPosition(sideX, sideY);
					w.revalidate();
				}
			}

			// Hide the engine tab-stone bottom row by hiding the stones' GRANDPARENT containers — NOT the stones
			// themselves. The engine re-pins the stone widgets every frame (setHidden/relocate on a stone is
			// overridden — confirmed on device), but the ancestor containers are normal widgets whose hidden state
			// STICKS. The PARENT holds only the stone icons (hiding it leaves the empty stone backgrounds); the
			// GRANDPARENT holds the whole row (icons + backgrounds), so hiding it clears the row completely
			// (StoneSuppressProbe steady-state PNG + device). Stones still resolve under the hidden ancestor, so
			// openTab (replaying a stone's op script) still switches tabs. STONE0 = left half, STONE7 = right half.
			if (hideStones)
			{
				final int[] ids = stoneIds(preEoc);
				hideRowOf(c, ids[0]);
				hideRowOf(c, ids[7]);
			}

			// One-shot: open a tab by replaying its stone's op script (works even with the stone hidden).
			final int openIdx = pendingOpenTab;
			if (openIdx >= 0)
			{
				pendingOpenTab = -1;
				openTabNow(c, preEoc, openIdx);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Hide the GRANDPARENT container of a stone (the whole stone-row half — icons + backgrounds). The engine
	 *  re-pins the stones but not their ancestor containers, so the grandparent's hidden state sticks and the row
	 *  disappears; the stones still resolve underneath for {@link #openTabNow}. */
	private static void hideRowOf(Client c, int stoneId)
	{
		try
		{
			final Widget s = c.getWidget(stoneId);
			if (s == null)
			{
				return;
			}
			final Widget p = s.getParent();
			final Widget gp = p == null ? null : p.getParent();
			if (gp != null && !gp.isHidden())
			{
				gp.setHidden(true);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static int[] stoneIds(boolean preEoc)
	{
		return preEoc ? new int[]{
			InterfaceID.ToplevelPreEoc.STONE0, InterfaceID.ToplevelPreEoc.STONE1, InterfaceID.ToplevelPreEoc.STONE2,
			InterfaceID.ToplevelPreEoc.STONE3, InterfaceID.ToplevelPreEoc.STONE4, InterfaceID.ToplevelPreEoc.STONE5,
			InterfaceID.ToplevelPreEoc.STONE6, InterfaceID.ToplevelPreEoc.STONE7, InterfaceID.ToplevelPreEoc.STONE8,
			InterfaceID.ToplevelPreEoc.STONE9, InterfaceID.ToplevelPreEoc.STONE10, InterfaceID.ToplevelPreEoc.STONE11,
			InterfaceID.ToplevelPreEoc.STONE12, InterfaceID.ToplevelPreEoc.STONE13,
		} : new int[]{
			InterfaceID.ToplevelOsrsStretch.STONE0, InterfaceID.ToplevelOsrsStretch.STONE1, InterfaceID.ToplevelOsrsStretch.STONE2,
			InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelOsrsStretch.STONE5,
			InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelOsrsStretch.STONE7, InterfaceID.ToplevelOsrsStretch.STONE8,
			InterfaceID.ToplevelOsrsStretch.STONE9, InterfaceID.ToplevelOsrsStretch.STONE10, InterfaceID.ToplevelOsrsStretch.STONE11,
			InterfaceID.ToplevelOsrsStretch.STONE12, InterfaceID.ToplevelOsrsStretch.STONE13,
		};
	}

	/** Open a tab by replaying its stone's op script with the stone as source + op 1 (RuneLite's standard
	 *  widget-op replay). Works while the stone is hidden — confirmed by TabScriptProbe. Client thread. */
	private static void openTabNow(Client c, boolean preEoc, int idx)
	{
		try
		{
			final int[] ids = stoneIds(preEoc);
			if (idx < 0 || idx >= ids.length)
			{
				return;
			}
			final Widget w = c.getWidget(ids[idx]);
			if (w == null)
			{
				return;
			}
			final Object[] op = w.getOnOpListener();
			if (op == null)
			{
				return;
			}
			c.createScriptEventBuilder(op).setSource(w).setOp(1).build().run();
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Switch the chat to tab {@code idx} by replaying that tab's op-script with op 1 (the left-click action) — the
	 *  same replay the tab stones use, so it works while the engine tab sprite is hidden. The op listener may sit on
	 *  the tab button (base) or its graphic child (base+1), so try both. Client thread. */
	private static void switchChatTabNow(Client c, int idx)
	{
		if (idx < 0 || idx >= CHAT_TAB_IDS.length)
		{
			return;
		}
		final int base = CHAT_TAB_IDS[idx];
		if (!replayOp(c, base, 1))
		{
			replayOp(c, base + 1, 1);   // the op listener may sit on the graphic child instead of the button
		}
		// The op-replay sets CHAT_VIEW but doesn't re-render the message list (the engine does that as part of the
		// real click). Force the chatbox to rebuild so the newly-selected channel's messages actually show.
		try
		{
			c.runScript(ScriptID.BUILD_CHATBOX);
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Replay a widget's op-script with the given op. Returns {@code true} if the widget has an op listener (so the
	 *  replay ran). Works on hidden widgets — {@code getWidget} resolves them and the script runs regardless. */
	private static boolean replayOp(Client c, int id, int op)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				return false;
			}
			final Object[] l = w.getOnOpListener();
			if (l == null)
			{
				return false;
			}
			c.createScriptEventBuilder(l).setSource(w).setOp(op).build().run();
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	// Minimap-enlarge geometry. The globe draw-area widget is RIGHT+TOP anchored, so growing its absolute size keeps
	// its top-right corner fixed and grows it left+down; at 130% the rendered terrain disc is centre ~(120,100)
	// radius ~87 (cluster-local). The chrome is then placed at explicit cluster-local targets (see applyMinimapScale)
	// tuned to match the official OSRS-Mobile layout for that disc.
	private static final int MM_NAT_SIZE = 152;   // natural globe draw-area width/height
	private static boolean minimapTreeDumped;
	private static boolean minimapScaleLogged;

	/** One-time recursive dump of the minimap cluster (MAP_CONTAINER tree) + the named chrome widgets, so a fresh
	 *  session can see the exact child layout (bounds / relative / size modes / sprite / type) before designing the
	 *  enlarge-and-reposition. Off-device research only. */
	// Cooldown so a failing open is not hammered every tick; reset implicitly once the orbs load (early return).
	private static int orbsOpenCooldown = 0;

	/**
	 * Open the Orbs interface (group 160) into the toplevel's ORBS layer if it isn't already loaded. Headless boots
	 * here build the ORBS layer but never populate it, leaving the orbs invisible; opening the interface (NON_MODAL,
	 * like the engine would) runs its onload so the orbs bind to live varplayers and render + update normally. Runs
	 * on the client thread (called from {@link #tick}). No-op once the orbs are present; if the toplevel rebuilds
	 * (resize / relogin) and drops them, the next tick re-opens after a short cooldown.
	 */
	private static void ensureOrbsLoaded(Client c, boolean preEoc)
	{
		if (c.getWidget(InterfaceID.Orbs.ORB_HEALTH) != null)
		{
			return; // already loaded
		}
		if (orbsOpenCooldown > 0)
		{
			orbsOpenCooldown--;
			return;
		}
		final int orbLayer = preEoc ? InterfaceID.ToplevelPreEoc.ORBS : InterfaceID.ToplevelOsrsStretch.ORBS;
		if (c.getWidget(orbLayer) == null)
		{
			return; // cluster not built yet (welcome screen / mid-rebuild)
		}
		orbsOpenCooldown = 50; // don't re-attempt every frame if the open doesn't take
		try
		{
			c.openInterface(orbLayer, InterfaceID.ORBS, WidgetModalMode.NON_MODAL);
			log.info("[veneer] opened Orbs interface {} into ORBS layer {}",
				InterfaceID.ORBS, Integer.toHexString(orbLayer));
		}
		catch (RuntimeException ex)
		{
			log.warn("[veneer] openInterface(orbs) failed", ex);
		}
	}

	private static void dumpMinimapTree(Client c, boolean preEoc)
	{
		final int[] ids = {
			InterfaceID.ToplevelPreEoc.MAP_CONTAINER, InterfaceID.ToplevelPreEoc.MAP_MINIMAP,
			InterfaceID.ToplevelPreEoc.MINIMAP, InterfaceID.ToplevelPreEoc.COMPASSCLICK,
			InterfaceID.ToplevelPreEoc.MAP_MINIMAP_GRAPHIC6, InterfaceID.ToplevelPreEoc.MAP_MINIMAP_GRAPHIC9,
			InterfaceID.ToplevelPreEoc.ORBS,
			InterfaceID.ToplevelPreEoc.MAP_NOCLICK_0, InterfaceID.ToplevelPreEoc.MAP_NOCLICK_1,
			InterfaceID.ToplevelPreEoc.MAP_NOCLICK_2, InterfaceID.ToplevelPreEoc.MAP_NOCLICK_3,
			InterfaceID.ToplevelPreEoc.MAP_NOCLICK_4, InterfaceID.ToplevelPreEoc.MAP_NOCLICK_5,
		};
		final String[] names = {
			"MAP_CONTAINER", "MAP_MINIMAP", "MINIMAP", "COMPASSCLICK", "GRAPHIC6", "GRAPHIC9", "ORBS",
			"NOCLICK0", "NOCLICK1", "NOCLICK2", "NOCLICK3", "NOCLICK4", "NOCLICK5",
		};
		for (int i = 0; i < ids.length; i++)
		{
			dumpWidget(c, names[i], ids[i], false);
		}
		// The individual orbs live in the Orbs sub-interface (group 0xa0) hosted in the ORBS layer — dump them by id
		// so we get the HP/prayer/run/spec orb + world-map/wiki/store icon + XP-button canvas positions.
		final int[] orbIds = {
			InterfaceID.Orbs.XP_DROPS, InterfaceID.Orbs.CR_BACKING, InterfaceID.Orbs.ORB_HEALTH,
			InterfaceID.Orbs.ORB_PRAYER, InterfaceID.Orbs.ORB_RUNENERGY, InterfaceID.Orbs.ORB_SPECENERGY,
			InterfaceID.Orbs.ORB_STORE, InterfaceID.Orbs.ORB_WORLDMAP, InterfaceID.Orbs.WIKI,
		};
		final String[] orbNames = {
			"XP_DROPS", "CR_BACKING", "ORB_HEALTH", "ORB_PRAYER", "ORB_RUNENERGY", "ORB_SPECENERGY",
			"ORB_STORE", "ORB_WORLDMAP", "WIKI",
		};
		for (int i = 0; i < orbIds.length; i++)
		{
			dumpWidget(c, orbNames[i], orbIds[i], false);
		}
	}

	private static void dumpWidget(Client c, String name, int id, boolean unused)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[veneer] mm-tree {} id={} = null", name, Integer.toHexString(id));
				return;
			}
			dumpWidgetObj(name + " id=" + Integer.toHexString(id), w);
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static void dumpWidgetObj(String label, Widget w)
	{
		try
		{
			final java.awt.Rectangle b = w.getBounds();
			log.info("[veneer] mm-tree {} type={} bounds={} rel=({},{}) orig=({},{},{}x{}) posMode=({},{}) sizeMode=({},{}) sprite={} model={} item={} hidden={} text='{}'",
				label, w.getType(), b, w.getRelativeX(), w.getRelativeY(),
				w.getOriginalX(), w.getOriginalY(), w.getOriginalWidth(), w.getOriginalHeight(),
				w.getXPositionMode(), w.getYPositionMode(), w.getWidthMode(), w.getHeightMode(),
				safeSpriteId(w), safeModelId(w), safeItemId(w), w.isHidden(), shortText(w));
		}
		catch (RuntimeException ex)
		{
			log.info("[veneer] mm-tree {} threw {}", label, String.valueOf(ex));
		}
	}

	private static int safeModelId(Widget w)
	{
		try
		{
			return w.getModelId();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static int safeItemId(Widget w)
	{
		try
		{
			return w.getItemId();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static String shortText(Widget w)
	{
		try
		{
			final String t = stripTags(w.getText());
			return t.length() > 16 ? t.substring(0, 16) : t;
		}
		catch (RuntimeException ex)
		{
			return "";
		}
	}

	/** Minimap-enlarge hook (currently DISABLED by default — {@code scalePct <= 100} no-ops). A clean bigger minimap
	 *  is not achievable by editing the engine widgets: the engine hard-pins every orb/icon each frame (so they can't
	 *  be rearranged to wrap a bigger globe) and the globe draw-area can only be resized, not repositioned (re-anchoring
	 *  it desyncs the minimap raster and the terrain stops drawing), so resizing alone grows the globe into the fixed
	 *  orbs. The size-only path is kept here for reference. Native-HUD layout is Classic (PreEoc 164) only. */
	private static void applyMinimapScale(Client c, boolean preEoc, int scalePct)
	{
		if (!preEoc)
		{
			return;   // the native HUD veneer only supports the Classic resizable layout
		}
		try
		{
			final Widget mm = c.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			if (mm == null)
			{
				return;   // cluster not built yet (welcome screen etc.)
			}
			if (TEST_APPLY && !minimapTreeDumped)
			{
				minimapTreeDumped = true;   // off-device record of the natural layout, before this frame's resize
				dumpMinimapTree(c, preEoc);
			}
			if (scalePct <= 100)
			{
				return;   // disabled → leave the natural minimap untouched (its layout IS the official-mobile layout)
			}
			// LIMITATION (confirmed off-device, 2026-06-07): a clean BIGGER minimap is not achievable by editing the
			// engine widgets. The engine hard-pins every minimap orb/icon to a fixed position each frame
			// (setForcedPosition updates the getter but is ignored at render — readback-confirmed), so the orbs can't
			// be rearranged to wrap a bigger globe; and the globe draw-area can only be RESIZED — repositioning or
			// re-anchoring it (to grow away from the orbs) desyncs the engine's minimap raster and the terrain stops
			// drawing. So resizing alone grows the globe left+down INTO the fixed orbs (they overlap). This size-only
			// path is kept (off by default) for reference; a genuinely bigger minimap needs a native scaled blit of
			// the whole MAP_CONTAINER region (uniform but soft + re-do hit-testing), not this.
			final double s = scalePct / 100.0;
			final int gs = (int) Math.round(MM_NAT_SIZE * s);
			if (mm.getWidthMode() != WidgetSizeMode.ABSOLUTE || mm.getOriginalWidth() != gs
				|| mm.getHeightMode() != WidgetSizeMode.ABSOLUTE || mm.getOriginalHeight() != gs)
			{
				mm.setWidthMode(WidgetSizeMode.ABSOLUTE);
				mm.setHeightMode(WidgetSizeMode.ABSOLUTE);
				mm.setOriginalWidth(gs);
				mm.setOriginalHeight(gs);
				mm.revalidate();
			}
			hide(c, InterfaceID.ToplevelPreEoc.MAP_MINIMAP_GRAPHIC9);   // frame ring no longer matches the bigger globe
			if (!minimapScaleLogged)
			{
				minimapScaleLogged = true;
				log.info("[veneer] MINIMAP_SCALE scalePct={} globe={}px (natural {})", scalePct, gs, MM_NAT_SIZE);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Scroll the chat message layer by {@code delta} canvas px (clamped), driving the chatbox's own scroll layer
	 *  ({@code SCROLLAREA}, the parent of the message lines) + scrollbar ({@code CHATSCROLLBAR}) via the engine's
	 *  {@code UPDATE_SCROLLBAR} script — the same path the scrollbar arrows use. No mouse-wheel event, so the camera
	 *  doesn't also zoom. Client thread. */
	private static void applyChatScroll(Client c, int delta)
	{
		try
		{
			final Widget area = c.getWidget(InterfaceID.Chatbox.SCROLLAREA);
			if (area == null)
			{
				return;
			}
			final int max = Math.max(0, area.getScrollHeight() - area.getHeight());
			final int cur = area.getScrollY();
			final int next = Math.max(0, Math.min(max, cur + delta));
			if (next != cur)
			{
				c.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Chatbox.CHATSCROLLBAR, InterfaceID.Chatbox.SCROLLAREA, next);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Build the per-frame chat-tab snapshot the native veneer reads to draw + state its enlarged buttons. Runs on
	 *  the client thread (inside {@link #tick}) right after the chatbox widgets are force-positioned, so the button
	 *  bounds are fresh. Reports each of the 7 tabs' canvas bounds (for the proxy-click target + native layout),
	 *  selected/new-message state (from the GRAPHIC sprite id), and the live filter sub-label text + colour. */
	private static void buildChatSnapshot(Client c)
	{
		try
		{
			final StringBuilder sb = new StringBuilder(640);
			sb.append("{\"loggedIn\":true")
				.append(",\"chatX\":").append(chatX)
				.append(",\"chatY\":").append(chatY)
				.append(",\"rowH\":").append(chatTabRowH > 0 ? chatTabRowH : CONTROLS_H);
			int view = -1;
			try
			{
				view = c.getVarcIntValue(VarClientID.CHAT_VIEW);
			}
			catch (RuntimeException ignore)
			{
			}
			int topLevel = -1;
			try
			{
				topLevel = c.getTopLevelInterfaceId();
			}
			catch (RuntimeException ignore)
			{
			}
			sb.append(",\"view\":").append(view)
				.append(",\"topLevelId\":").append(topLevel)
				.append(",\"tabs\":[");
			final int last = CHAT_TAB_IDS.length - 1;
			for (int i = 0; i < CHAT_TAB_IDS.length; i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				// ALL (index 0) and Report (last) have no filter sub-label.
				appendTab(c, sb, CHAT_TAB_IDS[i], CHAT_TAB_NAMES[i], i != 0 && i != last);
			}
			sb.append("]}");
			chatSnapshotJson = sb.toString();
		}
		catch (RuntimeException ignore)
		{
			// keep the previous snapshot
		}
	}

	/** Append one chat tab's geometry/state object. {@code base} = the tab parent widget; GRAPHIC=base+1,
	 *  TEXT=base+2, FILTER=base+3. */
	private static void appendTab(Client c, StringBuilder sb, int base, String name, boolean hasFilter)
	{
		final Widget btn = safeWidget(c, base);
		final Widget gfx = safeWidget(c, base + 1);
		final Widget txt = safeWidget(c, base + 2);
		final Widget flt = hasFilter ? safeWidget(c, base + 3) : null;
		final int[] b = canvasBounds(btn);
		final int spr = gfx == null ? -1 : safeSpriteId(gfx);
		sb.append("{\"name\":\"").append(name).append('"')
			.append(",\"x\":").append(b == null ? -1 : b[0])
			.append(",\"y\":").append(b == null ? -1 : b[1])
			.append(",\"w\":").append(b == null ? 0 : b[2])
			.append(",\"h\":").append(b == null ? 0 : b[3])
			.append(",\"sprite\":").append(spr)
			.append(",\"selected\":").append(spr == SPR_CHATBTN_SELECTED)
			.append(",\"new\":").append(spr == SPR_CHATBTN_NEW)
			.append(",\"labelColor\":").append(txt == null ? -1 : safeTextColor(txt))
			.append(",\"filter\":\"").append(flt == null ? "" : jsonEsc(stripTags(flt.getText())))
			.append('"')
			.append(",\"filterColor\":").append(flt == null ? -1 : safeTextColor(flt))
			.append('}');
	}

	private static Widget safeWidget(Client c, int id)
	{
		try
		{
			return c.getWidget(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** {@code [x,y,w,h]} canvas bounds of a widget, or {@code null} if absent/zero-size. Hidden is fine — the
	 *  engine CONTROLS row stays shown (covered by the native row), so its buttons keep real bounds. */
	private static int[] canvasBounds(Widget w)
	{
		if (w == null)
		{
			return null;
		}
		try
		{
			final java.awt.Rectangle r = w.getBounds();
			if (r == null || r.width <= 0 || r.height <= 0)
			{
				return null;
			}
			return new int[]{r.x, r.y, r.width, r.height};
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static int safeSpriteId(Widget w)
	{
		try
		{
			return w.getSpriteId();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static int safeTextColor(Widget w)
	{
		try
		{
			return w.getTextColor();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	/** Strip RuneLite/cs2 colour-and-format tags ({@code <col=..>}, {@code </col>}, {@code <br>}) from a label. */
	private static String stripTags(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		return s.replaceAll("<[^>]*>", "");
	}

	/** Minimal JSON string escaping (the only risky chars in chat sub-labels are quotes / backslashes). */
	private static String jsonEsc(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++)
		{
			final char ch = s.charAt(i);
			if (ch == '"' || ch == '\\')
			{
				out.append('\\').append(ch);
			}
			else if (ch >= 0x20)
			{
				out.append(ch);
			}
		}
		return out.toString();
	}

	private static void hide(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w != null && !w.isHidden())
			{
				w.setHidden(true);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Hide (or show) the engine chat-tab buttons' VISUALS — the per-tab sprite (base+1), channel name (base+2) and
	 *  filter sub-label (base+3) — while leaving the base button widget shown so it stays a valid proxy-click target.
	 *  Used to stop the engine's 8 tabs peeking out from under the native 7-tab veneer. */
	private static void setEngineChatTabsHidden(Client c, boolean hidden)
	{
		for (final int base : CHAT_TAB_IDS)
		{
			setHidden(c, base + 1, hidden);   // GRAPHIC — the tab-background sprite
			setHidden(c, base + 2, hidden);   // TEXT — the channel name
			setHidden(c, base + 3, hidden);   // FILTER — the live "On"/"Off"/… sub-label (absent on some tabs)
		}
	}

	private static void setHidden(Client c, int id, boolean hidden)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w != null && w.isHidden() != hidden)
			{
				w.setHidden(hidden);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static void forcePos(Client c, int id, int x, int y)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w != null)
			{
				w.setForcedPosition(x, y);
				w.revalidate();
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Position + size the chatbox message area ({@code CHATAREA}) so it aligns with + fits under the enlarged
	 *  native tab row. Key: the chat CONTENT (the message display + the bottom input prompt) is fixed-height and
	 *  does NOT shrink to a smaller area — shrinking CHATAREA clips the input line (the "username cut off until I
	 *  send a message" report). So instead of shrinking the content, GROW the chat container by the extra tab-row
	 *  height and keep CHATAREA at its full natural content height ({@code ORIG_CHAT_CONTENT_H}) below the tab row.
	 *  The display/input/line children then keep their natural sizes and fit. Width is the native row width (the
	 *  display children only need explicit widening when widened beyond the engine's default 519). */
	private static void sizeChatArea(Client c, int containerId, int rowW, int rowH)
	{
		try
		{
			final Widget area = c.getWidget(InterfaceID.Chatbox.CHATAREA);
			if (area == null)
			{
				return;
			}
			// A chatbox rebuild (e.g. triggered by switching the chat tab via op-replay) can leave the message
			// widgets HIDDEN, so the chat goes blank. Force them shown each frame — for the always-visible chat-at-top
			// they should never be hidden.
			setHidden(c, InterfaceID.Chatbox.CHATAREA, false);
			setHidden(c, InterfaceID.Chatbox.CHATDISPLAY, false);
			setHidden(c, InterfaceID.Chatbox.MES_LAYER, false);
			setHidden(c, InterfaceID.Chatbox.SCROLLAREA, false);
			// Grow the container so the full-height content sits BELOW the taller native tab row instead of being
			// squeezed into the original 165px box. +2 breathing room so the bottom input line never edge-clips.
			final Widget cont = c.getWidget(containerId);
			final int wantContainerH = rowH + ORIG_CHAT_CONTENT_H + 2;
			if (cont != null && (cont.getHeightMode() != WidgetSizeMode.ABSOLUTE || cont.getOriginalHeight() != wantContainerH))
			{
				cont.setHeightMode(WidgetSizeMode.ABSOLUTE);
				cont.setOriginalHeight(wantContainerH);
				cont.revalidate();
			}
			// Message area: full natural content height, at the native row width, positioned below the tab row.
			area.setForcedPosition(0, rowH);
			if (area.getWidthMode() != WidgetSizeMode.ABSOLUTE || area.getOriginalWidth() != rowW
				|| area.getHeightMode() != WidgetSizeMode.ABSOLUTE || area.getOriginalHeight() != ORIG_CHAT_CONTENT_H)
			{
				area.setWidthMode(WidgetSizeMode.ABSOLUTE);
				area.setOriginalWidth(rowW);
				area.setHeightMode(WidgetSizeMode.ABSOLUTE);
				area.setOriginalHeight(ORIG_CHAT_CONTENT_H);
				area.revalidate();
				// Only when the row is WIDER than the engine default do the display/scroll children need explicit
				// widening (they don't cascade); at the default 519 width they keep their natural, already-aligned
				// size + position, and their natural height fits the full-height area — so leave them untouched.
				if (rowW > CHAT_DEFAULT_W)
				{
					forceWidth(c, InterfaceID.Chatbox.CHATDISPLAY, rowW - 14, 7);
					forceWidth(c, InterfaceID.Chatbox.MES_LAYER, rowW - 14, 7);
					forceWidth(c, InterfaceID.Chatbox.SCROLLAREA, rowW - 30, 0);
				}
			}
			else
			{
				area.revalidate();
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Force a chat sub-widget to an ABSOLUTE width, LEFT-anchored at {@code leftX} (these widgets centre by
	 *  default, so a width wider than their parent would shift their left edge off-screen), then revalidate. Used
	 *  for the display/scroll widgets that don't inherit the widened CHATAREA via their own size modes. */
	private static void forceWidth(Client c, int id, int w, int leftX)
	{
		try
		{
			final Widget wd = c.getWidget(id);
			if (wd == null)
			{
				return;
			}
			if (wd.getWidthMode() != WidgetSizeMode.ABSOLUTE || wd.getOriginalWidth() != w
				|| wd.getXPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT || wd.getOriginalX() != leftX)
			{
				wd.setWidthMode(WidgetSizeMode.ABSOLUTE);
				wd.setOriginalWidth(w);
				wd.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				wd.setOriginalX(leftX);
				wd.revalidate();
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}


	// Dump the chatbox sub-widget layout for diagnosing the native chat-tab veneer. Fires a few times at STEADY
	// STATE (after the native side has applied the row width — chatAreaW>0 — so the dump reflects the resized
	// chat, not the first pre-native frame), spaced out, then stops.
	private static int dumpTick;
	private static int dumpsDone;

	private static void dumpChatLayoutOnce(Client c, int containerId)
	{
		if (dumpsDone >= 3)
		{
			return;   // cap the number of dumps (fires a few times at steady state)
		}
		if (dumpTick++ % 120 != 0)
		{
			return;   // ~every 2.4s
		}
		dumpsDone++;
		dumpOne(c, "CHAT_CONTAINER", containerId);
		dumpOne(c, "CONTROLS", InterfaceID.Chatbox.CONTROLS);
		dumpOne(c, "CHATAREA", InterfaceID.Chatbox.CHATAREA);
		dumpOne(c, "CHATDISPLAY", InterfaceID.Chatbox.CHATDISPLAY);
		dumpOne(c, "MES_LAYER", InterfaceID.Chatbox.MES_LAYER);
		dumpOne(c, "SCROLLAREA", InterfaceID.Chatbox.SCROLLAREA);
		dumpOne(c, "INPUT_CLICKAREA", InterfaceID.Chatbox.INPUT_CLICKAREA);
		dumpOne(c, "INPUT", InterfaceID.Chatbox.INPUT);
	}

	private static void dumpOne(Client c, String name, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[veneer] chat-layout {} = null", name);
				return;
			}
			final java.awt.Rectangle b = w.getBounds();
			log.info("[veneer] chat-layout {} bounds={} orig=({},{},{}x{}) relX={} relY={} hidden={}",
				name, b, w.getOriginalX(), w.getOriginalY(), w.getOriginalWidth(), w.getOriginalHeight(),
				w.getRelativeX(), w.getRelativeY(), w.isHidden());
		}
		catch (RuntimeException ex)
		{
			log.info("[veneer] chat-layout {} threw {}", name, String.valueOf(ex));
		}
	}
}
