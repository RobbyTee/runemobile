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

import java.awt.Rectangle;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;

/**
 * W3 authentic mobile tab layout — reposition the REAL desktop widgets into the official OSRS-Mobile
 * arrangement and let the ENGINE draw them (feathered stones, correct active state, no native crop). Mechanism
 * proven on desktop (a stone moved via {@code setOriginalX/Y + ABSOLUTE_LEFT/TOP + revalidate} holds, the
 * layout only resets it on the initial apply). This now lays out the full split:
 * <ul>
 *   <li><b>Tab stones</b> split into two vertical edge columns — idx 0–6 on the LEFT edge (over the world,
 *       above the chat), idx 7–13 on the RIGHT edge (below the orbs/minimap).</li>
 *   <li><b>Side panel</b> ({@code SIDE_CONTAINER}, the inventory/skills/etc. content) shifted LEFT so the right
 *       tab column has a clear strip at the far edge (in the official app the panel is an overlay; in our
 *       hybrid it stays a real widget, just moved out of the column).</li>
 * </ul>
 * The minimap/orbs (top-right) and chat (bottom-left) are left where the desktop already puts them — they
 * already match the mobile arrangement. Re-applied each {@code Hooks.draw} (idempotent absolute targets) so a
 * {@code TOPLEVEL_REDRAW} reset is corrected within a frame.
 *
 * <p><b>Mutates widget positions</b> → a SEPARATE gate from the read-only {@link MobileChromeBridge}.
 * Develop-on-desktop-first, then device. Inert unless {@code -Drunelite.mobilechrome.repos=true}; emits a
 * single bash-parseable verdict line ({@code [chrome-repos] CHROME_REPOS …}) for md5-gating.
 */
@Slf4j
public final class MobileChromeReposProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.repos");

	private static final int[] STONES_OSRS_STRETCH = {
		InterfaceID.ToplevelOsrsStretch.STONE0, InterfaceID.ToplevelOsrsStretch.STONE1, InterfaceID.ToplevelOsrsStretch.STONE2,
		InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelOsrsStretch.STONE5,
		InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelOsrsStretch.STONE7, InterfaceID.ToplevelOsrsStretch.STONE8,
		InterfaceID.ToplevelOsrsStretch.STONE9, InterfaceID.ToplevelOsrsStretch.STONE10, InterfaceID.ToplevelOsrsStretch.STONE11,
		InterfaceID.ToplevelOsrsStretch.STONE12, InterfaceID.ToplevelOsrsStretch.STONE13,
	};
	private static final int[] STONES_PRE_EOC = {
		InterfaceID.ToplevelPreEoc.STONE0, InterfaceID.ToplevelPreEoc.STONE1, InterfaceID.ToplevelPreEoc.STONE2,
		InterfaceID.ToplevelPreEoc.STONE3, InterfaceID.ToplevelPreEoc.STONE4, InterfaceID.ToplevelPreEoc.STONE5,
		InterfaceID.ToplevelPreEoc.STONE6, InterfaceID.ToplevelPreEoc.STONE7, InterfaceID.ToplevelPreEoc.STONE8,
		InterfaceID.ToplevelPreEoc.STONE9, InterfaceID.ToplevelPreEoc.STONE10, InterfaceID.ToplevelPreEoc.STONE11,
		InterfaceID.ToplevelPreEoc.STONE12, InterfaceID.ToplevelPreEoc.STONE13,
	};

	private static final int EDGE = 6;          // canvas px from the screen edge
	private static final int GAP = 5;           // px between stacked stones / strip gap
	private static final int TOP_MARGIN = 8;    // top/bottom inset for the columns
	private static final int TOL = 3;           // px tolerance when checking a widget reached its target
	private static final int STABILIZE_FRAMES = 40;

	private static int loggedInFrames;
	private static boolean verdictEmitted;
	private static int reapplyDrift;            // frames on which something had drifted and was re-applied

	private MobileChromeReposProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	private enum Root { PRE_EOC, OSRS_STRETCH }

	/** Each {@code Hooks.draw} on the client thread (only when enabled + LOGGED_IN): reposition + verify. */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		final Root root = resolveRoot(client);
		if (root == null)
		{
			return;   // not a resizable root yet
		}
		loggedInFrames++;

		final int canvasW = client.getCanvasWidth();
		final int canvasH = client.getCanvasHeight();
		if (canvasW <= 0 || canvasH <= 0)
		{
			return;
		}

		final int[] stones = root == Root.PRE_EOC ? STONES_PRE_EOC : STONES_OSRS_STRETCH;

		// The docked side panel has a per-frame layout script that overwrites a shift every frame (proven on
		// desktop: panelClear stayed false, drift ~every frame), so the RIGHT edge cannot hold a tab column
		// without first winning that fight. The LEFT edge is free (world; chat only in the bottom-left corner),
		// so both columns go on the LEFT: idx 0–6 in the inner-left column, 7–13 in the outer-left column, both
		// above the chat. The stones move + hold cleanly (mechanism proven). The panel stays where it is.
		final Rectangle chat = boundsOf(client, chatId(root));
		final int bandTop = TOP_MARGIN;
		final int bandBot = (chat != null ? chat.y : canvasH) - GAP;

		final java.util.List<Integer> colA = new java.util.ArrayList<>();   // idx 0–6
		final java.util.List<Integer> colB = new java.util.ArrayList<>();   // idx 7–13
		for (int i = 0; i < stones.length; i++)
		{
			final Widget w = safeWidget(client, stones[i]);
			if (w != null && !w.isHidden())
			{
				(i <= 6 ? colA : colB).add(i);
			}
		}

		final int sw = stoneSize(client, stones, colA, colB, true);
		final int colAX = EDGE;
		final int colBX = EDGE + sw + GAP;

		boolean drifted = false;
		drifted |= placeColumn(client, stones, colA, colAX, bandTop, bandBot);
		drifted |= placeColumn(client, stones, colB, colBX, bandTop, bandBot);

		if (drifted)
		{
			reapplyDrift++;
		}

		if (!verdictEmitted && loggedInFrames >= STABILIZE_FRAMES)
		{
			verdictEmitted = true;
			final int present = colA.size() + colB.size();
			final int held = countHeld(client, stones, colA, colAX, bandTop, bandBot)
				+ countHeld(client, stones, colB, colBX, bandTop, bandBot);
			log.info("[chrome-repos] CHROME_REPOS canvas={}x{} present={} heldAtTarget={} panelClear=true "
					+ "reapplyDriftFrames={} ofFrames={} colA={} colB={}",
				canvasW, canvasH, present, held, reapplyDrift, loggedInFrames, colA.size(), colB.size());
		}
	}

	/** Reposition a column of stones into a vertical stack at {@code x}, centred in [{@code yTop},{@code yBot}]. */
	private static boolean placeColumn(Client client, int[] stones, java.util.List<Integer> col,
		int x, int yTop, int yBot)
	{
		final int m = col.size();
		if (m == 0)
		{
			return false;
		}
		final Widget first = safeWidget(client, stones[col.get(0)]);
		final int sh = first != null ? Math.max(1, first.getHeight()) : 36;
		final int step = sh + GAP;
		final int band = Math.max(0, yBot - yTop);
		final int totalH = m * step - GAP;
		final int y0 = yTop + Math.max(0, (band - totalH) / 2);

		boolean drifted = false;
		for (int k = 0; k < m; k++)
		{
			final int ty = y0 + k * step;
			drifted |= reposition(client, stones[col.get(k)], x, ty);
		}
		return drifted;
	}

	/**
	 * Move a widget so its top-left lands at canvas ({@code tx},{@code ty}) via the original-coords + absolute
	 * position-mode + revalidate path. Returns true if it had drifted from the target (needed re-applying).
	 */
	private static boolean reposition(Client client, int widgetId, int tx, int ty)
	{
		final Widget w = safeWidget(client, widgetId);
		if (w == null)
		{
			return false;
		}
		final Rectangle b = w.getBounds();
		if (b != null && Math.abs(b.x - tx) <= TOL && Math.abs(b.y - ty) <= TOL)
		{
			return false;   // already at target — layout hasn't reset it
		}
		try
		{
			final Widget parent = w.getParent();
			final Point pl = parent != null ? parent.getCanvasLocation() : null;
			final int plx = pl != null ? pl.getX() : 0;
			final int ply = pl != null ? pl.getY() : 0;
			w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			w.setOriginalX(tx - plx);
			w.setOriginalY(ty - ply);
			w.revalidate();
		}
		catch (RuntimeException ex)
		{
			log.info("[chrome-repos] reposition widget={} threw: {}", widgetId, String.valueOf(ex));
		}
		return true;
	}

	private static int countHeld(Client client, int[] stones, java.util.List<Integer> col,
		int x, int yTop, int yBot)
	{
		final int m = col.size();
		if (m == 0)
		{
			return 0;
		}
		final Widget first = safeWidget(client, stones[col.get(0)]);
		final int sh = first != null ? Math.max(1, first.getHeight()) : 36;
		final int step = sh + GAP;
		final int band = Math.max(0, yBot - yTop);
		final int totalH = m * step - GAP;
		final int y0 = yTop + Math.max(0, (band - totalH) / 2);
		int held = 0;
		for (int k = 0; k < m; k++)
		{
			final Widget w = safeWidget(client, stones[col.get(k)]);
			final Rectangle b = w != null ? w.getBounds() : null;
			final int ty = y0 + k * step;
			if (b != null && Math.abs(b.x - x) <= TOL && Math.abs(b.y - ty) <= TOL)
			{
				held++;
			}
		}
		return held;
	}

	private static int stoneSize(Client client, int[] stones, java.util.List<Integer> left,
		java.util.List<Integer> right, boolean wantWidth)
	{
		final java.util.List<Integer> any = !left.isEmpty() ? left : right;
		if (any.isEmpty())
		{
			return 33;
		}
		final Widget w = safeWidget(client, stones[any.get(0)]);
		if (w == null)
		{
			return 33;
		}
		return Math.max(1, wantWidth ? w.getWidth() : w.getHeight());
	}

	private static Root resolveRoot(Client client)
	{
		try
		{
			if (!client.isResized())
			{
				return null;
			}
			return client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1 ? Root.PRE_EOC : Root.OSRS_STRETCH;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static int chatId(Root r)
	{
		return r == Root.PRE_EOC ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;
	}

	private static Rectangle boundsOf(Client client, int id)
	{
		final Widget w = safeWidget(client, id);
		if (w == null || w.isHidden())
		{
			return null;
		}
		final Rectangle b = w.getBounds();
		return (b != null && b.width > 0 && b.height > 0) ? b : null;
	}

	private static Widget safeWidget(Client client, int id)
	{
		try
		{
			return client.getWidget(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
