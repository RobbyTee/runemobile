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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

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

	// Tab stones. The native 2-col tab grid replaces them, so we HIDE all 14 engine stones (removes the gray cover
	// patch the old proxy approach needed) and switch tabs by REPLAYING the stone's own op script — which works
	// even with the stone hidden (TabScriptProbe: replay + worksHidden GREEN). pendingOpenTab is a one-shot action
	// applied on the client thread (where runScript is legal).
	private static volatile boolean hideStones;
	private static volatile int pendingOpenTab = -1;

	// One-shot device diagnostic for the stone-hide (why the bottom row won't disappear on the device account):
	// capture each stone's bounds pre-relocate, then log post-relocate bounds + parent + op-listener + hidden.
	private static int diagFrame;
	private static boolean diagDone;
	private static final int[] preX = new int[14];
	private static final int[] preY = new int[14];

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
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int chatId = preEoc ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;

			if (chatX >= 0)
			{
				final Widget w = c.getWidget(chatId);
				if (w != null)
				{
					w.setForcedPosition(chatX, chatY);
					w.revalidate();
				}
				// OSRS-Mobile tabs-on-top: the desktop chat stacks the tab row (CONTROLS, 23px) UNDER the message
				// area (CHATAREA, 142px). Flip it — force the tab row to the chat's top and push the messages down
				// by the tab height — so the layout reads tabs -> messages -> input like the mobile reference.
				forcePos(c, InterfaceID.Chatbox.CONTROLS, chatX, chatY);
				forcePos(c, InterfaceID.Chatbox.CHATAREA, chatX, chatY + CONTROLS_H);
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
				dumpChatLayoutOnce(c);
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

			// "Hide" the engine tab stones by RELOCATING them off-screen (setHidden does NOT stick for the stones —
			// they are re-shown each frame; StoneHideProbe confirmed off-screen forced-position is the only thing
			// that removes them). The native 2-col grid replaces them and openTab works regardless of stone position.
			if (hideStones)
			{
				final int ox = c.getCanvasWidth() + 200, oy = c.getCanvasHeight() + 200;
				final int[] ids = stoneIds(preEoc);
				if (!diagDone && diagFrame == 0)
				{
					for (int i = 0; i < 14; i++)
					{
						final java.awt.Rectangle b = rawBounds(c, ids[i]);
						preX[i] = b == null ? -9999 : b.x;
						preY[i] = b == null ? -9999 : b.y;
					}
					log.info("[veneer-diag] root={} stoneArrVarbit={} canvas={}x{} offTarget={},{}",
						preEoc ? "PreEoc" : "OsrsStretch", c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT),
						c.getCanvasWidth(), c.getCanvasHeight(), ox, oy);
				}
				for (final int id : ids)
				{
					relocateOff(c, id, ox, oy);
				}
				if (!diagDone && ++diagFrame >= 12)
				{
					diagDone = true;
					for (int i = 0; i < 14; i++)
					{
						final Widget w = safeWidget(c, ids[i]);
						final java.awt.Rectangle b = w == null ? null : w.getBounds();
						final Widget p = w == null ? null : w.getParent();
						final net.runelite.api.Point pl = p == null ? null : p.getCanvasLocation();
						final Object[] op = w == null ? null : w.getOnOpListener();
						log.info("[veneer-diag] STONE{} pre={},{} post={} parentLoc={} hidden={} onOp={}",
							i, preX[i], preY[i],
							b == null ? "null" : (b.x + "," + b.y + "," + b.width + "x" + b.height),
							pl == null ? "null" : (pl.getX() + "," + pl.getY()),
							w == null ? "?" : String.valueOf(w.isHidden()),
							op == null ? "null" : java.util.Arrays.toString(op));
					}
				}
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

	/** getBounds regardless of hidden state (diagnostic). */
	private static java.awt.Rectangle rawBounds(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return w == null ? null : w.getBounds();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** Force a widget to an absolute canvas position (setForcedPosition is parent-relative, so subtract the
	 *  parent's live canvas location). Used to push the tab stones off-screen. */
	private static void relocateOff(Client c, int id, int ax, int ay)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				return;
			}
			int px = 0, py = 0;
			final Widget p = w.getParent();
			if (p != null)
			{
				final net.runelite.api.Point pl = p.getCanvasLocation();
				if (pl != null)
				{
					px = pl.getX();
					py = pl.getY();
				}
			}
			w.setForcedPosition(ax - px, ay - py);
			w.revalidate();
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

	// One-time dump of the chatbox sub-widget layout, so the native side can plan moving the tab row (CONTROLS)
	// above the message area (CHATAREA/CHATDISPLAY) to match the OSRS-Mobile tabs-on-top layout.
	private static boolean dumped;

	private static void dumpChatLayoutOnce(Client c)
	{
		if (dumped)
		{
			return;
		}
		dumped = true;
		dumpOne(c, "CONTROLS", InterfaceID.Chatbox.CONTROLS);
		dumpOne(c, "CHATAREA", InterfaceID.Chatbox.CHATAREA);
		dumpOne(c, "CHATDISPLAY", InterfaceID.Chatbox.CHATDISPLAY);
		dumpOne(c, "CHAT_BACKGROUND", InterfaceID.Chatbox.CHAT_BACKGROUND);
		dumpOne(c, "INPUT_CLICKAREA", InterfaceID.Chatbox.INPUT_CLICKAREA);
		dumpOne(c, "MES_LAYER", InterfaceID.Chatbox.MES_LAYER);
		dumpOne(c, "SCROLLAREA", InterfaceID.Chatbox.SCROLLAREA);
		dumpOne(c, "CHAT_ALL", InterfaceID.Chatbox.CHAT_ALL);
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
